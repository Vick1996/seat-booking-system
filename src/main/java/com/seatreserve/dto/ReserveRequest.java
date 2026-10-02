package com.seatreserve.dto;

import java.util.List;

/** user_id is deliberately absent: identity comes from the token, a spoofed body field is ignored. */
public record ReserveRequest(List<String> seats, String idempotencyKey) {
}
