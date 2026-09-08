# Fibra vNext 最终目标架构

日期：2026-09-07

状态：目标架构已确认，尚未实现。实现完成前，`2026-08-21-fibra-kernel-architecture.md` 与
`2026-08-24-fibra-engine-architecture.md` 仍描述当前 `0.4.x` 行为；vNext 实现不得为旧公开 API、
旧模块名或旧配置入口保留兼容层。

Java Harness 只是宿主接入参考场景之一，见
[`2026-09-07-fibra-java-harness-integration.md`](./2026-09-07-fibra-java-harness-integration.md)。该场景用于
检验内置 definition、程序化 Engine command、snapshot stream、贡献桥接与宿主服务所有权是否足够
通用，不定义 Fibra 的产品方向或优先级；Agent、Tool、Skill 等业务类型始终留在场景适配层。

## 1. 结论

Fibra 不迁移到 cordis4j 的同步内核，也不在现有实现上继续增加便利重载。最终方案是一次明确的
架构重写：

1. 保留 Fibra 已验证的 Cordis 异步语义、单线程生命周期收敛、调用者所有权、完整事件模型和
   托管 Engine；这些能力是目标产品的正确性边界，不是实现包袱。
2. 吸收 cordis4j 的独立可关闭子作用域、纯配置组合模型、显式插件入口、`find` 风格查询、
   编译期注入和 Spring 自动导出思路，但不复制它的具体 API 与同步实现。
3. 删除当前“配置 loader 依赖 PF4J loader、Engine 再协调两个可变 loader”的线性结构。
   配置与制品改为两个只读输入端口，只有 Engine 的事务执行器能够修改运行态。
4. vNext 不再引入 PF4J。通用制品身份、校验、安装和事务进入 `fibra-artifact`；Java manifest、依赖图、
   版本约束和依赖感知 ClassLoader 进入 `fibra-runtime-java`。二者与 Engine generation/transaction
   使用同一状态模型；不复制 PF4J manager、插件状态机、extension 或生命周期 API。
5. Java Native 与 Node sidecar 是两个一等运行时实现，共同接入 Engine 定义的窄运行时端口；Engine
   不按 `runtimeType` 分支，也不接触 ClassLoader、Process 或 JSON-RPC 句柄。
6. 本期同时交付通用 `fibra-registry` 与 `fibra-bridge`：前者提供安装、版本、期望状态、审计和制品记录
   的易用控制面，后者提供贡献身份、Scope 归属、注册、撤销和调用适配机制。二者都不得引入 Tool、
   Agent、Skill 等场景语义。
7. 重画公共 API，而不是兼容旧 API：root 不再伪装成 uid 为 0 的 Fibra；`Context` 不再承载所有
   子系统的几十个重载；`get(key, boolean)`、`Mono.just(registration)`、loader 的一步式变更入口和
   分散事务门全部删除。

这不是从 cordis4j 与现有 Fibra 中选择一个，而是以 Cordis/DeepSeek 的真实语义为约束，选择两者中
更合理的边界后建立唯一运行模型。

## 2. 复核基线

本设计基于以下源码，而不是 README 功能列表：

- Fibra：`02fe4b5dcd7b1052203d2027c9808931dceddb65`。
- cordis4j：`6cfd56e684fb403ded952afc09eddb49a5228494`，设计契约 v2.13。
- DeepSeek Harness：`b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`，内置
  `@deepseek-ai/cordis` 版本 `4.0.2`。
- cordiverse/cordis：本地提交 `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4`。

DeepSeek 从旧基线 `141eb6f` 更新后，`vendor/cordis/src` 的 tree 哈希仍为
`745dea6282ba11f41fa0eb87171a4a56afe9730f`，即 `4.0.1 -> 4.0.2` 没有内核源码变化。
本轮新增的相关实现变化只在 `vendor/loader/src/internal.ts`：按真实方法形状而不是 Node 主版本判断
ESM loader v1/v2。该经验应进入制品适配层的兼容探测，不改变 Fibra core。

## 3. 两种现有实现的判断

| 维度 | Fibra 0.4.x | cordis4j 0.4.x | vNext 选择 |
|---|---|---|---|
| 生命周期执行 | root 共享 Reactor 单线程 dispatcher | 多把锁保护注册表，用户代码在锁外执行 | 保留单写者；所有状态提交回到同一生命周期 lane |
| 异步 effect | `Publisher`、逐项 `request(1)`、在途元素完成 | 同步 LIFO；异步另设 `pluginAsync/spawn` | 保留统一 Publisher 模型，不建立同步/异步两套生命周期 |
| 顶层卸载 | effect 内串行 LIFO，顶层 effect 并发 all-settled | 整个 domain 全串行 LIFO | 保留 Cordis/DeepSeek 语义，不吸收偏差 |
| 激活时序 | 至少让出一个 lifecycle tick | 满足时同步执行到落地 | 保留异步边界，避免重入语义漂移 |
| 失败恢复 | `update/restart` 可清错并重新收敛 | 失败 Fiber 终态，只能重新声明 | 保留可恢复实例 |
| 事件 | 共享 hook 表，含 `emit/parallel/serial/bail/waterfall` 与 target | 每 Context 独立 bus，仅同步且向父冒泡 | 保留 Fibra 完整模型 |
| 服务身份 | 稳定字符串名 + Java 类型校验 + isolate token | `Class + qualifier`，realm 与 qualifier 共用命名空间 | 保留字符串身份，支持跨 ClassLoader 契约与配置先行解析 |
| 调用者所有权 | `ServiceRef/InvocationContext` 显式传递 | 不建模服务调用者作用域 | 保留 Fibra 语义 |
| Context 子树 | 派生视图共享当前 Fiber，子 Context 不能独立关闭 | `fork()` 是独立可关闭资源子树 | 吸收独立 Scope，但与不可变 Context 视图分离 |
| 配置组合 | 功能完整，但 config loader 直接依赖并驱动 PF4J loader | core 内 `ComponentSpec` + 外部格式桥 | 吸收纯模型/解析边界，不把文件与 include 放进 core |
| 字节码装载 | PF4J manager + 自研包校验、事务与恢复 | 逐 JAR `URLClassLoader`，无依赖解析 | Fibra 原生 artifact graph + 依赖感知 ClassSpace，不引入 PF4J |
| 托管运行 | watcher、重试、readiness、journal、联合部署 | 手工 `Loader/HotReloadingLoader` | 保留 Engine，并收敛为唯一写入口 |
| Spring | 显式 bridge，安全但偏手工 | 注解 Bean 自动导出，方便但按具体类猜 key | 以显式 key/type 的注解构建在 bridge 之上 |

