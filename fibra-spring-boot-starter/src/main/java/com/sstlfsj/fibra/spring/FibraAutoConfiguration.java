package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Fibra 可选 Spring Boot 适配层的自动装配入口。
 *
 * <p>所有 Fibra 资源 bean 声明为 {@code destroyMethod = ""}，把关闭权交给 {@link FibraLifecycle}
 * 有序编排（watcher → configLoader → loader → root），避免 Spring 默认 destroy 打乱关闭顺序。
 */
@AutoConfiguration
@EnableConfigurationProperties(FibraProperties.class)
public class FibraAutoConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public Context fibraRootContext() {
        return FibraRuntime.create();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public FibraPluginLoader fibraPluginLoader(Context fibraRootContext, FibraProperties props) {
        Path pluginsRoot = props.getPluginsRoot();
        if (pluginsRoot == null) {
            throw new IllegalStateException("必填配置缺失: fibra.plugins-root（插件安装目录根）");
        }
        return new FibraPluginLoader(fibraRootContext, pluginsRoot);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public FibraConfigLoader fibraConfigLoader(Context fibraRootContext,
                                               FibraPluginLoader loader,
                                               FibraProperties props) {
        Path configLocation = props.getConfigLocation();
        if (configLocation == null) {
            throw new IllegalStateException("必填配置缺失: fibra.config-location（配置树位置）");
        }
        return FibraConfigLoader.builder(fibraRootContext, loader, configLocation).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public FibraServiceBridge fibraServiceBridge(Context fibraRootContext) {
        return new FibraServiceBridge(fibraRootContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public FibraLifecycle fibraLifecycle(Context fibraRootContext,
                                         FibraPluginLoader loader,
                                         FibraConfigLoader configLoader,
                                         FibraProperties props) {
        return new FibraLifecycle(fibraRootContext, loader, configLoader, null, props);
    }
}
