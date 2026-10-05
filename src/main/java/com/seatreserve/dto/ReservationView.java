package com.seatreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code expiresAt} appears only while the reservation is a hold, so an ordinary one has exactly the email's fields. */
public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats,
                              long amountPaise, String status,
                              @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime expiresAt) {
}
