package com.sstlfsj.fibra.plugins.fs.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchTimingTest {
    @Test
    void rejectsGraceMillisAboveSingleTimerMaximumAtConfigurationConstruction() {
        var error = assertThrows(IllegalArgumentException.class,
            () -> new SearchTiming(30_000L, 2_147_483_648L));

        assertEquals("graceMillis must be a positive finite number no greater than 2147483647",
            error.getMessage());
    }

    @Test
    void rejectsTimeoutMillisAboveSingleTimerMaximumAtConfigurationConstruction() {
        var error = assertThrows(IllegalArgumentException.class,
            () -> new SearchTiming(2_147_483_648L, 3_000L));

        assertEquals("timeoutMillis must be a positive finite number no greater than 2147483647",
            error.getMessage());
    }
}
