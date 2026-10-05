package com.seatreserve.service;

import com.seatreserve.exception.DomainException;
import com.seatreserve.repository.ReservationRepository;
import com.seatreserve.repository.ReservationRepository.ReservationRecord;
import com.seatreserve.repository.SeatRepository;
import com.seatreserve.repository.ShowRepository;
import com.seatreserve.repository.ShowRepository.ShowRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lock-free decline path. The repositories are scripted because the interleavings below cannot be forced
 * through the real database: a duplicate's two lookups straddling the winner's commit is a window of microseconds,
 * and a stress test that hunts for it passed with the guard removed.
 */
class ReservationServiceTest {
    private final UUID showId = UUID.randomUUID();
    private ShowRepository shows;
    private SeatRepository seats;
    private ReservationRepository reservations;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        shows = mock(ShowRepository.class);
        seats = mock(SeatRepository.class);
        reservations = mock(ReservationRepository.class);
        service = new ReservationService(shows, seats, reservations);
        when(shows.find(showId)).thenReturn(Optional.of(new ShowRecord("n", 100, 4, null)));
    }

    @Test
    void aDuplicateThatLosesTheRace_getsTheOriginalsReplay_notAConflictForItsOwnSeat() {
        var winner = new ReservationRecord(UUID.randomUUID(), showId, "alice", "confirmed", 100, "A1", null);
        // lookup #1 runs before the winner commits (misses); the seat read then runs after it (taken);
        // lookup #2 therefore finds the winner's reservation
        when(reservations.findByUserAndKey("alice", "k")).thenReturn(Optional.empty(), Optional.of(winner));
        when(seats.anyTaken(showId, "A1")).thenReturn(true);

        var result = service.reserve("alice", showId, List.of("A1"), "k");

        assertTrue(result.replay(), "a retry gets the original reservation (200), never a 409 for its own seat");
        assertEquals(winner.id(), result.reservation().reservationId());
    }

    @Test
    void aLoserOnATakenSeat_isDeclinedWithoutAnyLockOrWrite() {
        when(reservations.findByUserAndKey("bob", "k")).thenReturn(Optional.empty());
        when(seats.anyTaken(showId, "A1")).thenReturn(true);

        var e = assertThrows(DomainException.class, () -> service.reserve("bob", showId, List.of("A1"), "k"));

        assertEquals("seat-taken", e.reason());
        // the whole point: no advisory lock, no row lock, no insert, no claim
        verify(reservations, never()).advisoryLock(anyString());
        verify(seats, never()).lockForUpdate(any(), anyString());
        verify(reservations, never()).insert(any(), any(), anyString(), anyString(), anyLong(), anyInt(), anyString(), anyString(), any());
        verify(seats, never()).claim(any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aSameKeyDifferentRequestOnATakenSeat_isStillAnIdempotencyConflict() {
        var other = new ReservationRecord(UUID.randomUUID(), showId, "alice", "confirmed", 100, "A2", null);
        when(reservations.findByUserAndKey("alice", "k")).thenReturn(Optional.empty(), Optional.of(other));
        when(seats.anyTaken(showId, "A1")).thenReturn(true);

        var e = assertThrows(DomainException.class, () -> service.reserve("alice", showId, List.of("A1"), "k"));

        assertEquals("idempotency-conflict", e.reason());
    }
}
