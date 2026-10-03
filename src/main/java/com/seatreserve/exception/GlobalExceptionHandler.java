package com.seatreserve.exception;

import com.seatreserve.config.SeatMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Maps every expected outcome to a 4xx so declines never show up as server errors. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SeatMetrics metrics;

    public GlobalExceptionHandler(SeatMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> domain(DomainException e) {
        metrics.declined(e.reason());
        return body(e.status(), e.reason(), e.getMessage());
    }

    /** Saturation (no connection, lock wait, statement timeout, deadlock victim) is shed as 429. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            CannotAcquireLockException.class, QueryTimeoutException.class,
            DeadlockLoserDataAccessException.class})
    ResponseEntity<Map<String, Object>> overloaded(Exception e) {
        log.warn("shedding load as 429: {}", e.toString());
        metrics.declined("overloaded");
        return body(HttpStatus.TOO_MANY_REQUESTS, "overloaded", "server is busy, retry with the same idempotency key");
    }

    /**
     * Thrown by @PreAuthorize from inside a controller. Without this the catch-all below would turn a
     * plain "not allowed" into a 500: the filter chain's own handler never sees exceptions that this
     * advice has already caught.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "forbidden", "you are not allowed to do this");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "bad-request", "malformed request");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        // Spring's own client errors (unknown route 404, wrong method 405, wrong media type 415...)
        // carry their status; only genuinely unexpected exceptions are a 500.
        if (e instanceof ErrorResponse er) {
            HttpStatus s = HttpStatus.valueOf(er.getStatusCode().value());
            return body(s, s.name().toLowerCase().replace('_', '-'), s.getReasonPhrase());
        }
        log.error("unexpected error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "unexpected error");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus s, String reason, String msg) {
        return ResponseEntity.status(s).body(Map.of("error", s.getReasonPhrase(), "reason", reason, "message", msg));
    }
}
