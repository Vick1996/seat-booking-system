package com.seatreserve.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code maxConnections}: 0 (the default) sizes the cap from the heap; a positive value overrides it. */
@ConfigurationProperties(prefix = "seat")
public record SeatProperties(String jwtSecret, String adminKey, int maxConnections) {
}
