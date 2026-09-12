package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactException;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.DeploymentArtifact;
import com.sstlfsj.fibra.engine.DeploymentManifest;
import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.EngineStateStoreException;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.InitialArtifactSource;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import com.sstlfsj.fibra.engine.RuntimeCatalog;
import com.sstlfsj.fibra.engine.RuntimeResourceOwner;
import com.sstlfsj.fibra.engine.RuntimeResourceSnapshot;
import com.sstlfsj.fibra.engine.RuntimeResourceUpdate;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.spring.FibraService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraAutoConfigurationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void discoversTheStarterThroughBootAutoConfigurationImports(@TempDir Path work) {
        new ApplicationContextRunner()
            .withUserConfiguration(AutoConfiguredHost.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                var engine = context.getBean(FibraEngine.class);
                assertEquals(EngineState.RUNNING, engine.published().current().engine().state());
                assertSame(engine.published(), context.getBean(PublishedRuntime.class));
                assertNotNull(context.getBean(PluginRegistry.class));
                assertNotNull(context.getBean(JavaPluginRuntimeAdapter.class));
            });
    }

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
                assertFalse(context.containsBean("fibraTransactionJournal"),
                    "旧事务 journal 门禁由完整目标 state store 取代");
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

    @Test
    void normalContextCloseReleasesDefaultStoreLocks(@TempDir Path work) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> assertNotNull(context.getBean(FibraEngine.class)));

        assertDefaultStoresCanReopen(work);
    }

    @Test
    void wiresInitialArtifactsIntoTheFirstEngineChangeSet(@TempDir Path work)
        throws IOException {
        var source = Files.writeString(work.resolve("initial.plugin"), "fixture");
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(HostConfiguration.class)
            .withBean(InitialArtifactSource.class, () -> () -> {
                loads.incrementAndGet();
                return List.of(DeploymentArtifact.builder()
                    .artifactId(new ArtifactId("initial-plugin"))
                    .runtimeId(CustomRuntime.RUNTIME_ID).version("1.0.0")
                    .source(source).build());
            })
            .withPropertyValues("fibra.storage-root=" + work.resolve("storage"))
            .run(context -> {
                var engine = context.getBean(FibraEngine.class);
                assertEquals(1, loads.get());
                assertTrue(engine.published().current().engine().artifacts()
                    .containsKey(new ArtifactId("initial-plugin")));
            });
    }

    @Test
    void cleanupFailureContextCloseDoesNotReleaseEngineRetainedStoreLocks(
        @TempDir Path work) throws IOException {
        var source = Files.writeString(work.resolve("failing.plugin"), "fixture");
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(CleanupFailureConfiguration.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                var engine = context.getBean(FibraEngine.class);
                engine.start().block();
                context.getBean(PluginRegistry.class).install(PluginInstallRequest.builder()
                    .artifactId(new ArtifactId("failing-close-plugin"))
                    .runtimeId(FailingCloseRuntime.RUNTIME_ID).version("1.0.0")
                    .source(source).build()).block();
            });

        assertThrows(ArtifactException.class,
            () -> new ArtifactStore(work.resolve("artifacts")));
        assertThrows(EngineStateStoreException.class,
            () -> new FileEngineStateStore(work.resolve("state")));
    }

    @Test
    void failedCustomStateStoreLookupReleasesUntransferredDefaultArtifactStore(
        @TempDir Path work) {
        FactoryFailureConfiguration.defaultArtifactLocked.set(false);
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(FactoryFailureConfiguration.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> assertNotNull(context.getStartupFailure()));

        assertTrue(FactoryFailureConfiguration.defaultArtifactLocked.get());
        assertDefaultArtifactStoreCanReopen(work);
    }

    @Test
    void failedEngineBuildReleasesBothUntransferredDefaultStores(@TempDir Path work) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(BuildFailureConfiguration.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> assertNotNull(context.getStartupFailure()));

        assertDefaultStoresCanReopen(work);
    }

    @Test
    void engineUsesAndExclusivelyClosesCustomStateStore(@TempDir Path work) {
        var observed = new AtomicReference<RecordingEngineStateStore>();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withUserConfiguration(CustomStateStoreConfiguration.class)
            .withPropertyValues("fibra.storage-root=" + work)
            .run(context -> {
                var stateStore = context.getBean(RecordingEngineStateStore.class);
                observed.set(stateStore);
                assertTrue(stateStore.loadCalls > 0);
            });

        assertEquals(1, observed.get().closeCalls);
    }

    @Test
    void explicitlyConfiguredSourceRefreshImportsRepositoryChanges(@TempDir Path work) {
        var repository = InMemoryDesiredStateRepository.empty();
        var graph = new DesiredInputGraph(List.of(DesiredInputEntry.builder(
            "future", "not-installed").enabled(false).build()));
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class))
            .withBean(DesiredStateRepository.class, () -> repository)
            .withPropertyValues("fibra.storage-root=" + work,
                "fibra.source.refresh-interval=20ms")
            .run(context -> {
                assertEquals(Duration.ofMillis(20), context.getBean(
                    FibraSourceProperties.class).refreshInterval());
                var engine = context.getBean(FibraEngine.class);
                var transaction = repository.prepareReplace(
                    repository.load().snapshot().revision(), graph);
                transaction.commit();
                transaction.close();

                var refreshed = engine.published().views()
                    .filter(view -> view.engine().desiredGraph().equals(graph))
                    .next().block(TIMEOUT);
                assertNotNull(refreshed);
                assertTrue(refreshed.engineDiagnostics().targetSatisfied());
            });
    }

    private static void assertDefaultStoresCanReopen(Path work) {
        try (var artifacts = new ArtifactStore(work.resolve("artifacts"));
             var state = new FileEngineStateStore(work.resolve("state"))) {
            assertNotNull(artifacts);
            assertNotNull(state);
        }
    }

    private static void assertDefaultArtifactStoreCanReopen(Path work) {
        try (var artifacts = new ArtifactStore(work.resolve("artifacts"))) {
            assertNotNull(artifacts);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfiguredHost {
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

    @Configuration(proxyBeanMethods = false)
    static class CleanupFailureConfiguration {
        @Bean
        FailingCloseRuntime failingCloseRuntime() {
            return new FailingCloseRuntime();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryFailureConfiguration {
        static final AtomicBoolean defaultArtifactLocked = new AtomicBoolean();

        @Bean
        @Lazy
        EngineStateStore failingEngineStateStore(FibraProperties properties) {
            assertThrows(ArtifactException.class,
                () -> new ArtifactStore(properties.storageRoot().resolve("artifacts")));
            defaultArtifactLocked.set(true);
            throw new IllegalStateException("factory state store fixture failure");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BuildFailureConfiguration {
        @Bean
        PluginRuntimeAdapter factoryBuildFailureRuntime() {
            return new PluginRuntimeAdapter() {
                @Override
                public RuntimeId id() {
                    throw new IllegalStateException("factory runtime fixture failure");
                }

                @Override
                public Mono<RuntimeArtifactInspection> inspect(
                    com.sstlfsj.fibra.artifact.ArtifactRecord artifact) {
                    return Mono.error(new UnsupportedOperationException());
                }

                @Override
                public RuntimeResourceOwner create() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStateStoreConfiguration {
        @Bean(destroyMethod = "")
        RecordingEngineStateStore customStateStore() {
            return new RecordingEngineStateStore();
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
        public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                @Override public RuntimeResourceUpdate createUpdate(
                    List<com.sstlfsj.fibra.artifact.ArtifactRecord> target) {
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return target.stream().map(value -> value.id())
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return new RuntimeResourceSnapshot(RUNTIME_ID, List.of());
                        }
                        @Override public void adopt() { }
                        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }
                @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return new RuntimeResourceSnapshot(RUNTIME_ID, List.of());
                }
                @Override public Mono<Void> closeAsync() { return Mono.empty(); }
            };
        }
    }

    static final class FailingCloseRuntime implements PluginRuntimeAdapter {
        static final RuntimeId RUNTIME_ID = new RuntimeId("failing-close");

        @Override
        public RuntimeId id() {
            return RUNTIME_ID;
        }

        @Override
        public Mono<RuntimeArtifactInspection> inspect(
            com.sstlfsj.fibra.artifact.ArtifactRecord artifact) {
            return Mono.just(new RuntimeArtifactInspection(RUNTIME_ID, artifact.id(), Map.of()));
        }

        @Override
        public RuntimeResourceOwner create() {
            return new RuntimeResourceOwner() {
                @Override
                public RuntimeResourceUpdate createUpdate(
                    List<com.sstlfsj.fibra.artifact.ArtifactRecord> target) {
                    return new RuntimeResourceUpdate() {
                        @Override public Mono<Void> prepareAsync() { return Mono.empty(); }
                        @Override public Set<ArtifactId> affectedArtifacts() {
                            return target.stream().map(value -> value.id())
                                .collect(java.util.stream.Collectors.toSet());
                        }
                        @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                        @Override public RuntimeResourceSnapshot snapshot() {
                            return new RuntimeResourceSnapshot(RUNTIME_ID, List.of());
                        }
                        @Override public void adopt() { }
                        @Override public Mono<Void> closeAsync() { return Mono.empty(); }
                    };
                }

                @Override public RuntimeCatalog catalog() { return RuntimeCatalog.empty(); }
                @Override public RuntimeResourceSnapshot snapshot() {
                    return new RuntimeResourceSnapshot(RUNTIME_ID, List.of());
                }
                @Override public Mono<Void> closeAsync() {
                    return Mono.error(new IllegalStateException("runtime cleanup fixture failure"));
                }
            };
        }
    }

    static final class RecordingEngineStateStore implements EngineStateStore {
        private DeploymentManifest manifest;
        private int loadCalls;
        private int closeCalls;

        @Override
        public Optional<DeploymentManifest> load() {
            loadCalls++;
            return Optional.ofNullable(manifest);
        }

        @Override
        public void save(DeploymentManifest value) {
            manifest = value;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
