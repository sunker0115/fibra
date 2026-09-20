package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ScopeView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeOwnershipBoundaryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void contextExposesAStableViewThatCannotBeClosedOrDowncastToAnOwnedScope()
        throws Exception {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var first = context.scope();

            assertEquals(ScopeView.class, Context.class.getMethod("scope").getReturnType());
            assertSame(first, context.scope());
            assertFalse(first instanceof Scope);
            assertFalse(first instanceof java.lang.AutoCloseable);
            assertFalse(Scope.class.isAssignableFrom(first.getClass()));
            assertThrows(NoSuchMethodException.class,
                () -> ScopeView.class.getMethod("closeAsync"));
            assertThrows(NoSuchMethodException.class,
                () -> ScopeView.class.getMethod("close"));
        }
    }

    @Test
    void aViewOpensAnOwnedChildThatCanCloseWithoutClosingItsParentOrSibling() {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context().scope();
            var first = root.openChild("first");
            var sibling = root.openChild("sibling");
            var firstView = first.context().scope();

            assertNotSame(first, firstView);
            assertTrue(root.sharesDomainWith(first));
            assertTrue(first.sharesDomainWith(root));
            assertTrue(firstView.sharesDomainWith(sibling));

            first.closeAsync().block(TIMEOUT);

            assertTrue(first.isClosed());
            assertTrue(firstView.isClosed());
            assertFalse(root.isClosed());
            assertFalse(sibling.isClosed());
        }
    }

    @Test
    void pluginStartReceivesOnlyTheNonOwningViewOfItsRootScope() {
        var seen = new AtomicReference<ScopeView>();
        var definition = PluginDefinition.builder("scope-owner", Void.class,
            () -> (context, config) -> {
                seen.set(context.scope());
                return reactor.core.publisher.Mono.empty();
            }).build();

        try (var runtime = FibraRuntime.create()) {
            var owner = runtime.rootScope().openChild("plugin-owner");
            var instance = owner.context().plugins()
                .mount("scope-owner", definition.prepare(null));

            assertSame(instance, instance.settled().block(TIMEOUT));
            assertSame(owner.context().scope(), seen.get());
            assertFalse(seen.get() instanceof Scope);
            assertFalse(seen.get() instanceof java.lang.AutoCloseable);
            assertFalse(owner.isClosed());
        }
    }

    @Test
    void viewsFromDifferentDomainsDoNotShareOwnership() {
        try (var runtime = FibraRuntime.create(); var foreign = runtime.openDomain("foreign")) {
            var root = runtime.rootScope().context().scope();
            var foreignRoot = foreign.rootScope().context().scope();

            assertFalse(root.sharesDomainWith(foreignRoot));
            assertFalse(foreignRoot.sharesDomainWith(root));
        }
    }
}