### 3.1 不采用 cordis4j 内核的原因

cordis4j 已主动声明至少四项与 Cordis 主线的可观测偏差：同步激活、顶层 effect 全串行卸载、失败
Fiber 不可更新恢复、事件只在 Context 父链可见。它们让实现和示例更短，但会改变 DeepSeek Harness
依赖的运行语义。

其并发模型也不适合作为最终内核：`provide/on/intercept/fork` 等操作先产生状态，再向 ambient scope
登记 disposer；若登记与 `Context.dispose()` 竞态失败，cordis4j 只对
`plugin/pluginAsync/inject/spawn` 做接管，明确放弃其余“state-only”产物。对于实现了
`Service.start/stop` 的服务，这不仅是对象延迟回收，还是已经启动的外部资源可能没有 `stop()` 的
所有权缺口。Fibra 的单写者提交可以让“产生状态 + 登记逆操作”成为同一个不可分割动作，不需要逐
API 补竞态接管。

cordis4j 的逐 JAR `URLClassLoader` 和扫描所有 class 的入口发现适合演示型 HMR，不具备 Fibra 需要的
插件依赖图、包结构预检、候选 ClassSpace、磁盘事务和崩溃恢复。因此它不能替换制品层。这里保留的
是能力与行为，不是 PF4J 实现本身。

### 3.2 不能原样保留 Fibra 0.4.x 的原因

Fibra 的语义方向正确，但公共面与模块协作方式已经暴露出结构性成本：

- `Context` 同时承载服务、effect、插件、事件、反射、日志和关闭，重载数量过多；
- `Plugin<C>` 用“Publisher 发出 disposer”同时表达启动完成与资源所有权，导致最常见的同步注册也要
  写 `Mono.just(registration)`；
- root 被建模为特殊 Fibra，`dispose()` 等于 restart，概念不直观；
- `get(key, boolean)` 用布尔值隐藏 ACTIVE/非 ACTIVE 查询差异；
- `fibra-loader-config -> fibra-loader-pf4j` 使配置解析、制品解析和运行态修改无法独立；
- 两个 loader 各自拥有 prepare/commit/rollback 和逻辑事务门，Engine 再组合它们，增加了状态空间；
- 插件 SPI 暴露 PF4J `ExtensionPoint` 与 `@Extension`，使装载实现泄漏到插件作者；artifact 层又用
  空 PF4J Plugin 和 STARTED/STOPPED 状态包装真实 Fibra PluginInstance，形成第二状态机；
- 十个发布 artifact 中存在仅为当前依赖链和发布结构服务的拆分，用户选择成本高于实际职责数量。

继续添加便利方法只会扩大这些问题，因此 vNext 直接重写边界。

### 3.3 为什么 vNext 不再引入 PF4J

源码复核表明，Fibra 0.4.x 已自行实现包布局与安全校验、摘要、事务 journal、崩溃恢复、受影响闭包、
entrypoint 校验、配置重建和真实 PluginInstance 生命周期。PF4J manager 在其中主要充当可变 descriptor/
ClassLoader 容器，并用空 `org.pf4j.Plugin` 的 STARTED/STOPPED 包装 artifact；这与 Fibra core 和 Engine
形成重叠状态。

| 当前借用的 PF4J 能力 | vNext 落点 |
|---|---|
| properties descriptor | Java 运行时的单一 `JavaPluginManifest` |
| extension index/processor | 删除，manifest 显式 entrypoint |
| SemVer 表达式 | `VersionConstraint` 私有边界，可委托专门 SemVer 库 |
| 缺失依赖、版本、环和拓扑序 | `fibra-runtime-java` 的纯函数式 `JavaArtifactGraph` |
| plugin/dependency/resource 类加载 | `PluginClassLoader + JavaClassSpace` |
| repository scan | 通用 `ArtifactStore` + Java runtime adapter |
| PluginWrapper 与状态机 | 删除；artifact generation 与 PluginInstance 各有唯一事实 |
| start/stop/unload manager | Engine ChangeSet 提交、core Scope dispose、JavaClassSpace retire |

因此这是替换不匹配的抽象，而不是为减少一个依赖重写完整插件框架。实现只覆盖表中已被验收场景要求
的行为，并用 PF4J 现有行为测试、真实 JAR 测试和 Fibra 新契约三方对拍；不得复制 PF4J 源码后改名。

### 3.4 Registry 与 Bridge 的方案取舍

| 方案 | 架构与业务结果 | 判断 |
|---|---|---|
| 每个宿主自建 registry/bridge | Fibra 模块最少，但 Java/Node 安装状态、命名、撤销和 drain 会在 Harness、IDE、服务宿主中重复 | 拒绝；通用闭环不完整 |
| 全部并入 Engine | 调用入口看似少，但 Engine 同时承担制品控制面、业务贡献目录和运行时事务，形成新的总管对象 | 拒绝；高耦合且难以独立使用 |
| PF4J 风格 manager/extension registry | Java JAR 体验成熟，但 artifact、ClassLoader、extension 和状态集中在可变 manager，无法自然覆盖 Node 与 Scope 所有权 | 只吸收易用操作名，不吸收状态模型 |
| 独立 Registry + Bridge + runtime adapters | Registry 只做控制面，Bridge 只做贡献面，Engine 只做事实与事务，Java/Node 各自高内聚 | 采用 |

这里吸收 Cordis/Fibra 已验证的“注册即返回可撤销所有权”和 Scope 自动清理语义，也吸收 Harness 草图中
registry/runtime/bridge 的职责分离；但不会照搬 Harness 的 Tool 类型或 PF4J 的 Java-only manager。

### 3.5 为什么本期不复刻 DeepSeek Client Plugin System

