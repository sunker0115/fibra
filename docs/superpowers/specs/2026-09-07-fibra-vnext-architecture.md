# Fibra vNext 最终目标架构

日期：2026-09-07

状态：已确认并完成交付（2026-09-11）

本文定义 Fibra vNext 的最终系统边界、运行模型、模块职责和验收标准，不记录旧版本迁移过程，也不按
参考项目组织正文。运行代发布、排空与故障恢复的细化规则见
[运行代发布设计](./2026-09-10-fibra-generation-publication.md)；两文冲突时，以该细化设计为准。外部
实现的源码对拍统一收录在[源码参考目录](../references/README.md)。

## 1. 目标与边界

Fibra vNext 是一套面向受信任动态插件的托管运行底座。它允许 Java 原生插件、Node sidecar 和程序内建
插件进入同一个期望状态、生命周期、发布和诊断模型，同时保留纯内核嵌入方式。

### 1.1 核心不变量

1. 单个运行域保留 Cordis 的异步 effect、依赖驱动激活、调用者所有权、事件分派和关闭语义。
2. 不同运行代的服务、事件、Scope、插件实例和贡献完全隔离；候选代在发布前不可被宿主发现。
3. core 只有一个 lifecycle lane，托管层只有一个 Engine command loop；不存在其他可变写入口。
4. Engine 只发布整代不可变 `PublishedView`；宿主不分别拼接状态、贡献和诊断。
5. 配置和制品只是输入事实，不能直接修改 Runtime；所有托管变更统一编译为 `ChangeSet`。
6. Java 与 Node 通过同一个 `PluginRuntimeAdapter` 端口参与变更，Engine 不接触 ClassLoader、Process 或
   JSON-RPC 私有句柄。
7. 所有注册都归某个 `Scope`，注册生效与逆操作登记是同一个 lane command；关闭可等待且幂等。
8. `PENDING` 是合法运行状态，是否允许发布由逐 entry 的 `PublicationRequirement` 明确决定。
9. vNext 不引入 PF4J，不保留旧公开 API、旧模块名、旧配置入口或兼容转发。
10. Tool、Agent、Skill、Session、UI slot 等业务类型只存在于场景适配层，不进入 Fibra 通用模块。

### 1.2 两种使用方式

- 纯内核嵌入：应用直接创建 `FibraRuntime`，自行管理一个运行域，不获得制品事务和整代发布能力。
- 托管插件宿主：应用使用 `PluginRegistry`、`EngineCommand` 和 `PublishedRuntime`，不能取得 Engine 内部
  `FibraRuntime` 或完整 `Context`。

两种方式共享相同 core 语义，但不能混合成“Engine 管理一部分、宿主直接修改另一部分”的模式。

## 2. 系统总览

Fibra 分为控制面、运行域内能力面和发布面：

```text
控制面
外层宿主 -> PluginRegistry / EngineCommand -> FibraEngine -> PluginRuntimeAdapter

运行域内能力面
built-in / Java plugin -> Context -> Service / Event / Effect -> plugin

发布面
RuntimeDomain + ContributionDirectory -> PublishedView -> PublishedRuntime -> 外层宿主
```

对象关系如下：

```text
FibraEngine
  ├─ command loop                         托管变更的唯一写入口
  ├─ ArtifactStore / DesiredStateRepository
  ├─ PluginRuntimeAdapter[]               Java、Node 等运行时参与者
  ├─ FibraRuntime
  │    └─ lifecycle lane                  core 状态的唯一写入口
  ├─ candidate PublishedGeneration        最多一个，宿主不可见
  ├─ current PublishedGeneration          恰好一个已发布代
  ├─ draining PublishedGeneration         最多一个，不再接收新调用
  └─ PublishedRuntime                     宿主唯一能力入口
       └─ AtomicReference<PublishedView>
            ├─ EngineSnapshot
            ├─ ContributionSnapshot
            ├─ RuntimeDiagnostics
            └─ EngineDiagnostics
```

