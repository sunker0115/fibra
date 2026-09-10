package com.sstlfsj.fibra.migration;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

final class MigrationTestSupport {
    private MigrationTestSupport() {
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied within 5 seconds");
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }
}
