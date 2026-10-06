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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                {"idempotency key containing a NUL", url, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\\u0000x\"}", 400},
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
    void twoShowsMayShareAName() throws Exception {
        // the email's own example is "friday-night"; a checker re-running against the same URL creates it again
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String body = "{\"name\":\"friday-night\",\"price_paise\":25000,\"seats\":[\"A1\",\"A2\"]}";
        var first = post("/shows", admin, body);
        var second = post("/shows", admin, body);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(201, second.statusCode(), "a show name is a label, not a key: " + second.body());
        assertTrue(!id(first.body()).equals(id(second.body())), "each gets its own id");
    }

    @Test
    void aShowMayHoldExactlyTheMaximumNumberOfSeats() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String seats = String.join(",", java.util.stream.IntStream.rangeClosed(1, 500).mapToObj(i -> "\"S" + i + "\"").toList());
        var show = post("/shows", admin, "{\"name\":\"full-hall-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[" + seats + "]}");
        assertEquals(201, show.statusCode(), "500 seats is the limit, and it is allowed: " + show.body());
        assertTrue(show.body().contains("\"total_seats\":500"), "every seat is created");
    }

    @Test
    void thePriceCap_leavesRoomToReserveTheMostSeatsOneRequestAllows() throws Exception {
        // at the cap, the largest request (50 seats) must not overflow a 64-bit amount
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String user = token("{\"user_id\":\"whale\"}");
        String seats = String.join(",", java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(i -> "\"S" + i + "\"").toList());
        var show = post("/shows", admin, "{\"name\":\"cap-" + UUID.randomUUID() + "\",\"price_paise\":1000000000000,"
                + "\"per_user_limit\":50,\"seats\":[" + seats + "]}");
        assertEquals(201, show.statusCode(), "a price exactly at the cap is allowed: " + show.body());
        var res = post("/shows/" + id(show.body()) + "/reserve", user, "{\"seats\":[" + seats + "],\"idempotency_key\":\"big-1\"}");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"amount_paise\":50000000000000"), "50 x 10^12 paise: " + res.body());
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void aShowThatPredatesThePriceCap_isNeverA500() throws Exception {
        // a row written before the cap existed, or straight into the database, can hold a price whose total overflows
        UUID show = UUID.randomUUID();
        jdbc.update("insert into shows(id, name, price_paise, per_user_limit) values (?,?,?,4)", show, "legacy", Long.MAX_VALUE);
        jdbc.update("insert into seats(show_id, label) values (?, 'A1'), (?, 'A2')", show, show);

        var res = post("/shows/" + show + "/reserve", token("{\"user_id\":\"vera\"}"),
                "{\"seats\":[\"A1\",\"A2\"],\"idempotency_key\":\"legacy-1\"}");
        assertEquals(409, res.statusCode(), "price x seats overflows: a decline, not a server error: " + res.body());
        assertTrue(res.body().contains("amount-too-large"), res.body());
        // nothing was half-done: the seats are still free
        assertTrue(send2Get("/shows/" + show).contains("\"available\":2"));
    }

    String send2Get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void tokenMint_rejectsControlCharactersInTheUserId() throws Exception {
        // a NUL here is accepted into a token and then makes every later database call fail
        assertEquals(400, send("POST", "/auth/token", null, "{\"user_id\":\"bad\\u0000user\"}", "application/json"));
        assertEquals(400, send("POST", "/auth/token", null, "{\"user_id\":\"two\\nlines\"}", "application/json"));
        assertEquals(200, send("POST", "/auth/token", null, "{\"user_id\":\"fine-user_1\"}", "application/json"));
    }

    HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String id(String body) {
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        m.find();
        return m.group(1);
    }

    @Test
    void createShowRejectsBadInput_withClientErrors() throws Exception {
        String admin = token("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String tooMany = String.join(",", java.util.stream.IntStream.rangeClosed(1, 501).mapToObj(i -> "\"S" + i + "\"").toList());

        Object[][] cases = {
                {"name containing a NUL (Postgres cannot store it)", "{\"name\":\"a\\u0000b\",\"price_paise\":100,\"seats\":[\"A1\"]}", 400},
                {"name containing a newline", "{\"name\":\"a\\nb\",\"price_paise\":100,\"seats\":[\"A1\"]}", 400},
                {"price above the cap", "{\"name\":\"n30-" + UUID.randomUUID() + "\",\"price_paise\":1000000000001,\"seats\":[\"A1\"]}", 400},
                {"price that would overflow an amount", "{\"name\":\"n31-" + UUID.randomUUID() + "\",\"price_paise\":9223372036854775807,\"seats\":[\"A1\"]}", 400},
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
                {"hold_seconds zero", "{\"name\":\"n20-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"hold_seconds\":0}", 400},
                {"hold_seconds negative", "{\"name\":\"n21-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"hold_seconds\":-5}", 400},
                {"hold_seconds over a day", "{\"name\":\"n22-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"hold_seconds\":86401}", 400},
                {"fractional hold_seconds", "{\"name\":\"n23-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"],\"hold_seconds\":1.5}", 400},
                {"more than 500 seats (a cinema or theatre hall is the largest show)", "{\"name\":\"n12-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[" + tooMany + "]}", 400},
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
