package com.seatreserve.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus names: reservations_confirmed_total, reservations_declined_total{reason},
 * seats_available{show_id}. Counters are bumped by callers AFTER the transaction commits, so a
 * rolled-back attempt can never inflate them and they reconcile with the API state.
 */
@Component
public class SeatMetrics {
    private static final List<String> REASONS = List.of(
            "seat-taken", "per-user-limit", "idempotent-replay", "idempotency-conflict", "overloaded");
    private static final int MAX_TRACKED_SHOWS = 50;

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final ProbeDb probe;
    private final Counter confirmed;
    private final Counter held;
    private final ConcurrentHashMap<UUID, Boolean> tracked = new ConcurrentHashMap<>();

    public SeatMetrics(MeterRegistry registry, JdbcTemplate jdbc, ProbeDb probe) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.probe = probe;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed (seats sold)").register(registry);
        this.held = Counter.builder("reservations.held")
                .description("Time-boxed holds placed (shows created with hold_seconds)").register(registry);
        // pre-register so every reason is visible at 0 from the first scrape
        REASONS.forEach(r -> declinedCounter(r));
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void held() {
        held.increment();
    }

    /** Ignores reasons that are not reservation declines (e.g. show-exists), keeping cardinality fixed. */
    public void declined(String reason) {
        if (REASONS.contains(reason)) declinedCounter(reason).increment();
    }

    /**
     * Idempotent. The gauge reads the DB on every scrape, so it always matches GET /shows/{id}. It uses the
     * dedicated probe connection, not the reservation pool: a scrape must not queue behind customers. If the
     * probe connection is busy or the query fails, the last good value is reported instead of an error.
     */
    public void trackShow(UUID showId) {
        if (tracked.size() >= MAX_TRACKED_SHOWS || tracked.putIfAbsent(showId, true) != null) return;
        double[] lastGood = {0};
        Gauge.builder("seats.available", () -> probe.run(j -> {
                    try {
                        Integer n = j.queryForObject(
                                // a lapsed hold is available, matching GET /shows/{id}
                                "select count(*) from seats where show_id = ? and (status = 'available' "
                                        + "or (status = 'held' and expires_at < clock_timestamp()))",
                                Integer.class, showId);
                        lastGood[0] = n == null ? 0 : n;
                    } catch (RuntimeException e) {
                        // keep the last good value; a failed scrape must not become an error
                    }
                    return lastGood[0];
                }, lastGood[0]))
                .tag("show_id", showId.toString())
                .description("Seats currently available")
                .register(registry);
    }

    /** Picks up shows created before this process started (restarts, redeploys). */
    @EventListener(ApplicationReadyEvent.class)
    public void trackExistingShows() {
        jdbc.queryForList("select id from shows order by created_at desc limit " + MAX_TRACKED_SHOWS, UUID.class)
                .forEach(this::trackShow);
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined").tag("reason", reason)
                .description("Reservation attempts declined, by reason").register(registry);
    }
}