`PluginRegistry` 只管理安装、版本、期望状态和审计；`ContributionDirectory` 只管理代内贡献；runtime
adapter 只管理自己的制品物化和资源；Engine 只管理事实、事务与发布。任何类型同时承担其中两类职责，
都属于边界泄漏。

### 2.1 运行代关系与域内依赖

多个 `RuntimeDomain` 是同一 `FibraRuntime` 下彼此隔离的同级对象，不构成父子关系，也不相互依赖。
`candidate -> current -> draining -> closed` 表示同一个 generation 的时间角色变化，不是 domain 之间的
调用方向。它们只共享 lifecycle lane 的调度纪律和不可变宿主输入，不共享服务表、事件表、Scope 或
贡献目录：

```text
FibraRuntime（唯一 lifecycle lane）
  ├─ Generation G41 [draining]
  │    ├─ RuntimeDomain D41
  │    ├─ ContributionDirectory C41
  │    └─ Java ClassSpace / Node sidecars R41
  ├─ Generation G42 [current]
  │    ├─ RuntimeDomain D42
  │    ├─ ContributionDirectory C42
  │    └─ Java ClassSpace / Node sidecars R42
  └─ Generation G43 [candidate]
       ├─ RuntimeDomain D43
       ├─ ContributionDirectory C43
       └─ Java ClassSpace / Node sidecars R43
```

插件系统内部存在三种不同关系，不能合并画成一棵“插件依赖树”：

```text
1. Java artifact DAG（候选代创建前校验）

   provider ──requires──> contract <──requires── consumer

2. Service dependency graph（每个 RuntimeDomain 内动态解析）

   agent ──requires ModelService──> model-provider
     │
     └──requires ToolCatalog──────> tool-catalog
                                      │
                                      └──requires Store──> store-provider

3. Scope ownership tree（只决定关闭与清理）

   domain root
     ├─ plugin instance scope
     │    └─ invocation / nested scope
     └─ built-in plugin scope
```

`DesiredGraph` 保存 entry 及其 requires/provides 契约；实例挂载后，`ServiceRegistry` 为每个
`(ServiceKey, realm)` 槽位选择 effective provider，由此形成实际服务依赖图。该图支持链式依赖、共享
provider、扇入和扇出，不要求是树。`ContributionDirectory` 是按 identity/kind 建立的路由索引，也不是
依赖图；贡献随 owner Scope 撤销，并随整个 generation 发布。

场景覆盖如下：

| 场景 | 表达方式 | 结果 |
|---|---|---|
| 多级服务链、菱形依赖、多个 consumer 共享 provider | domain 内 Service graph | 支持；Engine 等待全部实例完成传递收敛后再验证候选代 |
| provider 缺失 | 实例进入 PENDING，诊断列出 `waitingFor` | `ACTIVE_REQUIRED` 拒绝发布；`PENDING_ALLOWED` 可以发布 |
| provider 替换或消失 | effective provider identity/epoch 变化 | dependent 先按旧快照清理，再重新解析和激活 |
| Java 共享 contract 与多制品依赖 | Java artifact DAG | 缺失、版本冲突和环在创建活动 ClassSpace 前拒绝 |
| session、tenant、request、嵌套插件资源 | Scope ownership tree | 父 Scope 关闭时递归关闭子树，不改变服务依赖方向 |
| Java 与 Node 能力共同对外发布 | 同代 ContributionDirectory | 支持统一快照、revision 调用和整代排空 |
| 任意 Java Service 直接注入 Node 进程 | 无隐式跨进程 Service graph | 不支持；必须定义显式 contribution/RPC adapter 与 schema |
| 跨 generation 服务或事件依赖 | 无表达入口 | 明确禁止，避免候选代和活动代相互污染 |
| 硬服务依赖环且没有外部 provider 打破环 | 所有相关实例保持 PENDING | 不伪造拓扑序；由发布要求决定拒绝或带诊断发布 |

