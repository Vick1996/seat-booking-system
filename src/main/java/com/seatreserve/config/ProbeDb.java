package com.seatreserve.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Database access for health checks and metrics that never touches the reservation pool. Probes that
 * borrow from that pool queue behind customers, so under load readiness flipped to 503 and scrapes took
 * seconds, which is exactly when a platform decides whether to keep routing to us. This opens a fresh
 * short-lived connection with tight timeouts, and at most ONE at a time, so probes can never add more
 * than a single session to the database however hard they are hit.
 */
@Component
public class ProbeDb {
    private final JdbcTemplate jdbc;
    private final Semaphore one = new Semaphore(1);

    public ProbeDb(DataSourceProperties props) {
        var ds = new DriverManagerDataSource(props.determineUrl(), props.determineUsername(), props.determinePassword());
        var timeouts = new Properties();
        timeouts.setProperty("connectTimeout", "2"); // seconds; a dead or paused DB must fail fast
        timeouts.setProperty("socketTimeout", "3");
        ds.setConnectionProperties(timeouts);
        this.jdbc = new JdbcTemplate(ds);
        this.jdbc.setQueryTimeout(3);
    }

    /** Runs {@code work} on the probe connection, or returns {@code whenBusy} if another probe is mid-flight. */
    public <T> T run(Function<JdbcTemplate, T> work, T whenBusy) {
        if (!one.tryAcquire()) return whenBusy;
        try {
            return work.apply(jdbc);
        } finally {
            one.release();
        }
    }
}
