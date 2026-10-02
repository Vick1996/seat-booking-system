package com.seatreserve.dto;

import java.util.List;
import java.util.UUID;

public record ShowView(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
                       int available, int held, int confirmed, List<SeatView> seats) {
}
