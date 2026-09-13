package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliInvocationCoordinatorTest {
    @Test
    void callersCancelOnlyTheExactInvocationTheyOwnAndKeepAdmissionOpen() {
        var coordinator = new CliInvocationCoordinator();
        try (var unrelated = coordinator.begin();
             var invocation = coordinator.begin()) {
            assertTrue(invocation.cancel());
            assertFalse(invocation.cancel());
            assertTrue(invocation.token().isCancelled());
            assertFalse(unrelated.token().isCancelled());
        }

        var next = coordinator.begin();
        assertFalse(next.token().isCancelled());
        next.close();
    }

    @Test
    void processStopIsIdempotentAndCompletesOnlyAfterEveryInvocationEnds() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        var first = coordinator.begin();
        var second = coordinator.begin();

        var draining = coordinator.stopAdmission();
        assertSame(draining, coordinator.stopAdmission());
        assertFalse(first.token().isCancelled());
        assertFalse(second.token().isCancelled());

        assertSame(draining, coordinator.cancelActive());

        assertTrue(first.token().isCancelled());
        assertTrue(second.token().isCancelled());
        assertFalse(draining.isDone());
        assertThrows(IllegalStateException.class, coordinator::begin);

        first.close();
        assertFalse(draining.isDone());
        second.close();
        draining.get(5, TimeUnit.SECONDS);
    }
}
