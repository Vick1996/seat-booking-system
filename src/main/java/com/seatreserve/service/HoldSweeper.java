package com.seatreserve.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks lapsed holds as expired and frees their seats. Housekeeping: a lapsed hold is already bookable without it. */
@Component
@EnableScheduling
public class HoldSweeper {
    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);
    private final ReservationService reservations;

    public HoldSweeper(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${seat.sweep-interval-ms:10000}", initialDelayString = "${seat.sweep-interval-ms:10000}")
    public void sweep() {
        try {
            int n = reservations.sweepExpired();
            if (n > 0) log.info("expired {} holds", n);
        } catch (RuntimeException e) {
            // housekeeping must never take the service down; the next tick retries
            log.warn("hold sweep failed: {}", e.toString());
        }
    }
}
