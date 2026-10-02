import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * On-sale stampede against a live URL. Single file, JDK only:  java burst/Burst.java <BASE_URL>
 *
 * Fires a mix of hot-seat storms, spread bookings, multi-seat pairs and same-key retries, then
 * prints the outcome distribution and checks: no seat sold twice, exactly one winner per hot
 * seat, zero 5xx, available+held+confirmed == total (sampled DURING the burst and after), and
 * confirmed seats == seats in the 201 responses. Exit code 1 if any correctness check fails.
 *
 * Tunables (env): BURST_REQUESTS=20000 BURST_USERS=2000 BURST_SEATS=5000 BURST_HOT=3
 *                 BURST_CONCURRENCY=1000 BURST_ADMIN_KEY=dev-admin-key BURST_SEED=42
 */
public class Burst {
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30)).build();

    record Spec(int user, String key, List<String> seats) {
    }

    record Outcome(int status, String reason, List<String> seats, String error) {
    }

    static int env(String k, int d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : Integer.parseInt(v.trim());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java burst/Burst.java <BASE_URL>");
            System.exit(2);
        }
        String base = args[0].replaceAll("/+$", "");
        int requests = env("BURST_REQUESTS", 20_000), users = env("BURST_USERS", 2_000);
        int seatCount = env("BURST_SEATS", 5_000), hot = env("BURST_HOT", 3);
        int concurrency = env("BURST_CONCURRENCY", 1_000), seed = env("BURST_SEED", 42);
        String adminKey = Optional.ofNullable(System.getenv("BURST_ADMIN_KEY")).orElse("dev-admin-key");

        System.out.printf("target %s | %d requests, %d users, %d seats (+%d hot), concurrency %d%n",
                base, requests, users, seatCount, hot, concurrency);

        // --- setup: warm up, admin token, show, user tokens ---
        for (int i = 0; i < 40; i++) {
            if (call(base, "GET", "/actuator/health/readiness", null, null).status == 200) break;
            Thread.sleep(2_000); // cold start on a free tier
        }
        String admin = token(base, "{\"user_id\":\"burst-admin\",\"admin_key\":\"" + adminKey + "\"}");
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= hot; i++) seats.add("H" + i);
        for (int i = 1; i <= seatCount; i++) seats.add("S" + i);
        String run = Long.toString(System.currentTimeMillis(), 36);
        var created = call(base, "POST", "/shows", admin, "{\"name\":\"burst-" + run + "\",\"price_paise\":25000,\"seats\":["
                + String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList()) + "]}");
        if (created.status != 201) fail("could not create show: " + created.status + " " + created.body);
        String show = find(created.body, "\"id\":\"([^\"]+)\"");
        int total = seats.size();

        String[] tokens = new String[users];
        var mintLimit = new Semaphore(64); // setup is not the stampede; don't open thousands of sockets at once
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            var futs = new ArrayList<Future<?>>();
            for (int u = 0; u < users; u++) {
                final int idx = u;
                futs.add(ex.submit(() -> {
                    mintLimit.acquire();
                    try {
                        tokens[idx] = token(base, "{\"user_id\":\"burst-u" + idx + "\"}");
                    } finally {
                        mintLimit.release();
                    }
                    return null;
                }));
            }
            for (var f : futs) f.get();
        }

        // --- plan the traffic ---
        Random rnd = new Random(seed);
        List<Spec> plan = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            double r = rnd.nextDouble();
            int u = rnd.nextInt(users);
            String key = "b-" + run + "-" + i;
            if (r < 0.40) plan.add(new Spec(u, key, List.of("H" + (1 + rnd.nextInt(hot)))));
            else if (r < 0.80) plan.add(new Spec(u, key, List.of("S" + (1 + rnd.nextInt(seatCount)))));
            else if (r < 0.90) {
                int a = 1 + rnd.nextInt(seatCount), b = 1 + rnd.nextInt(seatCount);
                if (a == b) b = a % seatCount + 1;
                plan.add(new Spec(u, key, List.of("S" + a, "S" + b)));
            } else if (!plan.isEmpty()) plan.add(plan.get(rnd.nextInt(plan.size()))); // same-key retry
            else plan.add(new Spec(u, key, List.of("H1")));
        }

        // --- run ---
        Map<String, Double> before = metrics(base);
        var outcomes = new ConcurrentLinkedQueue<Outcome>();
        var invariantBreaks = new AtomicInteger();
        var invariantSamples = new AtomicInteger();
        var running = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (running.get()) {
                try {
                    int[] c = counts(base, show);
                    invariantSamples.incrementAndGet();
                    if (c == null || c[1] + c[2] + c[3] != c[0] || c[0] != total) invariantBreaks.incrementAndGet();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // a failed sample is not a broken invariant; the final check is authoritative
                }
            }
        });
        Semaphore inflight = new Semaphore(concurrency);
        long t0 = System.nanoTime();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futs = new ArrayList<Future<?>>();
            for (Spec s : plan) {
                futs.add(ex.submit(() -> {
                    gate.await();
                    inflight.acquire();
                    try {
                        String body = "{\"seats\":[" + String.join(",", s.seats.stream().map(x -> "\"" + x + "\"").toList())
                                + "],\"idempotency_key\":\"" + s.key + "\"}";
                        var r = call(base, "POST", "/shows/" + show + "/reserve", tokens[s.user], body);
                        outcomes.add(r.error != null ? new Outcome(-1, null, List.of(), r.error)
                                : new Outcome(r.status, find(r.body, "\"reason\":\"([^\"]+)\""), seatsOf(r.body), null));
                    } finally {
                        inflight.release();
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var f : futs) f.get();
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        running.set(false);
        sampler.join(3_000);

        // --- tally ---
        int created201 = 0, replay200 = 0, c5xx = 0, transport = 0, other = 0;
        var declined = new TreeMap<String, Integer>();
        var sold = new HashMap<String, Integer>();
        for (Outcome o : outcomes) {
            if (o.status == 201) {
                created201++;
                o.seats.forEach(s -> sold.merge(s, 1, Integer::sum));
            } else if (o.status == 200) replay200++;
            else if (o.status >= 500) c5xx++;
            else if (o.status == -1) transport++;
            else if (o.status >= 400) declined.merge(o.status + " " + o.reason, 1, Integer::sum);
            else other++;
        }
        long doubleSold = sold.values().stream().filter(n -> n > 1).count();
        List<String> badHot = new ArrayList<>();
        for (int i = 1; i <= hot; i++) if (sold.getOrDefault("H" + i, 0) != 1) badHot.add("H" + i + "=" + sold.getOrDefault("H" + i, 0));

        int[] fin = counts(base, show);
        Map<String, Double> after = metrics(base);

        System.out.printf("%n== outcome distribution (%d responses in %.1fs, %.0f req/s) ==%n", outcomes.size(), secs, outcomes.size() / secs);
        System.out.printf("  201 confirmed (new)        %6d%n  200 idempotent replay        %6d%n", created201, replay200);
        declined.forEach((k, v) -> System.out.printf("  %-26s %6d%n", k, v));
        System.out.printf("  5xx                        %6d%n  transport errors/timeouts  %6d%n", c5xx, transport);
        var errKinds = new TreeMap<String, Integer>();
        for (Outcome o : outcomes) if (o.status == -1) errKinds.merge(o.error, 1, Integer::sum);
        errKinds.forEach((k, v) -> System.out.printf("      %5d x %s%n", v, k.length() > 110 ? k.substring(0, 110) : k));

        int sellable = (int) sold.keySet().stream().count();
        System.out.printf("%n== reconciliation ==%n  total_seats %d | available %d | held %d | confirmed %d | sum %d%n",
                total, fin[1], fin[2], fin[3], fin[1] + fin[2] + fin[3]);
        System.out.printf("  invariant sampled %d times during the burst, %d breaks%n", invariantSamples.get(), invariantBreaks.get());
        System.out.printf("  seats in 201 responses: %d distinct, %d sold twice%n", sellable, doubleSold);

        List<String> failures = new ArrayList<>();
        if (doubleSold > 0) failures.add(doubleSold + " seat(s) sold to more than one reservation");
        if (!badHot.isEmpty()) failures.add("hot seats must have exactly one winner: " + badHot);
        if (c5xx > 0) failures.add(c5xx + " responses were 5xx");
        if (fin[0] != total || fin[1] + fin[2] + fin[3] != fin[0]) failures.add("available+held+confirmed != total_seats");
        if (invariantBreaks.get() > 0) failures.add("invariant broke during the burst");
        if (fin[3] != sellable) failures.add("confirmed seats (" + fin[3] + ") != seats in 201 responses (" + sellable + ")");
        if (transport > 0) failures.add(transport + " requests failed at transport level (timeouts/refused)");

        if (before != null && after != null) {
            double dConf = d(after, before, "reservations_confirmed_total", "");
            double dTaken = d(after, before, "reservations_declined_total", "seat-taken");
            double dLimit = d(after, before, "reservations_declined_total", "per-user-limit");
            double dReplay = d(after, before, "reservations_declined_total", "idempotent-replay");
            int seen409Taken = declined.getOrDefault("409 seat-taken", 0), seen409Limit = declined.getOrDefault("409 per-user-limit", 0);
            boolean ok = dConf == created201 && dTaken == seen409Taken && dLimit == seen409Limit && dReplay == replay200;
            System.out.printf("%n== metrics (delta vs observed) ==%n  confirmed %.0f/%d | seat-taken %.0f/%d | per-user-limit %.0f/%d | replay %.0f/%d  -> %s%n",
                    dConf, created201, dTaken, seen409Taken, dLimit, seen409Limit, dReplay, replay200,
                    ok ? "RECONCILED" : "MISMATCH (other clients hitting this service?)");
        } else {
            System.out.println("\n== metrics == /actuator/prometheus not readable; skipped");
        }

        System.out.println();
        if (failures.isEmpty()) System.out.println("PASS: no double-sell, zero 5xx, invariant held.");
        else {
            failures.forEach(f -> System.out.println("FAIL: " + f));
            System.exit(1);
        }
    }

    // ---- helpers ----
    record Resp(int status, String body, String error) {
    }

    static Resp call(String base, String method, String path, String token, String body) {
        try {
            var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90));
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), null);
        } catch (Exception e) {
            return new Resp(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Setup only: retries connection-level failures (safe: minting a token has no side effects). */
    static String token(String base, String body) {
        Resp r = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            r = call(base, "POST", "/auth/token", null, body);
            if (r.status != -1) break; // got an HTTP answer; only transport errors are retried
            try {
                Thread.sleep(200L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (r.status != 200) fail("token mint failed: " + r.status + " " + r.body + " " + r.error);
        return find(r.body, "\"token\":\"([^\"]+)\"");
    }

    /** {total, available, held, confirmed} from GET /shows/{id}, or null if unreadable. */
    static int[] counts(String base, String show) {
        var r = call(base, "GET", "/shows/" + show, null, null);
        if (r.status != 200) return null;
        Matcher m = Pattern.compile("\"total_seats\":(\\d+),\"available\":(\\d+),\"held\":(\\d+),\"confirmed\":(\\d+)").matcher(r.body);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))} : null;
    }

    static Map<String, Double> metrics(String base) {
        var r = call(base, "GET", "/actuator/prometheus", null, null);
        if (r.status != 200) return null;
        var m = new HashMap<String, Double>();
        for (String line : r.body.split("\n"))
            if (line.startsWith("reservations_")) {
                int sp = line.lastIndexOf(' ');
                m.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
            }
        return m;
    }

    static double d(Map<String, Double> after, Map<String, Double> before, String name, String reason) {
        double a = 0, b = 0;
        for (var e : after.entrySet()) if (e.getKey().startsWith(name) && e.getKey().contains(reason)) a += e.getValue();
        for (var e : before.entrySet()) if (e.getKey().startsWith(name) && e.getKey().contains(reason)) b += e.getValue();
        return a - b;
    }

    static List<String> seatsOf(String body) {
        String arr = find(body, "\"seats\":\\[([^\\]]*)\\]");
        if (arr == null) return List.of();
        var out = new ArrayList<String>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(arr);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s == null ? "" : s);
        return m.find() ? m.group(1) : null;
    }

    static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }
}