## 3. Runtime 内核

### 3.1 核心概念

| 概念 | 职责 | 不承担 |
|---|---|---|
| `FibraRuntime` | root 所有者、唯一 lifecycle lane、运行域创建 | 配置、制品、宿主路由 |
| `RuntimeDomain` | 一代内的服务、事件、Scope 与插件实例可见性；代内贡献目录与它绑定发布 | 跨代共享可变状态 |
| `Scope` | 独立可关闭的资源所有权子树 | metadata/isolate 视图派生 |
| `Context` | 绑定 Scope 的不可变能力视图 | 关闭 root 或拥有资源 |
| `PluginDefinition<C>` | 名称、配置类型、校验器、requires、provides、实例工厂 | 运行实例状态 |
| `PluginInstance` | 配置、依赖快照、状态、effects 与状态收敛 | 制品安装和宿主发布 |

父 Scope 关闭时先关闭子 Scope。`Context.withMetadata/withRealm/withIntercept` 只派生视图，不新建
所有权。root 不伪装成特殊 `PluginInstance`。

### 3.2 单写者与注册原子性

运行时可变状态只在 lifecycle lane 上提交。每个注册动作必须在同一个 command 中完成：

```text
检查 Scope / PluginInstance 可接纳
  -> 建立状态与唯一 token
  -> 登记逆操作到所有者
  -> 发布代内可见状态并通知依赖者
```

任一步失败都在该 command 内撤销，不能出现“状态已生效但 disposer 尚未被拥有”的窗口。用户
`Publisher` 可以在任意线程运行，但只有回到 lifecycle lane 的信号才能改变 Runtime 状态。

### 3.3 插件与 Fiber 语义

插件实例状态固定为：

```text
PENDING -> STARTING -> ACTIVE -> STOPPING -> DISPOSED
                 \-> FAILED
```

必须满足以下行为：

- 依赖按稳定服务名和 realm 解析；ACTIVE 时保存 provider identity 快照。
- provider identity 变化时，dependent 使用旧激活快照完成清理，再按新 epoch 激活。
- 同一实例最多一个状态转换在途；目标变化只更新 target，当前转换落地后继续收敛。
- 启动和停止前各让出一个 lifecycle tick，避免同步重入改变 Cordis 可观察时序。
- `update/restart` 可以清除启动错误；依赖自然回归本身不复活 FAILED 实例。
- `settled()` 表示当前状态转换已经落地；稳定 PENDING 可以 settled，但不等于 ACTIVE。
- dispose、update、关闭和注册都提供可等待、幂等的完成结果。

### 3.4 Service、Event 与 Effect

- `ServiceKey<T>` 使用稳定名称和 Java 契约类型；realm 是独立维度。同一代、同一 realm 的同名异型
  服务直接拒绝，不跨 generation 缓存 Java `Class`。
- `find` 返回当前可用服务，`require` 要求服务存在；不使用 boolean 参数隐藏查询语义。
- 会产生调用方资源的服务通过 `ServiceRef` 与 `InvocationContext` 显式携带调用者 Scope，不使用
  ThreadLocal 或动态代理猜测所有权。
- `EventKey` 固定稳定名称、listener 类型和 `EventMode`。支持 `EMIT/PARALLEL/SERIAL/BAIL/WATERFALL`；
  调用方式与 key mode 不一致时直接拒绝。
- 同一 effect 内严格逆序串行清理；实例顶层 effects 并发启动并 all-settled。
- 异步 effect 逐项 `request(1)`。dispose 不丢弃已经请求的在途元素；元素到达后先归属，再停止请求并
  清理。
- 服务对象不会因为实现 `AutoCloseable` 就被推测性关闭。外部容器拥有的对象只注册 binding；Fibra
  拥有的资源必须显式登记 disposer。

## 4. Engine 与运行代发布

### 4.1 运行代

一个 `PublishedGeneration` 包含一个 `RuntimeDomain`、各 runtime 的候选资源、代内贡献目录和诊断事实。
一个 Engine 同时最多存在：

