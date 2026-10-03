package com.seatreserve;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/** Bad input must always be a clean 4xx, never a 5xx. Needs Docker; run with `./mvnw verify`. */
@Tag("it")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidationIT {
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
    void unknownRoute_wrongMethod_wrongContentType_areClientErrors() throws Exception {
        String user = token("{\"user_id\":\"vera\"}");

        assertEquals(404, send("GET", "/no/such/route", user, null, null), "unknown route");
        assertEquals(405, send("POST", "/shows/" + UUID.randomUUID(), user, "{}", "application/json"),
                "POST to a GET-only route");
        assertEquals(415, send("POST", "/auth/token", null, "user_id=vera", "text/plain"),
                "body that is not JSON");
    }

    @Test
    void reserveRejectsBadInput_withClientErrors() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String user = token("{\"user_id\":\"vera\"}");
        String show = showId(send2("POST", "/shows", admin,
                "{\"name\":\"v-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\",\"A2\"]}"));
        String url = "/shows/" + show + "/reserve";
        String fiftyOne = String.join(",", java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(i -> "\"A" + i + "\"").toList());

        // {description, path, body, expected status}
        Object[][] cases = {
                // an ABSENT key is a valid unique request (see ConcurrencyIT); a blank one is a client bug
                {"blank idempotency key", url, "{\"seats\":[\"A1\"],\"idempotency_key\":\" \"}", 400},
                {"no seats field", url, "{\"idempotency_key\":\"k\"}", 400},
                {"empty seats", url, "{\"seats\":[],\"idempotency_key\":\"k\"}", 400},
                {"duplicate seat in request", url, "{\"seats\":[\"A1\",\"A1\"],\"idempotency_key\":\"k\"}", 400},
                {"invalid label", url, "{\"seats\":[\"A 1\"],\"idempotency_key\":\"k\"}", 400},
                {"null seat entry", url, "{\"seats\":[null],\"idempotency_key\":\"k\"}", 400},
                {"too many seats (51)", url, "{\"seats\":[" + fiftyOne + "],\"idempotency_key\":\"k\"}", 400},
                {"seats is not a list", url, "{\"seats\":\"A1\",\"idempotency_key\":\"k\"}", 400},
                {"malformed json", url, "{\"seats\":", 400},
                {"empty body", url, "", 400},
                {"json null body", url, "null", 400},
                {"unknown seat", url, "{\"seats\":[\"Z9\"],\"idempotency_key\":\"k\"}", 404},
                {"unknown show", "/shows/" + UUID.randomUUID() + "/reserve", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}", 404},
                {"show id is not a uuid", "/shows/not-a-uuid/reserve", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}", 400},
        };
        var wrong = new java.util.ArrayList<String>();
        for (Object[] c : cases) {
            int got = send("POST", (String) c[1], user, (String) c[2], "application/json");
            if (got != (int) c[3]) wrong.add(c[0] + ": expected " + c[3] + " but got " + got);
        }
        assertEquals(java.util.List.of(), wrong, "every bad reserve must be a clean 4xx");
    }

    @Test
    void createShowRejectsBadInput_withClientErrors() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String name = "dup-" + UUID.randomUUID();
        assertEquals(201, send("POST", "/shows", admin,
                "{\"name\":\"" + name + "\",\"price_paise\":100,\"seats\":[\"A1\"]}", "application/json"));
        String tooMany = String.join(",", java.util.stream.IntStream.rangeClosed(1, 100_001).mapToObj(i -> "\"S" + i + "\"").toList());

        Object[][] cases = {
                {"duplicate show name", "{\"name\":\"" + name + "\",\"price_paise\":100,\"seats\":[\"A1\"]}", 409},
                {"missing name", "{\"price_paise\":100,\"seats\":[\"A1\"]}", 400},
                {"blank name", "{\"name\":\" \",\"price_paise\":100,\"seats\":[\"A1\"]}", 400},
                {"missing price", "{\"name\":\"n1-" + UUID.randomUUID() + "\",\"seats\":[\"A1\"]}", 400},
                {"negative price", "{\"name\":\"n2-" + UUID.randomUUID() + "\",\"price_paise\":-1,\"seats\":[\"A1\"]}", 400},
                {"fractional price (money is never a float)", "{\"name\":\"n3-" + UUID.randomUUID() + "\",\"price_paise\":100.5,\"seats\":[\"A1\"]}", 400},
                {"price is not a number", "{\"name\":\"n4-" + UUID.randomUUID() + "\",\"price_paise\":\"abc\",\"seats\":[\"A1\"]}", 400},
                {"no seats", "{\"name\":\"n5-" + UUID.randomUUID() + "\",\"price_paise\":100}", 400},
                {"empty seats", "{\"name\":\"n6-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[]}", 400},
                {"duplicate seat label", "{\"name\":\"n7-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\",\"A1\"]}", 400},
                {"invalid seat label", "{\"name\":\"n8-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A,1\"]}", 400},
                {"null seat label", "{\"name\":\"n9-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[null]}", 400},
                {"per_user_limit zero", "{\"name\":\"n10-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"per_user_limit\":0}", 400},
                {"fractional per_user_limit", "{\"name\":\"n11-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"per_user_limit\":1.5}", 400},
                {"more than 100000 seats", "{\"name\":\"n12-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[" + tooMany + "]}", 400},
                {"malformed json", "{\"name\":", 400},
                {"empty body", "", 400},
        };
        var wrong = new java.util.ArrayList<String>();
        for (Object[] c : cases) {
            int got = send("POST", "/shows", admin, (String) c[1], "application/json");
            if (got != (int) c[2]) wrong.add(c[0] + ": expected " + c[2] + " but got " + got);
        }
        assertEquals(java.util.List.of(), wrong, "every bad show must be a clean 4xx");

        String user = token("{\"user_id\":\"vera\"}");
        assertEquals(403, send("POST", "/shows", user, "{\"name\":\"x\",\"price_paise\":1,\"seats\":[\"A1\"]}", "application/json"),
                "non-admin");
        assertEquals(401, send("POST", "/shows", null, "{\"name\":\"x\",\"price_paise\":1,\"seats\":[\"A1\"]}", "application/json"),
                "no token");
    }

    // ---- helpers ----

    String send2(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String showId(String body) {
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        m.find();
        return m.group(1);
    }

    int send(String method, String path, String token, String body, String contentType) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    String token(String body) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        m.find();
        return m.group(1);
    }
}
