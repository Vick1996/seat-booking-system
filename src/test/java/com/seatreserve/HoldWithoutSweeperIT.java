package com.seatreserve;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expiry must be correct WITHOUT the sweeper: the sweeper here runs once an hour, so every outcome below comes from
 * the lazy-reclaim rules in the SQL (a lapsed hold is free, is not counted, and cannot be confirmed).
 * Needs Docker; run with `./mvnw verify`.
 */
@TestPropertySource(properties = "seat.sweep-interval-ms=3600000")
class HoldWithoutSweeperIT extends HoldTestBase {

    @Test
    void lapsedHold_isBookableByAnotherUser_andTheOldOwnerCannotResurrectIt() throws Exception {
        String show = createShow(1, "A1");
        String alice = userToken("alice"), bob = userToken("bob");
        String aliceId = reservationId(post("/shows/" + show + "/reserve", alice, reserveBody("A1")));

        Thread.sleep(1500);
        assertState(show, 1, 0, 0); // a lapsed hold reads as available

        var bobHold = post("/shows/" + show + "/reserve", bob, reserveBody("A1"));
        assertEquals(201, bobHold.statusCode(), "a lapsed hold is re-bookable at once: " + bobHold.body());

        var late = post("/reservations/" + aliceId + "/confirm", alice, "{}");
        assertEquals(409, late.statusCode(), late.body());
        assertTrue(late.body().contains("hold-expired"), late.body());

        assertEquals(200, post("/reservations/" + reservationId(bobHold) + "/confirm", bob, "{}").statusCode());
        assertState(show, 0, 0, 1); // bob owns it; alice's late confirm changed nothing
    }

    @Test
    void confirmAfterExpiry_isRefused_evenIfNobodyElseTookTheSeat() throws Exception {
        String show = createShow(1, "A1");
        String alice = userToken("alice");
        String id = reservationId(post("/shows/" + show + "/reserve", alice, reserveBody("A1")));

        Thread.sleep(1500);
        var late = post("/reservations/" + id + "/confirm", alice, "{}");
        assertEquals(409, late.statusCode(), "a lapsed hold must not become a sale: " + late.body());
        assertTrue(late.body().contains("hold-expired"), late.body());
        assertState(show, 1, 0, 0);
    }

    @Test
    void perUserLimit_countsLiveHolds_butNotLapsedOnes() throws Exception {
        String show = createShow(1, 2, "A1", "A2", "A3");
        String alice = userToken("alice");
        assertEquals(201, post("/shows/" + show + "/reserve", alice, reserveBody("A1")).statusCode());
        assertEquals(201, post("/shows/" + show + "/reserve", alice, reserveBody("A2")).statusCode());
        var over = post("/shows/" + show + "/reserve", alice, reserveBody("A3"));
        assertEquals(409, over.statusCode(), "two live holds fill a limit of 2: " + over.body());
        assertTrue(over.body().contains("per-user-limit"), over.body());

        Thread.sleep(1500); // both holds lapse and free the allowance
        assertEquals(201, post("/shows/" + show + "/reserve", alice, reserveBody("A3")).statusCode());
    }

    @Test
    void lapsedHold_underAStampede_stillHasExactlyOneWinner() throws Exception {
        String show = createShow(1, "A1");
        post("/shows/" + show + "/reserve", userToken("holder"), reserveBody("A1"));
        Thread.sleep(1500);

        int buyers = 60;
        var tokens = new ArrayList<String>();
        for (int i = 0; i < buyers; i++) tokens.add(userToken("storm-" + i));
        var gate = new CountDownLatch(1);
        var codes = new ConcurrentLinkedQueue<Integer>();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String t : tokens)
                ex.submit(() -> {
                    gate.await();
                    codes.add(post("/shows/" + show + "/reserve", t, reserveBody("A1")).statusCode());
                    return null;
                });
            gate.countDown();
        }
        assertEquals(1, codes.stream().filter(c -> c == 201).count(), "exactly one buyer takes the lapsed seat: " + codes);
        assertEquals(buyers - 1, codes.stream().filter(c -> c == 409).count(), codes.toString());
        assertState(show, 0, 1, 0);
    }
}
