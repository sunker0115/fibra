package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FibraAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class));

    @Test
    void registersCoreBeansWhenPluginsRootSet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("plugins"));
        java.nio.file.Files.writeString(dir.resolve("plugins.yaml"), "[]\n");
        runner.withPropertyValues(
                "fibra.plugins-root=" + dir.resolve("plugins"),
                "fibra.config-location=" + dir.resolve("plugins.yaml"))
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(Context.class);
                assertThat(ctx).hasSingleBean(FibraPluginLoader.class);
                assertThat(ctx).hasSingleBean(FibraServiceBridge.class);
                assertThat(ctx).hasSingleBean(FibraLifecycle.class);
            });
    }

    @Test
    void backsOffWhenHostProvidesContext(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("plugins"));
        java.nio.file.Files.writeString(dir.resolve("plugins.yaml"), "[]\n");
        runner.withPropertyValues(
                "fibra.plugins-root=" + dir.resolve("plugins"),
                "fibra.config-location=" + dir.resolve("plugins.yaml"))
            .withUserConfiguration(CustomContextConfig.class)
            .run(ctx -> assertThat(ctx.getBean(Context.class))
                .isSameAs(CustomContextConfig.CUSTOM));
    }

    @org.springframework.context.annotation.Configuration
    static class CustomContextConfig {
        static final Context CUSTOM = com.sstlfsj.fibra.runtime.FibraRuntime.create();
        @org.springframework.context.annotation.Bean
        Context fibraRootContext() { return CUSTOM; }
    }
}
