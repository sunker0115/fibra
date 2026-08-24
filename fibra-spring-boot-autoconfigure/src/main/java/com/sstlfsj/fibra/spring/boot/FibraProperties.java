package com.sstlfsj.fibra.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Fibra 托管运行时的不可变 Spring Boot 配置。 */
@ConfigurationProperties("fibra")
public record FibraProperties(Engine engine, Artifacts artifacts, Config config,
                              Startup startup, Shutdown shutdown) {
    public FibraProperties {
        engine = engine == null ? new Engine(null, null, null) : engine;
        artifacts = artifacts == null ? new Artifacts(null, null, null) : artifacts;
        config = config == null ? new Config(null, null) : config;
        startup = startup == null ? new Startup(null, null) : startup;
        shutdown = shutdown == null ? new Shutdown(null) : shutdown;
    }

    public record Engine(
        /** 周期完整重读间隔，用于修复丢失的文件事件。 */
        @DefaultValue("30s") Duration resyncInterval,
        /** Reconcile 失败后的首次重试退避。 */
        @DefaultValue("250ms") Duration retryInitialBackoff,
        /** Reconcile 指数退避上限。 */
        @DefaultValue("30s") Duration retryMaxBackoff) {
        public Engine {
            resyncInterval = resyncInterval == null ? Duration.ofSeconds(30) : resyncInterval;
            retryInitialBackoff = retryInitialBackoff == null
                ? Duration.ofMillis(250) : retryInitialBackoff;
            retryMaxBackoff = retryMaxBackoff == null
                ? Duration.ofSeconds(30) : retryMaxBackoff;
        }
    }

    public record Artifacts(
        /** 已安装标准插件目录；必须在启动前存在。 */
        Path installedRoot,
        /** 候选插件 ZIP目录；制品 source 开启时必须存在。 */
        Path incomingRoot,
        /** 制品 source 开关与去抖参数。 */
        Watch watch) {
        public Artifacts {
            watch = watch == null ? new Watch(false, null) : watch;
        }
    }

    public record Config(
        /** YAML或 JSON 配置根文件；必须在启动前存在。 */
        Path location,
        /** 配置 source 开关与去抖参数。 */
        Watch watch) {
        public Config {
            watch = watch == null ? new Watch(false, null) : watch;
        }
    }

    public record Watch(
        /** 是否监听对应来源并触发 Engine reconcile。 */
        @DefaultValue("false") boolean enabled,
        /** 合并连续文件事件的去抖时间。 */
        @DefaultValue("1s") Duration debounce) {
        public Watch {
            debounce = debounce == null ? Duration.ofSeconds(1) : debounce;
        }
    }

    public record Startup(
        /** 启动时必须处于 ACTIVE 的 Fibra entryId，不是 PF4J pluginId。 */
        @DefaultValue List<String> requiredEntries,
        /** 全部 required entry 共享的总就绪时间预算。 */
        @DefaultValue("60s") Duration readinessTimeout) {
        public Startup {
            requiredEntries = requiredEntries == null ? List.of() : List.copyOf(requiredEntries);
            readinessTimeout = readinessTimeout == null
                ? Duration.ofSeconds(60) : readinessTimeout;
        }
    }

    public record Shutdown(
        /** 等待 Fibra root 异步关闭完成的最长时间。 */
        @DefaultValue("30s") Duration rootCloseTimeout) {
        public Shutdown {
            rootCloseTimeout = rootCloseTimeout == null
                ? Duration.ofSeconds(30) : rootCloseTimeout;
        }
    }
}