DeepSeek Harness `b0a7d2c` 的前端插件不是由 Node sidecar 执行。一个 npm package 可用
`package.json.dsh.client` 与 `exports["./client"]` 声明 Client 面；Host 的 `ClientModuleRegistry` 扫描活动
Loader entry，组成带 revision 的 `WebBootGraph` 并交付 bundle；Browser 再创建独立的 Cordis
`Context + Loader`，运行第二棵 plugin tree。其 HMR 还负责 invalidate、prefetch、旧 fiber drain、样式
清理和 refresh。这是一套完整浏览器插件平台，不是增加一种制品后缀。

| 维度 | 完整复刻的收益 | 对 Fibra 当前阶段的代价 |
|---|---|---|
| 产品体验 | 一个产品包可同时贡献 Host 能力和 UI，配置与版本一致 | Fibra 当前没有真实 UI 宿主、slot 契约或前端插件作者需求可验收 |
| 架构 | 前后端都具备 Scope、依赖激活和可撤销贡献 | 必须维护服务端与每个浏览器两棵生命周期树，不能共享一个 ACTIVE 事实 |
| 安全 | 受信任插件可共享 React/Cordis/宿主 SDK，体验自然 | 同源脚本拥有页面权限；这不是隔离，非可信插件还要另建 iframe/origin 协议 |
| 一致性 | Host 可发布不可变 client graph 与 content revision | Engine 只能原子发布 graph，无法原子保证所有在线浏览器都已成功执行 |
| 工程成本 | 支持动态 UI、依赖级联和 HMR | 新增 TypeScript/npm 构建、模块表、缓存、source map、HMR、浏览器 E2E 和版本矩阵 |
| 通用性 | 非常适合 DeepSeek 自己的 React/Cordis Web/Desktop 产品 | 对无 UI 的 Java 服务、CLI、工作流宿主是无关复杂度，并会反向塑造通用 manifest |

因此本期不复制其 lazy-CJS loader、combo bundle、`window.__DSH_BOOT__`、client HMR 或 UI slot 系统，也不在
通用 artifact/Engine 契约中预埋 `client` 字段。当前只保留已经被 Java/Node 两个真实运行时证明需要的
`PluginRuntimeAdapter`、多制品 deployment、Registry revision 与 ContributionBridge。

未来 Java Harness 出现真实 Web/Desktop 宿主后，应在独立 `fibra-web` 集成中重新做一次决策：若插件均
为受信任代码，可吸收 DeepSeek 的 dual-face package、不可变 client graph、浏览器 Cordis runtime 和
按 fiber 撤销贡献；若需要第三方非可信 UI，则优先 sandboxed iframe/origin + message RPC。两种安全模型
不能在没有产品约束时合并成一个“通用 Web runtime”。

## 4. 最终模块与依赖方向

模块拆分以高内聚、低耦合为硬约束，而不是按类数量或未来设想拆包：每个发布模块只有一个主要变化
原因；端口由消费该能力的内层模块定义，外部 adapter 向端口依赖；只有应用 composition root 可以
同时依赖多个模块，且其中不得出现业务逻辑。

插件管理不是一条上下含义混杂的流水线，而是两条在 PluginInstance identity/revision 处对齐的单向流：

```text
控制面：宿主用例 -> PluginRegistry -> Fibra Engine -> Java/Node runtime
贡献面：Java/Node runtime -> ContributionBridge -> 宿主能力目录 -> 业务消费者
```

Registry 不注册工具，Bridge 不安装制品，runtime 不保存期望状态，Engine 不解释业务贡献。四个边界
分别只有一个变化原因。

下图中 `A -> B` 表示 A 依赖 B：

```text
fibra-core                -> fibra-api
fibra-config              -> fibra-api
fibra-artifact            -> fibra-api
fibra-bridge              -> fibra-api
fibra-engine              -> fibra-core + fibra-config + fibra-artifact
fibra-runtime-java        -> fibra-engine + fibra-artifact + fibra-api
fibra-runtime-node        -> fibra-engine + fibra-artifact + fibra-bridge + fibra-api
fibra-registry            -> fibra-engine
fibra-spring              -> fibra-api
fibra-spring-boot-starter -> fibra-spring + fibra-registry + fibra-runtime-java

fibra-plugin-archetype -> 只生成依赖 fibra-api 的独立插件工程

非发布模块：fibra-parity-tests、fibra-benchmarks、fibra-example、verification
```

`fibra-config` 与 `fibra-artifact` 互不依赖。配置模块定义最小只读 `PluginDefinitionResolver` 输入端口，
Engine 把 built-in catalog 与所有 runtime catalog 的合并视图适配到该端口；端口只暴露编译 desired
graph 所需的名称、配置类型和依赖契约，不暴露 ClassLoader、进程、安装目录或协议对象。程序注册的
内置 definition 与各运行时产生的 definition 使用同一 PluginInstance 生命周期，内置能力不需要先
打成制品。

依赖约束必须由构建测试固定：

| 模块 | 唯一主要职责 | 禁止依赖 |
|---|---|---|
| `fibra-api` | 稳定公开契约 | 其他所有 Fibra 模块、装载框架、Spring、具体宿主 |
| `fibra-core` | 运行时状态与生命周期语义 | config、artifact、engine、Spring、文件格式 |
| `fibra-config` | desired state 模型、repository 契约与编译 | artifact、core 实现、Engine、装载框架 |
| `fibra-artifact` | 运行时中立的制品身份、校验、安装存储与磁盘事务 | ClassLoader、Node、config、Engine、Spring、宿主业务 |
| `fibra-bridge` | 通用贡献身份、Scope 注册、撤销、快照与调用适配端口 | Tool/Agent/Skill、具体运行时、Engine、Spring |
| `fibra-engine` | runtime 端口、command、ChangeSet、事务、snapshot 与收敛 | 具体 runtime、Spring、Harness 或其他场景类型 |
| `fibra-runtime-java` | Java JAR、manifest、依赖图与隔离 ClassSpace | Node、config、Spring、宿主业务 |
| `fibra-runtime-node` | Node package、sidecar 与 JSON-RPC 远程端点 | Java ClassLoader、config、Spring、Tool/Agent/Skill |
| `fibra-registry` | 面向宿主的插件控制面与审计查询门面 | 具体 runtime、Spring、宿主业务 |
| `fibra-spring` | Spring 对通用 Fibra API 的适配 | artifact/config 内部实现、宿主业务 |
| `fibra-spring-boot-starter` | 自动装配 composition root | 业务规则与场景协议 |

