# Fibra

[![CI](https://github.com/sunker0115/fibra/actions/workflows/ci.yml/badge.svg)](https://github.com/sunker0115/fibra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Fibra 是 Cordis Core 4.0.1 的 Java 21 语义等价实现，并在其上提供可信的进程内插件装载、配置装配、持续收敛和联合部署事务。它可以作为 Java DeepSeek Harness、AI Agent 工具平台或其他动态插件宿主的基础，但不为任何单一业务框架做特殊处理，也不是整个 DeepSeek Harness 的翻译。

当前正式版本是 `v0.4.0`。该 Git 正式版本与 Maven Central 发布是两个独立状态；坐标尚未在 Central 确认可解析时，应从 `v0.4.0` 源码执行 `mvn install` 后再使用。

## 最终架构

```text
业务宿主（纯 Java / Spring Boot / CLI / Web）
                  │
          fibra-engine                 唯一托管入口
           ├── 配置 source
           ├── artifact source
           ├── 串行 reconcile
           └── deployment 联合事务
                  │
        fibra-loader-config            配置机制
                  │
         fibra-loader-pf4j             artifact 与 ClassLoader 机制
                  │
     fibra-pf4j-api / fibra-core / fibra-api
```

PF4J 只管理 `artifact` 发现、依赖图和 ClassLoader；Fibra 管理业务插件实例、服务和资源生命周期。`Plugin-Class` 被禁止，避免 PF4J 与 Fibra 同时管理业务生命周期。loader 不再拥有 watcher；自动监听、重试、周期重读和联合事务统一属于 `fibra-engine`。

根 reactor 有十个可发布 `artifact`：九个运行时 `artifact` 和一个开发工具 `artifact`。

- `fibra-api`：稳定的内核公开契约；
- `fibra-core`：唯一的 `Context`/`Fibra` 运行时；
- `fibra-pf4j-api`：标准插件入口契约；
- `fibra-loader-pf4j`：标准插件包、依赖图、ClassLoader 与单资源事务；
- `fibra-loader-config`：YAML/JSON 配置树、typed config 与运行实例事务；
- `fibra-engine`：插件、配置、source、reconcile、readiness、部署和关闭的唯一托管入口；
- `fibra-spring`：Spring Framework 生命周期与显式服务桥接；
- `fibra-spring-boot-autoconfigure`：不可变属性和 Boot 自动配置；
- `fibra-spring-boot-starter`：无生产代码的推荐依赖入口；
- `fibra-plugin-archetype`：生成独立插件项目的 Maven Archetype。

`fibra-example`、`fibra-parity-tests`、`fibra-benchmarks` 和 `verification` 只用于示例或验收，不远程发布。`fibra-benchmarks` 参加默认 reactor 以防基准代码腐化，但普通构建只编译和打包，不执行 JMH 测量。无源码 starter 仍按发布基线生成空 sources/Javadoc JAR。六个框架中立运行时 `artifact` 不依赖 Spring；Spring、Spring Shell、Spring AI、Web、Hasor 和 Solon 都不进入内核。

## 构建与验收

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

第一条命令执行 Cordis 71 项逐条对等测试、公开 API、真实多插件依赖、typed config、升级/降级/回滚、Spring 自动配置、插件模板生成与真实 Engine 装载，并编译打包 JMH 基准。后两条分别验证十个发布 `artifact` 的逐字节可复现性，以及仓库外 Java、Engine、Spring Boot 与 archetype 生成项目只通过发布坐标消费 Fibra 的能力。

完整发布边界和前置条件见[发布与构建基线](docs/release.md)。

## 内核运行时最小用法

```java
var root = FibraRuntime.create();
var greeting = ServiceKey.of("greeting", Greeting.class);
var registration = root.provide(greeting, name -> "你好，" + name);

var consumer = root.plugin(
    PluginDescriptor.<Void>builder("consumer").require(greeting).build(),
    (context, ignored) -> {
        var text = context.service(greeting)
            .invoke((invocation, service) -> service.greet("Fibra"));
        context.logger().info(text);
        return Mono.just(Disposables.noop());
    });

consumer.ready().block();
registration.dispose().block();
root.close();
```

需要动态插件和配置的宿主应依赖 `fibra-engine`，不直接组合两个 loader：

```java
try (var engine = FibraEngine.builder(
        Path.of("run/plugins"), Path.of("run/fibra.yaml"))
    .artifactSource(Path.of("run/incoming"), Duration.ofSeconds(1))
    .configSource(Duration.ofSeconds(1))
    .requiredEntries(List.of("greeting-provider"))
    .build()) {
    engine.start();
    engine.applyDeployment(Path.of("incoming/release.zip"));
}
```

托管 Engine 不公开内部 loader。宿主只能通过 `root()` 显式桥接服务，通过 `status()` 查询状态，通过 `requestReconcile()` 请求重新收敛，通过 `applyDeployment(...)` 提交强耦合的插件与配置联合变更。

## Spring Boot 适配

```xml
<dependency>
  <groupId>com.sstlfsj</groupId>
  <artifactId>fibra-spring-boot-starter</artifactId>
  <version>${fibra.version}</version>
</dependency>
```

```yaml
fibra:
  artifacts:
    installed-root: ./run/plugins
    incoming-root: ./run/incoming
    watch:
      enabled: true
      debounce: 1s
  config:
    location: ./run/fibra.yaml
    watch:
      enabled: true
      debounce: 1s
  startup:
    required-entries: [greeting-provider]
    readiness-timeout: 60s
```

starter 传递引入 autoconfigure、`fibra-spring` 和 Engine，自身没有生产 class。自动配置只暴露 `FibraEngine`、root `Context`、`FibraServiceBridge` 和 `FibraSpringLifecycle`，不把内部 loader 注册成 Spring bean。若宿主已经提供 `FibraEngine` 或 `Context`，整套默认托管单元全部退让。

插件对象不进入 Spring `BeanFactory`。宿主通过 `FibraServiceBridge.register(ServiceKey, service)` 显式把静态 Spring bean 暴露给 Fibra；动态服务引用不得跨 reload 缓存。

## 创建插件项目

从源码使用时先在 Fibra 根执行 `mvn install`，然后运行：

```bash
mvn archetype:generate -B \
  -DarchetypeGroupId=com.sstlfsj \
  -DarchetypeArtifactId=fibra-plugin-archetype \
  -DarchetypeVersion=0.4.0 \
  -DgroupId=org.example \
  -DartifactId=example-plugin \
  -Dversion=1.0.0 \
  -Dpackage=org.example.plugin \
  -DpluginId=example-plugin \
  -DfibraVersion=0.4.0
```

`archetypeVersion` 与 `fibraVersion` 必须使用同一个正式版本。生成项目不继承 Fibra 父 POM，包含 `plugin-api`、`plugin-impl`、`config` 和 `deployment` 四个模块；直接执行 `mvn verify` 即生成标准插件 ZIP 与带 SHA-256 的 deployment ZIP。IDEA 中使用同一 Archetype 坐标和六个输入即可生成，不能把本仓库模块复制成插件工程。

## 文档入口

- [Engine 最终架构](docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md)
- [Spring 运行时集成设计](docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md)
- [公共 API 使用手册](docs/api/README.md)
- [发布与构建基线](docs/release.md)
- [Cordis 源码映射](docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md)
- [Cordis 71 项测试等价表](docs/superpowers/references/2026-08-21-fibra-cordis-test-parity.md)
- [开源基线与取舍](docs/superpowers/references/2026-08-21-fibra-opensource-baselines.md)
- [公开签名基线目录](docs/api/)

## 贡献、安全与许可证

贡献流程见[贡献指南](CONTRIBUTING.md)。安全漏洞请遵循[安全策略](SECURITY.md)，不要通过公开 Issue 披露。

Fibra 使用 [Apache License 2.0](LICENSE)。Cordis 行为基线和其他依赖的归属信息见[第三方说明](THIRD_PARTY_NOTICES.md)；Cordis 原始 MIT 许可证随仓库和发布 JAR 一并保留。
