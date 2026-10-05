package com.seatreserve.controller;

import com.seatreserve.config.SeatMetrics;
import com.seatreserve.dto.ReservationView;
import com.seatreserve.dto.ReserveRequest;
import com.seatreserve.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {
    private final ReservationService reservations;
    private final SeatMetrics metrics;

    public ReservationController(ReservationService reservations, SeatMetrics metrics) {
        this.reservations = reservations;
        this.metrics = metrics;
    }

    /** The caller is the verified token's subject (@AuthenticationPrincipal); the body never names a user. */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationView> reserve(@AuthenticationPrincipal Jwt caller,
                                                   @PathVariable UUID id,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                   @RequestBody ReserveRequest req) {
        String key = headerKey != null ? headerKey : req.idempotencyKey();
        // No key at all: treat the request as unique. It gets no retry protection (nothing to match a
        // retry against), but seat uniqueness and the per-user limit still hold. A blank or oversized
        // key is a client bug and stays a 400 in the service.
        if (key == null) key = "auto-" + UUID.randomUUID();
        var result = reservations.reserve(caller.getSubject(), id, req.seats(), key);
        // counted here, after the transaction has committed
        if (result.replay()) metrics.declined("idempotent-replay");
        else if ("held".equals(result.reservation().status())) metrics.held();
        else metrics.confirmed();
        // 201 for a new reservation; a replay is 200 so "exactly one 201 per seat" stays true under retries
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.reservation());
    }

    /** Turns a hold into a sale (only for shows created with hold_seconds). Counted once, not on a repeat. */
    @PostMapping("/reservations/{id}/confirm")
    public ReservationView confirm(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        var result = reservations.confirm(caller.getSubject(), id);
        if (!result.replay()) metrics.confirmed();
        return result.reservation();
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return reservations.cancel(caller.getSubject(), id);
    }
}
