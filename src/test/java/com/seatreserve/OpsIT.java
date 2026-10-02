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