禁止新增 `common/shared/utils` 发布模块承接边界不清的代码。真正跨模块且属于公开语义的类型进入
`fibra-api`；只被一个模块使用的类型留在模块内部；纯实现复用不足以成为新模块。

### 4.1 `fibra-api`

只包含宿主与插件共享的稳定契约：`ServiceKey`、`Disposable`、`Context`、`Scope`、`Plugin`、
`PluginDefinition`、`PluginInstance` 只读状态、事件 key/options、调用上下文与插件入口 SPI。

Java 插件入口 SPI 位于本模块；标准 JAR 在 `META-INF/fibra/plugin.yaml` 中显式声明唯一入口类，并由
`fibra-runtime-java` 按该类名实例化。禁止扫描整个 JAR 猜实现，不生成 extension index，也不维护第二份
装载框架 descriptor。

### 4.2 `fibra-core`

包含唯一运行时实现：root runtime、生命周期 dispatcher、Scope 树、Fiber 状态机、服务/事件/属性
注册表和 effect 协议。它不知道配置文件、JAR、ClassLoader、watcher、deployment 或 Spring。

Reactor 与 SLF4J 继续是 core 的有意依赖。零依赖不是目标；统一异步语义和可验证的完成边界优先。

### 4.3 `fibra-config`

包含不可变 desired state 模型、YAML/JSON 解析、include、patch、限制、写回事务与纯编译器。文件文档
或程序化 repository snapshot 和只读 `PluginCatalog` 一起输出：

```text
DesiredSourceSnapshot + DesiredGraph + diagnostics
```

每个 Engine 必须配置且只能配置一个权威 `DesiredStateRepository`。文件、数据库和内存实现只是不同
adapter：文件 adapter 接受 watcher dirty signal，数据库/内存 adapter 接受带 expected revision 的
程序化写入；二者不能同时作为真源。deployment 也通过该 repository 的事务参与者提交，不建立旁路。

它不创建/更新/销毁 PluginInstance，不依赖 artifact 实现，也不拥有运行时事务。cordis4j 的
`ComponentSpec + EntryMeta + ComponentResolver` 分层思路在这里吸收，但类型、配置、依赖、isolate 与
intercept 必须在一个 `DesiredEntry` 中完成解析，不保留两个需要靠字符串 id 再拼接的半成品结果。

Fibra 配置继续只接受数据，不执行 `!!js`、SpEL、JEXL 或反射表达式。Cordis/dsh 配置兼容若有真实
迁移需求，应作为独立导入工具把源格式转换成 Fibra 文档，不能进入运行时核心路径。

### 4.4 `fibra-artifact`

只包含运行时中立的制品事实与磁盘操作：`ArtifactId`、runtime id、版本、checksum、不可变安装目录、
staging、quarantine、retire 和可恢复的磁盘事务。它不解析 Java/Node 专属 manifest，不建立 ClassLoader
依赖图，也不启动进程。

安装命令必须显式携带 runtime id；禁止根据 `.jar`、`.tgz`、目录内容或入口文件猜运行时。具体 runtime
adapter 在 prepare 阶段解析自己的唯一 manifest，并把规范化元数据和摘要写入 `ArtifactRecord`。需要
同时交付多个制品与 desired state 的 deployment 可以使用外层部署包，但每种运行时只能有一种 Fibra
原生制品格式。

artifact 只有 staged/installed/quarantined/retired 等制品事实；业务 ACTIVE/FAILED 只属于 core
`PluginInstance`。旧制品必须等整个事务成功、相关实例排空且运行时 generation 已 retire 后才能回收。

### 4.5 两个一等运行时

Engine 定义自己消费的窄 `PluginRuntimeAdapter` 端口，并按稳定 `RuntimeId` 注册实现。端口只允许：

- inspect 候选制品并返回规范化元数据与诊断；
- prepare 当前 generation 到候选 generation 的运行时变更；
- 提供候选 `PluginCatalog` 和 ChangeSet 的 commit/rollback/retire 参与者；
- 把运行时私有失败投影为通用 phase/cause，不泄漏可变句柄。

端口不得返回 `ClassLoader`、`Process`、RPC channel 等私有资源，Engine 也不得用 `if (runtimeType)` 解释
具体运行时。安装时遇到未注册、重复或不匹配的 runtime id 直接拒绝。Java 与 Node 已构成两个真实
消费者，因此这个端口是当前需求形成的边界，不是为假想扩展预埋的 driver 框架。

`fibra-runtime-java` 负责可信进程内插件：

- 标准 JAR 的唯一入口为 `META-INF/fibra/plugin.yaml`，显式声明 entrypoint；
- `JavaArtifactGraph` 纯计算缺失依赖、版本约束、环、稳定拓扑序和受影响闭包；
- `PluginClassLoader` 负责单个 JAR 的类与资源查找，`JavaClassSpace` 持有完整 immutable generation；
- JDK 与宿主明确导出的 API 包由过滤后的 parent 加载，插件自身优先，再按 manifest 顺序查询直接依赖；
- 停用先撤销贡献并排空调用，再 dispose Scope，最后按依赖逆序关闭并验证 ClassLoader 可回收。

版本表达式通过窄 `Version/VersionConstraint` 边界实现，可以委托专门 SemVer 库，但不暴露第三方版本
类型。Java 制品依赖图只决定 JAR 链接与 ClassSpace 顺序；运行时服务依赖仍由
`PluginDefinition.requires/provides` 描述，二者不能混成一张图。

`fibra-runtime-node` 负责进程外插件：

