package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import com.sstlfsj.fibra.spring.FibraServiceBridge;
import com.sstlfsj.fibra.spring.FibraSpringLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FibraAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class));

    @Test
    void createsOneManagedEngineWithoutExposingItsLoaders(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");

        runner.withPropertyValues(
                "fibra.artifacts.installed-root=" + plugins,
                "fibra.config.location=" + config)
            .run(context -> {
                assertThat(context).hasSingleBean(FibraEngine.class);
                assertThat(context).hasSingleBean(Context.class);
                assertThat(context).hasSingleBean(FibraServiceBridge.class);
                assertThat(context).hasSingleBean(FibraSpringLifecycle.class);
                assertThat(context).doesNotHaveBean(FibraPluginLoader.class);
                assertThat(context).doesNotHaveBean(FibraConfigLoader.class);
            });
    }

    @Test
    void backsOffTheWholeManagedUnitWhenAContextAlreadyExists() {
        runner.withUserConfiguration(HostContextConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(Context.class);
            assertThat(context).doesNotHaveBean(FibraEngine.class);
            assertThat(context).doesNotHaveBean(FibraServiceBridge.class);
            assertThat(context).doesNotHaveBean(FibraSpringLifecycle.class);
        });
    }

    @Test
    void backsOffTheWholeManagedUnitWhenAnEngineAlreadyExists(@TempDir Path work)
        throws Exception {
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");
        var engine = FibraEngine.builder(plugins, config).build();

        runner.withBean(FibraEngine.class, () -> engine).run(context -> {
            assertThat(context).hasSingleBean(FibraEngine.class);
            assertThat(context).doesNotHaveBean(Context.class);
            assertThat(context).doesNotHaveBean(FibraServiceBridge.class);
            assertThat(context).doesNotHaveBean(FibraSpringLifecycle.class);
        });
    }

    @Test
    void reportsTheExactMissingInstalledRootProperty() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("fibra.artifacts.installed-root");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class HostContextConfiguration {
        @Bean(destroyMethod = "close")
        Context hostFibraContext() {
            return FibraRuntime.create();
        }
    }
}
