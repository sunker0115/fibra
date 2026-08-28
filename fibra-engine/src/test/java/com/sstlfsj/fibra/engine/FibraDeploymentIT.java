package com.sstlfsj.fibra.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraDeploymentIT {
    @Test
    void appliesCheckedConfigAsOneDeploymentAndRejectsTampering(@TempDir Path work)
        throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("host/fibra.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[]");
        var deployment = deployment(work.resolve("deployment.zip"), "[]\n");

        try (var engine = FibraEngine.builder(installed, config).build()) {
            engine.start();
            var before = engine.status().appliedRevision().orElseThrow();
            var result = engine.applyDeployment(deployment);
            assertEquals("sample", result.deploymentId());
            assertEquals("1.0.0", result.deploymentVersion());
            assertEquals(result.appliedRevision(),
                engine.status().appliedRevision().orElseThrow());
            assertEquals(before, result.appliedRevision(),
                "只改变配置排版且运行语义不变时 applied revision 应保持稳定");
        }

        var tampered = deployment(work.resolve("tampered.zip"), "[ ]\n", "[]\n");
        try (var engine = FibraEngine.builder(installed, config).build()) {
            engine.start();
            assertThrows(FibraDeploymentException.class,
                () -> engine.applyDeployment(tampered));
            assertEquals(FibraEngineState.RUNNING, engine.status().state());
            assertEquals(java.util.List.of(), engine.status().failures(),
                "预检失败且运行态未变化时不应把 engine 标成 DEGRADED");
        }
    }

    @Test
    void committedDeploymentReturnsSuccessWhenPostCommitReceiptCleanupFails(
        @TempDir Path work) throws Exception {
        var installed = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]");
        var deployment = deployment(work.resolve("deployment.zip"), "[]");
        var revisions = installed.resolve(".fibra-engine/revisions");

        try (var engine = FibraEngine.builder(installed, config).build()) {
            engine.start();
            Files.writeString(revisions, "block receipt directory creation");

            var result = engine.applyDeployment(deployment);

            assertEquals(result.appliedRevision(),
                engine.status().appliedRevision().orElseThrow());
            assertEquals(FibraEngineState.RUNNING, engine.status().state());
            var transactions = installed.resolve(".fibra-engine/transactions");
            try (var paths = Files.list(transactions)) {
                var committed = false;
                for (var transaction : paths.toList()) {
                    committed |= EngineTransactionJournal.read(transaction).state()
                        == EngineTransactionState.COMMITTED;
                }
                assertTrue(committed);
            }
        }

        Files.delete(revisions);
        try (var recovered = FibraEngine.builder(installed, config).build()) {
            assertFalse(Files.exists(installed.resolve(".fibra-engine/transactions")));
        }
    }

    private static Path deployment(Path target, String config) throws Exception {
        return deployment(target, config, config);
    }

    private static Path deployment(Path target, String config, String checksummedConfig)
        throws Exception {
        var properties = """
            deployment.id=sample
            deployment.version=1.0.0
            config.path=config/fibra.yaml
            """.getBytes(StandardCharsets.UTF_8);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("deployment.properties", properties);
        entries.put("config/fibra.yaml", config.getBytes(StandardCharsets.UTF_8));
        var checksums = new StringBuilder();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var bytes = entry.getKey().equals("config/fibra.yaml")
                ? checksummedConfig.getBytes(StandardCharsets.UTF_8) : entry.getValue();
            checksums.append(sha(bytes)).append("  ").append(entry.getKey()).append('\n');
        });
        entries.put("checksums.sha256", checksums.toString()
            .getBytes(StandardCharsets.UTF_8));
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return target;
    }

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
