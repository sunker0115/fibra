package com.sstlfsj.fibra.engine;

import example.fibra.engine.RollbackEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineReconcileIT {
    @Test
    void preexistingBadCandidateDoesNotFailStartupAndRecoversAfterReplacement(
        @TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");
        var candidate = Files.writeString(incoming.resolve("sample.contract.zip"),
            "not-a-plugin-package");

        try (var engine = engine(installed, incoming, config)) {
            assertDoesNotThrow(engine::start);
            assertTrue(engine.isRunning());
            await().atMost(Duration.ofSeconds(3)).until(() ->
                engine.status().state() == FibraEngineState.DEGRADED);

            var replacement = pluginPackage(work.resolve("replacement.zip"),
                "sample.contract", "1.0.0");
            Files.move(replacement, candidate, StandardCopyOption.REPLACE_EXISTING);

            await().atMost(Duration.ofSeconds(5)).until(() ->
                Files.isRegularFile(installed.resolve(
                    "sample.contract/plugin.properties"))
                    && engine.status().state() == FibraEngineState.RUNNING
                    && engine.status().failures().isEmpty());
        }
    }

    @Test
    void partialReconcilePublishesTheActualAppliedCombination(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");

        try (var engine = engine(installed, incoming, config)) {
            engine.start();
            await().atMost(Duration.ofSeconds(2)).until(() ->
                engine.status().state() == FibraEngineState.RUNNING);
            var before = engine.status().appliedRevision().orElseThrow();

            Files.writeString(config, "not-an-array");
            pluginPackage(incoming.resolve("sample.contract.zip"),
                "sample.contract", "1.0.0");
            engine.requestReconcile();

            await().atMost(Duration.ofSeconds(5)).until(() ->
                Files.isRegularFile(installed.resolve(
                    "sample.contract/plugin.properties"))
                    && engine.status().state() == FibraEngineState.DEGRADED);
            assertNotEquals(before, engine.status().appliedRevision().orElseThrow(),
                "artifact 已提交时 applied revision 必须反映新 artifact 与旧 config");
        }
    }

    @Test
    void rejectsDifferentContentForTheSameHighestArtifactVersion(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");
        pluginPackage(incoming.resolve("first.zip"), "sample.contract", "1.0.0", "first");
        var conflicting = pluginPackage(incoming.resolve("second.zip"),
            "sample.contract", "1.0.0", "second");

        try (var engine = engine(installed, incoming, config)) {
            engine.start();

            await().atMost(Duration.ofSeconds(5)).until(() ->
                engine.status().state() == FibraEngineState.DEGRADED);
            assertFalse(Files.exists(installed.resolve("sample.contract")));
            var failure = engine.status().failures().getFirst();
            assertEquals(FibraEngineFailureStage.ARTIFACT_RECONCILE, failure.stage());
            assertTrue(failure.message().contains("sample.contract"));
            assertTrue(failure.message().contains("1.0.0"));

            Files.delete(conflicting);
            await().atMost(Duration.ofSeconds(5)).until(() ->
                Files.isRegularFile(installed.resolve("sample.contract/plugin.properties"))
                    && engine.status().state() == FibraEngineState.RUNNING
                    && engine.status().failures().isEmpty());
        }
    }

    @Test
    void acceptsDuplicatePathsForTheSameArtifactContent(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var incoming = Files.createDirectory(work.resolve("incoming"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");
        var first = pluginPackage(incoming.resolve("first.zip"),
            "sample.contract", "1.0.0", "same");
        Files.copy(first, incoming.resolve("second.zip"));

        try (var engine = engine(installed, incoming, config)) {
            engine.start();

            await().atMost(Duration.ofSeconds(5)).until(() ->
                Files.isRegularFile(installed.resolve("sample.contract/plugin.properties")));
            assertEquals(FibraEngineState.RUNNING, engine.status().state());
            assertTrue(engine.status().failures().isEmpty());
        }
    }

    @Test
    void incompleteConfigRollbackBlocksEveryMutationEntryPoint(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        installedRollbackPlugin(installed);
        var config = Files.writeString(work.resolve("fibra.yaml"), """
            - id: fixture
              name: fixture
              config: rollback-fail
            """);
        var deployment = work.resolve("deployment.zip");

        try (var engine = FibraEngine.builder(installed, config)
            .configSource(Duration.ofMillis(10))
            .resyncInterval(Duration.ofMillis(25))
            .retryBackoff(Duration.ofMillis(10), Duration.ofMillis(25))
            .build()) {
            engine.start();
            var before = engine.status().appliedRevision();
            Files.writeString(config, """
                - id: fixture
                  name: fixture
                  config: fail
                """);
            engine.requestReconcile();

            await().atMost(Duration.ofSeconds(5)).until(() ->
                engine.status().failures().stream().anyMatch(failure ->
                    failure.stage() == FibraEngineFailureStage.CONFIG_RECONCILE
                        && failure.message().contains("roll back")));

            assertThrows(IllegalStateException.class, engine::requestReconcile);
            assertThrows(IllegalStateException.class,
                () -> engine.applyDeployment(deployment));

            var serialized = FibraEngine.class.getDeclaredMethod(
                "applyDeploymentSerialized", Path.class);
            serialized.setAccessible(true);
            var invocation = assertThrows(InvocationTargetException.class,
                () -> serialized.invoke(engine, deployment));
            assertTrue(invocation.getCause() instanceof IllegalStateException);
            assertEquals(before, engine.status().appliedRevision());
        }
    }

    private static FibraEngine engine(Path installed, Path incoming, Path config) {
        return FibraEngine.builder(installed, config)
            .artifactSource(incoming, Duration.ofMillis(10))
            .resyncInterval(Duration.ofMillis(25))
            .retryBackoff(Duration.ofMillis(10), Duration.ofMillis(25))
            .build();
    }

    private static Path pluginPackage(Path target, String id, String version)
        throws Exception {
        return pluginPackage(target, id, version, "");
    }

    private static Path pluginPackage(Path target, String id, String version,
                                      String marker) throws Exception {
        var jar = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(jar)) {
            if (!marker.isEmpty()) {
                write(output, "marker.txt", marker.getBytes(StandardCharsets.UTF_8));
            }
        }
        var properties = "plugin.id=" + id + '\n'
            + "plugin.version=" + version + '\n';
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            write(output, id + "/plugin.properties",
                properties.getBytes(StandardCharsets.ISO_8859_1));
            write(output, id + "/lib/" + id + '-' + version + ".jar",
                jar.toByteArray());
        }
        return target;
    }

    private static void installedRollbackPlugin(Path installed) throws Exception {
        var root = Files.createDirectory(installed.resolve("fixture"));
        Files.writeString(root.resolve("plugin.properties"), """
            plugin.id=fixture
            plugin.version=1.0.0
            """);
        var lib = Files.createDirectory(root.resolve("lib"));
        try (var output = new JarOutputStream(
            Files.newOutputStream(lib.resolve("fixture-1.0.0.jar")))) {
            write(output, "META-INF/extensions.idx",
                (RollbackEntrypoint.class.getName() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            var resource = RollbackEntrypoint.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(resource));
            try (InputStream input = RollbackEntrypoint.class.getClassLoader()
                .getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("missing class resource " + resource);
                }
                input.transferTo(output);
            }
            output.closeEntry();
        }
    }

    private static void write(ZipOutputStream output, String name, byte[] value)
        throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
