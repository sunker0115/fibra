package com.sstlfsj.fibra.spring.boot;

import com.sstlfsj.fibra.artifact.PluginPackageStore;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.engine.DeploymentTargetStore;
import com.sstlfsj.fibra.engine.FileDeploymentTargetStore;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.HostServiceRegistry;
import com.sstlfsj.fibra.engine.HostTerminationPort;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.registry.FilePluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginAuditRepository;
import com.sstlfsj.fibra.registry.PluginRegistry;
import com.sstlfsj.fibra.runtime.java.JavaRuntimeProvider;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.spring.FibraServiceBridge;
import com.sstlfsj.fibra.spring.FibraServiceExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

@AutoConfiguration
@EnableConfigurationProperties(FibraProperties.class)
public class FibraAutoConfiguration {
    @Bean(destroyMethod = "") @ConditionalOnMissingBean
    PluginPackageStore fibraPluginPackageStore(FibraProperties properties) {
        return new PluginPackageStore(properties.storageRoot().resolve("packages"));
    }

    @Bean(destroyMethod = "") @ConditionalOnMissingBean
    DeploymentTargetStore fibraDeploymentTargetStore(FibraProperties properties) {
        return new FileDeploymentTargetStore(properties.storageRoot().resolve("target"));
    }

    @Bean @ConditionalOnMissingBean JavaRuntimeProvider fibraJavaRuntimeProvider() {
        return new JavaRuntimeProvider(List.of());
    }

    @Bean @ConditionalOnMissingBean NodeRuntimeProvider fibraNodeRuntimeProvider(
        FibraProperties properties) {
        return new NodeRuntimeProvider(NodeRuntimeOptions.defaults(Path.of("node"),
            properties.storageRoot().resolve("node-sessions")));
    }

    @Bean @ConditionalOnMissingBean ContributionKindRegistry fibraContributionKinds() {
        return ContributionKindRegistry.empty();
    }

    @Bean @ConditionalOnMissingBean HostTerminationPort fibraHostTerminationPort(
        ConfigurableApplicationContext context) {
        Executor executor = command -> Thread.ofPlatform().daemon()
            .name("fibra-spring-termination").start(command);
        return request -> executor.execute(context::close);
    }

    @Bean(destroyMethod = "close") @ConditionalOnMissingBean
    FibraEngine fibraEngine(PluginPackageStore packages, DeploymentTargetStore targets,
                            JavaRuntimeProvider java, NodeRuntimeProvider node,
                            ContributionKindRegistry kinds, HostTerminationPort termination,
                            HostServiceRegistry hostServices) {
        return FibraEngine.builder(packages, targets).hostServices(hostServices)
            .contributionKinds(kinds).hostTerminationPort(termination)
            .runtimeProvider(java).runtimeProvider(node).build();
    }

    @Bean @ConditionalOnMissingBean HostServiceRegistry fibraHostServiceRegistry() {
        return new HostServiceRegistry();
    }

    @Bean(destroyMethod = "close") @ConditionalOnMissingBean
    PluginAuditRepository fibraPluginAuditRepository(FibraProperties properties) {
        return new FilePluginAuditRepository(properties.storageRoot().resolve("audit.log"));
    }

    @Bean @ConditionalOnMissingBean PluginRegistry fibraPluginRegistry(
        FibraEngine engine, PluginPackageStore packages, PluginAuditRepository audit) {
        return new PluginRegistry(engine, packages, audit);
    }

    @Bean FibraEngineLifecycle fibraEngineLifecycle(FibraEngine engine) {
        return new FibraEngineLifecycle(engine);
    }

    @Bean @ConditionalOnMissingBean PublishedRuntime fibraPublishedRuntime(FibraEngine engine) {
        return engine.published();
    }

    @Bean @ConditionalOnMissingBean FibraServiceBridge fibraServiceBridge(
        HostServiceRegistry hostServices) {
        return new FibraServiceBridge(hostServices);
    }

    @Bean @ConditionalOnMissingBean static FibraServiceExporter fibraServiceExporter(
        ObjectProvider<FibraServiceBridge> bridge) {
        return new FibraServiceExporter(bridge::getObject);
    }
}
