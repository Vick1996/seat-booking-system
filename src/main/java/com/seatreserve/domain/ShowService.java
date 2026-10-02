package com.seatreserve.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShowService {
    static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final int MAX_SEATS = 100_000;

    public record SeatView(String label, String status) {
    }

    public record ShowView(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
                           int available, int held, int confirmed, List<SeatView> seats) {
    }

    private final JdbcTemplate jdbc;

    public ShowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ShowView create(String name, List<String> seats, long pricePaise, Integer perUserLimit) {
        if (name == null || name.isBlank()) throw DomainException.bad("invalid-name", "name is required");
        if (pricePaise < 0) throw DomainException.bad("invalid-price", "price_paise must be >= 0");
        if (seats == null || seats.isEmpty() || seats.size() > MAX_SEATS)
            throw DomainException.bad("invalid-seats", "seats must have 1.." + MAX_SEATS + " entries");
        int limit = perUserLimit == null ? 4 : perUserLimit;
        if (limit < 1) throw DomainException.bad("invalid-limit", "per_user_limit must be >= 1");
        var seen = new HashSet<String>();
        for (String s : seats) {
            if (s == null || !LABEL.matcher(s).matches())
                throw DomainException.bad("invalid-seat", "seat labels must match " + LABEL.pattern());
            if (!seen.add(s)) throw DomainException.bad("duplicate-seat", "duplicate seat label " + s);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into shows(id, name, price_paise, per_user_limit) values (?,?,?,?)",
                    id, name, pricePaise, limit);
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("show-exists", "a show named '" + name + "' already exists");
        }
        // one statement for the whole hall; labels cannot contain commas (see LABEL)
        jdbc.update("insert into seats(show_id, label) select ?, unnest(string_to_array(?, ','))",
                id, String.join(",", seats));
        return get(id);
    }

    /** One query = one snapshot, so the three counts always sum to total_seats. */
    @Transactional(readOnly = true)
    public ShowView get(UUID id) {
        Map<String, Object> show = jdbc.queryForList(
                "select name, price_paise, per_user_limit from shows where id = ?", id)
                .stream().findFirst().orElseThrow(() -> DomainException.notFound("show-not-found", "no such show"));
        // an expired hold reads as available: it is instantly re-bookable (lazy reclaim)
        var seats = new TreeMap<String, String>();
        jdbc.query("""
                select label,
                       case when status = 'held' and expires_at < clock_timestamp()
                            then 'available' else status end as st
                from seats where show_id = ? order by label""",
                rs -> {
                    seats.put(rs.getString(1), rs.getString(2));
                }, id);
        int a = 0, h = 0, c = 0;
        for (String st : seats.values()) {
            switch (st) {
                case "available" -> a++;
                case "held" -> h++;
                default -> c++;
            }
        }
        var views = seats.entrySet().stream().map(e -> new SeatView(e.getKey(), e.getValue())).toList();
        return new ShowView(id, (String) show.get("name"), ((Number) show.get("price_paise")).longValue(),
                ((Number) show.get("per_user_limit")).intValue(), seats.size(), a, h, c, views);
    }
}
