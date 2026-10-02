package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowRepository {
    public record ShowRecord(String name, long pricePaise, int perUserLimit) {
    }

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name, long pricePaise, int perUserLimit) {
        jdbc.update("insert into shows(id, name, price_paise, per_user_limit) values (?,?,?,?)",
                id, name, pricePaise, perUserLimit);
    }

    /** One statement for the whole hall; labels cannot contain commas (see ShowService.LABEL). */
    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.update("insert into seats(show_id, label) select ?, unnest(string_to_array(?, ','))",
                showId, String.join(",", labels));
    }

    public Optional<ShowRecord> find(UUID id) {
        return jdbc.query("select name, price_paise, per_user_limit from shows where id = ?",
                (rs, i) -> new ShowRecord(rs.getString(1), rs.getLong(2), rs.getInt(3)), id).stream().findFirst();
    }
}
