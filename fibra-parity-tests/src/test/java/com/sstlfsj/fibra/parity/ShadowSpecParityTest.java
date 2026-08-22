package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/shadow.spec.ts 的 4 项逐条映射。 */
class ShadowSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<Probe> INNER = ServiceKey.of("inner", Probe.class);
    private static final ServiceKey<Probe> OUTER = ServiceKey.of("outer", Probe.class);

    @Test
    void keepsCallerMetadataSeparateFromServiceShadow() {
        var innerOrigin = root.extend(Map.of("origin", "inner"));
        innerOrigin.provide(INNER, invocation -> new Inspection(
            invocation.caller().metadata("caller"), innerOrigin.metadata("origin")));
        var caller = root.extend(Map.of("caller", "outer"));
        var result = caller.service(INNER).invoke((invocation, service) -> service.inspect(invocation));
        assertEquals(new Inspection("outer", "inner"), result);
    }

    @Test
    void exposesCallerWithoutPreservingShadowForNoShadowServices() {
        root.provide(INNER, invocation -> new Inspection(
            invocation.caller().metadata("caller"), null));
        var caller = root.extend(Map.of("caller", "visible"));
        var result = caller.service(INNER).invoke((invocation, service) -> service.inspect(invocation));
        assertEquals("visible", result.caller());
        assertNull(result.shadow());
    }

    @Test
    void exposesCallerToCallableServices() {
        root.provide(INNER, invocation -> new Inspection(invocation.caller(), null));
        var caller = root.extend(Map.of("caller", "callable"));
        var result = caller.service(INNER).invoke((invocation, service) -> service.inspect(invocation));
        assertSame(caller, result.caller());
    }

    @Test
    void stripsServiceShadowBeforeCreatingPlugins() {
        var observed = new AtomicReference<Object>();
        root.provide(OUTER, invocation -> {
            var fibra = invocation.plugin(PluginDescriptor.<Void>builder("consumer").build(),
                (context, config) -> {
                    observed.set(context.metadata("caller"));
                    return Mono.empty();
                }, null);
            await(fibra);
            return new Inspection(invocation.caller(), null);
        });
        var caller = root.extend(Map.of("caller", "plugin-owner"));
        caller.service(OUTER).invoke((invocation, service) -> service.inspect(invocation));
        assertEquals("plugin-owner", observed.get());
    }

    @FunctionalInterface interface Probe { Inspection inspect(InvocationContext invocation); }
    record Inspection(Object caller, Object shadow) {}
}
