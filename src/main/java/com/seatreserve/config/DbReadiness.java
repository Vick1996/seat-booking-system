package com.seatreserve.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Readiness that fails closed AND fast. The stock db indicator borrows from the pool, which can
 * block for the full connection timeout (or forever on a hung socket), so an orchestrator probe
 * would time out instead of seeing a clean 503. Here the check runs under a hard deadline.
 */
@Component("seatDb")
public class DbReadiness implements HealthIndicator {
    private static final long DEADLINE_MS = 2_000;
    private final JdbcTemplate jdbc;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    public DbReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        Future<Integer> f = probes.submit(() -> jdbc.queryForObject("select 1", Integer.class));
        try {
            f.get(DEADLINE_MS, TimeUnit.MILLISECONDS);
            return Health.up().build();
        } catch (Exception e) {
            f.cancel(true);
            return Health.down().withDetail("reason", e instanceof java.util.concurrent.TimeoutException
                    ? "database did not answer within " + DEADLINE_MS + "ms" : "database unreachable").build();
        }
    }
}
