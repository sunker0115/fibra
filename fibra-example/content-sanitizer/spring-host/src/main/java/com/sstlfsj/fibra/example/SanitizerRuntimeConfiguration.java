package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.example.sanitizer.ContentSanitizerContribution;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeProvider;
import com.sstlfsj.fibra.spring.boot.FibraProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SanitizerExampleProperties.class)
class SanitizerRuntimeConfiguration {
    @Bean
    NodeRuntimeProvider nodeRuntimeProvider(
        SanitizerExampleProperties properties,
        FibraProperties fibraProperties) {
        return new NodeRuntimeProvider(NodeRuntimeOptions.defaults(
            properties.nodeExecutable(), fibraProperties.storageRoot()
                .resolve("node-sessions")));
    }

    @Bean
    ContributionKindRegistry contributionKinds() {
        return ContributionKindRegistry.of(ContentSanitizerContribution.KIND);
    }
}
