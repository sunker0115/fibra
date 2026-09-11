package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedContextPolicyContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ServiceKey<String> MESSAGE = ServiceKey.of("message", String.class);
    private static final ServiceKey<Integer> COUNT = ServiceKey.of("count", Integer.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicChildInheritsPoliciesForServicesNotDeclaredByItsParent(boolean typed) {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            root.services().provide(MESSAGE, "default");
            var configured = realm(realm(root, MESSAGE, "tenant", typed), COUNT, "tenant", typed);
            configured = intercept(configured, MESSAGE, "configured", typed);
            configured.services().provide(MESSAGE, "tenant-message");
            var seenMessage = new AtomicReference<String>();
            var seenIntercept = new AtomicReference<Object>();
            var childReference = new AtomicReference<PluginInstance<Void>>();
            var childDefinition = PluginDefinition.builder("child", Void.class,
                    () -> (context, config) -> {
                        seenMessage.set(context.services().require(MESSAGE));
                        seenIntercept.set(context.intercept(MESSAGE));
                        context.services().provide(COUNT, 7);
                        return Mono.empty();
                    })
                .require(MESSAGE, "definition-default")
                .provide(COUNT)
                .build();
            var parentDefinition = PluginDefinition.builder("parent", Void.class,
                () -> (context, config) -> {
                    childReference.set(context.plugins().mount("child", childDefinition.prepare(null)));
                    return Mono.empty();
                }).build();

            var parent = configured.plugins().mount("parent", parentDefinition.prepare(null));
            parent.settled().block(TIMEOUT);
            childReference.get().settled().block(TIMEOUT);

            assertTrue(parentDefinition.requires().isEmpty());
            assertTrue(parentDefinition.provides().isEmpty());
            assertEquals(PluginInstanceState.ACTIVE, childReference.get().state());
            assertEquals("tenant-message", seenMessage.get());
            assertEquals("configured", seenIntercept.get());
            assertEquals(7, configured.services().require(COUNT));
            assertTrue(root.services().find(COUNT).isEmpty());
            assertEquals("default", root.services().require(MESSAGE));
            assertNull(root.intercept(MESSAGE));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void derivingPoliciesDoesNotChangeTheParentContext(boolean typed) {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            var parent = intercept(realm(root, MESSAGE, "parent", typed), MESSAGE, "parent", typed);
            var child = intercept(realm(parent, MESSAGE, "child", typed), MESSAGE, "child", typed);
            root.services().provide(MESSAGE, "root");
            parent.services().provide(MESSAGE, "parent");
            child.services().provide(MESSAGE, "child");

            assertEquals("root", root.services().require(MESSAGE));
            assertNull(root.intercept(MESSAGE));
            assertEquals("parent", parent.services().require(MESSAGE));
            assertEquals("parent", parent.intercept(MESSAGE));
            assertEquals("child", child.services().require(MESSAGE));
            assertEquals("child", child.intercept(MESSAGE));
        }
    }

    @Test
    void namedPoliciesLeaveServiceTypesToTheActualRealmBindings() {
        var text = ServiceKey.of("versioned", String.class);
        var number = ServiceKey.of("versioned", Integer.class);
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context().withIntercept("versioned", "policy");
            var first = root.withRealm("versioned", "first");
            var second = root.withRealm("versioned", "second");
            first.services().provide(text, "first");
            second.services().provide(number, 2);

            assertEquals("first", first.services().require(text));
            assertEquals(2, second.services().require(number));
            assertEquals("policy", first.intercept(text));
            assertEquals("policy", second.intercept(number));
            assertThrows(IllegalArgumentException.class, () -> first.services().require(number));
            assertThrows(IllegalArgumentException.class, () -> second.services().require(text));
        }
    }

    @Test
    void policyEntrypointsRejectInvalidNamesAndNullValues() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            for (var name : new String[] {null, "", " \t\n"}) {
                assertThrows(IllegalArgumentException.class, () -> context.withRealm(name, "realm"));
                assertThrows(IllegalArgumentException.class, () -> context.withIntercept(name, "policy"));
            }
            assertThrows(NullPointerException.class, () -> context.withRealm(MESSAGE.name(), null));
            assertThrows(NullPointerException.class, () -> context.withIntercept(MESSAGE.name(), null));
            assertThrows(NullPointerException.class, () -> context.withRealm(MESSAGE, null));
            assertThrows(NullPointerException.class, () -> context.withIntercept(MESSAGE, null));
            assertThrows(NullPointerException.class, () -> context.withRealm((ServiceKey<?>) null, "realm"));
            assertThrows(NullPointerException.class, () -> context.withIntercept((ServiceKey<?>) null, "policy"));
        }
    }

    private static Context realm(Context context, ServiceKey<?> key, Object value, boolean typed) {
        return typed ? context.withRealm(key, value) : context.withRealm(key.name(), value);
    }

    private static Context intercept(Context context, ServiceKey<?> key, Object value, boolean typed) {
        return typed ? context.withIntercept(key, value) : context.withIntercept(key.name(), value);
    }
}
