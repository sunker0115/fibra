package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShadowSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Probe> INNER = ServiceKey.of("inner", Probe.class);
    private static final ServiceKey<Probe> OUTER = ServiceKey.of("outer", Probe.class);

    @Test
    void keepsCallerMetadataSeparateFromServiceShadow() {
        var provider = root.withMetadata("origin", "inner");
        provider.services().provide(INNER, invocation -> new Inspection(
            invocation.caller().metadata("caller"), provider.metadata("origin")));
        var caller = root.withMetadata("caller", "outer");
        var result = caller.services().reference(INNER).invoke(
            (invocation, service) -> service.inspect(invocation));
        assertEquals(new Inspection("outer", "inner"), result);
    }

    @Test
    void exposesCallerWithoutPreservingShadowForNoShadowServices() {
        root.services().provide(INNER, invocation -> new Inspection(
            invocation.caller().metadata("caller"), null));
        var caller = root.withMetadata("caller", "visible");
        var result = caller.services().reference(INNER).invoke(
            (invocation, service) -> service.inspect(invocation));
        assertEquals("visible", result.caller);
        assertNull(result.shadow);
    }

    @Test
    void exposesCallerToCallableServices() {
        root.services().provide(INNER,
            invocation -> new Inspection(invocation.caller(), null));
        var caller = root.withMetadata("caller", "callable");
        var result = caller.services().reference(INNER).invoke(
            (invocation, service) -> service.inspect(invocation));
        assertSame(caller, result.caller);
    }

    @Test
    void stripsServiceShadowBeforeCreatingPlugins() {
        var observed = new AtomicReference<Object>();
        root.services().provide(OUTER, invocation -> {
            var definition = PluginDefinition.builder("consumer", Void.class,
                () -> (context, config) -> {
                    observed.set(context.metadata("caller"));
                    return Mono.empty();
                }).build();
            await(invocation.plugins().mount("consumer", definition, null));
            return new Inspection(invocation.caller(), null);
        });
        var caller = root.withMetadata("caller", "plugin-owner");
        caller.services().reference(OUTER).invoke(
            (invocation, service) -> service.inspect(invocation));
        assertEquals("plugin-owner", observed.get());
    }

    @FunctionalInterface
    private interface Probe {
        Inspection inspect(InvocationContext invocation);
    }

    private record Inspection(Object caller, Object shadow) {
    }
}
