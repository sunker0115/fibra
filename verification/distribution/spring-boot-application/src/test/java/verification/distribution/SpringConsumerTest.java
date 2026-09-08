package verification.distribution;

import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.spring.boot.FibraAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpringConsumerTest {
    @Test
    void starterComposesThePublishedRuntime(@TempDir Path work) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                assertNotNull(context.getBean(PluginRegistry.class));
                assertEquals(EngineState.RUNNING,
                    context.getBean(FibraEngine.class).snapshot().state());
            });
    }
}
