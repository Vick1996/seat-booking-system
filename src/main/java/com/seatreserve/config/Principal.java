package com.seatreserve.config;

/** Identity derived from the verified JWT — never from a request body. */
public record Principal(String userId, boolean admin) {
}
