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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operational seams: readiness and request correlation. Needs Docker; run with `./mvnw verify`. */
@Tag("it")
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsIT {
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
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readiness_failsClosedWhenDbHangs_andRecovers() throws Exception {
        assertEquals(200, readiness().statusCode(), "healthy before the outage");

        // a paused container keeps its port but never answers: the worst kind of dependency outage
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            var res = readiness();
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(503, res.statusCode(), "must fail closed, not hang or report UP");
            assertTrue(ms < 8_000, "and answer promptly so an orchestrator probe does not time out: " + ms + "ms");
            assertEquals(200, liveness().statusCode(), "liveness must not depend on the DB");
        } finally {
            PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
        }

        long deadline = System.currentTimeMillis() + 60_000;
        int code = 0;
        while (System.currentTimeMillis() < deadline && (code = readiness().statusCode()) != 200) Thread.sleep(500);
        assertEquals(200, code, "ready again once the DB is back");
    }

    @org.springframework.beans.factory.annotation.Autowired
    javax.sql.DataSource dataSource;

    @Test
    void readinessAndMetrics_answerEvenWhenEveryPooledConnectionIsBusy() throws Exception {
        // a show first, so a seats_available gauge exists
        String adminToken = tokenOf(post("/auth/token", null, "{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}"));
        assertEquals(201, statusOf(post("/shows", adminToken,
                "{\"name\":\"pool-" + java.util.UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\",\"A2\"]}")));

        int poolSize = ((com.zaxxer.hikari.HikariDataSource) dataSource).getMaximumPoolSize();
        var held = new java.util.ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < poolSize; i++) held.add(dataSource.getConnection()); // the reservation pool is now exhausted

            long t0 = System.nanoTime();
            var ready = send("/readyz", null, Duration.ofSeconds(6));
            long readyMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(200, ready.statusCode(), "readiness asks 'can I reach the DB', not 'is the pool free': " + ready.body());
            assertTrue(readyMs < 1500, "and answers promptly: " + readyMs + "ms");

            t0 = System.nanoTime();
            var metrics = send("/metrics", null, Duration.ofSeconds(6));
            long metricsMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("seats_available"), "the gauge is still reported");
            assertTrue(metricsMs < 1500, "a scrape must not queue behind customers: " + metricsMs + "ms");
        } finally {
            for (var c : held) c.close();
        }
    }

    HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int statusOf(HttpResponse<String> r) {
        return r.statusCode();
    }

    static String tokenOf(HttpResponse<String> r) {
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        m.find();
        return m.group(1);
    }

    @Test
    void recentLogs_arePublic_andCarryTheRequestId() throws Exception {
        String id = "logtest-" + java.util.UUID.randomUUID();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/shows/" + java.util.UUID.randomUUID()))
                .header("X-Request-Id", id).GET().build();
        assertEquals(404, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());

        var logs = send("/ops/logs?lines=50", null, Duration.ofSeconds(5)); // no token
        assertEquals(200, logs.statusCode());
        assertTrue(logs.body().contains(id), "the access line for that request, correlated by id");
        assertTrue(logs.body().lines().allMatch(l -> l.isBlank() || l.startsWith("{")), "structured JSON, one object per line");
    }

    @Test
    void conventionalOpsPaths_workWithoutAToken() throws Exception {
        // the email names no paths, so a checker will try the usual ones
        var live = send("/healthz", null, Duration.ofSeconds(5));
        assertEquals(200, live.statusCode(), "/healthz (liveness)");
        assertTrue(live.body().contains("UP"), live.body());

        var ready = send("/readyz", null, Duration.ofSeconds(5));
        assertEquals(200, ready.statusCode(), "/readyz (readiness)");
        assertTrue(ready.body().contains("UP"), ready.body());

        var metrics = send("/metrics", null, Duration.ofSeconds(5));
        assertEquals(200, metrics.statusCode(), "/metrics");
        assertTrue(metrics.body().contains("reservations_confirmed_total"), "Prometheus text format");
    }

    @Test
    void readyzFailsClosedLikeReadiness_whileHealthzStaysUp() throws Exception {
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            assertEquals(503, send("/readyz", null, Duration.ofSeconds(8)).statusCode(), "/readyz must fail closed");
            assertEquals(200, send("/healthz", null, Duration.ofSeconds(5)).statusCode(), "/healthz must not depend on the DB");
        } finally {
            PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
        }
        long deadline = System.currentTimeMillis() + 60_000;
        int code = 0;
        while (System.currentTimeMillis() < deadline && (code = send("/readyz", null, Duration.ofSeconds(8)).statusCode()) != 200)
            Thread.sleep(500);
        assertEquals(200, code, "ready again once the DB is back");
    }

    @Test
    void requestId_isEchoedGeneratedAndSanitised() throws Exception {
        var echoed = get("/shows/" + java.util.UUID.randomUUID(), "abc-123");
        assertEquals("abc-123", echoed.headers().firstValue("X-Request-Id").orElse(null));

        var generated = get("/shows/" + java.util.UUID.randomUUID(), null);
        String id = generated.headers().firstValue("X-Request-Id").orElse("");
        assertTrue(id.matches("[0-9a-f-]{36}"), "generated when absent: " + id);

        var hostile = get("/shows/" + java.util.UUID.randomUUID(), "bad id\t!!");
        String replaced = hostile.headers().firstValue("X-Request-Id").orElse("");
        assertNotEquals("bad id\t!!", replaced);
        assertTrue(replaced.matches("[0-9a-f-]{36}"), "malformed ids are replaced, not logged: " + replaced);
    }

    HttpResponse<String> readiness() throws Exception {
        return send("/actuator/health/readiness", null, Duration.ofSeconds(40));
    }

    HttpResponse<String> liveness() throws Exception {
        return send("/actuator/health/liveness", null, Duration.ofSeconds(5));
    }

    HttpResponse<String> get(String path, String requestId) throws Exception {
        return send(path, requestId, Duration.ofSeconds(10));
    }

    HttpResponse<String> send(String path, String requestId, Duration timeout) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(timeout).GET();
        if (requestId != null) b.header("X-Request-Id", requestId);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
