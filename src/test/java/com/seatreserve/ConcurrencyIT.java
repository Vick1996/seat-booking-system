package com.seatreserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Needs Docker (Testcontainers). Run with `./mvnw verify`. */
@Tag("it")
@Testcontainers
@AutoConfigureObservability // tests default to a no-op registry; we want the real /actuator/prometheus
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyIT {
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

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final ObjectMapper json = new ObjectMapper();

    // ---- scenarios ----

    @Test
    void hotSeatStorm_exactlyOneWinner() throws Exception {
        String show = createShow(List.of("A11", "A12", "A13"));
        int buyers = 300;
        List<String> tokens = IntStream.range(0, buyers).mapToObj(i -> token("storm-" + i)).toList();
        double confirmedBefore = metric("reservations_confirmed_total", "");
        double takenBefore = metric("reservations_declined_total", "reason=\"seat-taken\"");

        var codes = fireAll(buyers, i -> post("/shows/" + show + "/reserve", tokens.get(i),
                "{\"seats\":[\"A12\"],\"idempotency_key\":\"k-" + i + "\"}").statusCode());

        assertEquals(1, count(codes, 201), "exactly one buyer wins seat A12: " + tally(codes));
        assertEquals(buyers - 1, count(codes, 409), "everyone else gets a clean 409: " + tally(codes));
        assertInvariant(show, 3, 2, 0, 1); // 3 seats: A12 confirmed to the winner, A11/A13 untouched

        // metrics must reconcile with what the API reported and with the DB state
        assertEquals(1, metric("reservations_confirmed_total", "") - confirmedBefore, "confirmed counter");
        assertEquals(buyers - 1, metric("reservations_declined_total", "reason=\"seat-taken\"") - takenBefore,
                "seat-taken counter");
        assertEquals(2, metric("seats_available", "show_id=\"" + show + "\""), "seats_available gauge");
    }

    @Test
    void perUserLimit_holdsUnderParallelRequests() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 12).mapToObj(i -> "S" + i).toList();
        String show = createShow(seats);
        String tok = token("greedy");

        // one user, 12 parallel reserves on 12 different seats with 12 different keys, limit = 4
        var codes = fireAll(12, i -> post("/shows/" + show + "/reserve", tok,
                "{\"seats\":[\"" + seats.get(i) + "\"],\"idempotency_key\":\"g-" + i + "\"}").statusCode());

        assertEquals(4, count(codes, 201), "at most (exactly) 4 may succeed: " + tally(codes));
        assertEquals(8, count(codes, 409), tally(codes));
        assertInvariant(show, 12, 8, 0, 4);
    }

    @Test
    void sameIdempotencyKey_inParallel_reservesOnce() throws Exception {
        String show = createShow(List.of("K1", "K2", "K3"));
        String tok = token("retrier");

        var codes = fireAll(40, i -> post("/shows/" + show + "/reserve", tok,
                "{\"seats\":[\"K1\",\"K2\"],\"idempotency_key\":\"same\"}").statusCode());

        assertEquals(1, count(codes, 201), "one creation: " + tally(codes));
        assertEquals(39, count(codes, 200), "the rest are replays of the original: " + tally(codes));
        assertInvariant(show, 3, 1, 0, 2); // 2 seats confirmed once, not 80
    }

    @Test
    void racingDuplicates_neverSeeAConflictForTheirOwnSeat() throws Exception {
        // A duplicate that loses the race to the original must get the original's 200 replay, never a seat-taken
        // 409 for the seat the original just took. Many short rounds, because the window is narrow.
        int rounds = 30, dupes = 8;
        List<String> seats = IntStream.range(0, rounds).mapToObj(i -> "R" + i).toList();
        String show = createShow(seats);
        var wrong = new ArrayList<String>();
        for (int round = 0; round < rounds; round++) {
            final String tok = token("racer-" + round), seat = seats.get(round), key = "dup-" + round;
            var codes = fireAll(dupes, i -> post("/shows/" + show + "/reserve", tok,
                    "{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"" + key + "\"}").statusCode());
            if (count(codes, 201) != 1 || count(codes, 200) != dupes - 1) wrong.add("round " + round + ": " + tally(codes));
        }
        assertEquals(List.of(), wrong, "exactly one 201 and the rest replays, every round");
    }

    @Test
    void missingIdempotencyKey_isAUniqueRequestNotAnError() throws Exception {
        String show = createShow(List.of("N1", "N2", "N3"));
        String alice = token("alice-nokey"), bob = token("bob-nokey");

        var first = post("/shows/" + show + "/reserve", alice, "{\"seats\":[\"N1\"]}");
        var second = post("/shows/" + show + "/reserve", alice, "{\"seats\":[\"N2\"]}");
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(201, second.statusCode(), second.body());
        assertTrue(!json.readTree(first.body()).get("reservation_id").asText()
                .equals(json.readTree(second.body()).get("reservation_id").asText()), "each keyless request is its own reservation");

        // without a key there is no retry protection, but a seat is still never sold twice
        assertEquals(409, post("/shows/" + show + "/reserve", bob, "{\"seats\":[\"N1\"]}").statusCode());
        assertInvariant(show, 3, 1, 0, 2);
    }

    @Test
    void keylessStorm_stillHasExactlyOneWinner() throws Exception {
        String show = createShow(List.of("H1"));
        int buyers = 100;
        List<String> tokens = IntStream.range(0, buyers).mapToObj(i -> token("keyless-" + i)).toList();

        var codes = fireAll(buyers, i -> post("/shows/" + show + "/reserve", tokens.get(i), "{\"seats\":[\"H1\"]}").statusCode());

        assertEquals(1, count(codes, 201), tally(codes));
        assertEquals(buyers - 1, count(codes, 409), tally(codes));
        assertInvariant(show, 1, 0, 0, 1);
    }

    @Test
    void overlappingMultiSeat_noDeadlock_noDoubleHold() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 6).mapToObj(i -> "M" + i).toList();
        String show = createShow(seats);
        int buyers = 120;
        List<String> tokens = IntStream.range(0, buyers).mapToObj(i -> token("multi-" + i)).toList();

        // overlapping pairs, requested in both orders, so naive lock ordering would deadlock
        var results = fireAll(buyers, i -> {
            String a = seats.get(i % 6), b = seats.get((i + 1) % 6);
            String body = (i % 2 == 0)
                    ? "{\"seats\":[\"" + a + "\",\"" + b + "\"],\"idempotency_key\":\"m-" + i + "\"}"
                    : "{\"seats\":[\"" + b + "\",\"" + a + "\"],\"idempotency_key\":\"m-" + i + "\"}";
            return post("/shows/" + show + "/reserve", tokens.get(i), body).statusCode();
        });

        assertEquals(0, results.stream().filter(c -> c != 201 && c != 409).count(), tally(results));
        int winners = count(results, 201);
        assertTrue(winners >= 1 && winners <= 3, "6 seats / 2 per request => at most 3 winners: " + tally(results));
        assertInvariant(show, 6, 6 - 2 * winners, 0, 2 * winners); // no seat sold twice
    }

    @Test
    void cancelAndSpoofing() throws Exception {
        String show = createShow(List.of("C1", "C2"));
        String alice = token("alice"), bob = token("bob");

        // body tries to act as bob; token says alice
        var r = post("/shows/" + show + "/reserve", alice,
                "{\"user_id\":\"bob\",\"seats\":[\"C1\"],\"idempotency_key\":\"a1\"}");
        assertEquals(201, r.statusCode());
        JsonNode res = json.readTree(r.body());
        assertEquals("alice", res.get("user_id").asText());
        assertEquals("confirmed", res.get("status").asText());
        assertEquals(25000, res.get("amount_paise").asLong());
        String id = res.get("reservation_id").asText();

        assertEquals(403, post("/reservations/" + id + "/cancel", bob, "{}").statusCode());
        assertInvariant(show, 2, 1, 0, 1);

        // the owner's cancel frees the seat, and bob can then take it
        assertEquals(200, post("/reservations/" + id + "/cancel", alice, "{}").statusCode());
        assertEquals(201, post("/shows/" + show + "/reserve", bob,
                "{\"seats\":[\"C1\"],\"idempotency_key\":\"b1\"}").statusCode());
        // alice's stale cancel must not resurrect or free bob's seat
        assertEquals(200, post("/reservations/" + id + "/cancel", alice, "{}").statusCode());
        assertInvariant(show, 2, 1, 0, 1);
    }

    // ---- helpers ----

    interface Job {
        int run(int i) throws Exception;
    }

    /** Releases all workers at once so the requests genuinely collide. */
    List<Integer> fireAll(int n, Job job) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                Callable<Integer> c = () -> {
                    gate.await();
                    return job.run(idx);
                };
                fs.add(ex.submit(c));
            }
            gate.countDown();
            List<Integer> out = new ArrayList<>();
            for (var f : fs) out.add(f.get());
            return out;
        }
    }

    String createShow(List<String> seats) {
        String admin = tokenBody("{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}");
        String body = "{\"name\":\"t-" + UUID.randomUUID() + "\",\"price_paise\":25000,\"seats\":["
                + String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList()) + "]}";
        var r = post("/shows", admin, body);
        assertEquals(201, r.statusCode(), r.body());
        return parse(r.body()).get("id").asText();
    }

    void assertInvariant(String show, int total, int available, int held, int confirmed) throws Exception {
        JsonNode s = json.readTree(get("/shows/" + show).body());
        assertEquals(total, s.get("total_seats").asInt());
        assertEquals(available, s.get("available").asInt(), "available");
        assertEquals(held, s.get("held").asInt(), "held");
        assertEquals(confirmed, s.get("confirmed").asInt(), "confirmed");
        assertEquals(total, s.get("available").asInt() + s.get("held").asInt() + s.get("confirmed").asInt(),
                "available + held + confirmed == total_seats");
    }

    String token(String user) {
        return tokenBody("{\"user_id\":\"" + user + "\"}");
    }

    String tokenBody(String body) {
        return parse(post("/auth/token", null, body).body()).get("token").asText();
    }

    HttpResponse<String> post(String path, String token, String body) {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return send(b.build());
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build());
    }

    HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sums matching series from the Prometheus text endpoint (0 if the series does not exist yet). */
    double metric(String name, String labelFragment) {
        double sum = 0;
        for (String line : get("/actuator/prometheus").body().split("\n")) {
            if (!line.startsWith(name + " ") && !line.startsWith(name + "{")) continue;
            if (!labelFragment.isEmpty() && !line.contains(labelFragment)) continue;
            sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        }
        return sum;
    }

    static int count(List<Integer> codes, int code) {
        return (int) codes.stream().filter(c -> c == code).count();
    }

    static String tally(List<Integer> codes) {
        Map<Integer, Long> m = new TreeMap<>();
        codes.forEach(c -> m.merge(c, 1L, Long::sum));
        return "status distribution " + m;
    }
}