- 一个 current：接收新的宿主调用；
- 一个 candidate：执行准备、激活和验证，对宿主不可见；
- 一个 draining：发布切换后不再接收新调用，只等待已有 lease 结束。

会发布新代的下一条 `ChangeSet` 必须等待 draining 正常完成或强制关闭结果落地，防止 ClassSpace、
sidecar 和其他代际资源无界增长。

### 4.2 PublishedRuntime 与 PublishedView

`PublishedRuntime` 是稳定宿主对象，内部只原子替换一个不可变 `PublishedView`：

```java
public record PublishedView(
        String viewRevision,
        String generationRevision,
        EngineSnapshot engine,
        ContributionSnapshot contributions,
        RuntimeDiagnostics diagnostics,
        EngineDiagnostics engineDiagnostics) {}
```

`viewRevision` 标识任意已发布事实变化；`generationRevision` 只在整代切换时变化。同一 generation 内的
PENDING 恢复、provider 变化或贡献增删只增加 view revision。

宿主调用必须携带选择贡献时观察到的 expected view revision。Engine 在一次发布临界区内切换路由、
关闭旧代准入并发布新 view；旧 revision 返回明确的 stale-revision 结果，不能悄悄路由到新代。

### 4.3 诊断投影

诊断是已发布运行事实的一部分，不从全局注册表或多个时点临时拼接：

| 投影 | 必需事实 |
|---|---|
| `RuntimeDiagnostics` | domain/generation、插件 instance/definition identity、状态、依赖、`waitingFor`、failure、publication requirement/impact |
| `RuntimeDiagnostics.services` | `ServiceKey`、effective provider 与 shadowed providers |
| `RuntimeDiagnostics.events` | 事件名称、mode、listener type，以及 listener owner/order/once/global |
| `ContributionSnapshot` | 目录 revision、contribution id/kind/descriptor；provider instance 来自 `ContributionId`，generation 来自当前 `PublishedView` |
| `EngineDiagnostics` | current/candidate/draining generation、transaction state/records、mutation gate 与 Engine failure |

这些 DTO 只描述结果，不暴露 Fiber、Context、ClassLoader、Process、RPC channel 或 registration 句柄。

### 4.4 ChangeSet 协议

artifact、config 和联合 deployment 共用同一个执行器与 journal 状态机：

```text
observe -> validate -> prepare -> verify -> journal -> commit
       -> durable decision -> publish view -> drain old -> retire
                         \-> rollback（仅 durable decision 之前）
```

- prepare 完成读取、摘要、依赖图、配置类型、ClassSpace/sidecar 候选和实例计划，不拆旧运行态。
- verify 等待候选服务图收敛，再按 entry 检查 `ACTIVE_REQUIRED` 或 `PENDING_ALLOWED`。
- commit 只消费准备结果，不重新读取不稳定来源。
- durable `COMMITTED` journal 是唯一提交点；其后 cleanup 失败只告警并由恢复流程继续。
- durable decision 前的 participant commit 必须可补偿；无法证明完整恢复时关闭 mutation gate。
- `COMMITTING` 状态下崩溃属于结果不确定，恢复不能猜测成功或失败，必须关闭 mutation gate 等待处理。

完整状态机、发布顺序、lease 排空和崩溃矩阵见[运行代发布设计](./2026-09-10-fibra-generation-publication.md)。

### 4.5 一致性保证与非承诺

Fibra 保证：

- candidate 对 current 不可见；失败候选不会产生部分可见代；
- `EngineSnapshot`、贡献快照、运行域诊断和 Engine 诊断来自同一个 view revision；
- 新调用只进入 current，旧调用持有代内 lease 直到完成或被明确终止；
- 内部持久决策、已登记资源补偿和恢复结果可查询；
- Java-only、Node-only 和 Java+Node 变更使用同一个发布协议。

Fibra 不承诺：

