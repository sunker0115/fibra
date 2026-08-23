package com.sstlfsj.fibra.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FibraPropertiesTest {
    @Test
    void bindsAllProperties() {
        var env = new MockEnvironment()
            .withProperty("fibra.plugins-root", "/var/fibra/plugins")
            .withProperty("fibra.staging-root", "/var/fibra/staging")
            .withProperty("fibra.config-location", "/etc/fibra/plugins.yaml")
            .withProperty("fibra.startup-required-plugins", "a,b")
            .withProperty("fibra.watcher.enabled", "true")
            .withProperty("fibra.watcher.debounce", "2s")
            .withProperty("fibra.shutdown-timeout", "30s");
        var binder = new Binder(ConfigurationPropertySources.get(env));

        var props = binder.bind("fibra", FibraProperties.class).get();

        assertEquals("/var/fibra/plugins", props.getPluginsRoot().toString());
        assertEquals("/var/fibra/staging", props.getStagingRoot().toString());
        assertEquals(List.of("a", "b"), props.getStartupRequiredPlugins());
        assertTrue(props.getWatcher().isEnabled());
        assertEquals(Duration.ofSeconds(2), props.getWatcher().getDebounce());
        assertEquals(Duration.ofSeconds(30), props.getShutdownTimeout());
    }
}
