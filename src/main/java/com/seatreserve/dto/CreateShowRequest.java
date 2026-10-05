package com.seatreserve.dto;

import java.util.List;

/** {@code holdSeconds} is optional: set it and reserve places a hold that must be confirmed in time. */
public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit, Integer holdSeconds) {
}
