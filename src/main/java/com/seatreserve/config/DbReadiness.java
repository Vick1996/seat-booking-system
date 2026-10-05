package com.seatreserve.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness that fails closed and fast, and measures "can I reach the database", not "is the reservation
 * pool free". It uses {@link ProbeDb}, so a saturated pool cannot turn a busy service into a "not ready"
 * one. The check is bounded by ProbeDb's connect and socket timeouts (well under a platform's 5s limit).
 */
@Component("seatDb")
public class DbReadiness implements HealthIndicator {
    private final ProbeDb probe;
    private volatile Health last = Health.down().withDetail("reason", "no probe has completed yet").build();

    public DbReadiness(ProbeDb probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        // when another probe is already running, reuse its latest answer instead of opening a second session
        return probe.run(jdbc -> {
            try {
                jdbc.queryForObject("select 1", Integer.class);
                last = Health.up().build();
            } catch (RuntimeException e) {
                last = Health.down().withDetail("reason", "database unreachable").build();
            }
            return last;
        }, last);
    }
}
