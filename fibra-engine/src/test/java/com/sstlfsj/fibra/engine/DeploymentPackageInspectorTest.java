package com.sstlfsj.fibra.engine;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentPackageInspectorTest {
    @Test
    void acceptsNormalizedDirectoryEntries(@TempDir Path work) throws Exception {
        var deployment = work.resolve("deployment.zip");
        var descriptor = """
            deployment.id=sample
            deployment.version=1.0.0
            config.path=config/fibra.yaml
            """.getBytes(StandardCharsets.UTF_8);
        var config = "[]\n".getBytes(StandardCharsets.UTF_8);
        var checksums = (sha256(descriptor) + "  deployment.properties\n"
            + sha256(config) + "  config/fibra.yaml\n")
            .getBytes(StandardCharsets.UTF_8);
        try (var output = new ZipArchiveOutputStream(deployment)) {
            addDirectory(output, "config/");
            addFile(output, "deployment.properties", descriptor);
            addFile(output, "config/fibra.yaml", config);
            addFile(output, "checksums.sha256", checksums);
        }

        var inspected = new DeploymentPackageInspector().inspect(deployment,
            work.resolve("workspace"));

        assertEquals("sample", inspected.id());
    }

    @Test
    void rejectsSymbolicLinksBeforeExtractingThePackage(@TempDir Path work)
        throws Exception {
        var deployment = work.resolve("deployment.zip");
        try (var output = new ZipArchiveOutputStream(deployment)) {
            var link = new ZipArchiveEntry("config/link.yaml");
            link.setUnixMode(UnixStat.LINK_FLAG | 0777);
            output.putArchiveEntry(link);
            output.write("target.yaml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }

        var failure = assertThrows(FibraDeploymentException.class,
            () -> new DeploymentPackageInspector().inspect(deployment,
                work.resolve("workspace")));

        assertEquals(FibraDeploymentErrorStage.VALIDATE, failure.stage());
        assertFalse(Files.exists(work.resolve("workspace/config/link.yaml")));
    }

    private static void addDirectory(ZipArchiveOutputStream output, String name)
        throws Exception {
        var entry = new ZipArchiveEntry(name);
        entry.setUnixMode(UnixStat.DIR_FLAG | 0755);
        output.putArchiveEntry(entry);
        output.closeArchiveEntry();
    }

    private static void addFile(ZipArchiveOutputStream output, String name, byte[] value)
        throws Exception {
        var entry = new ZipArchiveEntry(name);
        entry.setUnixMode(UnixStat.FILE_FLAG | 0644);
        output.putArchiveEntry(entry);
        output.write(value);
        output.closeArchiveEntry();
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value));
    }
}
