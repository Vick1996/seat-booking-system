package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {
    /** {@code expiresAt} is set only while a reservation is a hold. */
    public record ReservationRecord(UUID id, UUID showId, String userId, String status,
                                    long amountPaise, String seatSignature, OffsetDateTime expiresAt) {
    }

    private static final String COLUMNS = "id, show_id, user_id, status, amount_paise, seat_signature, expires_at";
    private static final RowMapper<ReservationRecord> MAPPER = (rs, i) -> new ReservationRecord(
            rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class), rs.getString("user_id"),
            rs.getString("status"), rs.getLong("amount_paise"), rs.getString("seat_signature"),
            rs.getObject("expires_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Transaction-scoped: released automatically at commit or rollback, so it can never leak. */
    public void advisoryLock(String name) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, name);
    }

    /** The moment a hold lapses, read from the DB clock so it agrees with every expiry comparison. */
    public OffsetDateTime expiryAfter(int seconds) {
        return jdbc.queryForObject("select clock_timestamp() + make_interval(secs => ?)", OffsetDateTime.class, seconds);
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
    public void insert(UUID id, UUID showId, String userId, String status, long amountPaise, int seatCount,
                       String idempotencyKey, String seatSignature, OffsetDateTime expiresAt) {
        jdbc.update("""
                insert into reservations(id, show_id, user_id, status, amount_paise, seat_count,
                                         idempotency_key, seat_signature, expires_at)
                values (?,?,?,?,?,?,?,?,?)""",
                id, showId, userId, status, amountPaise, seatCount, idempotencyKey, seatSignature, expiresAt);
    }

    public void markConfirmed(UUID id) {
        jdbc.update("update reservations set status = 'confirmed', expires_at = null where id = ?", id);
    }

    public void markCancelled(UUID id) {
        jdbc.update("update reservations set status = 'cancelled', expires_at = null where id = ?", id);
    }

    /** Housekeeping: a hold past its expiry is reported as expired. */
    public int markExpired() {
        return jdbc.update("update reservations set status = 'expired' where status = 'held' and expires_at < clock_timestamp()");
    }
}