- 回滚插件已经发送的邮件、网络请求、数据库提交等任意外部副作用；
- 与外部数据库、消息系统或浏览器客户端组成分布式 ACID 事务；
- 远程调用 exactly-once；
- 对非可信插件提供安全沙箱；
- 所有在线客户端与服务端 generation 在同一瞬间切换。

## 5. 模块与依赖边界

依赖方向如下，箭头表示左侧依赖右侧：

```text
fibra-core                -> fibra-api
fibra-config              -> fibra-api
fibra-artifact            -> fibra-api
fibra-bridge              -> fibra-api
fibra-engine              -> fibra-core + fibra-config + fibra-artifact + fibra-bridge
fibra-runtime-java        -> fibra-engine + fibra-artifact + fibra-api
fibra-runtime-node        -> fibra-engine + fibra-artifact + fibra-bridge + fibra-api
fibra-registry            -> fibra-engine
fibra-spring              -> fibra-api
fibra-spring-boot-starter -> fibra-spring + fibra-registry + fibra-runtime-java
```

`fibra-plugin-archetype` 只生成依赖 `fibra-api` 的独立插件工程。`fibra-parity-tests`、
`fibra-benchmarks`、`fibra-example` 和 verification 不发布为运行时模块。

| 模块 | 唯一主要职责 | 禁止承担 |
|---|---|---|
| `fibra-api` | 稳定插件与宿主契约 | Runtime 实现、装载框架、Spring、业务类型 |
| `fibra-core` | RuntimeDomain、Scope、插件生命周期、服务、事件、effect | 配置、制品、Engine、文件格式 |
| `fibra-config` | desired model、repository 端口与编译 | ClassLoader、Runtime 修改、Engine 事务 |
| `fibra-artifact` | 运行时中立制品身份、校验、安装存储与磁盘事务 | Java/Node 私有物化、config、Engine |
| `fibra-bridge` | 通用贡献身份、Scope 归属、撤销、快照与调用适配 | 具体贡献类型、制品安装、Engine 事务 |
| `fibra-engine` | runtime 端口、command、ChangeSet、journal、PublishedView | 具体 runtime、Spring、宿主业务 |
| `fibra-runtime-java` | manifest、依赖图、隔离 ClassSpace、Java 插件物化 | Node、config、宿主业务 |
| `fibra-runtime-node` | Node package、sidecar、JSON-RPC endpoint | Java ClassLoader、config、具体业务类型 |
| `fibra-registry` | 安装、版本、期望状态与审计控制面 | runtime 私有对象、业务贡献目录 |
| `fibra-spring` | Spring 服务与 Scope 协议适配 | Engine 装配、制品事务、宿主业务 |
| `fibra-spring-boot-starter` | 默认 composition root | 业务规则与场景协议 |

禁止新增 `common/shared/utils` 发布模块承接边界不清的代码。跨模块且属于公开语义的类型进入
`fibra-api`；纯实现复用不足以成为新模块。

## 6. 输入、运行时与宿主适配

### 6.1 Config 与 Artifact

`fibra-config` 把文件、数据库或内存中的声明编译为不可变 desired graph。每个 Engine 选择一个
`DesiredStateRepository`；程序化命令只有在 repository 可写时才能 upsert/remove，不能覆盖只读文件源。

`fibra-artifact` 只管理通用 identity、版本、摘要、安装记录、磁盘事务和审计事实。它不知道 JAR、npm、
ClassLoader 或 Process。配置与 artifact 互不依赖，由 Engine 在 ChangeSet 中对齐。

程序内建 definition 与 runtime catalog 合并后，通过最小只读 `PluginDefinitionResolver` 供配置编译；
该端口不暴露制品路径、运行时句柄或运行实例。

### 6.2 Java Runtime

标准 Java 制品使用 `META-INF/fibra/plugin.yaml`：

