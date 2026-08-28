package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private static FibraEngine engine(Path installed, Path incoming, Path config) {
        return FibraEngine.builder(installed, config)
            .artifactSource(incoming, Duration.ofMillis(10))
            .resyncInterval(Duration.ofMillis(25))
            .retryBackoff(Duration.ofMillis(10), Duration.ofMillis(25))
            .build();
    }

    private static Path pluginPackage(Path target, String id, String version)
        throws Exception {
        var jar = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(jar)) {
            // contract-only 插件的主 JAR 可以没有 Fibra entrypoint。
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

    private static void write(ZipOutputStream output, String name, byte[] value)
        throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
