package com.seatreserve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authentication boundary, observed only over HTTP. Forged tokens are built by re-signing a REAL
 * token that differs in exactly one respect, so a rejection can only be for the reason under test.
 * Needs Docker; run with `./mvnw verify`.
 */
@Tag("it")
@Testcontainers
@AutoConfigureObservability // tests default to a no-op registry; we want the real /actuator/prometheus
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIT {
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

    @Value("${seat.jwt-secret}")
    String secret;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    /** A request that needs a valid token and otherwise does nothing: 404 means "authenticated". */
    static final String PROBE = "/reservations/00000000-0000-0000-0000-000000000000/cancel";

    @Test
    void validToken_reachesTheController() throws Exception {
        String real = mint("alice", false);
        assertEquals(404, post(PROBE, real, "{}"), "authenticated, then 'no such reservation'");
        // control: the re-signing helper itself produces tokens the server accepts
        assertEquals(404, post(PROBE, resign(real, secret, p -> p), "{}"), "helper must yield a valid token");
    }

    @Test
    void missingOrMalformedCredentials_are401_withJsonReason() throws Exception {
        assertEquals(401, post(PROBE, null, "{}"), "no Authorization header");
        for (String header : new String[]{"garbage", "Basic YWxpY2U6cHc=", "Bearer ", "Bearer a.b.c", "Bearer not-a-jwt"}) {
            assertEquals(401, postWithHeader(PROBE, header, "{}"), "Authorization: " + header);
        }
        var res = rawPost(PROBE, null, "{}");
        assertTrue(res.body().contains("missing-or-invalid-token"), "keeps the documented JSON error: " + res.body());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("json"), "error body is JSON");
    }

    @Test
    void forgedTokens_are401() throws Exception {
        String real = mint("alice", false);

        // payload edited after signing: sub changed to bob, signature left as is
        String[] parts = real.split("\\.");
        Map<String, Object> payload = decode(parts[1]);
        payload.put("sub", "bob");
        String tampered = parts[0] + "." + b64(json.writeValueAsBytes(payload)) + "." + parts[2];
        assertEquals(401, post(PROBE, tampered, "{}"), "tampered payload");

        assertEquals(401, post(PROBE, resign(real, "a-completely-different-secret-a-completely-different-secret", p -> p), "{}"),
                "signed with the wrong key");

        assertEquals(401, post(PROBE, resign(real, secret, p -> {
            p.put("exp", Instant.now().minusSeconds(3600).getEpochSecond());
            return p;
        }), "{}"), "expired");

        assertEquals(401, post(PROBE, resign(real, secret, p -> {
            p.remove("sub");
            return p;
        }), "{}"), "no subject: there is no identity to act as");
    }

    @Test
    void unsignedToken_cannotForgeAnAdmin() throws Exception {
        // alg=none with admin:true and a valid expiry: the classic JWT bypass
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = b64(("{\"sub\":\"mallory\",\"admin\":true,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        String forged = header + "." + body + ".";
        assertEquals(401, post("/shows", forged, showBody()), "alg=none must never authenticate");
        assertEquals(401, post(PROBE, forged, "{}"));
    }

    @Test
    void roles_adminCreatesShows_userCannot() throws Exception {
        assertEquals(403, post("/shows", mint("alice", false), showBody()), "authenticated but not admin");
        assertEquals(201, post("/shows", mint("root", true), showBody()), "admin");
        assertEquals(403, post("/auth/token", null, "{\"user_id\":\"x\",\"admin_key\":\"wrong\"}"), "bad admin key");
    }

    @Test
    void publicRoutes_needNoToken() throws Exception {
        assertEquals(200, post("/auth/token", null, "{\"user_id\":\"alice\"}"));
        assertEquals(404, get("/shows/" + UUID.randomUUID()), "read-only show lookup is public (404, not 401)");
        assertEquals(200, get("/actuator/health/liveness"));
        assertEquals(200, get("/actuator/prometheus"));
    }

    // ---- helpers ----

    String mint(String user, boolean admin) throws Exception {
        String body = admin ? "{\"user_id\":\"" + user + "\",\"admin_key\":\"dev-admin-key\"}" : "{\"user_id\":\"" + user + "\"}";
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(rawPost("/auth/token", null, body).body());
        m.find();
        return m.group(1);
    }

    /** Re-sign a real token (same algorithm it was issued with) after mutating its payload. */
    String resign(String token, String key, UnaryOperator<Map<String, Object>> mutate) throws Exception {
        String[] p = token.split("\\.");
        Map<String, Object> header = decode(p[0]);
        String mac = switch ((String) header.get("alg")) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalStateException("unexpected alg " + header.get("alg"));
        };
        String h = b64(json.writeValueAsBytes(header));
        String pl = b64(json.writeValueAsBytes(mutate.apply(decode(p[1]))));
        Mac m = Mac.getInstance(mac);
        m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), mac));
        return h + "." + pl + "." + b64(m.doFinal((h + "." + pl).getBytes(StandardCharsets.UTF_8)));
    }

    Map<String, Object> decode(String part) throws Exception {
        return json.readValue(Base64.getUrlDecoder().decode(part), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String showBody() {
        return "{\"name\":\"auth-" + UUID.randomUUID() + "\",\"price_paise\":100,\"seats\":[\"A1\"]}";
    }

    int post(String path, String token, String body) throws Exception {
        return rawPost(path, token, body).statusCode();
    }

    int postWithHeader(String path, String authorization, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    HttpResponse<String> rawPost(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    int get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
