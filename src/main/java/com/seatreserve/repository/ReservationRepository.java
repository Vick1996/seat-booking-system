package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {
    public record ReservationRecord(UUID id, UUID showId, String userId, String status,
                                    long amountPaise, String seatSignature) {
    }

    private static final String COLUMNS = "id, show_id, user_id, status, amount_paise, seat_signature";
    private static final RowMapper<ReservationRecord> MAPPER = (rs, i) -> new ReservationRecord(
            rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class), rs.getString("user_id"),
            rs.getString("status"), rs.getLong("amount_paise"), rs.getString("seat_signature"));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Transaction-scoped: released automatically at commit or rollback, so it can never leak. */
    public void advisoryLock(String name) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, name);
    }

    public Optional<ReservationRecord> findByUserAndKey(String userId, String idempotencyKey) {
        return jdbc.query("select " + COLUMNS + " from reservations where user_id = ? and idempotency_key = ?",
                MAPPER, userId, idempotencyKey).stream().findFirst();
    }

    public Optional<ReservationRecord> findForUpdate(UUID id) {
        return jdbc.query("select " + COLUMNS + " from reservations where id = ? for update", MAPPER, id)
                .stream().findFirst();
    }

    /** Throws DuplicateKeyException if (user_id, idempotency_key) already exists. */
    public void insert(UUID id, UUID showId, String userId, long amountPaise, int seatCount,
                       String idempotencyKey, String seatSignature) {
        jdbc.update("""
                insert into reservations(id, show_id, user_id, status, amount_paise, seat_count,
                                         idempotency_key, seat_signature)
                values (?,?,?,'confirmed',?,?,?,?)""",
                id, showId, userId, amountPaise, seatCount, idempotencyKey, seatSignature);
    }

    public void markCancelled(UUID id) {
        jdbc.update("update reservations set status = 'cancelled' where id = ?", id);
    }
}
