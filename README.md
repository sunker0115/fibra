# Fibra

[![CI](https://github.com/sunker0115/fibra/actions/workflows/ci.yml/badge.svg)](https://github.com/sunker0115/fibra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Fibra 是面向 Java 21 宿主的通用插件底座。它把插件 package、期望状态、运行时执行、生命周期资源所有权、
贡献目录和管理控制面拆成稳定边界，可嵌入 Java 服务、Spring Boot 应用和正式 CLI 宿主。

当前开发版本为 `0.5.0-SNAPSHOT`。该版本采用新的 package、部署目标和 runtime SPI，不保留旧模型的兼容入口。
浏览器端执行器、Web 资源装载器、前端渲染框架绑定、连接层实现和产品会话恢复属于产品仓；Fibra 只发布
transport-neutral 的 client API 和协议契约。

## 快速开始

构建正式发行包需要 JDK 21、Maven 3.9.9、Node.js 20.20 或更高版本、pnpm 11.19.0、Python 3、`unzip`，以及当前
POSIX 目标平台的 `/bin/bash` 和 ripgrep 15.0.1。Node.js 和 ripgrep 路径可分别通过 `fibra.distribution.node` 与
`fibra.distribution.rg` Maven 属性覆盖。

```bash
mvn -pl fibra-distribution -am package

fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra --version
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra plugins list
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra tools list
fibra-distribution/target/fibra-0.5.0-SNAPSHOT/bin/fibra repl
```

`package` 同时生成可运行目录和 `fibra-0.5.0-SNAPSHOT-bin.zip`。发行目录包含 CLI、宿主依赖、12 个标准
Java 插件 package，以及目标平台使用的 Node.js、ripgrep 和 Bash 启动代理。

默认 profile 使用：

- `config/profiles/default.yaml`：期望条目树；
- `config/profiles/default.packages.yaml`：相对 `plugins/` 的完整 package 列表；
- `data/profiles/default/`：不可变 package、部署目标和审计数据。

首次启动从两份 profile 输入建立完整目标；之后从已保存目标恢复。修改输入后执行 `fibra apply`，重启不会
用目录现状静默覆盖已确认目标。

## 架构

```text
管理请求 / CLI / Spring Boot
            |
      PluginRegistry
            |
      FibraEngine             唯一串行 command lane
       |    |    |
       |    |    +-- PublishedRuntime：不可变事实与带 fence 调用
       |    +------- DeploymentTargetStore：完整 durable target
       +------------ PluginPackageStore：不可变逻辑 package
            |
   RuntimeProvider -> RuntimeDriver -> generation -> execution unit
            |
      Java / Node / 外部 runtime provider
            |
      RuntimeDomain + ContributionDirectory
```

一次 `ApplyDeployment` 原子提交完整 package selections、raw desired graph 和 `ConfigContextSnapshot`。
Engine 先编译并按 runtime slice prepare/validate/seal 完整 candidate，再以 CAS 保存新的 `DeploymentTarget`，
随后原子 promote、关闭旧准入、drain/stop 旧 units、activate 新 units，最后 retire 旧资源并发布实际观察结果。
保存前不会启动执行；运行时故障不会伪装成目标未保存；重启只从已确认的完整目标和精确 package revision
恢复。

模块职责：

- `fibra-api`、`fibra-core`：Scope、插件、服务、事件、effect 和唯一生命周期所有权实现；
- `fibra-config`：期望状态采集、校验、条件求值和配置绑定；
- `fibra-artifact`：`PluginPackage`、`PluginPackageStore`、内容摘要和 package revision；
- `fibra-engine`：完整目标、runtime SPI、串行变更、观察事实和 `PublishedRuntime`；
- `fibra-runtime-java`、`fibra-runtime-node`：Java 与 Node 的 `RuntimeProvider` 实现；
- `fibra-bridge`：贡献目录、调用适配、注册身份和排空；
- `fibra-registry`：安装、升级、启停、部署、查询和审计用例；
- `fibra-cli-api`、`fibra-cli`：CLI 契约、profile 宿主、动态命令和 REPL；
- `fibra-spring`、`fibra-spring-boot-starter`：显式宿主服务桥接和默认组合；
- `fibra-client-protocol`：Java 侧、传输中立的 client wire value 与 codec；
- `client/packages/client-api`、`client/packages/client-protocol`：正式 npm client 契约；
- `fibra-plugin-archetype`：独立 Java 插件工程骨架；
- `fibra-plugins`：fs、subprocess、shell、storage 及 tool consumer 的正式插件产品。

## 插件 package

安装、升级、持久化和恢复的原子单位是逻辑目录 package。根目录必须包含唯一 `fibra-package.yaml`：

```yaml
format: 1
id: example-plugin
version: 1.0.0
facets:
  - id: host
    role: host
    runtime: java
    target: host
    payload: host/main.jar
    dependencies: []
    capabilities: []
  - id: client
    role: client
    runtime: client
    target: client:web
    payload: client
    dependencies:
      - pluginId: example-contract
        facetId: host
    capabilities:
      - client.module
```

一个 package 可声明多个 facet。每个 facet 独立声明 `role`、`runtime`、`target`、包内 `payload`、精确 facet
依赖和所需宿主能力；逻辑插件身份与版本只在 package 根声明一次。payload 必须位于 package 真实目录内，
package 禁止符号链接。`PluginPackage` 对受控文件树计算内容身份，`PluginPackageStore` 以完整 package 为事务
单位保存不可变副本。

Java facet 的 payload 是主 JAR。JAR 中的 `META-INF/fibra/plugin.yaml` 只描述 Java 本地定义和入口；
contract-only JAR 可使用空对象，不复制 package 身份、版本和 facet 依赖。Node facet 的 payload 目录包含
Node 本地 descriptor 和入口。runtime 只能从 store 管理的 package 读取 payload，不能从安装候选路径执行。

## Runtime SPI

宿主通过 `FibraEngine.Builder.runtimeProvider(...)` 注册 runtime。一个 `RuntimeProvider` 提供唯一 runtime id、
contract identity、内建 package，并作为不可变、可复用 factory 为每次 Host 创建一个全新、独立的长期
`RuntimeDriver`。provider 不保存 Host/driver 可变状态，Engine 独占 driver 与 package/target stores 的关闭权。
driver 负责：

1. 探测和检查 facet；
2. 从完整 `RuntimeTargetSlice` 创建 candidate；
3. 准备 candidate，并以已编译 slice 封存 generation；
4. 按 `ExecutionUnitKey` 返回 execution unit；
5. 在同一生命周期 lane 上 reconcile、关闭准入、drain、stop 和采样观察事实。

Engine 只理解这组中立协议，不按 Java、Node 或 browser 类型分支。Java 和 Node provider 在本仓实现；产品侧
可基于公开 SPI 实现其他 runtime。浏览器端代码执行、页面渲染绑定、连接载体和产品重连状态机不得进入
Fibra 正式发布物。

## 身份与调用

`desiredEntryId` 是同一 facet 多实例化时的稳定期望身份；package revision、runtime instance、unit target
revision 和 lifecycle operation id 分别约束制品、运行单元代次和单次操作。依赖变化会进入受影响闭包，即使
某个 facet 自身 payload 未变化，也不会错误保留基于旧依赖准备的 generation。

贡献的业务身份是 `ContributionId(providerInstanceId, localName)`，可跨宿主重启保持稳定。调用准入必须同时
携带捕获时的 `viewRevision` 和 `registrationIdentity`；后两者是进程内 fence，重启或重新注册后不能复用。
因此同名能力更新不会把旧调用静默路由到新 handler。

## Client 契约边界

Fibra 正式发布两份 npm 包：

- `@sstlfsj/fibra-client-api`：client scope、module 生命周期和宿主调用门面；
- `@sstlfsj/fibra-client-protocol`：协议消息、身份 fence、assignment、resource descriptor 和严格 codec。

Java 侧的 `fibra-client-protocol` 与 TypeScript 协议包共享版本 1 fixture。资源只以相对路径、SHA-256 digest
和十进制 byte length 描述；协议不指定 WebSocket、IPC 或其他连接方式，也不提供浏览器端执行器、Web
资源装载器或前端渲染框架绑定。产品仓负责获取并复核资源、建立 session、选择连接方式、执行模块和恢复
产品状态。

每个 `Assignment` 显式携带 `desiredEntryId`、`definitionId`、`unitTargetRevision`、`runtimeInstanceId` 和
resolved `config`。产品 runner 从 entry module 的 `definitions[]` 精确选择一个 definition，并为每个
assignment 调用一次 `create(ClientInstanceContext)`；返回 module 的生命周期方法无参数。Fibra 不提供全局
definition registry、实例缓存、loader 或 runner。

## 正式 CLI 与 Spring Boot

CLI 的主要命令面：

```text
fibra [--home DIR] [--profile NAME] plugins list
fibra [全局选项] plugins install|upgrade PACKAGE
fibra [全局选项] plugins uninstall PLUGIN_ID
fibra [全局选项] plugins enable|disable INSTANCE_ID
fibra [全局选项] tools list
fibra [全局选项] tools invoke PROVIDER LOCAL_NAME --input JSON
fibra [全局选项] apply
fibra [全局选项] repl
```

动态命令和工具调用都从当前 `PublishedView` 捕获 descriptor、view revision 和 registration identity，再经过
同一准入路径执行。长期 REPL 只持有一个 Engine/Registry 宿主，不按输入行重建 runtime。

Spring Boot 引入 `fibra-spring-boot-starter` 后，默认建立 `PluginPackageStore`、
`FileDeploymentTargetStore`、Java/Node provider、Engine、Registry、审计仓库和 `PublishedRuntime`。
`fibra.storage-root` 默认为 `.fibra`。使用 `@FibraService` 显式导出宿主 Bean；动态插件对象不会被 Spring
扫描或托管。

## 构建与验证

```bash
mvn clean verify

corepack enable
corepack prepare pnpm@11.19.0 --activate
pnpm --dir client install --frozen-lockfile
scripts/verify-client-packages.sh
scripts/verify-reproducible-release.sh
scripts/verify-architecture-boundaries.sh
```

正式发布边界为 28 个 Maven 制品和 2 个 npm 制品。门禁覆盖公共签名、Java/Node 真实执行、package 与 durable
target 恢复、Spring、archetype、独立 Maven/npm 消费者、发行 ZIP、可复现性和正式归档内容。详细发布清单与
流程见 [发布与构建基线](docs/release.md)。`verify-distribution.sh` 会创建唯一一次全新 Maven 消费仓库，本地开发
与 PR 事件不执行该空仓门禁，每次 push 由 GitHub Actions 自动执行。

## 深入阅读

- [公共 API 与嵌入入口](docs/api/README.md)
- [正式插件角色与打包约束](fibra-plugins/README.md)
- [可运行示例](fibra-example/README.md)
- [Client Foundation 权威架构](docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md)
- [发布与构建基线](docs/release.md)
- [贡献指南](CONTRIBUTING.md)