- executable 制品显式声明唯一 entrypoint；
- contract-only 制品省略 entrypoint，只作为依赖图和 ClassSpace 节点；
- 每制品独立 ClassLoader，按显式依赖图委派；宿主导出的公共契约由 parent 唯一定义；
- 禁止扫描全部 class 猜入口，不生成 extension index，不维护第二套插件状态机；
- 候选使用完整新 ClassSpace，失败时关闭候选，发布后旧 ClassSpace 随 draining generation 回收；
- close-and-collect 必须有可观察门禁。ClassLoader 只提供类型隔离，不是安全沙箱。

### 6.3 Node Runtime

Node 插件作为受管 sidecar，通过版本化 JSON-RPC 协议参与同一 `PluginRuntimeAdapter`：

- prepare 完成包校验、进程启动、握手、能力与 schema 协商；
- endpoint 先适配为通用 contribution，再进入代内目录；
- 超时、取消、心跳、stderr、异常退出和消息边界必须结构化上报；
- retire 必须结束完整进程树，不能只关闭父进程或遗留孤儿；
- Engine snapshot 不暴露 Process、channel 或协议对象。

### 6.4 Registry、Bridge 与 Spring

`PluginRegistry` 向宿主提供 install/upgrade/enable/disable/uninstall 和查询接口，并明确区分 artifact、
desired、observed 三类状态；observed 只来自 `PublishedView.engine()`。

`ContributionDirectory` 位于 generation 内。Java handler 与 Node endpoint 由场景 adapter 转成相同
`ContributionKind`；Fibra 不规定 Tool、Agent 等 kind，也不规定外部名称渲染。撤销贡献、拒绝新调用和
排空在途调用先于 Scope 与 runtime 资源关闭。

`fibra-spring` 提供显式 key/type 的服务 bridge。Spring Bean 由容器拥有，bridge 只登记 binding；撤销
仍走 Scope 协议。`fibra-spring-boot-starter` 收集所有 `PluginRuntimeAdapter` Bean，装配一个 Engine、
Registry 和 PublishedRuntime；不能把 Engine 写死为 Java-only。

非 Spring 宿主遵循同一规则：外部服务在 Engine 构建或命令边界形成不可变 host binding snapshot，
adapter 只收集 binding，不取得 Engine 托管的 root Context，也不替外部所有者关闭服务对象。

## 7. 公开使用边界

### 7.1 纯内核 API

公共契约按职责分组：

```java
public interface Scope extends AutoCloseable {
    String name();
    Context context();
    Scope openChild(String name);
    boolean isClosed();
    Mono<Void> closeAsync();
    void close();
}

public interface Context {
    Scope scope();
    Services services();
    Effects effects();
    Events events();
    Plugins plugins();
    Properties properties();
    LoggerService logging();
    FibraLogger logger();
    FibraLogger logger(String name);
    FibraLogger loggerForService(String serviceName);

    Object metadata(String name);
    Context withMetadata(String name, Object value);
    Context withRealm(ServiceKey<?> key, Object label);
    Context withIntercept(ServiceKey<?> key, Object value);
    Context withLogger(LoggerIntercept value);
    Object intercept(ServiceKey<?> key);
}

public interface Plugin<C> {
    Mono<Void> start(Context context, C config);
}
```

`PluginDefinition<C>` 持有契约，desired entry 持有 instance id、config、realm 和发布要求，
`PluginInstance` 只表示运行事实；definition identity、artifact identity 与 instance identity 不混用。

### 7.2 托管宿主 API

托管入口保持为：

```text
FibraEngine.start()
FibraEngine.submit(EngineCommand)
FibraEngine.published()
PublishedRuntime.current()
PublishedRuntime.views()
PublishedRuntime.invoke(expectedViewRevision, ...)
FibraEngine.close()
```

Spring、HTTP、CLI、SDK 和管理 UI 属于外层宿主，只使用这些入口。需要完整 Service/Event/Effect 语义的
应用内建能力应注册为 built-in plugin，位于 RuntimeDomain 内；外层宿主永远不取得 core `Context`。

