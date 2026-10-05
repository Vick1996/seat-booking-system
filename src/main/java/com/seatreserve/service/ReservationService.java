package com.seatreserve.service;

import com.seatreserve.dto.ReservationView;
import com.seatreserve.dto.ReserveResult;
import com.seatreserve.exception.DomainException;
import com.seatreserve.repository.ReservationRepository;
import com.seatreserve.repository.ReservationRepository.ReservationRecord;
import com.seatreserve.repository.SeatRepository;
import com.seatreserve.repository.ShowRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Every method is one DB transaction at READ COMMITTED. Correctness never depends on the
 * isolation level: it comes from (1) an advisory lock per idempotency key, (2) an advisory
 * lock per (show, user) for the booking limit, (3) row locks on seats taken in label order
 * (deterministic order => no deadlock), and (4) a guarded UPDATE as the final test-and-set.
 *
 * A show created with hold_seconds makes reserve place a time-boxed HOLD that the owner must confirm; a hold
 * that runs out is free again at once (SeatRepository treats a lapsed hold as available). Without hold_seconds
 * reserve sells immediately, exactly as the assignment's example shows.
 */
@Service
public class ReservationService {
    static final int MAX_SEATS_PER_REQUEST = 50;

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;

    public ReservationService(ShowRepository shows, SeatRepository seats, ReservationRepository reservations) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
    }

    @Transactional
    public ReserveResult reserve(String userId, UUID showId, List<String> requested, String key) {
        if (key == null || key.isBlank() || key.length() > 200 || Inputs.hasControlChars(key))
            throw DomainException.bad("invalid-idempotency-key",
                    "idempotency key is required (max 200 chars, no control characters)");
        if (requested == null || requested.isEmpty() || requested.size() > MAX_SEATS_PER_REQUEST)
            throw DomainException.bad("invalid-seats", "seats must have 1.." + MAX_SEATS_PER_REQUEST + " entries");
        if (new HashSet<>(requested).size() != requested.size())
            throw DomainException.bad("duplicate-seat", "duplicate seat in request");
        for (String s : requested)
            if (s == null || !ShowService.LABEL.matcher(s).matches())
                throw DomainException.bad("invalid-seat", "invalid seat label");
        String sig = String.join(",", requested.stream().sorted().toList());
        int n = requested.size();

        var show = shows.find(showId)
                .orElseThrow(() -> DomainException.notFound("show-not-found", "no such show"));

        // (1) serialize same-key requests: later callers block here, then see the committed row.
        reservations.advisoryLock("idem:" + userId + ":" + key);

        var existing = reservations.findByUserAndKey(userId, key);
        if (existing.isPresent()) {
            var r = existing.get();
            if (!showId.equals(r.showId()) || !sig.equals(r.seatSignature()))
                throw DomainException.conflict("idempotency-conflict",
                        "idempotency key was already used with a different request");
            return new ReserveResult(view(r, r.status()), true);
        }

        // (2) serialize the booking-limit check per (show, user) so parallel keys cannot overshoot.
        reservations.advisoryLock("lim:" + showId + ":" + userId);
        if (seats.countActive(showId, userId) + n > show.perUserLimit())
            throw DomainException.conflict("per-user-limit",
                    "per-user limit of " + show.perUserLimit() + " seats would be exceeded");

        // (3) lock the seats in label order, then decide on the locked rows.
        var locked = seats.lockForUpdate(showId, sig);
        if (locked.size() != n)
            throw DomainException.notFound("seat-not-found", "one or more seats do not exist in this show");
        for (var seat : locked)
            if (!seat.free())
                throw DomainException.conflict("seat-taken", "seat " + seat.label() + " is already taken");

        UUID id = UUID.randomUUID();
        long amount;
        try {
            amount = Math.multiplyExact(show.pricePaise(), (long) n);
        } catch (ArithmeticException e) {
            // the price cap stops new shows overflowing, but a row written before it (or straight into the
            // database) can still hold a price whose total does not fit: a decline, never a 500
            throw DomainException.conflict("amount-too-large", "price x seats is too large to charge");
        }
        boolean hold = show.holdSeconds() != null;
        String status = hold ? "held" : "confirmed";
        var expiresAt = hold ? reservations.expiryAfter(show.holdSeconds()) : null;
        try {
            reservations.insert(id, showId, userId, status, amount, n, key, sig, expiresAt);
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("idempotency-conflict", "duplicate idempotency key");
        }

        // (4) the guarded test-and-set. Rows are already locked, so this must claim all n.
        if (seats.claim(showId, sig, userId, id, status, expiresAt) != n)
            throw DomainException.conflict("seat-taken", "seat was taken concurrently");

        return new ReserveResult(new ReservationView(id, showId, userId, List.of(sig.split(",")), amount, status, expiresAt), false);
    }

    /**
     * Owner-only: turns a live hold into a sale. Idempotent (confirming a sale again is a no-op). A hold that
     * has lapsed is refused with 409 and never resurrected, even if nobody else has taken the seat yet.
     */
    @Transactional
    public ReserveResult confirm(String userId, UUID reservationId) {
        var r = lockOwned(userId, reservationId);
        if ("confirmed".equals(r.status())) return new ReserveResult(view(r, "confirmed"), true);
        // same answer whether or not the sweeper has already tidied the record
        if ("expired".equals(r.status())) throw DomainException.conflict("hold-expired", "the hold expired; reserve again");
        if (!"held".equals(r.status()))
            throw DomainException.conflict("not-confirmable", "reservation is " + r.status());

        seats.lockByReservation(reservationId);
        int expected = r.seatSignature().split(",").length;
        if (seats.confirmHeld(reservationId, userId) != expected)
            throw DomainException.conflict("hold-expired", "the hold expired; reserve again");
        reservations.markConfirmed(reservationId);
        return new ReserveResult(view(r, "confirmed"), false);
    }

    /** Owner-only release of a hold or a sale. Idempotent: cancelling twice is a no-op, never a second release. */
    @Transactional
    public ReservationView cancel(String userId, UUID reservationId) {
        var r = lockOwned(userId, reservationId);
        if (!"confirmed".equals(r.status()) && !"held".equals(r.status())) return view(r, r.status());

        // same label order as reserve(), so a cancel can never deadlock against a reserve
        seats.lockByReservation(reservationId);
        // matches reservation_id, so it can never free a seat that now belongs to someone else
        seats.releaseByReservation(reservationId);
        reservations.markCancelled(reservationId);
        return view(r, "cancelled");
    }

    /** Housekeeping only: correctness already treats a lapsed hold as free, this just tidies the records. */
    @Transactional
    public int sweepExpired() {
        seats.releaseExpired();
        return reservations.markExpired();
    }

    private ReservationRecord lockOwned(String userId, UUID reservationId) {
        var r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> DomainException.notFound("reservation-not-found", "no such reservation"));
        if (!userId.equals(r.userId()))
            throw DomainException.forbidden("not-owner", "reservation belongs to another user");
        return r;
    }

    private static ReservationView view(ReservationRecord r, String status) {
        return new ReservationView(r.id(), r.showId(), r.userId(),
                Arrays.asList(r.seatSignature().split(",")), r.amountPaise(), status,
                "held".equals(status) ? r.expiresAt() : null);
    }
}
