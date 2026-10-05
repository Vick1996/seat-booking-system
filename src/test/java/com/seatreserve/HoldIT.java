package com.seatreserve;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time-boxed holds, opt-in per show via hold_seconds, with a fast sweeper. Without hold_seconds reserve confirms
 * immediately, exactly as before. Expiry correctness WITHOUT a sweeper is in HoldWithoutSweeperIT.
 * Needs Docker; run with `./mvnw verify`.
 */
@TestPropertySource(properties = "seat.sweep-interval-ms=200")
class HoldIT extends HoldTestBase {

    @Test
    void holdShow_reserveHolds_andOnlyTheOwnerCanConfirm() throws Exception {
        String show = createShow(30, "A1", "A2");
        String alice = userToken("alice"), bob = userToken("bob");

        var held = post("/shows/" + show + "/reserve", alice, reserveBody("A1"));
        assertEquals(201, held.statusCode(), held.body());
        JsonNode r = json.readTree(held.body());
        assertEquals("held", r.get("status").asText());
        assertTrue(r.hasNonNull("expires_at"), "a hold says when it lapses: " + held.body());
        assertState(show, 1, 1, 0);

        String id = r.get("reservation_id").asText();
        assertEquals(403, post("/reservations/" + id + "/confirm", bob, "{}").statusCode(), "only the owner can confirm");

        var confirmed = post("/reservations/" + id + "/confirm", alice, "{}");
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        assertEquals("confirmed", json.readTree(confirmed.body()).get("status").asText());
        assertFalse(json.readTree(confirmed.body()).hasNonNull("expires_at"), "a sale does not expire");
        assertState(show, 1, 0, 1);

        assertEquals(200, post("/reservations/" + id + "/confirm", alice, "{}").statusCode(), "confirming twice is harmless");
        assertState(show, 1, 0, 1);
    }

    @Test
    void showWithoutHoldSeconds_isUnchanged_reserveConfirmsImmediately() throws Exception {
        String show = createShow(null, "A1");
        var res = post("/shows/" + show + "/reserve", userToken("alice"), reserveBody("A1"));
        assertEquals(201, res.statusCode());
        JsonNode r = json.readTree(res.body());
        assertEquals("confirmed", r.get("status").asText(), "the email's example response, by default");
        assertFalse(r.has("expires_at"), "no expiry field on an ordinary reservation: " + res.body());
        assertFalse(json.readTree(get("/shows/" + show).body()).has("hold_seconds"));
    }

    @Test
    void ownerCancel_releasesAHold_andOthersCannot() throws Exception {
        String show = createShow(30, "A1");
        String alice = userToken("alice"), bob = userToken("bob");
        String id = reservationId(post("/shows/" + show + "/reserve", alice, reserveBody("A1")));

        assertEquals(403, post("/reservations/" + id + "/cancel", bob, "{}").statusCode());
        var cancelled = post("/reservations/" + id + "/cancel", alice, "{}");
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("cancelled", json.readTree(cancelled.body()).get("status").asText());
        assertFalse(json.readTree(cancelled.body()).has("expires_at"), "a cancelled hold has no expiry");
        assertState(show, 1, 0, 0);
        assertEquals(201, post("/shows/" + show + "/reserve", bob, reserveBody("A1")).statusCode());
    }

    @Test
    void sweeper_reportsALapsedHoldAsExpired_andALateConfirmIsHoldExpired() throws Exception {
        String show = createShow(1, "A1");
        String alice = userToken("alice");
        String body = reserveBody("A1");
        var hold = post("/shows/" + show + "/reserve", alice, body);
        assertEquals("held", json.readTree(hold.body()).get("status").asText());

        Thread.sleep(2500); // lapses at 1s; the sweeper runs every 200ms here
        var replay = post("/shows/" + show + "/reserve", alice, body); // same key: the original reservation
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("expired", json.readTree(replay.body()).get("status").asText(), "the record was tidied: " + replay.body());
        assertFalse(json.readTree(replay.body()).has("expires_at"));

        var late = post("/reservations/" + reservationId(hold) + "/confirm", alice, "{}");
        assertEquals(409, late.statusCode(), late.body());
        assertTrue(late.body().contains("hold-expired"), "same answer whether or not the sweeper ran: " + late.body());
    }
}
