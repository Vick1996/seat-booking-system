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
        if (seats.countConfirmed(showId, userId) + n > show.perUserLimit())
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
        long amount = Math.multiplyExact(show.pricePaise(), (long) n);
        try {
            reservations.insert(id, showId, userId, amount, n, key, sig);
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("idempotency-conflict", "duplicate idempotency key");
        }

        // (4) the guarded test-and-set. Rows are already locked, so this must claim all n.
        if (seats.claim(showId, sig, userId, id) != n)
            throw DomainException.conflict("seat-taken", "seat was taken concurrently");

        return new ReserveResult(new ReservationView(id, showId, userId, List.of(sig.split(",")), amount,
                "confirmed"), false);
    }

    /** Owner-only release. Idempotent: cancelling twice is a no-op, never a second release. */
    @Transactional
    public ReservationView cancel(String userId, UUID reservationId) {
        var r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> DomainException.notFound("reservation-not-found", "no such reservation"));
        if (!userId.equals(r.userId()))
            throw DomainException.forbidden("not-owner", "reservation belongs to another user");
        if (!"confirmed".equals(r.status())) return view(r, r.status());

        seats.lockByReservation(reservationId);
        seats.releaseByReservation(reservationId);
        reservations.markCancelled(reservationId);
        return view(r, "cancelled");
    }

    private static ReservationView view(ReservationRecord r, String status) {
        return new ReservationView(r.id(), r.showId(), r.userId(),
                Arrays.asList(r.seatSignature().split(",")), r.amountPaise(), status);
    }
}