- Node 包根目录只接受一个 Fibra 原生 `fibra-plugin.yaml`，显式声明受限入口和协议版本；
- 每个 `PluginInstance` 默认拥有一个 sidecar、一个 RPC session 和其全部远程 endpoint；
- 通过参数数组启动 `node`，禁止 Shell 拼接；canonical entrypoint 必须位于不可变安装目录内；
- JSON-RPC 明确定义握手、消息上限、超时、取消、心跳、stderr 日志和完整进程树终止；
- sidecar 退出时先撤销远程贡献，再使实例进入可诊断失败并交给 Engine 恢复策略；
- 只发布通用 remote endpoint，不识别 Tool、Agent、Skill 或宿主权限模型。

`.codex-plugin/plugin.json`、第三方 `package.json` 字段或 `cordis.yml` 若需支持，只能由场景导入器在安装前
转换为 Fibra 原生包；不得在 runtime 中保留多入口兼容分支。ClassLoader 隔离和 sidecar 隔离都不自动
等于安全沙箱；权限、凭据与操作系统级隔离由宿主通过显式 port 提供。

前端插件可以被同一控制面管理，但不属于 Node sidecar。未来出现真实浏览器/WebView 宿主后，Web
集成可以复用 Artifact、Registry、Engine revision 和 ContributionBridge 的边界思想；实际插件必须在
浏览器自己的生命周期树中运行，不能伪装成服务端 `PluginInstance`。

若目标是 DeepSeek Harness 式受信任 UI，应由独立 `fibra-web` 集成提供 Host client-graph composer 与
Browser Cordis runtime；若目标是第三方非可信 UI，应改用 sandboxed iframe/origin + message RPC。当前
阶段不在二者之间猜测，不加入 Web runtime、client manifest、UI slot 或浏览器状态字段。

### 4.6 `fibra-engine`

是唯一托管写入口，独占 `FibraRuntime`、runtime adapter registry、artifact repository、desired state
repository、source、command loop、journal 和当前 `EngineSnapshot`。任何自动 reconcile、手工
deployment、程序化 desired state、配置写回与关闭都进入同一个串行命令域；低层模块不再提供可以
绕过 Engine 修改托管运行态的一步式 API。

同一 ChangeSet 可以包含 Java generation、Node generation、desired graph 和 core 实例变化，但 Engine
只协调各参与者的 prepare/commit/rollback/retire，不接管运行时私有资源。`EngineSnapshot` 为每个制品
和实例记录 runtime id、revision、通用状态与失败阶段，不保存可变 ClassLoader 或进程句柄。

### 4.7 `fibra-registry`

这是面向宿主的易用插件控制面，而不是第二个插件管理器或第二套状态机。它组合 `EngineCommand`、
`EngineSnapshot` 和 Engine 已持有的 repository port，提供语义明确的：

```text
install / upgrade / enable / disable / uninstall
get / list / watch / history
```

`PluginRegistry` 分别展示三类事实，禁止压成一个含糊 `status`：

- artifact：版本、digest、runtime、安装和 retire 状态；
- desired：是否启用、目标版本、配置 revision 与 expected revision；
- observed：实例状态、活动 generation、失败 phase/cause 与 Engine revision。

写操作全部翻译为 `EngineCommand` 并等待命令结果；查询和 watch 只投影 `EngineSnapshot`。审计是已接受
命令与结果的 append-only 记录，通过 `PluginAuditRepository` port 持久化，不参与运行态判断，也不能
反向驱动 reconcile。本期提供内存 adapter 供嵌入/测试、append-only 文件 adapter 供独立运行；数据库
由宿主实现同一 port。文件、数据库或内存仍只能选择一个权威 desired repository。

### 4.8 `fibra-bridge`

这是通用贡献面，不是 Harness ToolRegistry。它提供：

- `ContributionId(providerInstanceId, localName)`，保证来源隔离和稳定身份；
- 宿主声明的 `ContributionKind<D, I, O>`，由每种场景定义 descriptor、输入和输出契约；
- 与当前 PluginInstance Scope 原子绑定的 register/revoke；
- 按 revision 发布的不可变 contribution snapshot；
- 携带 `InvocationContext` 的统一调用路由和 drain 边界；
- `ContributionAdapter`，把同一贡献投影到 Tool、命令、路由、编解码器等宿主目录。

Fibra 不规定 `plugin__<pluginId>__<name>` 之类外部名称。场景 adapter 根据 `ContributionId` 进行唯一
渲染，并在注册时完成冲突检查。Java 本地 handler 与 Node remote endpoint 都先适配成同一种场景贡献，
再进入一个目录；撤销贡献、拒绝新调用和排空在途调用必须先于实例 Scope/运行时资源关闭。

贡献 descriptor 不强制统一成 JSON：Java 场景可以使用强类型契约；需要跨进程的 kind 必须由该 kind
提供显式 codec 和 schema version。这样不会为了 Node Bridge 把所有 Java 调用都降级为字符串协议。

### 4.9 Spring 与工具

`fibra-spring` 提供程序化 `FibraServiceBridge`，并可提供显式的
`@FibraService(name = "...", type = SomeApi.class)`。注解必须同时声明稳定服务名与契约类型，不能按
Bean 名或实现类猜 key；注册和撤销都委托 bridge，并等待 Fibra 的异步完成边界。

`fibra-spring-boot-starter` 直接包含自动配置与配置元数据，默认装配 Registry、Engine 与 Java runtime，
不再为“无源码 starter”单独发布一个 autoconfigure artifact。Node runtime 是显式可选依赖，加入后
通过同一 runtime 注册入口装配；纯 Spring 用户只依赖 `fibra-spring`。Starter 的 composition root
必须收集容器内全部 `PluginRuntimeAdapter` Bean 交给同一个 Engine，不能把 Engine 构造写死为 Java
runtime；场景只声明新增 adapter，不负责复制 Engine、Registry、journal 和 artifact store 装配。

编译期注入处理器可作为 `fibra-api` 的配套 processor 交付，由 archetype 默认启用；生成结果必须
调用 core 的同一 `PluginDefinition/Scope` API，不建立第二套反射生命周期。

### 4.10 通用宿主扩展边界

Fibra 是通用运行时底座，不内置 Harness、工作流、IDE、游戏服务、Web 应用或其他业务模型。不同宿主
通过以下稳定接入面组合能力：

