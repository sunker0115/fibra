package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.deployment.BuiltInFacet;
import com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.observation.DurableTargetState;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.observation.EngineState;
import com.sstlfsj.fibra.engine.observation.FailureSubject;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.deployment.PluginSelection;
import com.sstlfsj.fibra.engine.observation.TargetConvergence;
import com.sstlfsj.fibra.engine.observation.TargetSaveState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JavaBuiltInRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final PluginId PLUGIN = new PluginId("built-in");
    private static final FacetId FACET = new FacetId("host");
    private static final String DIGEST = "a".repeat(64);
    @TempDir Path work;

    @Test
    void persistedDefinitionMismatchRemainsManageableAndAcceptsReplacement() throws Exception {
        var starts = new AtomicInteger();
        var metadata = metadata(Set.of("sample"));
        try (var host = host(new JavaBuiltInPackage(metadata,
            Map.of(FACET, List.of(definition("sample", starts)))))) {
            host.startAsync().block(TIMEOUT);
            host.submit(deployment(0, true)).block(TIMEOUT);
        }
        assertEquals(1, starts.get());
        var bytes = Files.readAllBytes(work.resolve("target/target.json"));
        var broken = new JavaBuiltInPackage(metadata, Map.of(FACET, List.of()));
        try (var host = host(broken)) {
            var view = host.startAsync().block(TIMEOUT);
            assertEquals(EngineState.RUNNING, view.engine().state());
            assertEquals(DurableTargetState.PRESENT, view.engine().durableState());
            assertEquals(TargetConvergence.BLOCKED, view.engine().targetConvergence());
            assertTrue(view.engine().current().isEmpty());
            assertTrue(view.engine().candidate().isEmpty());
            assertInstanceOf(FailureSubject.DurableTarget.class,
                view.engineDiagnostics().failure().orElseThrow().subject());
            assertTrue(view.engineDiagnostics().mutationGateOpen());
            assertTrue(view.engineDiagnostics().terminationRequest().isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(work.resolve("target/target.json")));
            assertEquals(view, host.startAsync().block(TIMEOUT));

            var replacement = host.submit(deployment(1, false)).block(TIMEOUT).view();
            assertEquals(TargetConvergence.SATISFIED, replacement.engine().targetConvergence());
            assertEquals(2, replacement.engine().target().orElseThrow().targetRevision());
            assertTrue(replacement.engineDiagnostics().failure().isEmpty());
            assertEquals(1, starts.get(), "the mismatched provider must not start plugin code");
        }
        try (var host = host(broken)) {
            assertEquals(TargetConvergence.SATISFIED,
                host.startAsync().block(TIMEOUT).engine().targetConvergence());
        }
    }

    @Test
    void completeMetadataIsCheckedBeforeSavingEvenWhenRequestedDefinitionExists() {
        var starts = new AtomicInteger();
        var broken = new JavaBuiltInPackage(metadata(Set.of("sample", "missing")),
            Map.of(FACET, List.of(definition("sample", starts))));
        try (var host = host(broken)) {
            host.startAsync().block(TIMEOUT);
            var error = assertThrows(EngineChangeException.class,
                () -> host.submit(deployment(0, true)).block(TIMEOUT));
            assertEquals(TargetSaveState.NOT_SAVED, error.targetSaveState());
            assertFalse(Files.exists(work.resolve("target/target.json")));
            assertTrue(host.snapshot().candidate().isEmpty());
            assertTrue(host.snapshot().current().isEmpty());
            assertEquals(EngineState.RUNNING, host.snapshot().state());
            assertEquals(0, starts.get());
        }
    }

    private FibraEngine host(JavaBuiltInPackage builtIn) {
        return FibraEngine.builder(new PluginPackageStore(work.resolve("packages")),
                new FileDeploymentTargetStore(work.resolve("target")))
            .runtimeProvider(new JavaRuntimeProvider(List.of(builtIn)))
            .hostTerminationPort(ignored -> fail("recoverable definition mismatch requested termination"))
            .build();
    }

    private static BuiltInPluginPackage metadata(Set<String> definitions) {
        return BuiltInPluginPackage.builder().pluginId(PLUGIN).version("1")
            .packageDigest(DIGEST).facets(List.of(BuiltInFacet.builder(FACET,
                JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
                .definitionIds(definitions).build())).build();
    }

    private static JavaDefinitionEntry<Void> definition(String name, AtomicInteger starts) {
        return new JavaDefinitionEntry<>(PluginDefinition.builder(name, Void.class,
            () -> (context, ignored) -> Mono.fromRunnable(starts::incrementAndGet)).build(),
            ignored -> null);
    }

    private static ApplyDeployment deployment(long revision, boolean selected) {
        var entries = selected ? List.of(DesiredInputEntry.builder("unit",
            new PluginDefinitionRef(PLUGIN.value(), FACET.value(), "sample")).build())
            : List.<DesiredInputEntry>of();
        return ApplyDeployment.builder(new DesiredInputGraph(entries)).expectedRevision(revision)
            .selections(selected ? List.of(new PluginSelection(PLUGIN, DIGEST, true)) : List.of())
            .configContext(ConfigContextSnapshot.empty()).build();
    }
}
