package com.seatreserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared setup for the time-boxed hold tests. Subclasses choose how often the sweeper runs. */
@Tag("it")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class HoldTestBase {
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
    final ObjectMapper json = new ObjectMapper();

    /** Keys are scoped per user and tests share one database, so every request gets its own. */
    static String key() {
        return UUID.randomUUID().toString();
    }

    String reserveBody(String seat) {
        return "{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"" + key() + "\"}";
    }

    String createShow(Integer holdSeconds, String... seats) throws Exception {
        return createShow(holdSeconds, null, seats);
    }

    String createShow(Integer holdSeconds, Integer perUserLimit, String... seats) throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String extra = (holdSeconds == null ? "" : ",\"hold_seconds\":" + holdSeconds)
                + (perUserLimit == null ? "" : ",\"per_user_limit\":" + perUserLimit);
        var res = post("/shows", admin, "{\"name\":\"h-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":["
                + String.join(",", java.util.Arrays.stream(seats).map(s -> "\"" + s + "\"").toList()) + "]" + extra + "}");
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asText();
    }

    void assertState(String show, int available, int held, int confirmed) throws Exception {
        JsonNode s = json.readTree(get("/shows/" + show).body());
        assertEquals(available, s.get("available").asInt(), "available");
        assertEquals(held, s.get("held").asInt(), "held");
        assertEquals(confirmed, s.get("confirmed").asInt(), "confirmed");
        assertEquals(s.get("total_seats").asInt(), available + held + confirmed, "available + held + confirmed == total_seats");
    }

    String userToken(String user) throws Exception {
        return token("{\"user_id\":\"" + user + "\"}");
    }

    String token(String body) throws Exception {
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(post("/auth/token", null, body).body());
        m.find();
        return m.group(1);
    }

    String reservationId(HttpResponse<String> res) throws Exception {
        return json.readTree(res.body()).get("reservation_id").asText();
    }

    HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
