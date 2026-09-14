package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EnginePersistenceBoundaryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void failureBeforeTargetReplacementKeepsRunningStateAndAllowsTheNextCommand(@TempDir Path work) {
        var starts = new AtomicInteger();
        var io = new FailingIo();
        var store = new FileEngineStateStore(work, io);
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old")))
            .stateStore(store).catalog(catalog(starts)).build()) {
            var initial = engine.start().block(TIMEOUT);
            var identity = initial.engine().instances().get("sample").identity();
            io.failBeforeReplace = true;
            var failure = assertThrows(EngineChangeException.class,
                () -> replace(engine, graph("new")));

            assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
            assertTrue(failure.view().engineDiagnostics().mutationGateOpen());
            assertEquals(graph("old"), store.load().orElseThrow().desiredGraph());
            assertEquals(graph("old"), failure.view().engine().desiredGraph());
            assertEquals(1, starts.get());
            io.failBeforeReplace = false;
            var updated = replace(engine, graph("new"));
            assertTrue(updated.engineDiagnostics().targetSatisfied());
            assertEquals(identity, updated.engine().instances().get("sample").identity());
            assertEquals(2, starts.get());
        }
    }

    @Test
    void unconfirmedReplacementStopsMutationsAndReopensTheActualSavedTarget(@TempDir Path work) {
        var starts = new AtomicInteger();
        var io = new FailingIo();
        var store = new FileEngineStateStore(work, io);
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("old")))
            .stateStore(store).catalog(catalog(starts)).build()) {
            engine.start().block(TIMEOUT);
            io.failAfterReplace = true;
            var failure = assertThrows(EngineChangeException.class,
                () -> replace(engine, graph("saved")));

            assertInstanceOf(EngineStateStore.SaveUnconfirmedException.class, failure.getCause());
            assertEquals(TargetSaveState.UNCONFIRMED, failure.targetSaveState());
            assertFalse(failure.view().engineDiagnostics().mutationGateOpen());
            assertEquals(graph("saved"), store.load().orElseThrow().desiredGraph());
            assertEquals(graph("old"), failure.view().engine().desiredGraph());
            assertEquals(1, starts.get(), "unconfirmed target must not start running");
            assertThrows(MutationGateClosedException.class, () -> replace(engine, graph("later")));
        }
        try (var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .stateStore(new FileEngineStateStore(work)).catalog(catalog(starts)).build()) {
            var restored = engine.start().block(TIMEOUT);
            assertEquals(graph("saved"), restored.engine().desiredGraph());
            assertTrue(restored.engineDiagnostics().targetSatisfied());
            assertEquals(2, starts.get());
        }
    }

    @Test
    void corruptTargetDoesNotFallBackToTheSourceOrOverwriteEvidence(@TempDir Path work) throws Exception {
        var path = work.resolve("target.json");
        Files.writeString(path, "broken target");
        var starts = new AtomicInteger();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("fallback")))
            .stateStore(new FileEngineStateStore(work)).catalog(catalog(starts)).build()) {
            assertThrows(EngineStateStoreException.class, () -> engine.start().block(TIMEOUT));
            assertEquals(EngineState.FAILED, engine.published().current().engine().state());
            assertFalse(engine.published().current().engineDiagnostics().mutationGateOpen());
            assertEquals(0, starts.get());
            assertEquals("broken target", Files.readString(path));
        }
    }

    private static PublishedView replace(FibraEngine engine, DesiredInputGraph graph) {
        var current = engine.published().current();
        return engine.submit(new ReplaceDesiredGraph(null,
            current.engine().desiredSource().revision(), graph)).block(TIMEOUT).view();
    }

    private static PluginCatalog catalog(AtomicInteger starts) {
        var definition = PluginDefinition.builder("sample", String.class, () -> (context, config) -> {
            starts.incrementAndGet();
            return Mono.empty();
        }).build();
        return PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value));
    }

    private static DesiredInputGraph graph(String config) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("sample", "sample")
            .config(LiteralValue.of(config)).build()));
    }

    private static final class FailingIo implements FileEngineStateStore.StorageIo {
        boolean failBeforeReplace;
        boolean failAfterReplace;

        @Override public void force(Path path) throws IOException {
            if (failBeforeReplace && !Files.isDirectory(path)) throw new IOException("staged file sync failed");
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @Override public void replace(Path staged, Path target) throws IOException {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (failAfterReplace) throw new IOException("replacement completion is unknown");
        }
    }
}
