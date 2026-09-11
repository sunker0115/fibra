package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationContextCancellationTest {
    @Test
    void derivedInvocationKeepsCallerAndCarriesTheExplicitToken() {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().context();
            var base = InvocationContext.of(caller, "service");
            var source = new CancellationSource();
            var derived = base.withCancellation(source.token());
            var key = ServiceKey.of("probe", String.class);
            caller.services().provide(key, "value");

            assertSame(caller, derived.caller());
            assertSame(source.token(), derived.cancellation());
            assertSame(source.token(), derived.service(key)
                .invoke((serviceInvocation, ignored) -> serviceInvocation.cancellation()));
            assertFalse(base.cancellation().isCancelled());
            source.cancel();
            assertTrue(derived.cancellation().isCancelled());
        }
    }
}
