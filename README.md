# Fibra

[![CI](https://github.com/sunker0115/fibra/actions/workflows/ci.yml/badge.svg)](https://github.com/sunker0115/fibra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Fibra 是 Java 21 的通用插件底座。它把生命周期与资源所有权、期望状态、不可变制品、运行时适配、贡献目录和管理控制面拆成稳定边界，可直接嵌入 Java 服务，也可用于 Harness、Agent 平台或 Spring Boot 宿主，并作为正式 CLI 宿主的运行底座。

当前开发版本为 `0.5.0-SNAPSHOT`，是无兼容层的 vNext 重构。唯一权威设计是 [Fibra vNext 架构](docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md)。Java Harness 只是一个接入场景，参考 [Java Harness 集成](docs/superpowers/specs/2026-09-07-fibra-java-harness-integration.md)。

当前 pre-CLI 快照已经交付长期 `RuntimeDomain`、实例差量更新、首次联合制品启动、provider-managed
子进程范围和 fs、fs-search、shell、storage 正式插件。顶层 `fibra-cli`、可执行 ZIP 与仓库外解压启动
仍是后续交付项，因此不能把该快照描述为完整命令行产品。

## 架构

```text
业务场景 / Harness / Spring Boot
        │                         │
 PluginRegistry             PublishedRuntime
 安装、升级、启停、审计        单一 PublishedView 与带 revision 调用
        └──────────┬──────────────┘
               FibraEngine        唯一串行变更入口
                    ├── EngineStateStore       单个完整目标
                    ├── PublishedState         一致事实与调用路由
                    ├── RuntimeDomain          长期运行域
                    │    ├── Scope 所有权树
                    │    ├── 插件及实际服务依赖
                    │    └── ContributionDirectory
                    └── Java / Node owners     仅替换受影响资源
```

模块职责：

- `fibra-api`：`Scope`、`Context`、插件、服务、事件和 effect 公共契约；
- `fibra-core`：唯一 lifecycle lane 与资源所有权实现；
- `fibra-config`：期望状态解析、校验、编译和写回事务；
- `fibra-artifact`：运行时中立的不可变制品存储、摘要与精确 revision 读取；
- `fibra-engine`：runtime port、串行命令、完整目标保存、差量协调和一致 `PublishedView`；
- `fibra-runtime-java`：单一 manifest、依赖图和按制品管理的隔离 ClassLoader；
- `fibra-runtime-node`：受限 Node 入口、sidecar、JSON-RPC、心跳和进程树治理；
- `fibra-bridge`：本地与远程贡献的统一目录、调用适配和 drain；
- `fibra-registry`：面向管理面的安装、升级、启停、查询、watch 和审计；
- `fibra-spring`、`fibra-spring-boot-starter`：显式 Spring 服务桥接和组合入口；
- `fibra-plugin-archetype`：生成独立 Java 插件工程，其主 JAR 用作安装包的 payload；
- `fibra-plugins`：正式插件产品的根聚合模块，自身不发布；`fibra-tool-api` 以及 fs、subprocess、shell、
  storage 四个领域在其下分别发布 contract、provider 和 tool consumer。

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

var instance = scope.context().plugins().mount("provider", provider.prepare("你好"));
instance.settled().block();
var text = scope.context().services().reference(greeting)
    .invoke((invocation, service) -> service.greet("Fibra"));

scope.close();
runtime.close();
```

注册到插件 `Context` 的服务、事件和 effect 自动归当前插件实例所有；关闭实例或 `Scope` 会按逆序撤销，不要求插件手工返回 registration 列表。

`InvocationContext` 明确分开能力语境和资源语境：`caller()` 决定 realm、intercept、logger、
`plugins()` 与服务解析，`effects()` 及嵌套 `ServiceRef` 沿内部资源 `Context` 保留实际 owner；`scope()`
返回该 owner 所在的生命周期 Scope。普通服务调用的资源语境就是 caller，因此插件内调用创建的 effect
仍归插件实例；贡献调用的 `caller()` 是注册插件 owner，资源语境则是宿主为该次调用创建的临时 Scope
`Context`。两者必须位于同一 `RuntimeDomain`，调用结束会先排空临时 Scope，再释放贡献的在途计数。

## 正式插件

正式插件和宿主主程序分离，每个插件都是独立制品；provider 与 consumer 是运行时角色，不是两套插件
格式。provider 向 `RuntimeDomain` 提供服务，consumer 只依赖 contract，并把工具注册到长期
`ContributionDirectory`。当前产品模块如下：

- 宿主工具契约：`fibra-tool-api`；
- 文件：`fibra-fs`、`fibra-fs-local`、`fibra-tool-fs`、`fibra-tool-fs-search`；
- 子进程：`fibra-subprocess`、`fibra-subprocess-local`；
- Shell：`fibra-shell`、`fibra-shell-local`、`fibra-tool-shell`；
- 配置存储：`fibra-storage`、`fibra-storage-json`、`fibra-tool-storage`。

除宿主使用的 `fibra-tool-api` 外，以上包含 12 个动态插件的 Maven JAR 发布物。每个 JAR 是对应 Java
安装目录包的 payload；实际安装、升级和持久化均以完整包根目录为单位。它们通过真实制品图和
公开 `PublishedRuntime` 联合验收，不进入宿主 classpath，也不打入 Engine 或宿主 JAR。完整角色关系、
依赖和打包约束见 [正式插件说明](fibra-plugins/README.md)。

`fibra-subprocess-local` 是文件搜索和 Shell 共用的独立 Java provider。Windows 使用 kill-on-close
Job Object；Linux 优先使用 user-systemd transient scope，能力不可用时显式降级到较弱的进程组监督器；
macOS 使用进程组边界并保留逃逸后代限制。它与 Node sidecar 的进程管理实现相互独立。

当前 Maven 插件 JAR 不捆绑外部可执行文件。宿主须分别为 subprocess、搜索和 Shell provider 配置可执行的
Node.js、ripgrep 与 Bash 路径；建议使用绝对路径。项目 CI 固定使用 ripgrep 15.0.1，与当前 DSH
0.1.5-rc.2 锁定的 `@vscode/ripgrep` 1.18.0 一致。未来 CLI/ZIP 发行层可按目标平台携带二进制并注入
现有配置，不需要改变插件公开 API。

## 插件安装单元

Java 与 Node 统一安装目录包，包根的 `plugin.properties` 只允许三个字段：

```properties
formatVersion=1
runtime=java
payload=lib/main.jar
```

Java 包的 `lib/main.jar` 为主 payload，`lib/` 中的其他 JAR 是包内私有依赖。Node 包使用
`runtime=node`，`payload` 指向包内独立目录，例如 `payload/`。payload 必须存在且位于包根内，
不能是绝对路径、包根本身或越界路径；整个安装包禁止符号链接。裸 JAR 和直接在根目录放置
`fibra-plugin.yaml` 的旧 Node 目录都不作为安装输入。

`plugin.properties` 仅描述布局和 runtime 路由，不重复声明插件标识、版本、依赖或入口。
`PluginArtifactProbe` 调用对应 runtime 读取内部 manifest，返回的 `DeploymentArtifact.source()`
始终是整个包根。`ArtifactStore` 复制完整包并计算内容摘要，runtime 从受管副本解析 payload。

## Java 插件 payload

Java 制品 JAR 中只声明一个 `META-INF/fibra/plugin.yaml`。可运行插件实现
`PluginEntrypoint<C>` 并声明唯一 `entrypoint`：

```yaml
id: greeting
version: 1.0.0
entrypoint: org.example.GreetingEntrypoint
requires: []
```

内部 manifest 是 `id`、`version`、`requires` 和 `entrypoint` 的唯一真源。只承载共享 SPI/DTO 的
contract-only JAR 省略 `entrypoint`，仍参与 SemVer 依赖图，但不会生成可挂载的 `PluginDefinition`。
插件工程仅以 `provided` 方式依赖 `fibra-api` 及其契约制品。`fibra-runtime-java` 校验 manifest、解析
SemVer 依赖图，为每个安装包建立隔离 `URLClassLoader`：主 JAR 优先，随后按路径顺序读取 `lib/` 中的
私有 JAR，插件间类型仍沿显式依赖图委派。主 JAR 和私有 JAR 均不得通过 manifest 的 `Class-Path`
扩展装载路径。升级替换变化制品及其旧新依赖图中的反向依赖闭包，无关 ClassLoader 保留；旧资源在
相关调用和实例清理完成后关闭。不扫描注解或全部 class 猜测入口，也不生成扩展索引。

## Node 插件

Node 安装包根声明 `runtime=node` 和 `payload=payload`；其 `payload/` 目录包含
`fibra-plugin.yaml` 和目录内的 `.js`、`.mjs` 或 `.cjs` 入口。内部 manifest 是插件标识、版本、
入口、协议和贡献声明的唯一真源：

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

runtime 从 ArtifactStore 受管包的 payload 解析入口。宿主使用参数数组启动随模块发布的进程监督器，
再由监督器启动该入口，全程不经过 Shell。
`NodeSidecar` 只处理有界 JSON-RPC、请求超时、取消、心跳和异常退出；`NodeProcessUnit` 负责一个可等待的
受管进程范围，按“stdin EOF、软终止、强终止、范围静默、目录清理”收口。POSIX 使用独立进程组，
Windows 使用系统进程树终止后端；主动逃离受管范围不属于本地 sidecar 的安全保证。Node 贡献和 Java
本地贡献登记到长期运行域的 `ContributionDirectory`，只由 Engine 当前发布的 `PublishedRuntime` 对外调用。

## 托管与 Spring Boot

需要动态安装和管理时依赖 `fibra-engine` 或 `fibra-registry`；宿主只通过 `start()`、`submit(EngineCommand)` 和稳定的 `published()` 门面工作。`PublishedRuntime.current()` / `views()` 返回状态、诊断和贡献一致的不可变视图，能力调用必须携带选择能力时看到的 `viewRevision`。所有外部变更经过同一个命令队列：预检、保存完整目标、差量协调、发布实际结果。目标保存成功不等于插件已经达成目标；保存后的运行故障不会反写旧目标。恢复只按完整目标读取精确制品，不回退旧版本或猜测源文件。仅在没有已保存目标时，宿主提供的初始制品和配置树才进入同一个首次启动 `ChangeSet`；已有持久目标启动时不会被插件目录、默认空配置或 watcher 静默覆盖。

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
  source:
    refresh-interval: 1s
```

starter 自动组合 `ArtifactStore`、持久化 `FileEngineStateStore`、Java runtime、Engine 与 Registry。
初始化阶段仅将标注 `@FibraService` 的宿主 Bean 收集为显式 host binding，不扫描或托管动态插件对象。
Engine 启动时冻结并复制这些 binding；bridge 返回的 `ServiceRegistration.dispose()` 仅能在启动前取消
待收集 binding，启动后不会动态撤销已发布服务。
Spring 继续拥有 Bean，运行域关闭与在途排空由 Engine 负责。

`fibra.source.refresh-interval` 默认 `0s`，即不自动导入配置源；设置为正值后，文件事件只触发可合并的 dirty signal，周期 resync 负责发现丢失通知。无效源保留 last-good 目标和运行实例并公开失败诊断，修正后自动恢复。已有持久目标启动时只建立源观察基线，不会被源文件静默覆盖。

默认存储由 Engine 内部持有并关闭；如提供自定义 `ArtifactStore` 或 `EngineStateStore` bean，须声明 `@Bean(destroyMethod = "")`，不能让容器再次独立关闭。

## 构建

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

当前发布边界为 25 个 Maven 制品，其中包含 12 个动态插件的 Java payload JAR，`fibra-tool-storage` 已
纳入发布、可复现和仓库外消费清单。完整 reactor 覆盖真实 JAR、真实 Node 进程、目标恢复、Spring、
archetype、架构边界和 JMH 编译门禁。

本快照尚未合入正式 CLI 与 ZIP 分发结构，因此当前 GitHub CI 只验证 pre-CLI 边界；最终全仓、公开 API、
可复现和空 Maven 仓分发门禁必须在 CLI/ZIP 合入后统一重跑，不能沿用此前 24 制品的结果关闭交付。
可运行示例见 [`fibra-example`](fibra-example/README.md)，公共入口见 [`docs/api`](docs/api/README.md)，
发布边界见 [`docs/release.md`](docs/release.md)。

## 许可证

Fibra 使用 [Apache License 2.0](LICENSE)。Cordis 行为参考和其他依赖的归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
