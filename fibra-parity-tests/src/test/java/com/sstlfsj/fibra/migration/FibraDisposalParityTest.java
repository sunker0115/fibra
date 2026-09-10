package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.sstlfsj.fibra.migration.MigrationTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraDisposalParityTest {
    @Test
    void startsTopLevelEffectsConcurrentlyAndWaitsForAll() {
        var firstStarted = new AtomicBoolean();
        var secondStarted = new AtomicBoolean();
        var firstGate = Sinks.<Void>one();
        var secondGate = Sinks.<Void>one();
        var runtime = FibraRuntime.create();
        var effects = runtime.rootScope().context().effects();
        effects.add(() -> Mono.defer(() -> {
            firstStarted.set(true);
            return firstGate.asMono();
        }));
        effects.add(() -> Mono.defer(() -> {
            secondStarted.set(true);
            return secondGate.asMono();
        }));

        var closing = runtime.closeAsync().toFuture();
        await(firstStarted::get);
        await(secondStarted::get);
        assertFalse(closing.isDone());

        firstGate.tryEmitEmpty();
        secondGate.tryEmitEmpty();
        closing.join();
    }

    @Test
    void unloadLogsAndIsolatesEachTopLevelFailure() {
        var siblingDisposed = new AtomicBoolean();
        var runtime = FibraRuntime.create();
        runtime.rootScope().context().effects().add(
            () -> Mono.error(new IllegalStateException("broken")));
        runtime.rootScope().context().effects().add(
            Disposables.from(() -> siblingDisposed.set(true)));

        assertDoesNotThrow(() -> runtime.closeAsync().block());

        assertTrue(siblingDisposed.get());
    }
}
