package com.seatreserve.domain;

import org.springframework.http.HttpStatus;

/** A business outcome with a 4xx status — a decline, never a server error. */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String reason;

    public DomainException(HttpStatus status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static DomainException conflict(String reason, String msg) {
        return new DomainException(HttpStatus.CONFLICT, reason, msg);
    }

    public static DomainException notFound(String reason, String msg) {
        return new DomainException(HttpStatus.NOT_FOUND, reason, msg);
    }

    public static DomainException bad(String reason, String msg) {
        return new DomainException(HttpStatus.BAD_REQUEST, reason, msg);
    }

    public static DomainException forbidden(String reason, String msg) {
        return new DomainException(HttpStatus.FORBIDDEN, reason, msg);
    }
}
