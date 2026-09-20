package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.BuiltInFacet;
import com.sstlfsj.fibra.engine.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.registry.PluginAuditEntry;
import com.sstlfsj.fibra.registry.PluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaBuiltInPackage;
import com.sstlfsj.fibra.runtime.java.JavaDefinitionEntry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class EngineTransactionBenchmark {
    private static final String DEFINITION_NAME = "bench-noop";
    private static final String INSTANCE_ID = "bench-instance";
    private static final PluginId PLUGIN_ID = new PluginId("benchmark-host");
    private static final FacetId FACET_ID = new FacetId("main");

    private FibraEngine engine;
    private PluginRegistry registry;
    private Path work;

    @Setup
    public void setup() throws Exception {
        var definition = PluginDefinition.builder(DEFINITION_NAME, Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var metadata = BuiltInPluginPackage.builder().pluginId(PLUGIN_ID)
            .version("1.0.0").packageDigest("a".repeat(64))
            .facets(List.of(BuiltInFacet.builder(FACET_ID,
                    JavaRuntimeProvider.RUNTIME_ID, new ExecutionTarget("host"))
                .definitionIds(Set.of(DEFINITION_NAME)).build()))
            .build();
        var builtIn = new JavaBuiltInPackage(metadata,
            Map.of(FACET_ID, List.of(new JavaDefinitionEntry<>(definition,
                ignored -> null))));
        var desired = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder(INSTANCE_ID,
                new PluginDefinitionRef(PLUGIN_ID.value(), FACET_ID.value(),
                    DEFINITION_NAME)).build()));
        work = Files.createTempDirectory("fibra-engine-benchmark-");
        var packages = new PluginPackageStore(work.resolve("packages"));
        engine = FibraEngine.builder(packages, DeploymentTargetStore.inMemory())
            .runtimeProvider(new JavaRuntimeProvider(List.of(builtIn)))
            .hostTerminationPort(request -> { }).build();
        registry = new PluginRegistry(engine, packages, new DiscardingAudit());
        engine.startAsync().block();
        registry.deploy(new PluginDeploymentRequest(List.of(metadata.selection(true)),
            desired, ConfigContextSnapshot.empty())).block();
    }

    @TearDown
    public void tearDown() throws Exception {
        engine.close();
        try (var paths = Files.walk(work)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(2)
    public String disableAndEnable() {
        registry.disable(INSTANCE_ID).block();
        registry.enable(INSTANCE_ID).block();
        return registry.snapshot().viewRevision();
    }

    private static final class DiscardingAudit implements PluginAuditRepository {
        @Override
        public PluginAuditEntry append(String operation, String target,
                                       boolean succeeded,
                                       TargetSaveState targetSaveState,
                                       String viewRevision,
                                       String detail) {
            return PluginAuditEntry.builder().sequence(1).timestamp(Instant.EPOCH)
                .operation(operation).target(target).succeeded(succeeded)
                .targetSaveState(targetSaveState).viewRevision(viewRevision).detail(detail).build();
        }

        @Override
        public List<PluginAuditEntry> history() {
            return List.of();
        }
    }
}
