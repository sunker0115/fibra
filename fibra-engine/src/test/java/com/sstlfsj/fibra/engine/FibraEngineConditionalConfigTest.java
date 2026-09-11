package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigException;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGroup;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineConditionalConfigTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void initialContextIsPublishedAndViewAndContextCompareAndSetAreIndependent() {
        var initialContext = context(true, "one");
        var nextContext = context(true, "two");
        var graph = graph(configuredEntry("conditional"));
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> Mono.empty()).build();

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(stringCatalog(definition)).configContext(initialContext).build()) {
            var started = engine.start().block(TIMEOUT);

            assertEquals(initialContext.revision(),
                started.engineDiagnostics().contextRevision());
            assertEquals(LiteralValue.of("one"),
                started.engine().instances().get("conditional").config());

            assertThrows(PublishedRevisionConflictException.class, () -> engine.submit(
                new ReplaceConfigContext("stale-view", initialContext.revision(), nextContext))
                .block(TIMEOUT));
            assertThrows(IllegalArgumentException.class, () -> engine.submit(
                new ReplaceConfigContext(started.viewRevision(), "stale-context", nextContext))
                .block(TIMEOUT));

            assertEquals(started, engine.published().current());
        }
    }

    @Test
    void contextOnlyChangeDoesNotSaveOrReviseTheDeploymentTarget() {
        var store = new RecordingStateStore();
        var initialContext = context(true, "one");
        var nextContext = context(true, "two");
        var graph = graph(configuredEntry("conditional"));
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> Mono.empty()).build();

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .stateStore(store).catalog(stringCatalog(definition)).configContext(initialContext).build()) {
            var started = engine.start().block(TIMEOUT);
            var savesBefore = store.saves;

            var changed = engine.submit(new ReplaceConfigContext(started.viewRevision(),
                initialContext.revision(), nextContext)).block(TIMEOUT).view();

            assertEquals(savesBefore, store.saves);
            assertEquals(started.engineDiagnostics().targetRevision(),
                changed.engineDiagnostics().targetRevision());
            assertEquals(nextContext.revision(), changed.engineDiagnostics().contextRevision());
            assertNotEquals(started.viewRevision(), changed.viewRevision());
            assertTrue(changed.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void conditionFlipOnlyReconcilesItsSubtreeAndKeepsSiblingIdentity() {
        var startsConditional = new AtomicInteger();
        var startsSibling = new AtomicInteger();
        var conditional = PluginDefinition.builder("conditional", Void.class,
            () -> (ignored, config) -> {
                startsConditional.incrementAndGet();
                return Mono.empty();
            }).build();
        var sibling = PluginDefinition.builder("sibling", Void.class,
            () -> (ignored, config) -> {
                startsSibling.incrementAndGet();
                return Mono.empty();
            }).build();
        var graph = new DesiredInputGraph(List.of(
            DesiredInputGroup.builder("switch")
                .when(reference("/enabled"))
                .children(List.of(DesiredInputEntry.builder("child", "conditional").build()))
                .build(),
            DesiredInputEntry.builder("sibling", "sibling").build()));
        var disabled = ConfigContextSnapshot.of(Map.of("enabled", false));
        var enabled = ConfigContextSnapshot.of(Map.of("enabled", true));

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(conditional, ignored -> null),
                new PluginCatalogEntry<>(sibling, ignored -> null)))
            .configContext(disabled).build()) {
            var started = engine.start().block(TIMEOUT);
            var siblingIdentity = started.engine().instances().get("sibling").identity();

            assertFalse(started.engine().instances().containsKey("child"));
            var changed = engine.submit(new ReplaceConfigContext(started.viewRevision(),
                disabled.revision(), enabled)).block(TIMEOUT).view();

            assertTrue(changed.engine().instances().containsKey("child"));
            assertEquals(siblingIdentity, changed.engine().instances().get("sibling").identity());
            assertEquals(1, startsConditional.get());
            assertEquals(1, startsSibling.get());
        }
    }

    @Test
    void resolvedConfigChangeUpdatesTheOriginalInstanceWithoutChangingRawDesired() {
        var starts = new AtomicInteger();
        var expression = reference("/value");
        var raw = DesiredInputEntry.builder("sample", "sample").config(expression).build();
        var graph = graph(raw);
        var first = ConfigContextSnapshot.of(Map.of("value", "one"));
        var second = ConfigContextSnapshot.of(Map.of("value", "two"));
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> {
                starts.incrementAndGet();
                return Mono.empty();
            }).build();

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .catalog(stringCatalog(definition)).configContext(first).build()) {
            var started = engine.start().block(TIMEOUT);
            var identity = started.engine().instances().get("sample").identity();
            var changed = engine.submit(new ReplaceConfigContext(started.viewRevision(),
                first.revision(), second)).block(TIMEOUT).view();

            assertEquals(identity, changed.engine().instances().get("sample").identity());
            assertEquals(2, starts.get());
            assertEquals(LiteralValue.of("two"), changed.engine().instances().get("sample").config());
            assertEquals(expression, changed.engine().desiredGraph().plugins().get("sample").config());
        }
    }

    @Test
    void failedContextEvaluationKeepsThePreviousContextTargetAndRuntimeIntact() {
        var store = new RecordingStateStore();
        var valid = ConfigContextSnapshot.of(Map.of("value", "ready"));
        var invalid = ConfigContextSnapshot.empty();
        var graph = graph(DesiredInputEntry.builder("sample", "sample")
            .config(reference("/value")).build());
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> Mono.empty()).build();

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .stateStore(store).catalog(stringCatalog(definition)).configContext(valid).build()) {
            var started = engine.start().block(TIMEOUT);
            var savesBefore = store.saves;
            var identity = started.engine().instances().get("sample").identity();

            assertThrows(ConfigException.class, () -> engine.submit(new ReplaceConfigContext(
                started.viewRevision(), valid.revision(), invalid)).block(TIMEOUT));

            var current = engine.published().current();
            assertEquals(started, current);
            assertEquals(savesBefore, store.saves);
            assertEquals(valid.revision(), current.engineDiagnostics().contextRevision());
            assertEquals(started.engineDiagnostics().targetRevision(),
                current.engineDiagnostics().targetRevision());
            assertEquals(identity, current.engine().instances().get("sample").identity());
            assertEquals(LiteralValue.of("ready"), current.engine().instances().get("sample").config());
            assertTrue(current.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void failedResolvedConfigBindingKeepsThePreviousContextAndViewIntact() {
        var store = new RecordingStateStore();
        var valid = ConfigContextSnapshot.of(Map.of("value", "ready"));
        var rejected = ConfigContextSnapshot.of(Map.of("value", "rejected"));
        var graph = graph(DesiredInputEntry.builder("sample", "sample")
            .config(reference("/value")).build());
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> Mono.empty()).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> {
            if ("rejected".equals(value)) {
                throw new IllegalArgumentException("rejected resolved config");
            }
            return (String) value;
        }));

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph))
            .stateStore(store).catalog(catalog).configContext(valid).build()) {
            var started = engine.start().block(TIMEOUT);
            var savesBefore = store.saves;

            assertThrows(DesiredBindingException.class, () -> engine.submit(
                new ReplaceConfigContext(started.viewRevision(), valid.revision(), rejected))
                .block(TIMEOUT));

            assertEquals(started, engine.published().current());
            assertEquals(savesBefore, store.saves);
            assertTrue(started.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void restartEvaluatesTheSavedRawTargetWithTheBuildersCurrentContext() {
        var store = new RecordingStateStore();
        var raw = graph(DesiredInputEntry.builder("sample", "sample")
            .config(reference("/value")).build());
        var firstContext = ConfigContextSnapshot.of(Map.of("value", "first"));
        var reopenedContext = ConfigContextSnapshot.of(Map.of("value", "reopened"));
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (ignored, config) -> Mono.empty()).build();

        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(raw))
            .stateStore(store).catalog(stringCatalog(definition)).configContext(firstContext).build()) {
            engine.start().block(TIMEOUT);
        }
        var unrelatedSource = graph(DesiredInputEntry.builder("sample", "sample")
            .config(LiteralValue.of("source-must-not-win")).build());
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(unrelatedSource))
            .stateStore(store).catalog(stringCatalog(definition)).configContext(reopenedContext).build()) {
            var reopened = engine.start().block(TIMEOUT);

            assertEquals(raw, reopened.engine().desiredGraph());
            assertEquals(LiteralValue.of("reopened"),
                reopened.engine().instances().get("sample").config());
            assertEquals(reopenedContext.revision(),
                reopened.engineDiagnostics().contextRevision());
            assertTrue(reopened.engineDiagnostics().targetSatisfied());
        }
    }

    private static DesiredInputGraph graph(DesiredInputEntry... entries) {
        return new DesiredInputGraph(List.of(entries));
    }

    private static DesiredInputEntry configuredEntry(String id) {
        return DesiredInputEntry.builder(id, "sample")
            .when(reference("/enabled")).config(reference("/value")).build();
    }

    private static ConfigContextSnapshot context(boolean enabled, String value) {
        return ConfigContextSnapshot.of(Map.of("enabled", enabled, "value", value));
    }

    private static LiteralValue reference(String pointer) {
        return LiteralValue.of(Map.of("$ref", pointer));
    }

    private static PluginCatalog stringCatalog(PluginDefinition<String> definition) {
        return PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value));
    }

    private static final class RecordingStateStore implements EngineStateStore {
        private DeploymentManifest target;
        private int saves;

        @Override
        public Optional<DeploymentManifest> load() {
            return Optional.ofNullable(target);
        }

        @Override
        public void save(DeploymentManifest manifest) {
            saves++;
            target = manifest;
        }
    }
}
