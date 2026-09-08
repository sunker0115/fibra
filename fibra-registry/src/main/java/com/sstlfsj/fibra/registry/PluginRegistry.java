package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.engine.EngineCommand;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.EngineCommandResult;
import com.sstlfsj.fibra.engine.EngineSnapshot;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.InstallArtifact;
import com.sstlfsj.fibra.engine.ReplaceDesiredGraph;
import com.sstlfsj.fibra.engine.UninstallArtifact;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class PluginRegistry {
    private final FibraEngine engine;
    private final PluginAuditRepository audit;

    public PluginRegistry(FibraEngine engine, PluginAuditRepository audit) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Mono<RegistrySnapshot> install(PluginInstallRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("install", request.artifactId().value(), snapshot -> {
            if (snapshot.artifacts().containsKey(request.artifactId())) {
                throw new IllegalArgumentException("artifact is already installed: "
                    + request.artifactId().value());
            }
            return installCommand(snapshot, request);
        });
    }

    public Mono<RegistrySnapshot> upgrade(PluginInstallRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("upgrade", request.artifactId().value(), snapshot -> {
            var current = snapshot.artifacts().get(request.artifactId());
            if (current == null) {
                throw new IllegalArgumentException("artifact is not installed: "
                    + request.artifactId().value());
            }
            if (!current.runtimeId().equals(request.runtimeId())) {
                throw new IllegalArgumentException("upgrade cannot change runtime id");
            }
            return installCommand(snapshot, request);
        });
    }

    public Mono<RegistrySnapshot> deploy(PluginDeploymentRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("deploy", "deployment", snapshot -> ApplyDeployment.builder(
                request.graph())
            .expectedRevision(snapshot.revision())
            .expectedDesiredRevision(snapshot.desiredSource().revision())
            .artifacts(request.artifacts().stream().map(artifact ->
                DeploymentArtifact.builder().artifactId(artifact.artifactId())
                    .runtimeId(artifact.runtimeId()).version(artifact.version())
                    .source(artifact.source()).build()).toList())
            .build());
    }

    public Mono<RegistrySnapshot> enable(PluginEnableRequest request) {
        Objects.requireNonNull(request, "request");
        return mutate("enable", request.instanceId(), snapshot -> {
            var entry = DesiredEntry.builder(request.instanceId(), request.definitionName())
                .config(request.config()).realms(request.realms())
                .intercepts(request.intercepts())
                .source(Path.of("registry", request.instanceId())).build();
            return replace(snapshot, snapshot.desiredGraph().upsert(entry));
        });
    }

    public Mono<RegistrySnapshot> disable(String instanceId) {
        requireName(instanceId, "instanceId");
        return mutate("disable", instanceId, snapshot -> {
            var current = snapshot.desiredGraph().require(instanceId);
            var disabled = current.toBuilder().enabled(false).build();
            return replace(snapshot, snapshot.desiredGraph().upsert(disabled));
        });
    }

    public Mono<RegistrySnapshot> uninstall(ArtifactId artifactId) {
        Objects.requireNonNull(artifactId, "artifactId");
        return mutate("uninstall", artifactId.value(), snapshot ->
            new UninstallArtifact(snapshot.revision(), artifactId));
    }

    public RegistrySnapshot snapshot() {
        return project(engine.snapshot());
    }

    public Optional<RegistryPluginState> get(String instanceId) {
        requireName(instanceId, "instanceId");
        var snapshot = snapshot();
        var desired = snapshot.desired().get(instanceId);
        var observed = snapshot.observed().get(instanceId);
        return desired == null && observed == null ? Optional.empty()
            : Optional.of(new RegistryPluginState(instanceId, desired, observed));
    }

    public List<RegistryPluginState> list() {
        var snapshot = snapshot();
        var ids = new java.util.TreeSet<String>();
        ids.addAll(snapshot.desired().keySet());
        ids.addAll(snapshot.observed().keySet());
        return ids.stream().map(id -> new RegistryPluginState(id,
            snapshot.desired().get(id), snapshot.observed().get(id))).toList();
    }

    public Flux<RegistrySnapshot> watch() {
        return engine.snapshots().map(PluginRegistry::project);
    }

    public List<PluginAuditEntry> history() {
        return audit.history();
    }

    private Mono<RegistrySnapshot> mutate(String operation, String target,
                                          Function<EngineSnapshot, EngineCommand> command) {
        return Mono.defer(() -> {
            final EngineCommand prepared;
            try {
                prepared = command.apply(engine.snapshot());
            } catch (RuntimeException failure) {
                append(operation, target, false, engine.snapshot().revision(), failure);
                return Mono.error(failure);
            }
            return engine.submit(prepared)
                .doOnSuccess(result -> append(operation, target, true,
                    result.snapshot().revision(), null))
                .doOnError(failure -> append(operation, target, false,
                    engine.snapshot().revision(), failure))
                .map(EngineCommandResult::snapshot)
                .map(PluginRegistry::project);
        });
    }

    private static ReplaceDesiredGraph replace(EngineSnapshot snapshot,
                                               DesiredGraph graph) {
        return new ReplaceDesiredGraph(snapshot.revision(),
            snapshot.desiredSource().revision(), graph);
    }

    private static InstallArtifact installCommand(EngineSnapshot snapshot,
                                                  PluginInstallRequest request) {
        return InstallArtifact.builder().expectedRevision(snapshot.revision())
            .artifactId(request.artifactId()).runtimeId(request.runtimeId())
            .version(request.version()).source(request.source()).build();
    }

    private void append(String operation, String target, boolean succeeded,
                        String revision, Throwable failure) {
        audit.append(operation, target, succeeded, revision,
            failure == null ? "accepted" : failure.toString());
    }

    private static RegistrySnapshot project(EngineSnapshot snapshot) {
        var desired = new LinkedHashMap<String, DesiredEntry>();
        snapshot.desiredGraph().entries().forEach(entry ->
            desired.put(entry.instanceId(), entry));
        return new RegistrySnapshot(snapshot.revision(), snapshot.artifacts(), desired,
            snapshot.instances());
    }

    private static void requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
