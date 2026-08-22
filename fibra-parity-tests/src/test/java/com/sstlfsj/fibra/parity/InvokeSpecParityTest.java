package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Cordis packages/core/tests/invoke.spec.ts 的 2 项逐条映射。 */
class InvokeSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<CallableConfig> FOO = ServiceKey.of("foo", CallableConfig.class);
    private static final ServiceKey<Object> DEPENDENCY = ServiceKey.of("dependency", Object.class);

    @Test
    @SuppressWarnings("unchecked")
    void functionalService() {
        root.provide(FOO, (invocation, init) -> {
            var result = new LinkedHashMap<String, Integer>();
            result.put("a", 1);
            invocation.caller().interceptValues("foo").forEach(value ->
                result.putAll((Map<String, Integer>) value));
            result.putAll(init);
            return result;
        });
        var caller = root.intercept("foo", Map.of("b", 2));
        var result = caller.service(FOO).invoke((invocation, service) ->
            service.call(invocation, Map.of("c", 3)));
        assertEquals(Map.of("a", 1, "b", 2, "c", 3), result);
    }

    @Test
    void usesServiceShadowForCallableExtensions() {
        var dependency = new Object();
        root.provide(DEPENDENCY, dependency);
        root.provide(FOO, (invocation, ignored) -> Map.of(
            "same", invocation.caller().get(DEPENDENCY) == dependency ? 1 : 0));
        var result = root.service(FOO).invoke((invocation, service) ->
            service.call(invocation, Map.of()));
        assertEquals(1, result.get("same"));
    }

    @FunctionalInterface
    interface CallableConfig {
        Map<String, Integer> call(InvocationContext invocation, Map<String, Integer> init);
    }
}
