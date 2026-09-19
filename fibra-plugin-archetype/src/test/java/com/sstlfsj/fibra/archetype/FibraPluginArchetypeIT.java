package com.sstlfsj.fibra.archetype;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.PluginPackage;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.ExecutionObservation;
import com.sstlfsj.fibra.engine.ExecutionUnitKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginSelection;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraPluginArchetypeIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void generatedPackageLoadsThroughTheJavaRuntime(@TempDir Path work)
        throws Exception {
        var generated = Path.of("target/test-classes/projects/basic/project",
            "sample-fibra-plugin");
        var jar = generated.resolve("target/sample-fibra-plugin-1.0.0.jar");
        assertTrue(Files.isRegularFile(jar),
            () -> "generated plugin JAR is missing: " + jar);
        var root = generated.resolve("target/sample-fibra-plugin-1.0.0-plugin");
        assertTrue(Files.isDirectory(root),
            () -> "generated package is missing: " + root);
        var pluginPackage = PluginPackage.read(root);
        assertEquals("sample-fibra-plugin", pluginPackage.pluginId().value());
        assertEquals("1.0.0", pluginPackage.version());
        assertEquals(root.resolve("lib/plugin.jar").toRealPath(),
            pluginPackage.facets().getFirst().payload());
        assertEquals(-1, Files.mismatch(jar,
            pluginPackage.facets().getFirst().payload()));

        var packages = new PluginPackageStore(work.resolve("packages"));
        final PluginPackageRecord installed;
        try (var transaction = packages.prepareInstall(root)) {
            installed = transaction.save();
        }
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder(
                "generated-plugin", new PluginDefinitionRef(
                    "sample-fibra-plugin", "main", "sample-fibra-plugin"))
            .build()));
        try (var engine = FibraEngine.builder(packages,
                DeploymentTargetStore.inMemory())
            .runtimeProvider(new JavaRuntimeProvider(List.of()))
            .hostTerminationPort(ignored -> { }).build()) {
            engine.startAsync().block(TIMEOUT);
            var deployed = engine.submit(ApplyDeployment.builder(graph)
                .expectedRevision(0)
                .selections(List.of(new PluginSelection(installed.pluginId(),
                    installed.packageRevision(), true)))
                .configContext(ConfigContextSnapshot.empty()).build())
                .block(TIMEOUT).view();

            var observation = deployed.engine().units().get(
                new ExecutionUnitKey("generated-plugin"));
            assertEquals(ExecutionObservation.State.ACTIVE,
                observation.aggregateState());
            assertTrue(deployed.diagnostics().plugins().stream().anyMatch(plugin ->
                plugin.pluginId().equals("sample-fibra-plugin")
                    && plugin.state() == PluginInstanceState.ACTIVE));
        }
    }
}
