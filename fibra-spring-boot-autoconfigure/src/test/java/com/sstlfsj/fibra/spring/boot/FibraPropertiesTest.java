package com.sstlfsj.fibra.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FibraPropertiesTest {
    @Test
    void publishesCompleteMetadataWithDefaultsAndWithoutOldProperties() throws Exception {
        var resource = FibraProperties.class.getClassLoader().getResource(
            "META-INF/spring-configuration-metadata.json");
        assertNotNull(resource);
        final byte[] metadata;
        try (var input = resource.openStream()) {
            metadata = input.readAllBytes();
        }
        var content = new String(metadata, StandardCharsets.UTF_8);
        var propertiesSection = content.substring(content.indexOf("\"properties\""),
            content.indexOf("\"hints\""));
        var names = new LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"(fibra\\.[^\\\"]+)\\\"")
            .matcher(propertiesSection);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        assertEquals(new LinkedHashSet<>(List.of(
            "fibra.artifacts.incoming-root",
            "fibra.artifacts.installed-root",
            "fibra.artifacts.watch.debounce",
            "fibra.artifacts.watch.enabled",
            "fibra.config.location",
            "fibra.config.watch.debounce",
            "fibra.config.watch.enabled",
            "fibra.engine.resync-interval",
            "fibra.engine.retry-initial-backoff",
            "fibra.engine.retry-max-backoff",
            "fibra.shutdown.root-close-timeout",
            "fibra.startup.readiness-timeout",
            "fibra.startup.required-entries"
        )), names);
        assertTrue(content.contains("\"defaultValue\" : \"30s\"")
            || content.contains("\"defaultValue\": \"30s\""));
        assertTrue(content.contains("全部 required entry 共享的总就绪时间预算"));
        assertFalse(content.contains("fibra.plugins-root"));
        assertFalse(content.contains("fibra.staging-root"));
        assertFalse(content.contains("fibra.startup-required-plugins"));
    }

    @Test
    void bindsTheFrozenNestedPropertyGraph() {
        var environment = new MockEnvironment()
            .withProperty("fibra.engine.resync-interval", "45s")
            .withProperty("fibra.engine.retry-initial-backoff", "500ms")
            .withProperty("fibra.engine.retry-max-backoff", "20s")
            .withProperty("fibra.artifacts.installed-root", "/run/fibra/plugins")
            .withProperty("fibra.artifacts.incoming-root", "/run/fibra/incoming")
            .withProperty("fibra.artifacts.watch.enabled", "true")
            .withProperty("fibra.artifacts.watch.debounce", "2s")
            .withProperty("fibra.config.location", "/etc/fibra/fibra.yaml")
            .withProperty("fibra.config.watch.enabled", "true")
            .withProperty("fibra.config.watch.debounce", "3s")
            .withProperty("fibra.startup.required-entries", "agent,tools")
            .withProperty("fibra.startup.readiness-timeout", "90s")
            .withProperty("fibra.shutdown.root-close-timeout", "15s");

        var properties = new Binder(ConfigurationPropertySources.get(environment))
            .bind("fibra", FibraProperties.class).get();

        assertEquals(Duration.ofSeconds(45), properties.engine().resyncInterval());
        assertEquals(Duration.ofMillis(500), properties.engine().retryInitialBackoff());
        assertEquals(Duration.ofSeconds(20), properties.engine().retryMaxBackoff());
        assertEquals("/run/fibra/plugins", properties.artifacts().installedRoot().toString());
        assertEquals("/run/fibra/incoming", properties.artifacts().incomingRoot().toString());
        assertTrue(properties.artifacts().watch().enabled());
        assertEquals(Duration.ofSeconds(2), properties.artifacts().watch().debounce());
        assertEquals("/etc/fibra/fibra.yaml", properties.config().location().toString());
        assertTrue(properties.config().watch().enabled());
        assertEquals(Duration.ofSeconds(3), properties.config().watch().debounce());
        assertEquals(List.of("agent", "tools"), properties.startup().requiredEntries());
        assertEquals(Duration.ofSeconds(90), properties.startup().readinessTimeout());
        assertEquals(Duration.ofSeconds(15), properties.shutdown().rootCloseTimeout());
    }

    @Test
    void appliesDefaultsToOmittedNestedValues() {
        var environment = new MockEnvironment()
            .withProperty("fibra.artifacts.installed-root", "/run/fibra/plugins")
            .withProperty("fibra.config.location", "/etc/fibra/fibra.yaml");

        var properties = new Binder(ConfigurationPropertySources.get(environment))
            .bind("fibra", FibraProperties.class).get();

        assertEquals(Duration.ofSeconds(30), properties.engine().resyncInterval());
        assertEquals(Duration.ofMillis(250), properties.engine().retryInitialBackoff());
        assertEquals(Duration.ofSeconds(30), properties.engine().retryMaxBackoff());
        assertFalse(properties.artifacts().watch().enabled());
        assertEquals(Duration.ofSeconds(1), properties.artifacts().watch().debounce());
        assertFalse(properties.config().watch().enabled());
        assertEquals(Duration.ofSeconds(1), properties.config().watch().debounce());
        assertEquals(List.of(), properties.startup().requiredEntries());
        assertEquals(Duration.ofSeconds(60), properties.startup().readinessTimeout());
        assertEquals(Duration.ofSeconds(30), properties.shutdown().rootCloseTimeout());
    }
}
