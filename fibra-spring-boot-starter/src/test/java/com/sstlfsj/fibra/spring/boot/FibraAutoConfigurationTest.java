package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.FileTransactionJournal;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.PluginCatalog;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.RuntimeGeneration;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeGenerationRequest;
import com.sstlfsj.fibra.engine.RuntimeGenerationSnapshot;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.engine.TransactionJournal;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.spring.FibraService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraAutoConfigurationTest {
    @Test
    void composesEngineRegistryJavaRuntimeAndExplicitSpringServices(
        @TempDir Path work) throws IOException {
        var plugin = Files.writeString(work.resolve("custom.plugin"), "fixture");
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(HostConfiguration.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                assertNotNull(context.getBean(PluginRegistry.class));
                assertNotNull(context.getBean(JavaPluginRuntimeAdapter.class));
                assertInstanceOf(FileTransactionJournal.class,
                    context.getBean(TransactionJournal.class));
                var engine = context.getBean(FibraEngine.class);
                var published = engine.published().current();
                assertEquals(EngineState.RUNNING, published.engine().state());
                assertTrue(published.diagnostics().services().stream()
                    .anyMatch(service -> service.service().name().equals("greeting")));
                var artifactId = new ArtifactId("custom-plugin");
                var installed = context.getBean(PluginRegistry.class).install(
                    PluginInstallRequest.builder().artifactId(artifactId)
                        .runtimeId(CustomRuntime.RUNTIME_ID).version("1.0.0")
                        .source(plugin).build()).block();
                assertTrue(installed.artifacts().containsKey(artifactId));
            });
    }

    interface Greeting {
        String greet(String name);
    }

    @FibraService(name = "greeting", type = Greeting.class)
    static final class GreetingService implements Greeting {
        @Override
        public String greet(String name) {
            return "hello " + name;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostConfiguration {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        CustomRuntime customRuntime() {
            return new CustomRuntime();
        }
    }

    static final class CustomRuntime implements PluginRuntimeAdapter {
        static final RuntimeId RUNTIME_ID = new RuntimeId("custom");

        @Override
        public RuntimeId id() {
            return RUNTIME_ID;
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(
            com.sstlfsj.fibra.artifact.ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(RUNTIME_ID,
                artifact.id(), Map.of()));
        }

        @Override
        public RuntimeGeneration create(RuntimeGenerationRequest request) {
            var artifacts = request.artifacts().stream().collect(
                java.util.stream.Collectors.toMap(value -> value.id(), value -> value));
            var snapshot = new RuntimeGenerationSnapshot(RUNTIME_ID, "custom-1",
                artifacts, Set.of());
            return new RuntimeGeneration() {
                @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                @Override public RuntimeGenerationSnapshot snapshot() { return snapshot; }
                @Override public PluginCatalog catalog() { return PluginCatalog.empty(); }
                @Override public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }
    }
}
