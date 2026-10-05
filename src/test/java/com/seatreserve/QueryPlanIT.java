package com.seatreserve;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A decline must be an index lookup on the seat's key, not a scan of the whole show. On a freshly created table
 * Postgres has no statistics, so without help it picks the (show_id, status) index and fetches EVERY seat of the
 * show to find one (measured: 1.45 ms per call at 5,000 seats against 0.14 ms at 500, and growing with the show).
 * A checker creates a show and bursts it at once, before the background analyzer has run.
 * Needs Docker; run with `./mvnw verify`.
 */
class QueryPlanIT extends HoldTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void justAfterAShowIsCreated_theSeatLookupUsesTheKeyIndex_notAScanOfTheWholeShow() throws Exception {
        // 500 is the largest show currently allowed (ShowService.MAX_SEATS); the effect grows with the show
        String[] seats = IntStream.rangeClosed(1, 500).mapToObj(i -> "S" + i).toArray(String[]::new);
        String show = createShow(null, seats); // no ANALYZE has run yet: autovacuum waits about a minute

        // the same statement the service sends, as a server-prepared (generic) plan like the JDBC driver uses
        String plan = jdbc.execute((ConnectionCallback<String>) con -> {
            try (var st = con.createStatement()) {
                st.execute("prepare q(uuid, text) as select exists (select 1 from seats where show_id = $1 "
                        + "and label = any(string_to_array($2, ',')) "
                        + "and not (status = 'available' or (status = 'held' and expires_at < clock_timestamp())))");
                st.execute("set plan_cache_mode = force_generic_plan");
                var rs = st.executeQuery("explain execute q('" + show + "', 'S250')");
                var sb = new StringBuilder();
                while (rs.next()) sb.append(rs.getString(1)).append('\n');
                return sb.toString();
            } finally {
                try (var st = con.createStatement()) { // do not leak session settings into the pooled connection
                    st.execute("reset plan_cache_mode");
                    st.execute("deallocate q");
                }
            }
        });

        // With statistics the planner chooses by true cost: a seq scan on a tiny table, the key index on a large one.
        // Without them it takes the (show_id, status) index and fetches every seat of the show through it.
        assertFalse(plan.contains("idx_seats_show_status"), "must not fetch every seat of the show:\n" + plan);
    }
}
