package com.sstlfsj.fibra.verification.host;

import com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.deployment.PluginFacetSource;
import com.sstlfsj.fibra.engine.execution.CompiledRuntimeSlice;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.execution.ExecutionUnitPlan;
import com.sstlfsj.fibra.engine.execution.RuntimePlan;
import com.sstlfsj.fibra.engine.execution.RuntimeUnitFence;
import com.sstlfsj.fibra.engine.observation.ExecutionObservation;
import com.sstlfsj.fibra.engine.observation.RuntimeDriverSnapshot;
import com.sstlfsj.fibra.engine.publication.PublishedView;
import com.sstlfsj.fibra.engine.runtime.PreparedRuntimeGeneration;
import com.sstlfsj.fibra.engine.runtime.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.runtime.RuntimeCandidate;
import com.sstlfsj.fibra.engine.runtime.RuntimeDriver;
import com.sstlfsj.fibra.engine.runtime.RuntimeHostServices;
import com.sstlfsj.fibra.engine.runtime.RuntimeProvider;
import com.sstlfsj.fibra.engine.runtime.RuntimeTargetSlice;
import com.sstlfsj.fibra.engine.runtime.RuntimeUnitGeneration;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.client.protocol.ClientMessage;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.engine.*;
import com.sstlfsj.fibra.verification.external.ExternalFixtureRuntimeHarness;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Verification-only product projection; delegates all execution to the public external SPI fixture. */
final class ClientProtocolFixtureProvider implements RuntimeProvider {
    private final ExternalFixtureRuntimeHarness external = new ExternalFixtureRuntimeHarness();
    private final Map<String, ClientMessage.Assignment> assignments = new ConcurrentHashMap<>();
    private final AtomicReference<Gate> nextPrepare = new AtomicReference<>();
    private final AtomicReference<Gate> nextDrain = new AtomicReference<>();
    private RuntimeHostServices services;

    @Override public RuntimeId id() { return external.provider().id(); }
    @Override public String contractIdentity() { return external.provider().contractIdentity(); }
    @Override public List<BuiltInPluginPackage> builtInPackages() { return external.provider().builtInPackages(); }

