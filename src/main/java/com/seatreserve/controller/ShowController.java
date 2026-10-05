package com.seatreserve.controller;

import com.seatreserve.config.SeatMetrics;
import com.seatreserve.dto.CreateShowRequest;
import com.seatreserve.dto.ShowView;
import com.seatreserve.exception.DomainException;
import com.seatreserve.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ShowController {
    private final ShowService shows;
    private final SeatMetrics metrics;

    public ShowController(ShowService shows, SeatMetrics metrics) {
        this.shows = shows;
        this.metrics = metrics;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')") // the email: "POST /shows (admin)"
    public ShowView create(@RequestBody CreateShowRequest req) {
        if (req.pricePaise() == null) throw DomainException.bad("invalid-price", "price_paise is required");
        var show = shows.create(req.name(), req.seats(), req.pricePaise(), req.perUserLimit(), req.holdSeconds());
        metrics.trackShow(show.id()); // after commit, so the gauge never reads a half-created show
        return show;
    }

    @GetMapping("/shows/{id}")
    public ShowView get(@PathVariable UUID id) {
        return shows.get(id);
    }
}
