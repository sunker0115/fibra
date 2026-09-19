package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfilePackageSourceTest {
    @TempDir Path work;

    @Test
    void returnsOnlyDistinctPathsInsideTheProfilePackageRoot() throws Exception {
        var root = Files.createDirectories(work.resolve("packages"));
        Files.createDirectories(root.resolve("first"));
        Files.createDirectories(root.resolve("second"));
        var manifest = Files.writeString(work.resolve("packages.yaml"), "- first\n- second\n");

        assertEquals(2, new ProfilePackageSource(manifest, root).load().size());
    }

    @Test
    void rejectsPathOutsideTheProfilePackageRoot() throws Exception {
        var root = Files.createDirectories(work.resolve("packages"));
        var manifest = Files.writeString(work.resolve("packages.yaml"), "- ../outside\n");

        assertThrows(IllegalArgumentException.class,
            () -> new ProfilePackageSource(manifest, root).load());
    }
}