    @Override
    public RuntimeDriver create(RuntimeHostServices host) {
        services = host;
        var delegate = external.provider().create(host);
        return new RuntimeDriver() {
            @Override public RuntimeId id() { return delegate.id(); }
            @Override public Mono<RuntimeArtifactInspection> probe(PluginFacetSource source) { return delegate.probe(source); }
            @Override public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) { return delegate.inspect(facet); }
            @Override public RuntimeDriverSnapshot snapshot() { return delegate.snapshot(); }
            @Override public Mono<Void> closeAsync() { return delegate.closeAsync(); }
            @Override public RuntimeCandidate createCandidate(RuntimeTargetSlice target) {
                var candidate = delegate.createCandidate(target);
                var gate = nextPrepare.getAndSet(null);
                var templates = new LinkedHashMap<ExecutionUnitKey, AssignmentTemplate>();
                return new RuntimeCandidate() {
                    @Override public Mono<Void> prepareAsync() {
                        return candidate.prepareAsync()
                            .then(Mono.fromRunnable(() -> prepareTemplates(target, templates)))
                            .then(gate == null ? Mono.empty() : gate.awaitRelease());
                    }
                    @Override public RuntimePlan preparedPlan() { return candidate.preparedPlan(); }
                    @Override public Mono<Void> closeAsync() { return candidate.closeAsync(); }
                    @Override public PreparedRuntimeGeneration seal(CompiledRuntimeSlice slice) {
                        var generation = candidate.seal(slice);
                        var units = new LinkedHashMap<ExecutionUnitKey, RuntimeUnitGeneration>();
                        generation.units().forEach((key, unit) -> {
                            var assignment = templates.get(key).bind(unit.fence());
                            assignments.put(unit.fence().runtimeInstanceId(), assignment);
                            units.put(key, wrap(unit));
                        });
                        return new PreparedRuntimeGeneration() {
                            @Override public Map<ExecutionUnitKey, RuntimeUnitGeneration> units() { return Map.copyOf(units); }
                            @Override public Mono<Void> abortAsync() {
                                return generation.abortAsync().doOnSuccess(ignored -> removeAssignments(units));
                            }
                            @Override public Mono<Void> retireAsync() {
                                return generation.retireAsync().doOnSuccess(ignored -> removeAssignments(units));
                            }
                        };
                    }
                };
            }
        };
    }

    private RuntimeUnitGeneration wrap(RuntimeUnitGeneration unit) {
        return new RuntimeUnitGeneration() {
            @Override public ExecutionUnitPlan plan() { return unit.plan(); }
            @Override public RuntimeUnitFence fence() { return unit.fence(); }
            @Override public ExecutionObservation snapshot() { return unit.snapshot(); }
            @Override public void closeAdmission() { unit.closeAdmission(); }
            @Override public Mono<ExecutionObservation> reconcileAsync(String operation) { return unit.reconcileAsync(operation); }
            @Override public Mono<ExecutionObservation> stopAsync(String operation, Instant deadline) {
                return unit.stopAsync(operation, deadline);
            }
            @Override public Mono<ExecutionObservation> drainAsync(String operation, Instant deadline) {
                var gate = nextDrain.getAndSet(null);
                return (gate == null ? Mono.<Void>empty() : gate.awaitRelease())
                    .then(Mono.defer(() -> unit.drainAsync(operation, deadline)));
            }
        };
    }

    private void removeAssignments(Map<ExecutionUnitKey, RuntimeUnitGeneration> units) {
        units.values().forEach(unit -> assignments.remove(unit.fence().runtimeInstanceId()));
    }

    private static void prepareTemplates(RuntimeTargetSlice target,
                                         Map<ExecutionUnitKey, AssignmentTemplate> templates) {
        for (var id : target.affectedEntryIds()) {
            var resolved = target.desired().entries().get(id);
            var entry = (DesiredInputEntry) resolved.input();
            var source = target.facets().stream().map(PluginFacetSource::facet)
                .filter(facet -> facet.pluginId().value().equals(entry.definitionRef().pluginId())
                    && facet.facet().facetId().value().equals(entry.definitionRef().facetId()))
                .findFirst().orElseThrow();
            try {
                var bytes = Files.readAllBytes(source.facet().payload().resolve("index.mjs"));
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                templates.put(new ExecutionUnitKey(id), new AssignmentTemplate(entry,
                    source, resolved.resolvedConfig().orElseThrow(),
                    new ClientMessage.ResourceDescriptor("index.mjs", digest, bytes.length)));
            } catch (Exception error) {
                throw new IllegalStateException("cannot prepare client fixture metadata", error);
            }
        }
    }

    List<ClientMessage.Assignment> currentAssignments(PublishedView view) {
        // Candidate metadata and retirement units are intentionally not the projection source.
        return view.engine().current().stream().flatMap(current -> current.observations().entrySet().stream())
            .filter(entry -> entry.getValue().runtimeId().equals(id()))
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                var detail = entry.getValue().executions().getFirst();
                var assignment = assignments.get(detail.runtimeInstanceId());
                if (assignment == null || !assignment.desiredEntryId().equals(entry.getKey().value())
                    || assignment.unitTargetRevision() != detail.unitTargetRevision()) {
                    throw new IllegalStateException("current observation has no exact compiled assignment");
                }
                return assignment;
            }).toList();
    }

    void goOnline() { external.controller().goOnline(); }
    int stops() { return external.controller().snapshot().counters().stops(); }
    Gate holdNextPrepare() { var gate = new Gate(); nextPrepare.set(gate); return gate; }
    Gate holdNextDrain() { var gate = new Gate(); nextDrain.set(gate); return gate; }
    void replay(RuntimeUnitFence oldFence) {
        services.requestObservationRefresh(oldFence);
        services.requestReconcile(java.util.Set.of(oldFence), "retired-client-fixture-replay");
    }

    static final class Gate {
        private final Sinks.One<Void> entered = Sinks.one();
        private final Sinks.One<Void> release = Sinks.one();
        Mono<Void> awaitRelease() {
            return Mono.defer(() -> {
                entered.tryEmitEmpty();
                return release.asMono();
            });
        }
        void awaitEntered(Duration timeout) { entered.asMono().block(timeout); }
        void release() { release.tryEmitEmpty(); }
    }

    private record AssignmentTemplate(DesiredInputEntry entry, ManagedFacet source,
                                      com.sstlfsj.fibra.value.LiteralValue config,
                                      ClientMessage.ResourceDescriptor resource) {
        ClientMessage.Assignment bind(RuntimeUnitFence fence) {
            return new ClientMessage.Assignment(source.pluginId().value(), source.facet().facetId().value(),
                entry.id(), entry.definitionRef().definitionId(), fence.runtimeInstanceId(), fence.unitTargetRevision(),
                source.facet().executionTarget().value(), config, resource.path(), source.facet().payloadDigest(),
                source.facet().requiredCapabilities(), List.of(resource));
        }
    }
}
