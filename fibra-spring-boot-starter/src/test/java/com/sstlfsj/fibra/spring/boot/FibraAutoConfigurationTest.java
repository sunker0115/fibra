package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FibraAutoConfigurationTest {
    @TempDir Path work;

    @Test
    void composesTheProviderBasedEngineWithJavaAndNodeOnly() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                var engine = context.getBean(FibraEngine.class);
                assertEquals(EngineState.RUNNING, engine.snapshot().state());
                assertSame(engine.published(), context.getBean(PublishedRuntime.class));
                assertNotNull(context.getBean(PluginRegistry.class));
                assertNotNull(context.getBean(JavaRuntimeProvider.class));
                assertNotNull(context.getBean(NodeRuntimeProvider.class));
            });
    }
}
