package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.config.DesiredStateRepository;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FileTransactionJournal;
import com.sstlfsj.fibra.engine.PluginRuntimeAdapter;
import com.sstlfsj.fibra.engine.TransactionJournal;
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
@EnableConfigurationProperties(FibraProperties.class)
public class FibraAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    DesiredStateRepository fibraDesiredStateRepository() {
        return InMemoryDesiredStateRepository.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    ArtifactStore fibraArtifactStore(FibraProperties properties) {
        return new ArtifactStore(properties.storageRoot().resolve("artifacts"));
    }

    @Bean
    @ConditionalOnMissingBean
    JavaPluginRuntimeAdapter fibraJavaRuntimeAdapter() {
        return new JavaPluginRuntimeAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionJournal fibraTransactionJournal(FibraProperties properties) {
        return new FileTransactionJournal(properties.storageRoot().resolve("transactions"));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    FibraEngine fibraEngine(DesiredStateRepository desired,
                            ArtifactStore artifacts,
                            ObjectProvider<PluginRuntimeAdapter> runtimes,
                            TransactionJournal journal) {
        var builder = FibraEngine.builder(desired).artifactStore(artifacts)
            .journal(journal);
        runtimes.orderedStream().forEach(builder::runtimeAdapter);
        return builder.build();
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
    FibraServiceBridge fibraServiceBridge(FibraEngine engine) {
        return new FibraServiceBridge(engine.runtime().rootScope().context());
    }

    @Bean
    @ConditionalOnMissingBean
    static FibraServiceExporter fibraServiceExporter(
        ObjectProvider<FibraServiceBridge> bridge) {
        return new FibraServiceExporter(bridge::getObject);
    }
}
