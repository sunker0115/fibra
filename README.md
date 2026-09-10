# Fibra

[![CI](https://github.com/sunker0115/fibra/actions/workflows/ci.yml/badge.svg)](https://github.com/sunker0115/fibra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Fibra 是 Java 21 的通用插件底座。它把生命周期与资源所有权、期望状态、制品事务、运行时适配、贡献目录和管理控制面拆成稳定边界，可直接嵌入 Java 服务，也可用于 Harness、Agent 平台、CLI 或 Spring Boot 宿主。

当前开发版本为 `0.5.0-SNAPSHOT`，是无兼容层的 vNext 重构。唯一权威设计是 [Fibra vNext 架构](docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md)。Java Harness 只是一个接入场景，参考 [Java Harness 集成](docs/superpowers/specs/2026-09-07-fibra-java-harness-integration.md)。

## 架构

```text
业务场景 / Harness / Spring Boot
        │                         │
 PluginRegistry             PublishedRuntime
 安装、升级、启停、审计        单一 PublishedView 与带 revision 调用
        └──────────┬──────────────┘
               FibraEngine        唯一托管变更入口与 ChangeSet 事务
                    │
       AtomicReference<PublishedState>
                    │
          ┌─────────┴─────────┐
     RuntimeDomain        RuntimeDomain
       published           candidate / draining
    Scope、服务、事件       Scope、服务、事件
    generation-local       generation-local
 ContributionDirectory  ContributionDirectory
          │                   │
   Java / Node runtime   Java / Node runtime
```

模块职责：

- `fibra-api`：`Scope`、`Context`、插件、服务、事件和 effect 公共契约；
- `fibra-core`：唯一 lifecycle lane 与资源所有权实现；
- `fibra-config`：期望状态解析、校验、编译和写回事务；
- `fibra-artifact`：运行时中立的制品存储、摘要、隔离和磁盘事务；
- `fibra-engine`：runtime port、命令、ChangeSet、持久化 journal 和原子 `PublishedView`；
- `fibra-runtime-java`：单一 manifest、依赖图和隔离 ClassSpace；
- `fibra-runtime-node`：受限 Node 入口、sidecar、JSON-RPC、心跳和进程树治理；
- `fibra-bridge`：本地与远程贡献的统一目录、调用适配和 drain；
- `fibra-registry`：面向管理面的安装、升级、启停、查询、watch 和审计；
- `fibra-spring`、`fibra-spring-boot-starter`：显式 Spring 服务桥接和组合入口；
- `fibra-plugin-archetype`：生成独立 Java 插件 JAR。

浏览器/WebView 前端插件、远程市场、非可信插件沙箱和具体 Agent 模型不在本期核心内。

## 最小内核用法

```java
var runtime = FibraRuntime.create();
var scope = runtime.rootScope().openChild("application");
var greeting = ServiceKey.of("greeting", Greeting.class);

var provider = PluginDefinition.builder("provider", String.class,
    () -> (context, prefix) -> {
        context.services().provide(greeting, name -> prefix + ", " + name);
        return Mono.empty();
    })
    .provide(greeting)
    .build();

var instance = scope.context().plugins().mount("provider", provider, "你好");
instance.settled().block();
var text = scope.context().services().reference(greeting)
    .invoke((invocation, service) -> service.greet("Fibra"));

scope.close();
runtime.close();
```

注册到插件 `Context` 的服务、事件和 effect 自动归当前插件实例所有；关闭实例或 `Scope` 会按逆序撤销，不要求插件手工返回 registration 列表。

## Java 插件 JAR

Java 制品 JAR 中只声明一个 `META-INF/fibra/plugin.yaml`。可运行插件实现
`PluginEntrypoint<C>` 并声明唯一 `entrypoint`：

```yaml
id: greeting
version: 1.0.0
entrypoint: org.example.GreetingEntrypoint
requires: []
```

只承载共享 SPI/DTO 的 contract-only JAR 省略 `entrypoint`，仍参与 SemVer 依赖图和 ClassSpace，但不会生成可挂载的 `PluginDefinition`。插件工程仅以 `provided` 方式依赖 `fibra-api` 及其契约制品。`fibra-runtime-java` 校验 manifest、解析 SemVer 依赖图，为每个制品建立隔离 `URLClassLoader`，并在旧 generation 排空后关闭 ClassSpace。不需要 `plugin.properties`、注解扫描或扩展索引。

## Node 插件

Node 插件目录包含 `fibra-plugin.yaml` 和目录内的 `.js`、`.mjs` 或 `.cjs` 入口：

```yaml
id: echo-node
version: 1.0.0
protocol: 1
entrypoint: index.mjs
contributions:
  - name: echo
    kind: tool
    schemaVersion: 1
    method: echo
    descriptor: { title: Echo }
```

宿主使用参数数组启动 `node <entrypoint>`，不经过 Shell。运行时限制消息大小，提供请求超时、取消、心跳、异常退出诊断、进程树终止和会话目录清理。Node 贡献和 Java 本地贡献登记到所属运行代的 `ContributionDirectory`，只由 Engine 当前发布的 `PublishedRuntime` 对外调用。

## 托管与 Spring Boot

需要动态安装和管理时依赖 `fibra-engine` 或 `fibra-registry`；宿主只通过 `start()`、`submit(EngineCommand)` 和稳定的 `published()` 门面工作。`PublishedRuntime.current()` / `views()` 返回状态、诊断和贡献一致的不可变视图，能力调用必须携带选择能力时看到的 `viewRevision`。所有外部变更经过同一个 ChangeSet command loop，未完成的持久事务会在启动时恢复或关闭 mutation gate。

Spring Boot 只需引入：

```xml
<dependency>
  <groupId>com.sstlfsj</groupId>
  <artifactId>fibra-spring-boot-starter</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```

```yaml
fibra:
  storage-root: ./.fibra
```

starter 自动组合 `ArtifactStore`、持久化 `TransactionJournal`、Java runtime、Engine 与 Registry。宿主 bean 只有标注 `@FibraService` 才会显式进入 Fibra root，不会扫描或托管动态插件对象。

## 构建

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

完整 reactor 包含真实 JAR、真实 Node 进程、事务恢复、Spring、archetype、架构边界、分发和 JMH 编译门禁。可运行示例见 [`fibra-example`](fibra-example/README.md)，公共入口见 [`docs/api`](docs/api/README.md)，发布边界见 [`docs/release.md`](docs/release.md)。

## 许可证

Fibra 使用 [Apache License 2.0](LICENSE)。Cordis 行为参考和其他依赖的归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
