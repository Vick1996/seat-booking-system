package com.seatreserve.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sizing of the connection cap. The expected values are worked from the measurements behind it
 * (a request in flight costs roughly 250KB of heap), not recomputed with the same formula.
 */
class ConnectionLimitConfigTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void smallInstance_getsASmallCap() {
        // 512MB container => ~384MB heap. Measured: 1,000 in flight is fine, 4,000 runs out of memory.
        int cap = ConnectionLimitConfig.fromHeap(384 * MIB);
        assertEquals(1536, cap);
        assertTrue(cap > 1000 && cap < 2000, "between the sizes measured to pass and to be marginal: " + cap);
    }

    @Test
    void biggerHeap_getsAProportionallyBiggerCap() {
        assertEquals(6144, ConnectionLimitConfig.fromHeap(1536 * MIB)); // 2GB container
    }

    @Test
    void cap_isClampedBothWays() {
        assertEquals(256, ConnectionLimitConfig.fromHeap(1 * MIB), "never so small the service cannot serve");
        assertEquals(30_000, ConnectionLimitConfig.fromHeap(Long.MAX_VALUE), "never unbounded (an unset heap limit reports Long.MAX_VALUE)");
    }
}
