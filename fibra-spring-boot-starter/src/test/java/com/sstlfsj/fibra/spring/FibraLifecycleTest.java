package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FibraLifecycleTest {
    @Test
    void startFailsWhenRequiredPluginMissing(@TempDir Path dir) throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path config = Files.writeString(dir.resolve("plugins.yaml"), "[]\n");
        Context root = FibraRuntime.create();
        FibraPluginLoader loader = new FibraPluginLoader(root, plugins);
        FibraConfigLoader configLoader =
            FibraConfigLoader.builder(root, loader, config).build();

        var props = new FibraProperties();
        props.setStartupRequiredPlugins(List.of("does-not-exist"));
        props.setShutdownTimeout(java.time.Duration.ofSeconds(5));

        var lifecycle = new FibraLifecycle(root, loader, configLoader, null, props);

        var ex = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(ex.getMessage().contains("does-not-exist"), ex.getMessage());

        lifecycle.stop();
        root.close();
    }
}
