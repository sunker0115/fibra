package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredCompilation;
import com.sstlfsj.fibra.config.DesiredSourceSnapshot;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.FileDesiredStateRepository;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticDesiredRefreshTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void changedSourceUpdatesTheExistingInstanceAutomatically(@TempDir Path work)
        throws Exception {
        var root = work.resolve("fibra.yaml");
        write(root, "one");
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var definition = PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> {
                starts.incrementAndGet();
                context.effects().add((Disposable) () -> Mono.fromRunnable(
                    stops::incrementAndGet));
                return Mono.empty();
            }).build();

        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(
                root, ConfigLimits.defaults()))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition,
                value -> (String) value)))
            .autoRefresh(Duration.ofMillis(50))
            .build()) {
            var initial = engine.start().block(TIMEOUT);
            var identity = initial.engine().instances().get("sample").identity();

            var refresh = engine.published().views()
                .filter(view -> LiteralValue.of("two").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            write(root, "two");
            var refreshed = Mono.fromFuture(refresh).block(TIMEOUT);

            assertEquals(identity,
                refreshed.engine().instances().get("sample").identity());
            assertEquals(2, starts.get());
            assertEquals(1, stops.get());
        }
    }

    @Test
    void periodicResyncFindsAChangeWithoutAFileSignal() {
        var repository = new InMemoryDesiredStateRepository(graph("one"));
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(repository)
            .catalog(catalog(definition)).autoRefresh(Duration.ofMillis(25)).build()) {
            var initial = engine.start().block(TIMEOUT);
            var transaction = repository.prepareReplace(
                repository.load().snapshot().revision(), graph("two"));
            var refresh = engine.published().views()
                .filter(view -> LiteralValue.of("two").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            transaction.commit();
            transaction.close();
            var refreshed = Mono.fromFuture(refresh).block(TIMEOUT);

            assertEquals(initial.engine().instances().get("sample").identity(),
                refreshed.engine().instances().get("sample").identity());
        }
    }

    @Test
    void invalidOrDeletedSourceKeepsLastGoodTargetAndRestoringItClearsTheFailure(
        @TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        write(root, "one");
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var definition = definition(starts, stops);
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(
                root, ConfigLimits.defaults()))
            .catalog(catalog(definition)).autoRefresh(Duration.ofMillis(25)).build()) {
            var initial = engine.start().block(TIMEOUT);
            var identity = initial.engine().instances().get("sample").identity();

            var failure = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.FAILED)
                .next().toFuture();
            Files.delete(root);
            var failed = Mono.fromFuture(failure).block(TIMEOUT);
            assertNotNull(failed.engineDiagnostics().failure());
            assertTrue(failed.engineDiagnostics().targetSatisfied());
            assertTrue(failed.engineDiagnostics().mutationGateOpen());
            assertEquals(initial.engine().desiredSource(), failed.engine().desiredSource());
            assertEquals(identity, failed.engine().instances().get("sample").identity());
            assertEquals(LiteralValue.of("one"),
                failed.engine().instances().get("sample").config());

            var recovery = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.IDLE
                    && view.engineDiagnostics().failure() == null)
                .next().toFuture();
            write(root, "one");
            var recovered = Mono.fromFuture(recovery).block(TIMEOUT);
            assertNull(recovered.engineDiagnostics().failure());
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertEquals(initial.engine().desiredSource(), recovered.engine().desiredSource());
            assertEquals(identity, recovered.engine().instances().get("sample").identity());
            assertEquals(1, starts.get());
            assertEquals(0, stops.get());
        }
    }

    @Test
    void conditionalEvaluationFailureKeepsLastGoodTargetUntilTheSourceRecovers(
        @TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        Files.writeString(root, """
            - id: sample
              plugin: sample
              when: {$eq: [{$ref: /enabled}, true]}
              config: {$ref: /value}
            """);
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var repository = new FileDesiredStateRepository(root, ConfigLimits.defaults());
        try (var engine = FibraEngine.builder(repository)
            .catalog(catalog(definition(starts, stops)))
            .configContext(ConfigContextSnapshot.of(Map.of("enabled", true, "value", "one")))
            .autoRefresh(Duration.ofMillis(25)).build()) {
            var initial = engine.start().block(TIMEOUT);
            var identity = initial.engine().instances().get("sample").identity();
            var targetRevision = initial.engineDiagnostics().targetRevision();
            var sourceRevision = initial.engine().desiredSource().revision();

            var failure = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.FAILED)
                .next().toFuture();
            Files.writeString(root, """
                - id: sample
                  plugin: sample
                  when: {$ref: /missing-condition}
                  config: {$ref: /value}
                """);
            var rejectedRevision = repository.load().snapshot().revision();
            var failed = Mono.fromFuture(failure).block(TIMEOUT);

            assertNotNull(failed.engineDiagnostics().failure());
            assertTrue(failed.engineDiagnostics().targetSatisfied());
            assertTrue(failed.engineDiagnostics().mutationGateOpen());
            assertEquals(targetRevision, failed.engineDiagnostics().targetRevision());
            assertEquals(sourceRevision, failed.engine().desiredSource().revision());
            assertNotEquals(rejectedRevision, failed.engine().desiredSource().revision());
            assertEquals(identity, failed.engine().instances().get("sample").identity());
            assertEquals(LiteralValue.of("one"), failed.engine().instances().get("sample").config());
            assertEquals(1, starts.get());
            assertEquals(0, stops.get());

            var recovery = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.IDLE
                    && LiteralValue.of("two").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            Files.writeString(root, """
                - id: sample
                  plugin: sample
                  when: {$eq: [{$ref: /enabled}, true]}
                  context: {value: two}
                  config: {$ref: /value}
                """);
            var recoveredRevision = repository.load().snapshot().revision();
            var recovered = Mono.fromFuture(recovery).block(TIMEOUT);

            assertNull(recovered.engineDiagnostics().failure());
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertTrue(recovered.engineDiagnostics().mutationGateOpen());
            assertEquals(recoveredRevision, recovered.engine().desiredSource().revision());
            assertEquals(identity, recovered.engine().instances().get("sample").identity());
            assertEquals(2, starts.get());
            assertEquals(1, stops.get());

            Mono.delay(Duration.ofMillis(150)).block(TIMEOUT);
            assertEquals(2, starts.get());
            assertEquals(1, stops.get());
        }
    }

    @Test
    void closeWaitsForAnAcceptedRefreshBeforeStoppingTheSourceMonitor(
        @TempDir Path work) throws Exception {
        var first = Files.createDirectories(work.resolve("first")).resolve("fibra.yaml");
        var second = Files.createDirectories(work.resolve("second")).resolve("fibra.yaml");
        var repository = new BlockingDesiredRepository(first, second);
        var engine = FibraEngine.builder(repository)
            .catalog(catalog(definition(new AtomicInteger(), new AtomicInteger())))
            .autoRefresh(Duration.ofHours(1)).build();
        try {
            engine.start().block(TIMEOUT);
            repository.blockNextLoad();
            var refresh = engine.submit(new RefreshDesired(null)).toFuture();
            assertTrue(repository.loadEntered.await(5, TimeUnit.SECONDS));

            var close = engine.closeAsync().toFuture();
            assertFalse(close.isDone());
            repository.releaseLoad.countDown();

            assertTrue(refresh.get(5, TimeUnit.SECONDS).view()
                .engineDiagnostics().targetSatisfied());
            close.get(5, TimeUnit.SECONDS);
        } finally {
            repository.releaseLoad.countDown();
            engine.closeAsync().block(TIMEOUT);
        }
    }

    @Test
    void sourceFailureDoesNotMaskALaterManagedCommandFailure(
        @TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        write(root, "one");
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(
                root, ConfigLimits.defaults()))
            .catalog(catalog(definition)).autoRefresh(Duration.ofMillis(25)).build()) {
            engine.start().block(TIMEOUT);
            var sourceFailure = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.FAILED)
                .next().toFuture();
            Files.delete(root);
            var sourceFailed = Mono.fromFuture(sourceFailure).block(TIMEOUT);
            assertTrue(sourceFailed.engineDiagnostics().targetSatisfied());

            var failed = assertThrows(EngineChangeException.class,
                () -> engine.submit(InstallArtifact.builder()
                    .expectedRevision(sourceFailed.viewRevision())
                    .artifactId(new ArtifactId("missing-store"))
                    .runtimeId(new RuntimeId("fixture"))
                    .version("1.0.0").source(root).build()).block(TIMEOUT));
            assertFalse(failed.view().engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void unchangedSourceDoesNotOverwriteAManagedDesiredChange(@TempDir Path work)
        throws Exception {
        var root = work.resolve("fibra.yaml");
        write(root, "source");
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(
                root, ConfigLimits.defaults()))
            .catalog(catalog(definition)).autoRefresh(Duration.ofMillis(25)).build()) {
            var initial = engine.start().block(TIMEOUT);
            var managed = engine.submit(new ReplaceDesiredGraph(initial.viewRevision(),
                initial.engine().desiredSource().revision(), graph("managed")))
                .block(TIMEOUT).view();

            Mono.delay(Duration.ofMillis(150)).block(TIMEOUT);

            assertEquals(LiteralValue.of("managed"), engine.published().current()
                .engine().instances().get("sample").config());
            assertEquals(managed.viewRevision(), engine.published().current().viewRevision());

            var sourceImport = engine.published().views()
                .filter(view -> LiteralValue.of("next-source").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            write(root, "next-source");
            var imported = Mono.fromFuture(sourceImport).block(TIMEOUT);
            assertTrue(imported.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void recoveredTargetIsNotOverwrittenByTheInitialSourceObservation(
        @TempDir Path work) throws Exception {
        var root = work.resolve("fibra.yaml");
        write(root, "source");
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(), graph("saved")));
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(new FileDesiredStateRepository(
                root, ConfigLimits.defaults()))
            .stateStore(stateStore).catalog(catalog(definition))
            .autoRefresh(Duration.ofMillis(25)).build()) {
            engine.start().block(TIMEOUT);

            Mono.delay(Duration.ofMillis(150)).block(TIMEOUT);

            assertEquals(LiteralValue.of("saved"), engine.published().current()
                .engine().instances().get("sample").config());

            var sourceImport = engine.published().views()
                .filter(view -> LiteralValue.of("changed").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            write(root, "changed");
            var imported = Mono.fromFuture(sourceImport).block(TIMEOUT);
            assertTrue(imported.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void recoveredTargetPublishesAnInvalidInitialSourceWithoutWaitingForTheInterval() {
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(), graph("saved")));
        DesiredStateRepository unavailable = () -> {
            throw new IllegalStateException("source unavailable");
        };
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(unavailable)
            .stateStore(stateStore).catalog(catalog(definition))
            .autoRefresh(Duration.ofHours(1)).build()) {
            var failure = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.FAILED
                    && view.engineDiagnostics().failure() != null)
                .next().toFuture();
            var initial = engine.start().block(TIMEOUT);
            var failed = Mono.fromFuture(failure).block(Duration.ofSeconds(1));

            assertEquals(LiteralValue.of("saved"), initial.engine().instances()
                .get("sample").config());
            assertNotNull(failed);
            assertTrue(failed.engineDiagnostics().targetSatisfied());
            assertTrue(failed.engineDiagnostics().mutationGateOpen());
        }
    }

    @Test
    void recoveredTargetUsesTheFirstSuccessfulObservationAsItsSourceBaseline() {
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(), graph("saved")));
        var delegate = new InMemoryDesiredStateRepository(graph("source"));
        var repository = new FailOnceDesiredRepository(delegate);
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(repository)
            .stateStore(stateStore).catalog(catalog(definition))
            .autoRefresh(Duration.ofMillis(25)).build()) {
            engine.start().block(TIMEOUT);
            Mono.delay(Duration.ofMillis(150)).block(TIMEOUT);

            assertEquals(LiteralValue.of("saved"), engine.published().current()
                .engine().instances().get("sample").config());

            var transaction = delegate.prepareReplace(
                delegate.load().snapshot().revision(), graph("changed"));
            var sourceImport = engine.published().views()
                .filter(view -> LiteralValue.of("changed").equals(
                    view.engine().instances().get("sample").config()))
                .next().toFuture();
            transaction.commit();
            transaction.close();
            var imported = Mono.fromFuture(sourceImport).block(TIMEOUT);
            assertTrue(imported.engineDiagnostics().targetSatisfied());
        }
    }

    @Test
    void explicitRefreshImportsTheSourceWhileTheAutomaticBaselineIsStillPending() {
        var stateStore = EngineStateStore.inMemory();
        stateStore.save(new DeploymentManifest(Map.of(), graph("saved")));
        var repository = new FailTwiceDesiredRepository(
            new InMemoryDesiredStateRepository(graph("source")));
        var definition = definition(new AtomicInteger(), new AtomicInteger());
        try (var engine = FibraEngine.builder(repository)
            .stateStore(stateStore).catalog(catalog(definition))
            .autoRefresh(Duration.ofHours(1)).build()) {
            var failure = engine.published().views()
                .filter(view -> view.engineDiagnostics().phase() == ChangePhase.FAILED)
                .next().toFuture();
            engine.start().block(TIMEOUT);
            var failed = Mono.fromFuture(failure).block(TIMEOUT);

            var refreshed = engine.submit(new RefreshDesired(failed.viewRevision()))
                .block(TIMEOUT).view();

            assertEquals(LiteralValue.of("source"), refreshed.engine().instances()
                .get("sample").config());
            assertTrue(refreshed.engineDiagnostics().targetSatisfied());
        }
    }

    private static PluginDefinition<String> definition(AtomicInteger starts,
                                                        AtomicInteger stops) {
        return PluginDefinition.builder("sample", String.class,
            () -> (context, config) -> {
                starts.incrementAndGet();
                context.effects().add((Disposable) () -> Mono.fromRunnable(
                    stops::incrementAndGet));
                return Mono.empty();
            }).build();
    }

    private static PluginCatalog catalog(PluginDefinition<String> definition) {
        return PluginCatalog.of(new PluginCatalogEntry<>(definition,
            value -> (String) value));
    }

    private static DesiredInputGraph graph(String config) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder(
            "sample", "sample").config(LiteralValue.of(config)).build()));
    }

    private static void write(Path root, String config) throws Exception {
        Files.writeString(root, "- id: sample\n  plugin: sample\n  config: "
            + config + "\n");
    }

    private static final class BlockingDesiredRepository implements DesiredStateRepository {
        private final DesiredCompilation initial;
        private final DesiredCompilation refreshed;
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch loadEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLoad = new CountDownLatch(1);

        private BlockingDesiredRepository(Path initialSource, Path refreshedSource) {
            initial = DesiredCompilation.builder()
                .snapshot(new DesiredSourceSnapshot("blocking", "one", Set.of(initialSource)))
                .graph(graph("one")).build();
            refreshed = DesiredCompilation.builder()
                .snapshot(new DesiredSourceSnapshot("blocking", "two", Set.of(refreshedSource)))
                .graph(graph("one")).build();
        }

        private void blockNextLoad() {
            blockNext.set(true);
        }

        @Override
        public DesiredCompilation load() {
            if (blockNext.compareAndSet(true, false)) {
                loadEntered.countDown();
                try {
                    if (!releaseLoad.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release source load");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("source load interrupted", failure);
                }
                return refreshed;
            }
            return initial;
        }
    }

    private static final class FailOnceDesiredRepository implements DesiredStateRepository {
        private final DesiredStateRepository delegate;
        private final AtomicBoolean first = new AtomicBoolean(true);

        private FailOnceDesiredRepository(DesiredStateRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public DesiredCompilation load() {
            if (first.compareAndSet(true, false)) {
                throw new IllegalStateException("transient source failure");
            }
            return delegate.load();
        }
    }

    private static final class FailTwiceDesiredRepository implements DesiredStateRepository {
        private final DesiredStateRepository delegate;
        private final AtomicInteger failures = new AtomicInteger(2);

        private FailTwiceDesiredRepository(DesiredStateRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public DesiredCompilation load() {
            if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("transient source failure");
            }
            return delegate.load();
        }
    }
}
