# Fibra

[![CI](https://github.com/sunker0115/fibra/actions/workflows/ci.yml/badge.svg)](https://github.com/sunker0115/fibra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Fibra 是 Java 21 的通用插件底座。它把生命周期与资源所有权、期望状态、不可变制品、运行时适配、贡献目录
和管理控制面拆成稳定边界，可直接嵌入 Java 服务，也可支撑 Harness、Agent 平台、Spring Boot 宿主和正式
CLI 应用。

当前开发版本为 `0.5.0-SNAPSHOT`，是 vNext 重构后的开发基线，不保留 `0.4.x` 兼容层。长期
`RuntimeDomain`、实例差量更新、Java/Node runtime、fs/search/shell/storage 正式插件、动态 CLI command、
安全历史、补全和高亮、调用级取消与信号协调、受控终端租约、渐进 renderer、可执行 ZIP 和仓库外消费均已
交付。CLI 公共边界已经冻结；同一 `0.5.x` 版本列只接受二进制兼容的增加或修复。

## 快速开始

从源码构建发行包需要 JDK 21、Maven 3.9.9、Node.js、ripgrep 15.0.1，以及当前 POSIX 目标平台的
`/bin/bash`。Node.js 和 ripgrep 可执行文件可以分别通过 `fibra.distribution.node` 与
`fibra.distribution.rg` Maven 属性覆盖。

```bash
mvn -pl fibra-distribution -am package

fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra --version
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra plugins list
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra tools list
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra \
  tools invoke storage-tools load --input '{}'
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra repl
```

`package` 同时生成可直接运行的目录和
`fibra-distribution/target/fibra-0.5.0-SNAPSHOT-bin.zip`。发行目录包含 CLI、宿主依赖、12 个标准 Java
插件包，以及目标平台的 Node.js、ripgrep 和 Bash 启动代理；`bin/fibra` 自动以所在目录作为安装根目录。

默认 profile 首次启动时从 `config/profiles/default.yaml` 与 `default.artifacts.yaml` 建立完整目标，之后从
`data/` 恢复已保存目标。修改 profile 输入后显式执行 `fibra apply`；它们不会在重启时静默覆盖运行目标。

## 选择接入方式

| 目标 | 入口 | 继续阅读 |
|---|---|---|
| 直接运行和管理插件 | 二进制发行包的 `bin/fibra` | [正式 CLI 宿主](#正式-cli-宿主) |
| 在 Java 应用内使用生命周期内核 | `fibra-api`、`FibraRuntime` | [最小内核用法](#最小内核用法) |
| 建立动态插件宿主 | `fibra-engine`、`fibra-registry`、`PublishedRuntime` | [托管与 Spring Boot](#托管与-spring-boot) |
| 接入 Spring Boot | `fibra-spring-boot-starter` | [托管与 Spring Boot](#托管与-spring-boot) |
| 编写 Java 或 Node 插件 | 标准插件安装目录包 | [插件安装单元](#插件安装单元) |

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
- `fibra-cli-api`：应用元数据、bootstrap/dynamic command、调用上下文、输出、退出状态和终端租约契约；
- `fibra-cli`：profile 级正式宿主、插件管理、PublishedRuntime 工具调用和长期 REPL；
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

单独发布的 Maven 插件 JAR 不捆绑外部可执行文件。嵌入式宿主须分别为 subprocess、搜索和 Shell provider
配置 Node.js、ripgrep 与 Bash 路径，建议使用绝对路径。正式 ZIP 已按目标平台携带 Node.js、ripgrep 和
Bash 启动代理，并通过默认 profile 注入现有配置，不需要改变插件公开 API。项目 CI 固定使用 ripgrep
15.0.1，与当前 DSH 0.1.5-rc.2 锁定的 `@vscode/ripgrep` 1.18.0 一致。

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

## 正式 CLI 宿主

CLI 的默认目录是 `${fibra.home}/config`、`${fibra.home}/plugins` 和 `${fibra.home}/data`。每个 profile
使用 `config/profiles/<profile>.yaml` 作为配置条目树，并使用相邻的
`<profile>.artifacts.yaml` 显式列出相对 `plugins/` 的完整插件包集合。首次启动从两份输入建立并保存一个
完整目标；后续启动直接恢复该目标，只有 `apply` 会重新导入两份 profile 输入。

命令面如下：

```text
fibra [--home DIR] [--profile NAME] plugins list
fibra [全局选项] plugins install|upgrade PACKAGE
fibra [全局选项] plugins uninstall ARTIFACT_ID
fibra [全局选项] plugins enable|disable INSTANCE_ID
fibra [全局选项] tools list
fibra [全局选项] tools invoke PROVIDER LOCAL_NAME --input JSON
fibra [全局选项] apply
fibra [全局选项] repl
```

一次性动态命令和 REPL 使用同一命令代规则与 invocation 准入路径；每次涉及运行时 command
contribution 的操作从一个捕获视图新建私有 Picocli 解析对象，不跨线程或跨行共享 `CommandSpec`。REPL 在会话期间只创建一个 Engine/Registry 宿主，因此插件实例、
ClassLoader、Node sidecar、effects 和在途调用不会因每行命令重建。`plugins install/upgrade` 接受明确的
本地标准插件包路径并把内容复制到当前 profile 的不可变制品库；网络链接下载属于后续市场/来源适配层，
不伪装成本地安装。`tools list` 与 `tools invoke` 都经过当前不可变 `PublishedView`，调用携带同一
view revision；关闭和 JVM shutdown 会先取消 CLI 发起的在途调用，再由 Engine 排空受管资源。
工具成功与失败都使用带 `isError` 的判别式 JSON。成功包含有序 `content` 与可选
`structuredContent`；失败包含同样可直接展示的 `content` 以及稳定 `error.code/message`，调用方不需要
解析易变文案。例如：

```json
{"content":[{"type":"text","text":"saved"}],"isError":false,"structuredContent":{"revision":1},"viewRevision":"..."}
{"content":[{"type":"text","text":"Error: timed out"}],"error":{"code":"TIMEOUT","message":"timed out"},"isError":true,"viewRevision":"..."}
```

该形状由 `fibra-tool-api` 的成功产物与调用终态统一投影，可适配最新 MCP `2026-07-28`，但 CLI JSON
不是 MCP JSON-RPC response；`resultType`、`input_required` 和协议 `_meta` 由未来 MCP bridge 管理。
直接 `tools invoke` 失败仍以进程退出码 4 表达 CLI 业务失败，不为每个工具错误码再造一套退出码。

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
    kind: fibra.tool
    schemaVersion: 2
    method: echo
    descriptor:
      displayName: Echo
      description: Echo arguments from the Node sidecar
      inputSchema: { type: object }
      outputSchema: { type: object }
```

runtime 从 ArtifactStore 受管包的 payload 解析入口。宿主使用参数数组启动随模块发布的进程监督器，
再由监督器启动该入口，全程不经过 Shell。
runtime 内部的 `NodeSidecar` 只处理有界 JSON-RPC、逐请求 deadline/取消、心跳和异常退出；远端请求先登记为
调用 Scope 的资源，取消只影响该请求并等待原请求终态。取消宽限耗尽、协议故障、心跳失败或异常退出才
升级为实例级故障。`NodeProcessUnit` 负责一个可等待的受管进程范围，按“stdin EOF、软终止、强终止、
范围静默、目录清理”收口。POSIX 使用独立进程组，Windows 使用系统进程树终止后端；主动逃离受管范围
不属于本地 sidecar 的安全保证。Node 贡献和 Java 本地贡献登记到长期运行域的 `ContributionDirectory`，
只由 Engine 当前发布的 `PublishedRuntime` 对外调用，不向宿主公开 sidecar 或协议请求入口。

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

## 构建与验证

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

当前发布边界为 27 个 Maven 制品，其中包含 `fibra-cli-api`、正式 `fibra-cli` 和 12 个动态插件的 Java
payload JAR；这些制品已纳入发布、可复现和临时仓部署清单。仓库外消费者从 ZIP 启动器加载真实 Java
command 插件，验证执行、help 与停用后消失。完整 reactor 覆盖真实 JAR、真实 Node 进程、目标恢复、Spring、archetype、
架构边界和 JMH 编译门禁。

CLI 公共 API、全仓、可复现制品、空 Maven 仓消费者和解压启动门禁必须随发行边界变化统一重跑，不能用
较早提交或本地缓存结果替代当前发布证据。

## 深入阅读

- [公共 API 与嵌入入口](docs/api/README.md)
- [可运行示例](fibra-example/README.md)
- [正式插件角色与打包约束](fibra-plugins/README.md)
- [发布边界与 Maven Central 流程](docs/release.md)
- [vNext 权威架构与 `0.5.x` 维护边界](docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md)
- [行为验收账本](docs/superpowers/references/2026-09-11-behavior-verification-ledger.md)
- [Java Harness 接入场景](docs/superpowers/specs/2026-09-07-fibra-java-harness-integration.md)
- [DeepSeek Harness Java 公开架构分析](docs/superpowers/references/2026-09-14-deepseek-harness-java-analysis.md)

## 许可证

Fibra 使用 [Apache License 2.0](LICENSE)。Cordis 行为参考和其他依赖的归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