- `PluginCatalog`：合并程序注册的 built-in definitions 与 JAR artifact definitions；
- `PluginRuntimeAdapter`：让 Java、Node 及后续真实运行时接入同一 Engine 事务；
- `PluginRegistry`：提供不泄漏 Engine 内部结构的控制面；
- `ContributionBridge`：把不同来源的插件能力投影到场景目录；
- `DesiredStateRepository`：每个 Engine 只选择一个文件、数据库或内存 adapter，并编译成同一个
  desired graph；
- `EngineCommand`：把程序化变更意图提交到唯一事务执行器；
- `EngineSnapshot`/snapshot stream：把实际状态投影给管理面、健康检查和 UI；
- root Scope 服务注册：显式导入宿主服务；Spring 场景通过 `FibraServiceBridge` 使用同一协议；
- `Scope`、稳定 `ServiceKey`、事件与 effect：承载场景自己的生命周期和能力协议。

这些是运行时组合原语，不包含业务名词。Java 与 Node 只在各自 runtime module 中存在；core 和 Engine
不允许出现具体运行时分支。后续脚本或其他语言只有形成真实制品格式、资源模型和验收用例后，才能
新增 adapter；不能继续把 `PluginRuntimeAdapter` 扩成万能 driver 框架。

扩展性来自稳定组合点，不来自跨层回调：repository 只交付 snapshot，catalog 只提供 definition，
EngineCommand 只表达意图，EngineSnapshot 只发布事实，插件只通过 Context 使用能力。任一接口若同时
包含存储、运行、业务注册和状态投影中的两类职责，必须在进入实现前拆回所属模块。

Fibra 发布物只交付通用模块和 Java/Node 两个运行时。某个场景专属的 contribution kind、名称渲染、
外部格式导入器和 starter 应留在使用方项目，或在出现多个独立消费者后作为单独集成项目发布，不能
进入 `fibra-api`。

典型组合只验证同一组原语，不产生场景分支：

| 场景 | catalog | desired repository | 宿主适配 |
|---|---|---|---|
| 纯 Java 嵌入 | built-in | 内存 | 直接使用 Runtime/Engine |
| 文件驱动插件宿主 | built-in + Java/Node | YAML/JSON | Registry + 目录 source |
| Spring Boot 服务 | built-in + Java/Node | 文件或数据库二选一 | Spring bridge/starter |
| Agent Harness | built-in + Java/Node | 数据库或内存 | Harness contribution adapter |
| 测试 | built-in | 内存 | 临时 Scope 与虚拟资源 |

服务 binding 始终由当前 Scope 撤销，但 service 对象本身不会因为实现 `AutoCloseable` 就被猜测性关闭。
外部容器拥有的对象只注册 binding；由 Fibra 拥有的资源必须另外提供显式 disposer，并与 binding 一起
登记到同一 Scope。Spring bridge 和其他 adapter 不得改变这一规则。

## 5. 内核运行模型

### 5.1 四个不同概念

- `FibraRuntime`：root 所有者，拥有唯一 lifecycle lane 和 root Scope；关闭后不可恢复。
- `Scope`：可独立关闭的资源所有权子树，适合 session、tenant、request 或测试边界；父 Scope 关闭时
  子 Scope 先关闭。`Scope.closeAsync()` 是权威完成边界，`close()` 只做阻塞适配。
- `Context`：绑定某个 Scope 的不可变能力视图；metadata、isolate、intercept 派生新视图但不新建
  所有权。Context 自身不再 `close()`，避免“关闭派生视图却关闭 root”的歧义。
- `PluginInstance`：响应式组件实例，拥有依赖快照、配置、状态、effects 与重载惯性；root 不再伪装成
  PluginInstance。

### 5.2 单写者提交协议

运行时可变状态只在 lifecycle lane 上读写。每个公开注册动作必须在一次 lane command 中同时完成：

```text
检查 Scope/Fiber 可接纳
  -> 建立状态与唯一 token
  -> 把逆操作登记到所有者
  -> 发布可见状态/通知依赖者
```

任何一步失败都在同一 command 内撤销，不能出现“状态已生效、disposer 尚未被所有”的窗口。用户
Publisher 可在任意线程执行，但每个信号都回到 lifecycle lane 后才能改变状态。

### 5.3 Fiber 语义

状态固定为 `PENDING/STARTING/ACTIVE/FAILED/STOPPING/DISPOSED`。实现必须继续满足：

- 依赖按稳定服务名与 isolate token 解析；ACTIVE 时保存 provider 身份快照；
- provider 身份变化触发 dependent 先卸载、再按新 epoch 激活；
- 同一实例最多一个迁移在途，目标变化只更新 target，当前迁移落地后继续收敛；
- 启动和停止前各让出一个 lifecycle tick；
- `update` 清除启动错误并重新收敛，依赖自然回归本身不复活 FAILED；
- 同一 effect 内严格逆序串行清理；实例顶层 effects 并发启动、all-settled；
- async effect 逐项 `request(1)`，dispose 不取消已发出的 request，在途元素到达后归属并清理；
- provider 撤销先从公共绑定移除，再使用依赖实例的激活快照完成 drain；
- dispose、update、关闭和注册都有可等待且幂等的完成结果。

### 5.4 面向使用者的 API 形状

公共契约按以下形状实现。命名可以在 Java 编译验证发现冲突时做同义调整，但不得改变职责分组、
所有权与完成语义：

```java
public interface Disposable {
    Mono<Void> dispose();
}

public interface Scope extends AutoCloseable {
    String name();
    Context context();
    Scope openChild(String name);
    Mono<Void> closeAsync();
    void close();                 // 阻塞适配 closeAsync()
}

public interface Context {
    Scope scope();
    Services services();
    Effects effects();
    Events events();
    Plugins plugins();
    FibraLogger logger();

    Context withMetadata(String name, Object value);
    Context withRealm(ServiceKey<?> key, Object label);
    Context withIntercept(ServiceKey<?> key, Object value);
    Object intercept(ServiceKey<?> key);
}

@FunctionalInterface
public interface Plugin<C> {
    Mono<Void> start(Context context, C config);
}

public interface PluginFactory<C> {
    Plugin<C> create();           // 每个运行实例一个插件对象
}

public interface PluginEntrypoint<C> {
    PluginDefinition<C> definition();
}
```

