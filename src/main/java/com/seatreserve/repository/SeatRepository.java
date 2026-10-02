package com.seatreserve.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Seat rows. Several methods only make sense inside the caller's transaction (row locks are held
 * until it ends), so they are always called from a @Transactional service method.
 * {@code seats} arguments are a comma-joined, label-sorted list (labels cannot contain commas).
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

    /** One query = one snapshot, so counts derived from it always sum to the total. */
    public Map<String, String> statusByLabel(UUID showId) {
        var seats = new TreeMap<String, String>();
        jdbc.query("select label, status from seats where show_id = ? order by label",
                rs -> {
                    seats.put(rs.getString(1), rs.getString(2));
                }, showId);
        return seats;
    }

    public int countConfirmed(UUID showId, String userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from seats where show_id = ? and holder_user_id = ? and status = 'confirmed'",
                Integer.class, showId, userId);
        return n == null ? 0 : n;
    }

    /** Locks the seats in label order (the same order for every caller, so no deadlock). */
    public List<LockedSeat> lockForUpdate(UUID showId, String seats) {
        return jdbc.query("""
                select label, status = 'available' as free
                from seats
                where show_id = ? and label = any(string_to_array(?, ','))
                order by label for update""",
                (rs, i) -> new LockedSeat(rs.getString(1), rs.getBoolean(2)), showId, seats);
    }

    /** The guarded test-and-set: only rows that are still available are claimed. */
    public int claim(UUID showId, String seats, String userId, UUID reservationId) {
        return jdbc.update("""
                update seats set status = 'confirmed', holder_user_id = ?, reservation_id = ?
                where show_id = ? and label = any(string_to_array(?, ',')) and status = 'available'""",
                userId, reservationId, showId, seats);
    }

    /** Same label order as lockForUpdate, so a cancel can never deadlock against a reserve. */
    public void lockByReservation(UUID reservationId) {
        jdbc.query("select label from seats where reservation_id = ? order by label for update", rs -> {
        }, reservationId);
    }

    /** Matches reservation_id, so it can never free a seat that now belongs to someone else. */
    public void releaseByReservation(UUID reservationId) {
        jdbc.update("""
                update seats set status = 'available', holder_user_id = null, reservation_id = null
                where reservation_id = ?""", reservationId);
    }
}
