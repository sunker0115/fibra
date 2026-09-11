package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.EngineStateStore;
import com.sstlfsj.fibra.engine.FileEngineStateStore;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.registry.FilePluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import com.sstlfsj.fibra.spring.FibraServiceBridge;
import com.sstlfsj.fibra.spring.FibraServiceExporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({FibraProperties.class, FibraSourceProperties.class})
public class FibraAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    DesiredStateRepository fibraDesiredStateRepository() {
        return InMemoryDesiredStateRepository.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    JavaPluginRuntimeAdapter fibraJavaRuntimeAdapter() {
        return new JavaPluginRuntimeAdapter();
    }

    /**
     * Engine is the exclusive owner of the stores after this factory returns.
     * A user-supplied ArtifactStore or EngineStateStore bean must declare
     * {@code destroyMethod = ""}; Spring must not close it independently.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    FibraEngine fibraEngine(DesiredStateRepository desired,
                            FibraProperties properties,
                            FibraSourceProperties sourceProperties,
                            ObjectProvider<ArtifactStore> artifactStores,
                            ObjectProvider<PluginRuntimeAdapter> runtimes,
                            ObjectProvider<EngineStateStore> stateStores,
                            HostServiceRegistry hostServices) {
        ArtifactStore artifacts = null;
        EngineStateStore stateStore = null;
        try {
            artifacts = artifactStores.getIfAvailable();
            if (artifacts == null) {
                artifacts = new ArtifactStore(properties.storageRoot().resolve("artifacts"));
            }
            stateStore = stateStores.getIfAvailable();
            if (stateStore == null) {
                stateStore = new FileEngineStateStore(properties.storageRoot().resolve("state"));
            }
            var builder = FibraEngine.builder(desired).artifactStore(artifacts)
                .stateStore(stateStore).hostServices(hostServices);
            if (!sourceProperties.refreshInterval().isZero()) {
                builder.autoRefresh(sourceProperties.refreshInterval());
            }
            runtimes.orderedStream().forEach(builder::runtimeAdapter);
            var engine = builder.build();
            artifacts = null;
            stateStore = null;
            return engine;
        } catch (RuntimeException | Error failure) {
            closeUntransferred(stateStore, artifacts, failure);
            throw failure;
        }
    }

    private static void closeUntransferred(EngineStateStore stateStore,
                                           ArtifactStore artifacts,
                                           Throwable failure) {
        closeUntransferred(stateStore, failure);
        closeUntransferred(artifacts, failure);
    }

    private static void closeUntransferred(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    HostServiceRegistry fibraHostServiceRegistry() {
        return new HostServiceRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    PluginAuditRepository fibraPluginAuditRepository(FibraProperties properties) {
        return new FilePluginAuditRepository(properties.storageRoot().resolve("audit.log"));
    }

    @Bean
    @ConditionalOnMissingBean
    PluginRegistry fibraPluginRegistry(FibraEngine engine,
                                       PluginAuditRepository audit) {
        return new PluginRegistry(engine, audit);
    }

    @Bean
    FibraEngineLifecycle fibraEngineLifecycle(FibraEngine engine) {
        return new FibraEngineLifecycle(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    PublishedRuntime fibraPublishedRuntime(FibraEngine engine) {
        return engine.published();
    }

    @Bean
    @ConditionalOnMissingBean
    FibraServiceBridge fibraServiceBridge(HostServiceRegistry hostServices) {
        return new FibraServiceBridge(hostServices);
    }

    @Bean
    @ConditionalOnMissingBean
    static FibraServiceExporter fibraServiceExporter(
        ObjectProvider<FibraServiceBridge> bridge) {
        return new FibraServiceExporter(bridge::getObject);
    }
}