最小使用方式是：

```java
try (var runtime = FibraRuntime.create();
     var scope = runtime.rootScope().openChild("session-42")) {
    var ctx = scope.context().withRealm(GREETING, "tenant-a");

    var registration = ctx.services().provide(GREETING, greeting);
    var value = ctx.services().require(GREETING);
    var optional = ctx.services().find(GREETING);

    ctx.events().on(CHANGED, listener, EventOptions.defaults());
    var instance = ctx.plugins().mount("consumer", definition, config);
    instance.settled().block();
}
```

`PluginDefinition<C>` 固定承载公开名称、`Class<C>`、validator、requires、provides 和
`PluginFactory<C>`；`mount` 的第一个参数是运行实例 id。定义身份、artifact 身份与实例 id 是三个
不同概念，不能再把 `entryId` 写进 descriptor/entrypoint 来混用。

以下 API 取舍已经确定：

- 删除布尔语义参数；ACTIVE 查询用 `require/find`，非 ACTIVE 快照只供内核诊断接口；
- 服务 key 始终显式包含稳定名称，不提供按实现类自动生成跨插件 key 的便捷入口；
- 插件定义与运行实例分开：definition 描述契约，desired entry 提供实例 id/config/realm，
  PluginInstance 只表示运行事实；
- 插件启动完成与资源所有权分开。插件 body 注册的资源自动归当前实例；高级异步 effect 通过
  `effects().collect(Publisher<Disposable>)` 明确进入，普通 `provide/on` 不再返回给
  `Mono.just(...)` 才能被拥有；
- `ServiceRef/InvocationContext` 继续作为会产生调用方资源的服务调用规范入口，不能退化为直接值调用；
- `settled()` 表示当前迁移落地，不能命名为 `ready()` 后在 PENDING 时成功，Engine readiness 由显式
  required-entry 状态判断。

## 6. Engine 的唯一事务内核

### 6.1 不可变事实

`EngineSnapshot` 同时记录各 runtime 的活动 catalog、artifact records、desired source snapshot、desired
graph、运行实例映射、applied revision 与活动失败。状态查询只读该快照，不重新读取可能变化的文件。

watcher 仍只产生 dirty signal；周期 resync 仍是正确性来源。Node loader 本次升级再次证明：环境探测
必须基于真实能力形状而不是版本号。Fibra 对文件系统、ClassLoader 或未来 JDK 差异也遵循相同原则，
在 prepare 阶段探测实际能力，不能按版本猜测。

### 6.2 统一变更协议

所有变更都由一个 `ChangeSet` 协议执行：

```text
observe -> validate -> prepare -> journal -> commit -> verify -> publish snapshot -> retire old
                                         \-> rollback on failure
```

- 松散 artifact 信号生成指定 runtime 的 artifact-only ChangeSet；
- 松散 config 信号生成 config-only ChangeSet；
- deployment package 生成 artifact+config 联合 ChangeSet；
- 三者共享同一执行器和 journal 状态机，只是参与者集合不同；
- prepare 完成全部读取、摘要、依赖图、配置类型和候选实例计划，不拆旧运行态；
- commit 只执行准备结果，不再访问不稳定来源；
- `COMMITTING` 覆盖 participant commit 到持久 `COMMITTED` 之间的结果不确定窗口；若进程在该状态
  崩溃，启动恢复不得猜测提交结果，必须关闭 mutation gate，等待运维修复；
- durable committed journal 是唯一提交点；其后 cleanup 失败只告警并由下次启动完成；
- rollback 无法证明完整恢复时永久关闭 mutation gate，直到进程关闭；
- 旧 snapshot、旧目录和旧 runtime generation 只在新 snapshot 发布且相关实例排空后 retire。

Engine command loop 与 core lifecycle lane 是两个单写者层级：Engine loop 串行外部变更事务，core lane
串行运行时状态。调用方向永远是 Engine -> core；core callback 不允许反向等待 Engine，避免锁环。

### 6.3 运行入口

托管入口保持小而稳定：

```java
start()
submit(EngineCommand)
snapshot()
snapshots()
runtime()
close()
```

`EngineCommand` 表达 artifact install/uninstall、desired entry upsert/remove、deployment 等通用宿主意图，
并携带 expected revision；Engine 在内部把意图编译成 ChangeSet。upsert/remove 只在所选
`DesiredStateRepository` 可写时可用，不能偷偷覆盖只读文件源。watcher 与周期 resync 只产生相同 command
或 dirty signal。`snapshots()` 只在 revision 改变时发布不可变 `EngineSnapshot`，供任意宿主投影实际
状态。

不公开内部 config/artifact repository，不提供绕过 transaction executor 的 `mount/update/unmount`。
需要纯内核的嵌入场景直接使用 `FibraRuntime`；不存在“拿 Engine 管一半、自己管另一半”的模式。

### 6.4 性能验证边界

JMH 只承载可在单 JVM 内稳定重复的热路径：core 的服务与事件调用、`ContributionBridge` 的本地贡献
调用，以及排除持久化介质后的 Registry/Engine 事务编排。artifact 复制、JAR/ClassLoader、Node
sidecar 冷启动与 JSON-RPC、Spring 启动和 HTTP 请求受文件系统、进程调度与操作系统影响，应由真实
集成测试和分发验证覆盖，不能混入微基准后用单机数字代表端到端性能。

基准源码参加默认 reactor 以发现 API 漂移，普通构建不执行 JMH。仓库不保存缺少提交号、固定环境、
运行命令和原始结果的参考数值；性能判断必须比较同环境多次结果与误差区间。

## 7. 从 cordis4j 吸收与拒绝清单

### 7.1 吸收

1. 独立可关闭资源子树，但实现为 `Scope`，不让不可变 Context 兼任所有权。
2. `find`/`require` 两种明确查询，不使用 boolean 模式参数。
3. 配置先映射成不可变 desired model，再由宿主 resolver 绑定实现。
4. 插件入口显式且不依赖装载框架公开 API。
5. 编译期注入生成与 Spring Bean 自动导出的易用性，但必须落到同一核心协议。
6. 每项偏差、边界和失败路径都写入契约并用测试固定的做法。
7. ClassLoader close-and-collect 的可观测测试。

