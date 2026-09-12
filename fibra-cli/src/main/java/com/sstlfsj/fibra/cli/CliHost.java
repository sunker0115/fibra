package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.ConfigLimits;
import com.sstlfsj.fibra.config.FileDesiredStateRepository;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginArtifactProbe;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.registry.FilePluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** CLI profile namespace内唯一的 Engine、Registry 与持久资源所有者。 */
final class CliHost implements AutoCloseable {
    private final CliPaths paths;
    private final PluginArtifactProbe probe;
    private final FibraEngine engine;
    private final PluginRegistry registry;
    private final FilePluginAuditRepository audit;
    private boolean closed;
    private Throwable closeFailure;

    private CliHost(CliPaths paths, PluginArtifactProbe probe, FibraEngine engine,
                    PluginRegistry registry, FilePluginAuditRepository audit) {
        this.paths = paths;
        this.probe = probe;
        this.engine = engine;
        this.registry = registry;
        this.audit = audit;
    }

    static CliHost open(CliPaths paths) {
        var config = new FileDesiredStateRepository(paths.profileFile(), ConfigLimits.defaults());
        FileEngineStateStore state = null;
        ArtifactStore artifacts = null;
        FilePluginAuditRepository audit = null;
        FibraEngine engine = null;
        try {
            Files.createDirectories(paths.workspaceRoot());
            Files.createDirectories(paths.storageRoot());
            state = new FileEngineStateStore(paths.stateRoot());
            artifacts = new ArtifactStore(paths.artifactRoot());
            audit = new FilePluginAuditRepository(paths.auditFile());
            var java = new JavaPluginRuntimeAdapter();
            var node = new NodePluginRuntimeAdapter(name -> "fibra.tool".equals(name)
                ? Optional.of(ToolContributions.KIND) : Optional.empty(), NodeRuntimeOptions.defaults(
                    paths.nodeExecutable(), paths.nodeSessionRoot()));
            var probe = new PluginArtifactProbe(List.of(java, node));
            var source = new ProfileArtifactSource(paths.profileArtifactsFile(), paths.pluginsRoot(), probe);
            engine = FibraEngine.builder(config).stateStore(state).artifactStore(artifacts)
                .initialArtifacts(source).configContext(ConfigContextSnapshot.of(paths.configContext()))
                .runtimeAdapter(java).runtimeAdapter(node).build();
            var registry = new PluginRegistry(engine, audit);
            engine.start().block();
            return new CliHost(paths, probe, engine, registry, audit);
        } catch (Throwable failure) {
            closeAfterFailedStart(failure, engine, artifacts, state, audit);
            throwUnchecked(failure);
            throw new AssertionError("unreachable");
        }
    }

    PluginRegistry registry() {
        return registry;
    }

    PublishedRuntime published() {
        return engine.published();
    }

    PluginArtifactProbe probe() {
        return probe;
    }

    RegistrySnapshot apply() {
        var graph = new FileDesiredStateRepository(paths.profileFile(), ConfigLimits.defaults())
            .load().graph();
        var source = new ProfileArtifactSource(paths.profileArtifactsFile(), paths.pluginsRoot(), probe);
        var artifacts = source.load().stream().map(CliHost::installRequest).toList();
        return registry.deploy(new PluginDeploymentRequest(artifacts, graph)).block();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            if (closeFailure != null) throwUnchecked(closeFailure);
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            engine.close();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            audit.close();
        } catch (Throwable error) {
            failure = append(failure, error);
        }
        if (failure != null) {
            closeFailure = failure;
            throwUnchecked(failure);
        }
    }

    private static PluginInstallRequest installRequest(DeploymentArtifact artifact) {
        return PluginInstallRequest.builder().artifactId(artifact.artifactId())
            .runtimeId(artifact.runtimeId()).version(artifact.version()).source(artifact.source()).build();
    }

    private static void closeAfterFailedStart(Throwable failure, FibraEngine engine,
                                                ArtifactStore artifacts, FileEngineStateStore state,
                                                FilePluginAuditRepository audit) {
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        } else {
            close(failure, artifacts);
            close(failure, state);
        }
        close(failure, audit);
    }

    private static void close(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) return next;
        if (failure != next) failure.addSuppressed(next);
        return failure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("CLI host operation failed", failure);
    }
}
