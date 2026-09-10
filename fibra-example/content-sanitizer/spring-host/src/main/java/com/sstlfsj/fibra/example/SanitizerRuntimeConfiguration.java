package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.example.sanitizer.ContentSanitizerContribution;
import com.sstlfsj.fibra.runtime.node.NodePluginRuntimeAdapter;
import com.sstlfsj.fibra.runtime.node.NodeRuntimeOptions;
import com.sstlfsj.fibra.spring.boot.FibraProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SanitizerExampleProperties.class)
class SanitizerRuntimeConfiguration {
    @Bean
    NodePluginRuntimeAdapter nodePluginRuntime(
        SanitizerExampleProperties properties,
        FibraProperties fibraProperties) {
        return new NodePluginRuntimeAdapter(
            name -> ContentSanitizerContribution.KIND_NAME.equals(name)
                ? Optional.of(ContentSanitizerContribution.KIND) : Optional.empty(),
            NodeRuntimeOptions.defaults(properties.nodeExecutable(),
                fibraProperties.storageRoot().resolve("node-sessions")));
    }
}