### 7.2 拒绝

1. 同步 core 加 `pluginAsync/spawn` 的双模型。
2. 整个 Fiber domain 全串行卸载。
3. 同步激活与失败 Fiber 永久不可恢复。
4. 每 Context 独立事件总线和仅向父级冒泡。
5. `Class + qualifier` 作为跨动态插件的服务身份，以及 realm/qualifier 共用命名空间。
6. `Service.start/stop`；服务生命周期继续由 plugin/effect 表达，避免第二生命周期。
7. 扫描 JAR 全部 class 猜入口。
8. 无依赖图、无崩溃恢复的逐 JAR HMR 作为生产制品层。
9. 把 include/baseUrl/文件格式等 loader 概念放进 core。
10. 每个定时器占用一个 `Thread.sleep` 虚拟线程的 timer 模块；需要定时能力时应复用统一 scheduler，
    并作为独立、真实需求驱动的能力交付。
11. 为追求零依赖而删除 LoggerService、异步事件或 Reactor 完成边界。

## 8. 无兼容重构顺序

1. 先冻结 vNext 契约测试：DeepSeek Cordis 对等语义、Scope 所有权、注册/关闭竞态、调用者所有权、
   新 API 编译基线。
2. 重写 `fibra-api` 与 `fibra-core`：Runtime/Scope/Context/PluginInstance 四分，删除旧 API；现有实现
   不做 adapter。
3. 把配置解析与 desired graph 编译从运行态 reconcile 中抽离，形成独立 `fibra-config`。
4. 把通用制品身份、安装存储与磁盘事务重写为 `fibra-artifact`；删除 `fibra-pf4j-api`、
   `fibra-loader-pf4j` 和全部 PF4J 依赖。
5. 重写 Engine 为 runtime port + snapshot + ChangeSet + 唯一 transaction executor，再接入 source 与
   recovery。
6. 实现 `fibra-runtime-java` 与 `fibra-runtime-node` 两个纵向切片，分别固定真实 JAR/ClassSpace 和
   sidecar/JSON-RPC 契约，并通过同一 Engine 事务验收。
7. 实现 `fibra-bridge` 与 `fibra-registry`，用至少一个非 Harness 示例和 Harness 参考场景共同验证通用性。
8. 重写 Spring 与 archetype，使所有示例只展示 Registry、Engine 和新 runtime 入口。
9. 删除旧模块、旧签名基线、旧配置 API 和旧文档入口；全仓 grep 不得残留兼容转发。
10. 完成 parity、竞态、部分提交失败、真实 JAR、Node sidecar、崩溃恢复、Spring、分发和 JMH 门禁后再
    发布新的破坏性版本。

每个步骤都以可独立变绿的纵向切片交付，但中间版本不对外发布；不允许长期保留新旧运行时并存。

## 9. 验收标准

- DeepSeek `@deepseek-ai/cordis` 当前可观测内核行为逐项映射；参考版本变化先做源码 tree 与行为对拍，
  不按包版本号推断。
- 所有运行时可变状态只有一个 core 写入 lane；所有托管变更只有一个 Engine command loop。
- 注册与所有权登记原子化，覆盖 provide/on/intercept/scope/plugin/async effect 与关闭竞态。
- 配置和 artifact 模块可在不创建 FibraRuntime 的情况下完成解析、校验和磁盘事务测试。
- 全仓依赖树与源码中不存在 PF4J；标准 JAR 只含一个 Fibra manifest，不需要 `plugin.properties`、
  `@Extension` 或 extension index。
- Java 与 Node runtime 通过同一个 `PluginRuntimeAdapter` 端口参加 ChangeSet；core/Engine 无
  `runtimeType` 分支，snapshot 不泄漏 ClassLoader、Process 或 RPC channel。
- Node sidecar 的握手、消息边界、超时、取消、心跳、异常退出、进程树终止和孤儿清理均有真实进程测试。
- 普通插件的最小实现不需要显式返回 registration disposer；异步初始化仍有明确完成边界。
- 程序注册的 built-in definition 与 Java/Node definition 共用 catalog、desired graph、状态机和 Scope；
  built-in definition 不需要打包成制品。
- 文件配置与程序化 EngineCommand 进入同一事务执行器；宿主可订阅 snapshot revision 而无需轮询 loader。
- PluginRegistry 的 install/upgrade/enable/disable/uninstall 全部可由公开 API 完成；其 artifact、desired、
  observed 三类状态可解释且只有 observed 来自 EngineSnapshot。
- Java 本地 handler 与 Node remote endpoint 可通过同一种 ContributionKind 进入一个宿主目录；注册归属
  Scope，卸载会先撤销并排空调用，名称渲染由场景 adapter 决定。
- 至少用纯 Java 嵌入宿主和 Spring Boot 宿主验证同一接入面；Java Harness 只能作为额外场景测试，不能
  成为 core API 的特殊分支。
- 构建门禁验证上述禁止依赖；除 composition root 外不存在 config/artifact/core 的横向依赖或宿主到
  Fibra 的反向依赖。
- Engine 的三类 ChangeSet 共用一个事务状态机；部分提交失败和崩溃恢复均有真实文件、ClassSpace 与
  sidecar 测试。
- 公开发布 artifact 数量与用户职责一一对应，不发布只为内部依赖链服务的空壳模块。
- 无旧 API 兼容层、无弃用转发、无第二状态机、无双事务门。
- 全量 `mvn verify`、外部分发验证、API 签名门禁与基准门禁全部通过。

## 10. 明确不进入本轮目标架构的能力

- 远程插件市场、自动下载与信任策略；
- OSGi/ModuleLayer、非可信插件沙箱；
- 浏览器/WebView 前端运行时及 UI 扩展槽协议；
- LangChain4j、Spring AI 或具体 Agent 业务模型；
- Cordis/dsh 配置运行时兼容层与 JavaScript 表达式执行；
- 没有真实业务需求支撑的通用 timer、任务调度或多 provider 扩展。

这些能力未来只能通过独立 adapter 或新的架构决策进入，不能预埋在 vNext 核心抽象中。