## 8. 可观测性与运维边界

- watcher 只产生 dirty signal；周期 resync 是发现丢失通知的正确性来源。
- 环境兼容性按实际能力形状探测，不按 JDK、Node 或操作系统版本号猜测。
- desired source revision、generation revision 与 view revision 分开表达，不能共用一个模糊 revision。
- 失败必须定位到 source、validate、prepare、verify、commit、publish、drain 或 retire 阶段。
- `PENDING` 必须公开 `waitingFor`；服务必须能追溯 provider/owner；事件和贡献必须能追溯 owner/generation。
- mutation gate 关闭、结果不确定 journal、draining 超时和 retire 失败都属于公开健康事实。

JMH 只覆盖单 JVM 内可稳定重复的 core、贡献目录和排除持久介质后的事务编排热路径。文件复制、
ClassLoader、sidecar 启动、JSON-RPC、Spring 启动和 HTTP 请求使用真实集成或分发验证，不能用单机微基准
代表端到端性能。

## 9. 非目标

以下能力不属于 vNext，未来必须由真实需求和新的架构决策引入：

- 远程插件市场、自动下载和信任策略；
- OSGi/ModuleLayer 或非可信插件沙箱；
- 浏览器/WebView runtime、client graph、HMR 与 UI slot 协议；
- LangChain4j、Spring AI、Tool、Agent、Session 等业务模型；
- Cordis/DSH 配置兼容层或 JavaScript 表达式执行；
- 没有独立消费者支撑的通用 timer、任务调度、多 provider 或万能 runtime driver。

Java Harness 只是验证 built-in definition、EngineCommand、PublishedView、贡献适配和宿主服务所有权的
参考场景，详见[Java Harness 接入设计](./2026-09-07-fibra-java-harness-integration.md)，不定义 Fibra 的
产品方向。

## 10. 验收与证据

最终交付必须同时满足：

- 71 项 Cordis 原始行为与 44 项 Fibra 额外回归逐项通过，不能用新增测试数量抵扣；
- RuntimeDomain 跨代隔离、候选不可见、PublishedView 原子切换和旧代 lease 排空通过；
- artifact/config/联合 deployment 使用同一 ChangeSet，覆盖补偿失败、mutation gate 与崩溃恢复；
- Java 使用真实 JAR 验证依赖图、资源委派和 ClassLoader 回收；
- Node 使用真实进程验证握手、超时、取消、心跳、异常退出与进程树终止；
- 公开 API 签名、模块依赖、Spring、示例、archetype、外部消费和可复现分发门禁通过；
- 全仓无 PF4J、旧 loader、`Engine.runtime()`、共享可变 ContributionBridge 或兼容转发残留。

当前状态：上述门禁已完成；120 项行为/API/架构组合测试、无排除的 28 模块 `mvn verify` 和独立分发
验证均于 2026-09-11 通过。逐项方法和执行结果见
[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)。

设计结论使用以下固定源码基线：

| 来源 | 固定基线 | 证据 |
|---|---|---|
| Fibra 0.4.x | `02fe4b5dcd7b1052203d2027c9808931dceddb65` | 旧实现问题只用于解释最终边界，不进入主叙事 |
| cordiverse/cordis | `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` | [Cordis 行为证据](../references/2026-09-09-cordis-behavior-evidence.md) |
| DeepSeek Harness | `b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`、`a66e4702047846cdaa10c66c9d3df3951f5ea70d` | [插件依赖、装载与更新基线](../references/2026-09-09-plugin-dependency-baselines.md) |
| cordis4j | `6cfd56e684fb403ded952afc09eddb49a5228494`、设计契约 v2.13 | [cordis4j 设计证据](../references/2026-09-11-cordis4j-design-evidence.md) |

这些来源只解释为什么选择当前边界，不扩大 Fibra 的实现承诺。两篇用户提供的解读文章作为问题清单
收录在[源码参考目录](../references/README.md)，结论仍以固定源码、测试与本设计为准。
