package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginCatalogEntry;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.registry.PluginAuditEntry;
import com.sstlfsj.fibra.registry.PluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginEnableRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
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
import java.util.List;
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
    private static final PluginEnableRequest ENABLE = PluginEnableRequest.of(
        INSTANCE_ID, DEFINITION_NAME, com.sstlfsj.fibra.value.LiteralValue.of(null));

    private FibraEngine engine;
    private PluginRegistry registry;

    @Setup
    public void setup() {
        var definition = PluginDefinition.builder(DEFINITION_NAME, Void.class,
            () -> (context, config) -> Mono.empty()).build();
        var catalog = PluginCatalog.of(new PluginCatalogEntry<>(definition,
            ignored -> null));
        var desired = new DesiredInputGraph(List.of(
            DesiredInputEntry.builder(INSTANCE_ID, DEFINITION_NAME).build()));
        engine = FibraEngine.builder(new InMemoryDesiredStateRepository(desired))
            .catalog(catalog).build();
        registry = new PluginRegistry(engine, new DiscardingAudit());
        engine.start().block();
    }

    @TearDown
    public void tearDown() {
        engine.close();
    }

    @Benchmark
    @OperationsPerInvocation(2)
    public String disableAndEnable() {
        registry.disable(INSTANCE_ID).block();
        registry.enable(ENABLE).block();
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
