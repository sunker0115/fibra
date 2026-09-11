package com.sstlfsj.fibra;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationSourceTest {
    @Test
    void sourceCancelsItsReadOnlyTokenExactlyOnce() {
        var source = new CancellationSource();
        var token = source.token();

        assertFalse(token.isCancelled());
        assertTrue(source.cancel());
        assertFalse(source.cancel());
        token.cancelled().block(Duration.ofSeconds(1));
        assertTrue(token.isCancelled());
    }

    @Test
    void neverTokenStaysOpen() {
        var completed = new AtomicBoolean();
        var subscription = CancellationToken.never().cancelled()
            .doOnSuccess(ignored -> completed.set(true)).subscribe();

        assertFalse(CancellationToken.never().isCancelled());
        assertFalse(completed.get());
        subscription.dispose();
    }
}
