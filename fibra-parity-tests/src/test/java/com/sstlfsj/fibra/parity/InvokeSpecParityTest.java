package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvokeSpecParityTest extends CordisSpecSupport {
    private static final ServiceKey<CallableConfig> FOO =
        ServiceKey.of("foo", CallableConfig.class);
    private static final ServiceKey<Object> DEPENDENCY =
        ServiceKey.of("dependency", Object.class);

    @Test
    @SuppressWarnings("unchecked")
    void functionalService() {
        root.services().provide(FOO, (invocation, initial) -> {
            var result = new LinkedHashMap<String, Integer>();
            result.put("a", 1);
            var intercept = invocation.caller().intercept(FOO);
            if (intercept != null) {
                result.putAll((Map<String, Integer>) intercept);
            }
            result.putAll(initial);
            return result;
        });
        var caller = root.withIntercept(FOO, Map.of("b", 2));
        var result = caller.services().reference(FOO).invoke(
            (invocation, service) -> service.call(invocation, Map.of("c", 3)));
        assertEquals(Map.of("a", 1, "b", 2, "c", 3), result);
    }

    @Test
    void usesServiceShadowForCallableExtensions() {
        var dependency = new Object();
        root.services().provide(DEPENDENCY, dependency);
        root.services().provide(FOO, (invocation, ignored) -> Map.of(
            "same", invocation.caller().services().require(DEPENDENCY) == dependency
                ? 1 : 0));
        var result = root.services().reference(FOO).invoke(
            (invocation, service) -> service.call(invocation, Map.of()));
        assertEquals(1, result.get("same"));
    }

    @FunctionalInterface
    private interface CallableConfig {
        Map<String, Integer> call(InvocationContext invocation,
                                  Map<String, Integer> initial);
    }
}
