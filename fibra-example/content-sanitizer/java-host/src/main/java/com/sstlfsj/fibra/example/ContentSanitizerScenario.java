package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.bridge.ContributionSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.example.sanitizer.ContentSanitizerContribution;
import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import com.sstlfsj.fibra.example.sanitizer.SanitizeResult;
import com.sstlfsj.fibra.example.sanitizer.SanitizerDescriptor;
import com.sstlfsj.fibra.registry.InMemoryPluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ContentSanitizerScenario implements AutoCloseable {
    public static final ArtifactId ARTIFACT_ID = new ArtifactId("content-sanitizer");
    public static final String INSTANCE_ID = "default-sanitizer";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    private final FibraEngine engine;
    private final PublishedRuntime published;
    private final PluginRegistry registry;
    private boolean removed;
    private boolean closed;

    private ContentSanitizerScenario(FibraEngine engine,
                                     PluginRegistry registry) {
        this.engine = engine;
        published = engine.published();
        this.registry = registry;
    }

    public static ContentSanitizerScenario open(Path pluginDirectory,
                                                Path nodeExecutable,
                                                Path storageRoot) {
        var nodeRuntime = new NodePluginRuntimeAdapter(
            name -> ContentSanitizerContribution.KIND_NAME.equals(name)
                ? Optional.of(ContentSanitizerContribution.KIND) : Optional.empty(),
            NodeRuntimeOptions.defaults(nodeExecutable,
                storageRoot.resolve("node-sessions")));
        var engine = FibraEngine.builder(InMemoryDesiredStateRepository.empty())
            .artifactStore(new ArtifactStore(storageRoot.resolve("artifacts")))
            .stateStore(new FileEngineStateStore(storageRoot.resolve("engine")))
            .runtimeAdapter(nodeRuntime).build();
        var registry = new PluginRegistry(engine, new InMemoryPluginAuditRepository());
        try {
            engine.start().block(OPERATION_TIMEOUT);
            var install = PluginInstallRequest.builder().artifactId(ARTIFACT_ID)
                .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
                .source(pluginDirectory).build();
            var desired = DesiredInputEntry.builder(INSTANCE_ID, ARTIFACT_ID.value())
                .config(com.sstlfsj.fibra.value.LiteralValue.of(Map.of(
                    "replacement", "[REDACTED]",
                    "rules", List.of("email", "bearer-token", "api-key"))))
                .build();
            registry.deploy(new PluginDeploymentRequest(List.of(install),
                new DesiredInputGraph(List.of(desired)))).block(OPERATION_TIMEOUT);
            return new ContentSanitizerScenario(engine, registry);
        } catch (RuntimeException | Error failure) {
            engine.close();
            throw failure;
        }
    }

    public SanitizerDescriptor descriptor() {
        return contributions().entries().stream()
            .filter(entry -> entry.id().equals(ContentSanitizerContribution.id(INSTANCE_ID)))
            .map(entry -> (SanitizerDescriptor) entry.descriptor())
            .findFirst().orElseThrow(() -> new IllegalStateException(
                "content sanitizer contribution is unavailable"));
    }

    public SanitizeResult sanitize(SanitizeRequest request) {
        var view = published.current();
        return published.invoke(view.viewRevision(), identity(view, ContentSanitizerContribution.KIND,
            ContentSanitizerContribution.id(INSTANCE_ID)), ContentSanitizerContribution.KIND,
            ContentSanitizerContribution.id(INSTANCE_ID), request)
            .block(OPERATION_TIMEOUT);
    }

    private static long identity(PublishedView view, ContributionKind<?, ?, ?> kind, ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .map(entry -> entry.registrationIdentity()).findFirst().orElseThrow();
    }

    public void remove() {
        if (removed) {
            return;
        }
        registry.disable(INSTANCE_ID).block(OPERATION_TIMEOUT);
        registry.uninstall(ARTIFACT_ID).block(OPERATION_TIMEOUT);
        removed = true;
    }

    public PluginRegistry registry() {
        return registry;
    }

    public ContributionSnapshot contributions() {
        return published.current().contributions();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.close();
    }
}
