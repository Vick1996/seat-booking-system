package com.seatreserve.service;

import com.seatreserve.dto.SeatView;
import com.seatreserve.dto.ShowView;
import com.seatreserve.exception.DomainException;
import com.seatreserve.repository.SeatRepository;
import com.seatreserve.repository.ShowRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShowService {
    static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final int MAX_SEATS = 100_000;

    private final ShowRepository shows;
    private final SeatRepository seats;

    public ShowService(ShowRepository shows, SeatRepository seats) {
        this.shows = shows;
        this.seats = seats;
    }

    @Transactional
    public ShowView create(String name, List<String> labels, long pricePaise, Integer perUserLimit) {
        if (name == null || name.isBlank()) throw DomainException.bad("invalid-name", "name is required");
        if (pricePaise < 0) throw DomainException.bad("invalid-price", "price_paise must be >= 0");
        if (labels == null || labels.isEmpty() || labels.size() > MAX_SEATS)
            throw DomainException.bad("invalid-seats", "seats must have 1.." + MAX_SEATS + " entries");
        int limit = perUserLimit == null ? 4 : perUserLimit;
        if (limit < 1) throw DomainException.bad("invalid-limit", "per_user_limit must be >= 1");
        var seen = new HashSet<String>();
        for (String s : labels) {
            if (s == null || !LABEL.matcher(s).matches())
                throw DomainException.bad("invalid-seat", "seat labels must match " + LABEL.pattern());
            if (!seen.add(s)) throw DomainException.bad("duplicate-seat", "duplicate seat label " + s);
        }
        UUID id = UUID.randomUUID();
        try {
            shows.insert(id, name, pricePaise, limit);
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("show-exists", "a show named '" + name + "' already exists");
        }
        shows.insertSeats(id, labels);
        return get(id);
    }

    @Transactional(readOnly = true)
    public ShowView get(UUID id) {
        var show = shows.find(id)
                .orElseThrow(() -> DomainException.notFound("show-not-found", "no such show"));
        var statuses = seats.statusByLabel(id);
        int available = 0, held = 0, confirmed = 0;
        for (String st : statuses.values()) {
            switch (st) {
                case "available" -> available++;
                case "held" -> held++;
                default -> confirmed++;
            }
        }
        var views = statuses.entrySet().stream().map(e -> new SeatView(e.getKey(), e.getValue())).toList();
        return new ShowView(id, show.name(), show.pricePaise(), show.perUserLimit(), statuses.size(),
                available, held, confirmed, views);
    }
}
