package com.seatreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** {@code holdSeconds} appears only for a show created with holds, so ordinary shows look exactly as before. */
public record ShowView(UUID id, String name, long pricePaise, int perUserLimit,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer holdSeconds,
                       int totalSeats, int available, int held, int confirmed, List<SeatView> seats) {
}
