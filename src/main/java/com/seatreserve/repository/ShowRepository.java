package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {
    /** {@code holdSeconds} is null for an ordinary show (reserve sells immediately). */
    public record ShowRecord(String name, long pricePaise, int perUserLimit, Integer holdSeconds) {
    }

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name, long pricePaise, int perUserLimit, Integer holdSeconds) {
        jdbc.update("insert into shows(id, name, price_paise, per_user_limit, hold_seconds) values (?,?,?,?,?)",
                id, name, pricePaise, perUserLimit, holdSeconds);
    }

    /** One statement for the whole hall; labels cannot contain commas (see ShowService.LABEL). */
    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.update("insert into seats(show_id, label) select ?, unnest(string_to_array(?, ','))",
                showId, String.join(",", labels));
    }

    public Optional<ShowRecord> find(UUID id) {
        return jdbc.query("select name, price_paise, per_user_limit, hold_seconds from shows where id = ?",
                (rs, i) -> new ShowRecord(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getObject(4, Integer.class)),
                id).stream().findFirst();
    }
}
