package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.engine.ApplyDeployment;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.deployment.*;
import com.sstlfsj.fibra.plugins.acceptance.AuthorConfigurationQualification;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SanitizerAuthorConfigurationIT {
    private static final List<AuthorConfigurationQualification> FAILED_AUTHOR_OWNERS = new java.util.ArrayList<>();
    @Test
    void copiedInactivePackagePassesWholePackageQualificationAndValueBoundaries(@TempDir Path work)
        throws Exception {
        var source = Path.of(System.getProperty("fibra.example.plugin"));
        var copy = work.resolve("copied-package");
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = copy.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        var packages = new PluginPackageStore(work.resolve("store"));
        var targets = DeploymentTargetStore.inMemory();
        var engine = FibraEngine.builder(packages, targets).runtimeProvider(new NodeRuntimeProvider(
                NodeRuntimeOptions.defaults(Path.of(System.getProperty("fibra.test.node", "node")), work.resolve("sessions"))))
            .hostTerminationPort(ignored -> { }).build();
        try (var qa = new AuthorConfigurationQualification(engine, packages, targets,
                owner -> {
                    FAILED_AUTHOR_OWNERS.add(owner);
                    throw new AssertionError("QA cleanup requires host termination");
                })) {
            engine.startAsync().block(Duration.ofSeconds(10));
            com.sstlfsj.fibra.artifact.PluginPackageRecord installed;
            try (var transaction = packages.prepareInstall(copy)) { installed = transaction.save(); }
            var selection = new PluginSelection(installed.pluginId(), installed.packageRevision(), false);
            var applied = engine.submit(ApplyDeployment.builder(new DesiredInputGraph(List.of()))
                .expectedRevision(0).selections(List.of(selection)).configContext(ConfigContextSnapshot.empty())
                .build()).block(Duration.ofSeconds(10)).view();
            var document = qa.qualify(selection, applied.engine().target().orElseThrow().targetRevision());
            var schema = ConfigSchema.compile(document.facets().get("main").get("content-sanitizer"));
            var nullable = new java.util.LinkedHashMap<String, Object>();
            nullable.put("replacement", null); nullable.put("rules", null);
            for (var value : List.of(Map.of(), nullable, Map.of("replacement", ""), Map.of("rules", List.of()),
                Map.of("rules", List.of("email", "email", "api-key", "bearer-token")))) {
                assertTrue(schema.validate(LiteralValue.of(value)).isEmpty());
            }
            for (var value : List.of(Map.of("rules", List.of("unknown")), Map.of("rules", "email"),
                Map.of("replacement", 1), Map.of("unknown", true), List.of())) {
                assertTrue(schema.validate(LiteralValue.of(value)).isPresent());
            }
            assertTrue(schema.validate(LiteralValue.of(null)).isPresent());
            assertFalse(Files.exists(work.resolve("sessions")), "static qualification must not spawn Node");
        }
    }
}
