package com.seatreserve.dto;

/** A new reservation (201) or the replay of an earlier one with the same idempotency key (200). */
public record ReserveResult(ReservationView reservation, boolean replay) {
}
