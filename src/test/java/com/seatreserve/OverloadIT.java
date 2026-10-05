package com.seatreserve;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saturation must be shed as a 4xx, never a 5xx. A lock wait longer than lock_timeout is exactly what a
 * starved instance produces (measured at 0.1 CPU: five 500s, all "canceling statement due to lock timeout").
 * Needs Docker; run with `./mvnw verify`.
 */
@Tag("it")
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverloadIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void utc() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // a short lock wait so the test does not sit through the production 10s
        r.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout='500ms'; SET statement_timeout='30s'");
    }

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    DataSource dataSource;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void lockTimeout_isShedAs429_neverA500_andTheServiceRecovers() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String user = token("{\"user_id\":\"vera\"}");
        String show = id(post("/shows", admin, "{\"name\":\"o-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"]}").body());
        double declinedBefore = overloadedCount();

        // someone else holds the seat's row lock for longer than lock_timeout
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select 1 from seats where show_id = '" + show + "' and label = 'A1' for update");

            var res = post("/shows/" + show + "/reserve", user, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}");
            assertEquals(429, res.statusCode(), "a lock timeout is saturation, not a server error: " + res.body());
            assertTrue(res.body().contains("overloaded"), res.body());
            holder.rollback();
        }

        assertEquals(1, overloadedCount() - declinedBefore, "counted as an overloaded decline");
        // nothing was half-done: the same request succeeds once the lock is gone
        assertEquals(201, post("/shows/" + show + "/reserve", user, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}").statusCode());
    }

    @Test
    void aDeclineOnASoldSeat_takesNoLocks_soItIsNeverStuckBehindARowLock() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String alice = token("{\"user_id\":\"alice\"}"), bob = token("{\"user_id\":\"bob\"}");
        String show = id(post("/shows", admin, "{\"name\":\"d-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"]}").body());
        assertEquals(201, post("/shows/" + show + "/reserve", alice, "{\"seats\":[\"A1\"],\"idempotency_key\":\"a1\"}").statusCode());

        // ~83% of a stampede is losers asking for a seat that is already sold; they must not queue for its row lock
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select 1 from seats where show_id = '" + show + "' and label = 'A1' for update");

            long t0 = System.nanoTime();
            var res = post("/shows/" + show + "/reserve", bob, "{\"seats\":[\"A1\"],\"idempotency_key\":\"b1\"}");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(409, res.statusCode(), "a sold seat is a clean decline, not a lock timeout: " + res.body());
            assertTrue(res.body().contains("seat-taken"), res.body());
            assertTrue(ms < 400, "and it answers without waiting on the lock (lock_timeout here is 500ms): " + ms + "ms");
            holder.rollback();
        }
    }

    // ---- helpers ----

    double overloadedCount() throws Exception {
        double sum = 0;
        for (String line : http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body().split("\n"))
            if (line.startsWith("reservations_declined_total") && line.contains("overloaded"))
                sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        return sum;
    }

    String token(String body) throws Exception {
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(post("/auth/token", null, body).body());
        m.find();
        return m.group(1);
    }

    static String id(String body) {
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        m.find();
        return m.group(1);
    }

    HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
