package com.seatreserve.api;

import com.seatreserve.config.AuthFilter;
import com.seatreserve.config.Principal;
import com.seatreserve.config.SeatMetrics;
import com.seatreserve.domain.DomainException;
import com.seatreserve.domain.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShowController {
    public record CreateShow(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
    }

    private final ShowService shows;
    private final SeatMetrics metrics;

    public ShowController(ShowService shows, SeatMetrics metrics) {
        this.shows = shows;
        this.metrics = metrics;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowService.ShowView create(@RequestAttribute(AuthFilter.ATTR) Principal who,
                                       @RequestBody CreateShow req) {
        if (!who.admin()) throw DomainException.forbidden("admin-only", "admin token required");
        if (req.pricePaise() == null) throw DomainException.bad("invalid-price", "price_paise is required");
        var show = shows.create(req.name(), req.seats(), req.pricePaise(), req.perUserLimit());
        metrics.trackShow(show.id()); // after commit, so the gauge never reads a half-created show
        return show;
    }

    @GetMapping("/shows/{id}")
    public ShowService.ShowView get(@PathVariable UUID id) {
        return shows.get(id);
    }
}
