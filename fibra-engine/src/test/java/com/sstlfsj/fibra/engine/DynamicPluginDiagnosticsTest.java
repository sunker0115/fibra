package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.EventMode;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DynamicPluginDiagnosticsTest {
    @Test
    void everyPublishedViewUsesOneStateCaptureDuringContinuousInstanceUpdates() {
        var parentContext = new AtomicReference<com.sstlfsj.fibra.Context>();
        var definition = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) -> {
            parentContext.set(context);
            return Mono.empty();
        }).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("parent", "parent").build()));
        var done = ServiceKey.of("finished-updates", String.class);
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null))).build()) {
            engine.start().block(Duration.ofSeconds(5));
            var collected = engine.published().views().takeUntil(view -> view.diagnostics().services().stream()
                .anyMatch(service -> service.service().name().equals(done.name()))).collectList().toFuture();
            var instance = parentContext.get().plugins().current().orElseThrow();
            for (var index = 0; index < 300; index++) instance.update(null).block(Duration.ofSeconds(5));
            parentContext.get().scope().context().services().provide(done, "complete");

            var views = Mono.fromFuture(collected).block(Duration.ofSeconds(5));
            assertTrue(views.size() > 2);
            for (var view : views) {
                var managed = view.engine().instances().get("parent");
                var diagnostic = view.diagnostics().plugins().stream()
                    .filter(plugin -> plugin.identity() == managed.identity()).findFirst().orElseThrow();
                assertEquals(managed.state(), diagnostic.state(), "view " + view.viewRevision());
                assertEquals(managed.failure(), diagnostic.failure(), "view " + view.viewRevision());
            }
        }
    }

    @Test
    void disposedManagedInstanceDoesNotBorrowTheStateOfASameNamedReplacement() {
        var parentContext = new AtomicReference<com.sstlfsj.fibra.Context>();
        var definition = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) -> {
            parentContext.set(context);
            return Mono.empty();
        }).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("parent", "parent").build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, ignored -> null))).build()) {
            var initial = engine.start().block(Duration.ofSeconds(5));
            var original = parentContext.get().plugins().current().orElseThrow();
            original.dispose().block(Duration.ofSeconds(5));
            var replacement = parentContext.get().scope().context().plugins().mount("parent", definition.prepare(null));
            replacement.settled().block(Duration.ofSeconds(5));
            var view = engine.published().views().filter(value -> value.diagnostics().plugins().stream()
                .anyMatch(plugin -> plugin.identity() == replacement.identity()
                    && plugin.state() == PluginInstanceState.ACTIVE)).next().block(Duration.ofSeconds(5));

            var managed = view.engine().instances().get("parent");
            assertEquals(initial.engine().instances().get("parent").identity(), managed.identity());
            assertNotEquals(replacement.identity(), managed.identity());
            assertEquals(PluginInstanceState.DISPOSED, managed.state());
            assertTrue(!managed.requirementSatisfied());
        }
    }

    @Test
    void scopedServiceAndEventChangesRefreshDiagnosticsWhileManagedPluginsStayActive() {
        var parentContext = new AtomicReference<com.sstlfsj.fibra.Context>();
        var parent = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) -> {
            parentContext.set(context);
            return Mono.empty();
        }).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("parent", "parent").build()));
        var service = ServiceKey.of("dynamic-service", String.class);
        var event = EventKey.of("dynamic-event", Runnable.class, EventMode.EMIT);
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(parent, ignored -> null))).build()) {
            engine.start().block(Duration.ofSeconds(5));
            var appeared = engine.published().views().filter(view ->
                view.diagnostics().services().stream().anyMatch(value -> value.service().name().equals(service.name()))
                    && view.diagnostics().events().stream().anyMatch(value -> value.name().equals(event.name())
                        && value.listeners().size() == 1)).next().toFuture();
            var scope = parentContext.get().scope().openChild("dynamic-resources");
            scope.context().services().provide(service, "value");
            scope.context().events().on(event, () -> { });
            var active = Mono.fromFuture(appeared).block(Duration.ofSeconds(5));
            assertEquals(PluginInstanceState.ACTIVE, active.engine().instances().get("parent").state());

            var removed = engine.published().views().filter(view ->
                view.diagnostics().services().stream().noneMatch(value -> value.service().name().equals(service.name()))
                    && view.diagnostics().events().stream().filter(value -> value.name().equals(event.name()))
                        .allMatch(value -> value.listeners().isEmpty())).next().toFuture();
            scope.closeAsync().block(Duration.ofSeconds(5));
            assertEquals(PluginInstanceState.ACTIVE,
                Mono.fromFuture(removed).block(Duration.ofSeconds(5)).engine().instances().get("parent").state());
        }
    }

    @Test
    void childMountedAfterPublicationIsObservedWithoutAParentStateChange() {
        var parentContext = new AtomicReference<com.sstlfsj.fibra.Context>();
        var parent = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) -> {
            parentContext.set(context);
            return Mono.empty();
        }).build();
        var child = PluginDefinition.builder("dynamic", Void.class,
            () -> (context, ignored) -> Mono.empty()).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("parent", "parent").build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(parent, ignored -> null))).build()) {
            engine.start().block(Duration.ofSeconds(5));
            var appeared = engine.published().views().filter(view -> view.diagnostics().plugins().stream()
                .anyMatch(plugin -> plugin.pluginId().equals("dynamic")
                    && plugin.state() == PluginInstanceState.ACTIVE)).next().toFuture();

            var instance = parentContext.get().plugins().mount("child", child.prepare(null));
            instance.settled().block(Duration.ofSeconds(5));
            var active = Mono.fromFuture(appeared).block(Duration.ofSeconds(5));
            assertEquals(1, active.engine().instances().size());
            assertEquals(PluginInstanceState.ACTIVE, active.engine().instances().get("parent").state());

            var removed = engine.published().views().filter(view ->
                !view.viewRevision().equals(active.viewRevision()) && view.diagnostics().plugins().stream()
                    .noneMatch(plugin -> plugin.pluginId().equals("dynamic"))).next().toFuture();
            instance.dispose().block(Duration.ofSeconds(5));
            assertEquals(1, Mono.fromFuture(removed).block(Duration.ofSeconds(5)).diagnostics().plugins().size());
        }
    }

    @Test
    void scopeLocalChildWithSameIdDoesNotInheritTheManagedDeclarationRequirement() {
        var missing = ServiceKey.of("missing", String.class);
        var child = PluginDefinition.builder("dynamic", Void.class,
            () -> (context, ignored) -> Mono.empty()).require(missing).build();
        var parent = PluginDefinition.builder("parent", Void.class, () -> (context, ignored) -> {
            context.scope().openChild("nested").context().plugins().mount("same-id", child.prepare(null));
            return Mono.empty();
        }).build();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder("same-id", "parent").build()));
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(parent, ignored -> null))).build()) {
            var view = engine.start().block(Duration.ofSeconds(5));

            assertEquals(1, view.engine().instances().size());
            var managed = view.engine().instances().get("same-id");
            assertEquals("parent", managed.definitionName());
            assertEquals(PublicationRequirement.ACTIVE_REQUIRED, managed.publicationRequirement());
            assertTrue(managed.requirementSatisfied());
            assertEquals(2, view.diagnostics().plugins().size());
            var dynamic = view.diagnostics().plugins().stream()
                .filter(plugin -> plugin.pluginId().equals("dynamic")).findFirst().orElseThrow();
            assertEquals("same-id", dynamic.instanceId());
            assertEquals(PluginInstanceState.PENDING, dynamic.state());
            assertEquals(List.of("missing"), dynamic.waitingFor().stream().map(value -> value.name()).toList());
        }
    }

    @Test
    void observedRequirementUsesTheSameStateRulesAsVerification() {
        for (var requirement : PublicationRequirement.values()) {
            for (var state : PluginInstanceState.values()) {
                var snapshot = PluginInstanceSnapshot.builder().instanceId("p").definitionName("p")
                    .config(LiteralValue.NullValue.INSTANCE).state(state)
                    .publicationRequirement(requirement).build();
                var expected = state == PluginInstanceState.ACTIVE
                    || requirement == PublicationRequirement.PENDING_ALLOWED && state == PluginInstanceState.PENDING;
                assertEquals(expected, snapshot.requirementSatisfied(), requirement + " / " + state);
            }
        }
    }
}
