package com.sstlfsj.fibra.loader.pf4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraDirectoryPluginManagerTest {
    @TempDir
    Path temp;

    @Test
    void scansOnlyVisibleDirectPackageDirectories() throws Exception {
        PluginPackageFixtures.standardDirectory(temp, "alpha", "1.0.0");
        PluginPackageFixtures.standardDirectory(temp, "beta", "1.0.0");
        var preflight = Files.createDirectory(temp.resolve(".fibra-preflight"));
        PluginPackageFixtures.standardDirectory(preflight, "ignored-preflight", "1.0.0");
        var transactions = Files.createDirectory(temp.resolve(".fibra-transactions"));
        PluginPackageFixtures.standardDirectory(transactions, "ignored-transaction", "1.0.0");
        PluginPackageFixtures.writeJar(temp.resolve("legacy.jar"), java.util.Map.of());
        var manager = new FibraDirectoryPluginManager(temp);

        var loaded = manager.loadPluginsStrict();

        assertEquals(List.of("alpha", "beta"), loaded);
        manager.unloadPluginsStrict(List.of("beta", "alpha"));
    }

    @Test
    void addsOnlyDirectLibJarsInStableFileNameOrder() throws Exception {
        var plugin = PluginPackageFixtures.standardDirectory(temp, "alpha", "1.0.0");
        var lib = plugin.resolve("lib");
        Files.createDirectory(plugin.resolve("classes"));
        PluginPackageFixtures.writeJar(lib.resolve("z-private.jar"), java.util.Map.of());
        PluginPackageFixtures.writeJar(lib.resolve("a-private.jar"), java.util.Map.of());
        var manager = new FibraDirectoryPluginManager(temp);

        manager.loadPluginsStrict(List.of(plugin));

        var classLoader = (FibraPluginClassLoader) manager.getPluginClassLoader("alpha");
        var names = java.util.Arrays.stream(classLoader.getURLs())
            .map(url -> Path.of(url.getPath()).getFileName().toString())
            .toList();
        assertEquals(List.of("a-private.jar", "alpha-1.0.0.jar", "z-private.jar"), names);
        manager.unloadPluginsStrict(List.of("alpha"));
        assertTrue(classLoader.isClosed());
    }
}
