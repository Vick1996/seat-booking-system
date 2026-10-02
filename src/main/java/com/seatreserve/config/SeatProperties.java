package com.seatreserve.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seat")
public record SeatProperties(String jwtSecret, String adminKey) {
}
