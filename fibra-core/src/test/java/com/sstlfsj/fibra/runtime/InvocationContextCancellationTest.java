package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationContextCancellationTest {
    @Test
    void derivedInvocationKeepsCallerAndCarriesTheExplicitToken() {
        try (var runtime = FibraRuntime.create()) {
            var caller = runtime.rootScope().context();
            var scope = runtime.rootScope().openChild("invocation");
            var base = InvocationContext.of(caller, scope, "service");
            var source = new CancellationSource();
            var derived = base.withCancellation(source.token());
            var key = ServiceKey.of("probe", String.class);
            caller.services().provide(key, "value");

            assertSame(caller, derived.caller());
            assertSame(scope, derived.scope());
            assertSame(source.token(), derived.cancellation());
            derived.service(key).invoke((serviceInvocation, ignored) -> {
                assertSame(scope, serviceInvocation.scope());
                assertSame(source.token(), serviceInvocation.cancellation());
                return null;
            });
            assertFalse(base.cancellation().isCancelled());
            source.cancel();
            assertTrue(derived.cancellation().isCancelled());
        }
    }

    @Test
    void rejectsAResourceScopeFromAnotherRuntimeDomain() {
        try (var runtime = FibraRuntime.create(); var foreign = runtime.openDomain("foreign")) {
            var caller = runtime.rootScope().context();

            assertThrows(IllegalArgumentException.class, () ->
                InvocationContext.of(caller, foreign.rootScope(), "service"));
        }
    }
}
