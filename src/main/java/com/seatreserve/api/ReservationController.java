package com.seatreserve.api;

import com.seatreserve.config.AuthFilter;
import com.seatreserve.config.Principal;
import com.seatreserve.domain.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ReservationController {
    /** user_id is deliberately absent: identity comes from the token, a spoofed body field is ignored. */
    public record ReserveRequest(List<String> seats, String idempotencyKey) {
    }

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<Body> reserve(@RequestAttribute(AuthFilter.ATTR) Principal who,
                                        @PathVariable UUID id,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                        @RequestBody ReserveRequest req) {
        String key = headerKey != null ? headerKey : req.idempotencyKey();
        var result = reservations.reserve(who.userId(), id, req.seats(), key);
        // 201 for a new reservation; a replay is 200 so "exactly one 201 per seat" stays true under retries
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(Body.of(result.reservation()));
    }

    @PostMapping("/reservations/{id}/cancel")
    public Body cancel(@RequestAttribute(AuthFilter.ATTR) Principal who, @PathVariable UUID id) {
        return Body.of(reservations.cancel(who.userId(), id));
    }

    public record Body(UUID reservationId, UUID showId, String userId, List<String> seats,
                       long amountPaise, String status) {
        static Body of(ReservationService.ReservationView v) {
            return new Body(v.reservationId(), v.showId(), v.userId(), v.seats(), v.amountPaise(), v.status());
        }
    }
}
