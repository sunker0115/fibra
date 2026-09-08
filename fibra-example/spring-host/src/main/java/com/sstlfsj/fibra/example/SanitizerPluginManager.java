package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.bridge.ContributionBridge;
import com.sstlfsj.fibra.config.DesiredEntry;
import com.sstlfsj.fibra.config.DesiredGraph;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.example.sanitizer.ContentSanitizerContribution;
import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import com.sstlfsj.fibra.example.sanitizer.SanitizeResult;
import com.sstlfsj.fibra.registry.PluginDeploymentRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
final class SanitizerPluginManager implements ApplicationRunner {
    static final String INSTANCE_ID = "default-sanitizer";
    private static final ArtifactId ARTIFACT_ID = new ArtifactId("content-sanitizer");
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);

    private final SanitizerExampleProperties properties;
    private final PluginRegistry registry;
    private final FibraEngine engine;
    private final ContributionBridge bridge;

    SanitizerPluginManager(SanitizerExampleProperties properties,
                           PluginRegistry registry,
                           FibraEngine engine,
                           ContributionBridge bridge) {
        this.properties = properties;
        this.registry = registry;
        this.engine = engine;
        this.bridge = bridge;
    }

    @Override
    public void run(ApplicationArguments args) {
        var install = PluginInstallRequest.builder().artifactId(ARTIFACT_ID)
            .runtimeId(NodePluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .source(properties.pluginDirectory().toAbsolutePath().normalize()).build();
        var desired = DesiredEntry.builder(INSTANCE_ID, ARTIFACT_ID.value())
            .config(Map.of(
                "replacement", "[REDACTED]",
                "rules", List.of("email", "bearer-token", "api-key")))
            .build();
        registry.deploy(new PluginDeploymentRequest(List.of(install),
            new DesiredGraph(List.of(desired)))).block(OPERATION_TIMEOUT);
    }

    SanitizeResult sanitize(SanitizeRequest request) {
        return bridge.invoke(engine.runtime().rootScope().context(),
            ContentSanitizerContribution.KIND,
            ContentSanitizerContribution.id(INSTANCE_ID), request)
            .block(OPERATION_TIMEOUT);
    }

    PluginRegistry registry() {
        return registry;
    }
}
