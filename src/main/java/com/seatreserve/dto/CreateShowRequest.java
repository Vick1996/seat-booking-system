package com.seatreserve.dto;

import java.util.List;

public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
}
