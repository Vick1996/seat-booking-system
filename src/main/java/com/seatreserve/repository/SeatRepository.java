package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Seat rows. Several methods only make sense inside the caller's transaction (row locks are held
 * until it ends), so they are always called from a @Transactional service method.
 * {@code seats} arguments are a comma-joined, label-sorted list (labels cannot contain commas).
 *
 * A hold that has run out of time is treated as free everywhere ("lazy reclaim"), so correctness never
 * depends on the sweeper having run: it can be re-reserved the instant it expires.
 */
@Repository
public class SeatRepository {
    /** A seat locked FOR UPDATE; {@code free} is its availability as seen under that lock. */
    public record LockedSeat(String label, boolean free) {
    }

    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One query = one snapshot, so counts derived from it always sum to the total. An expired hold reads as available. */
    public Map<String, String> statusByLabel(UUID showId) {
        var seats = new TreeMap<String, String>();
        jdbc.query("""
                select label,
                       case when status = 'held' and expires_at < clock_timestamp() then 'available' else status end
                from seats where show_id = ? order by label""",
                rs -> {
                    seats.put(rs.getString(1), rs.getString(2));
                }, showId);
        return seats;
    }

    /**
     * Lock-free pre-check: is any requested seat sold or under a live hold right now? A plain SELECT never waits on a
     * row lock, so a loser can be turned away without queueing behind the winner. It may be a few milliseconds stale,
     * which is safe: a stale "free" just falls into the locked path (the authority), and a stale "taken" for a seat
     * released a moment ago is the same as the request having arrived just before the release.
     */
    public boolean anyTaken(UUID showId, String seats) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from seats
                               where show_id = ? and label = any(string_to_array(?, ','))
                                 and not (status = 'available' or (status = 'held' and expires_at < clock_timestamp())))""",
                Boolean.class, showId, seats));
    }

    /** Seats this user holds against the per-user limit: sold ones plus holds that have not yet lapsed. */
    public int countActive(UUID showId, String userId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from seats
                where show_id = ? and holder_user_id = ?
                  and (status = 'confirmed' or (status = 'held' and expires_at > clock_timestamp()))""",
                Integer.class, showId, userId);
        return n == null ? 0 : n;
    }

    /** Locks the seats in label order (the same order for every caller, so no deadlock). */
    public List<LockedSeat> lockForUpdate(UUID showId, String seats) {
        return jdbc.query("""
                select label,
                       (status = 'available' or (status = 'held' and expires_at < clock_timestamp())) as free
                from seats
                where show_id = ? and label = any(string_to_array(?, ','))
                order by label for update""",
                (rs, i) -> new LockedSeat(rs.getString(1), rs.getBoolean(2)), showId, seats);
    }

    /**
     * The guarded test-and-set: only seats that are still free (available, or a lapsed hold) are claimed.
     * {@code status} is 'confirmed' (expiresAt null) for an ordinary show or 'held' for a show with holds.
     */
    public int claim(UUID showId, String seats, String userId, UUID reservationId, String status, OffsetDateTime expiresAt) {
        return jdbc.update("""
                update seats set status = ?, holder_user_id = ?, reservation_id = ?, expires_at = ?
                where show_id = ? and label = any(string_to_array(?, ','))
                  and (status = 'available' or (status = 'held' and expires_at < clock_timestamp()))""",
                status, userId, reservationId, expiresAt, showId, seats);
    }

    /** Promotes a live hold to a sale. Counts only holds that have not lapsed, so it never resurrects an expired one. */
    public int confirmHeld(UUID reservationId, String userId) {
        return jdbc.update("""
                update seats set status = 'confirmed', expires_at = null
                where reservation_id = ? and holder_user_id = ?
                  and status = 'held' and expires_at > clock_timestamp()""", reservationId, userId);
    }

    /** Same label order as lockForUpdate, so a cancel or confirm can never deadlock against a reserve. */
    public void lockByReservation(UUID reservationId) {
        jdbc.query("select label from seats where reservation_id = ? order by label for update", rs -> {
        }, reservationId);
    }

    /** Matches reservation_id, so it can never free a seat that now belongs to someone else. */
    public void releaseByReservation(UUID reservationId) {
        jdbc.update("""
                update seats set status = 'available', holder_user_id = null, reservation_id = null, expires_at = null
                where reservation_id = ?""", reservationId);
    }

    /** Housekeeping. SKIP LOCKED: never wait on, or deadlock with, a seat a live request is working on. */
    public int releaseExpired() {
        return jdbc.update("""
                update seats s set status = 'available', holder_user_id = null, reservation_id = null, expires_at = null
                from (select show_id, label from seats
                      where status = 'held' and expires_at < clock_timestamp()
                      for update skip locked) x
                where s.show_id = x.show_id and s.label = x.label""");
    }
}
