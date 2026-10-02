package com.seatreserve.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every method is one DB transaction at READ COMMITTED. Correctness never depends on the
 * isolation level: it comes from (1) an advisory lock per idempotency key, (2) an advisory
 * lock per (show, user) for the booking limit, (3) row locks on seats taken in label order
 * (deterministic order => no deadlock), and (4) a guarded UPDATE as the final test-and-set.
 */
@Service
public class ReservationService {
    static final int MAX_SEATS_PER_REQUEST = 50;

    public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats,
                                  long amountPaise, String status) {
    }

    public record ReserveResult(ReservationView reservation, boolean replay) {
    }

    private final JdbcTemplate jdbc;

    public ReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReserveResult reserve(String userId, UUID showId, List<String> requested, String key) {
        if (key == null || key.isBlank() || key.length() > 200)
            throw DomainException.bad("invalid-idempotency-key", "idempotency key is required (max 200 chars)");
        if (requested == null || requested.isEmpty() || requested.size() > MAX_SEATS_PER_REQUEST)
            throw DomainException.bad("invalid-seats", "seats must have 1.." + MAX_SEATS_PER_REQUEST + " entries");
        if (new HashSet<>(requested).size() != requested.size())
            throw DomainException.bad("duplicate-seat", "duplicate seat in request");
        for (String s : requested)
            if (s == null || !ShowService.LABEL.matcher(s).matches())
                throw DomainException.bad("invalid-seat", "invalid seat label");
        String sig = String.join(",", requested.stream().sorted().toList());
        int n = requested.size();

        Map<String, Object> show = jdbc.queryForList(
                "select price_paise, per_user_limit from shows where id = ?", showId)
                .stream().findFirst().orElseThrow(() -> DomainException.notFound("show-not-found", "no such show"));
        long price = ((Number) show.get("price_paise")).longValue();
        int limit = ((Number) show.get("per_user_limit")).intValue();

        // (1) serialize same-key requests: later callers block here, then see the committed row.
        advisoryLock("idem:" + userId + ":" + key);

        var existing = jdbc.queryForList("""
                select id, show_id, status, amount_paise, seat_signature
                from reservations where user_id = ? and idempotency_key = ?""", userId, key);
        if (!existing.isEmpty()) {
            var r = existing.get(0);
            if (!showId.equals(r.get("show_id")) || !sig.equals(r.get("seat_signature")))
                throw DomainException.conflict("idempotency-conflict",
                        "idempotency key was already used with a different request");
            return new ReserveResult(view(r, userId), true);
        }

        // (2) serialize the booking-limit check per (show, user) so parallel keys cannot overshoot.
        advisoryLock("lim:" + showId + ":" + userId);
        Integer active = jdbc.queryForObject(
                "select count(*) from seats where show_id = ? and holder_user_id = ? and status = 'confirmed'",
                Integer.class, showId, userId);
        if (active + n > limit)
            throw DomainException.conflict("per-user-limit",
                    "per-user limit of " + limit + " seats would be exceeded");

        // (3) lock the seats in label order, then decide on the locked rows.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select label, status = 'available' as free
                from seats
                where show_id = ? and label = any(string_to_array(?, ','))
                order by label for update""", showId, sig);
        if (rows.size() != n)
            throw DomainException.notFound("seat-not-found", "one or more seats do not exist in this show");
        for (var row : rows)
            if (!Boolean.TRUE.equals(row.get("free")))
                throw DomainException.conflict("seat-taken", "seat " + row.get("label") + " is already taken");

        UUID id = UUID.randomUUID();
        long amount = Math.multiplyExact(price, (long) n);
        try {
            jdbc.update("""
                    insert into reservations(id, show_id, user_id, status, amount_paise, seat_count,
                                             idempotency_key, seat_signature)
                    values (?,?,?,'confirmed',?,?,?,?)""", id, showId, userId, amount, n, key, sig);
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("idempotency-conflict", "duplicate idempotency key");
        }

        // (4) the guarded test-and-set. Rows are already locked, so this must claim all n.
        int claimed = jdbc.update("""
                update seats set status = 'confirmed', holder_user_id = ?, reservation_id = ?
                where show_id = ? and label = any(string_to_array(?, ',')) and status = 'available'""",
                userId, id, showId, sig);
        if (claimed != n) throw DomainException.conflict("seat-taken", "seat was taken concurrently");

        return new ReserveResult(new ReservationView(id, showId, userId, List.of(sig.split(",")), amount,
                "confirmed"), false);
    }

    /** Owner-only release. Idempotent: cancelling twice is a no-op, never a second release. */
    @Transactional
    public ReservationView cancel(String userId, UUID reservationId) {
        var rows = jdbc.queryForList("""
                select id, show_id, user_id, status, amount_paise, seat_signature
                from reservations where id = ? for update""", reservationId);
        if (rows.isEmpty()) throw DomainException.notFound("reservation-not-found", "no such reservation");
        var r = new HashMap<>(rows.get(0));
        if (!userId.equals(r.get("user_id")))
            throw DomainException.forbidden("not-owner", "reservation belongs to another user");
        if (!"confirmed".equals(r.get("status"))) return view(r, userId);

        // same label order as reserve(), so a cancel can never deadlock against a reserve
        jdbc.query("select label from seats where reservation_id = ? order by label for update", rs -> {
        }, reservationId);
        // matches reservation_id, so it can never free a seat that now belongs to someone else
        jdbc.update("""
                update seats set status = 'available', holder_user_id = null, reservation_id = null
                where reservation_id = ?""", reservationId);
        jdbc.update("update reservations set status = 'cancelled' where id = ?", reservationId);
        r.put("status", "cancelled");
        return view(r, userId);
    }

    private void advisoryLock(String name) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, name);
    }

    private ReservationView view(Map<String, Object> r, String userId) {
        return new ReservationView((UUID) r.get("id"), (UUID) r.get("show_id"), userId,
                Arrays.asList(((String) r.get("seat_signature")).split(",")),
                ((Number) r.get("amount_paise")).longValue(), (String) r.get("status"));
    }
}
