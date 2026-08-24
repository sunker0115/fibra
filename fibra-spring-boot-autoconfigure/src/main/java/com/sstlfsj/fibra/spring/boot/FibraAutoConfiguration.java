package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.spring.FibraServiceBridge;
import com.sstlfsj.fibra.spring.FibraSpringLifecycle;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.LinkedHashSet;

@AutoConfiguration
@EnableConfigurationProperties(FibraProperties.class)
@ConditionalOnMissingBean({FibraEngine.class, Context.class})
public class FibraAutoConfiguration {
    @Bean(destroyMethod = "")
    FibraEngine fibraEngine(FibraProperties properties) {
        validate(properties);
        var engine = properties.engine();
        var artifacts = properties.artifacts();
        var config = properties.config();
        var builder = FibraEngine.builder(artifacts.installedRoot(), config.location())
            .resyncInterval(engine.resyncInterval())
            .retryBackoff(engine.retryInitialBackoff(), engine.retryMaxBackoff())
            .requiredEntries(properties.startup().requiredEntries())
            .readinessTimeout(properties.startup().readinessTimeout())
            .rootCloseTimeout(properties.shutdown().rootCloseTimeout());
        if (artifacts.watch().enabled()) {
            builder.artifactSource(artifacts.incomingRoot(), artifacts.watch().debounce());
        }
        if (config.watch().enabled()) {
            builder.configSource(config.watch().debounce());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "")
    Context fibraRoot(FibraEngine engine) {
        return engine.root();
    }

    @Bean
    FibraServiceBridge fibraServiceBridge(Context fibraRoot) {
        return new FibraServiceBridge(fibraRoot);
    }

    @Bean
    FibraSpringLifecycle fibraSpringLifecycle(FibraEngine engine) {
        return new FibraSpringLifecycle(engine);
    }

    private static void validate(FibraProperties properties) {
        var engine = properties.engine();
        var artifacts = properties.artifacts();
        var config = properties.config();
        existingDirectory(artifacts.installedRoot(), "fibra.artifacts.installed-root");
        existingFile(config.location(), "fibra.config.location");
        if (artifacts.watch().enabled()) {
            existingDirectory(artifacts.incomingRoot(), "fibra.artifacts.incoming-root");
        }
        positive(engine.resyncInterval(), "fibra.engine.resync-interval");
        positive(engine.retryInitialBackoff(), "fibra.engine.retry-initial-backoff");
        positive(engine.retryMaxBackoff(), "fibra.engine.retry-max-backoff");
        if (engine.retryMaxBackoff().compareTo(engine.retryInitialBackoff()) < 0) {
            throw invalid("fibra.engine.retry-max-backoff", engine.retryMaxBackoff(),
                "must not be less than fibra.engine.retry-initial-backoff");
        }
        positive(artifacts.watch().debounce(), "fibra.artifacts.watch.debounce");
        positive(config.watch().debounce(), "fibra.config.watch.debounce");
        positive(properties.startup().readinessTimeout(),
            "fibra.startup.readiness-timeout");
        positive(properties.shutdown().rootCloseTimeout(),
            "fibra.shutdown.root-close-timeout");
        var unique = new LinkedHashSet<String>();
        for (var entry : properties.startup().requiredEntries()) {
            if (entry == null || entry.isBlank()) {
                throw invalid("fibra.startup.required-entries", entry,
                    "must not contain a blank entry");
            }
            if (!unique.add(entry)) {
                throw invalid("fibra.startup.required-entries", entry,
                    "must not contain duplicates");
            }
        }
    }

    private static void existingDirectory(java.nio.file.Path value, String key) {
        if (value == null || !Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(key, value, "must be an existing directory");
        }
    }

    private static void existingFile(java.nio.file.Path value, String key) {
        if (value == null || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(key, value, "must be an existing file");
        }
    }

    private static void positive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(key, value, "must be positive");
        }
    }

    private static IllegalArgumentException invalid(String key, Object value,
                                                    String message) {
        return new IllegalArgumentException(key + "=" + value + " " + message);
    }
}
