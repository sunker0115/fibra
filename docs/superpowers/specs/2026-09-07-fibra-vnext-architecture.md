# Fibra vNext 最终目标架构

日期：2026-09-07

状态：设计、实现、自动化交付验收与独立审核已完成（2026-09-13）

本文是 Fibra vNext 唯一权威设计，定义最终系统边界、运行模型、模块职责和验收标准；不记录旧版本
迁移过程，也不按参考项目组织正文。外部实现的源码对拍统一收录在
[源码参考目录](../references/README.md)。

## 1. 目标与边界

Fibra vNext 是一套面向受信任动态插件的托管运行底座。它允许 Java 原生插件、Node sidecar 和程序内建
插件进入同一个期望状态、生命周期、发布和诊断模型，同时保留纯内核嵌入方式。

交付以满足实际场景的最小完整架构为目标，不以比参考项目更多的组件或更强的限制作为增强。
以 DSH `0.1.2-rc.1`（`a66e4702047846cdaa10c66c9d3df3951f5ea70d`）的插件系统为固定行为基线，
逐项覆盖插件协作、配置装配与动态管理场景，提供等价或增强的实现，不退化、不变形。Java/Node 的
实现机制和输入语法可以不同，但不能削弱核心行为契约；等价性由源码对照和行为测试证明。
Tool、Agent、Session 等业务插件的实现不因此进入通用底座；其所需的通用插件能力仍须完整提供。

运行时安全、在线发布一致性和持久目标保存是三项独立职责。前两项不要求数据库；持久化只保存
不可变制品与一个完整部署目标，不引入内嵌数据库或通用多参与者持久事务协调器，不将审计与部署
绑定为原子提交。简化实现不能丢失已确认保存的目标、接受半份清单，或在恢复失败时悄悄回退。

### 1.1 核心不变量

1. 单个运行域保留 Cordis 的异步 effect、依赖驱动激活、调用者所有权、事件分派和关闭语义。
2. 托管 Engine 在长期运行域内进行实例差量更新；配置树、服务依赖图和资源所有权树分别建模。
3. core 只有一个 lifecycle lane，托管层只有一个 Engine command loop；不存在其他可变写入口。
4. Engine 原子发布不可变 `PublishedView`；宿主不分别拼接状态、贡献和诊断。视图一致不等于生命周期变更原子。
5. 配置和制品只是输入事实，不能直接修改 Runtime；所有托管变更统一编译为 `ChangeSet`。
6. Java 与 Node 通过同一个 `PluginRuntimeAdapter` 端口参与变更，Engine 不接触 ClassLoader、Process 或
   JSON-RPC 私有句柄。
7. 运行域内所有注册都归某个 `Scope`，注册生效与逆操作登记是同一个 lane command；关闭可等待且幂等。
8. `PENDING` 是合法运行状态；逐 entry 的 `PublicationRequirement` 决定目标是否达成，不允许隐瞒实际运行状态。
9. vNext 不引入 PF4J，不保留旧公开 API、旧模块名、旧配置入口或兼容转发。
10. Tool、Agent、Skill、Session、UI slot 等业务类型只存在于场景适配层，不进入 Fibra 通用模块。

### 1.2 两种使用方式

- 纯内核嵌入：应用直接创建 `FibraRuntime`，自行管理运行域，不获得持久部署目标和托管视图发布能力。
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
  ├─ EngineStateStore                     单一部署目标的保存与读取
  ├─ ArtifactStore / desired input source  不可变制品内容与候选配置采集
  ├─ PluginRuntimeAdapter[]               Java、Node 等运行时参与者
  ├─ FibraRuntime
  │    └─ lifecycle lane                  core 状态的唯一写入口
  ├─ RuntimeDomain                        长期运行，局部变更实例和服务
  │    ├─ Scope ownership tree            实例、嵌套插件与调用资源
  │    └─ ContributionDirectory           注册、撤销与条目调用排空
  ├─ runtime resources                    按制品依赖闭包准备和回收
  └─ PublishedRuntime                     宿主唯一能力入口
       └─ AtomicReference<PublishedView>
            ├─ EngineSnapshot
            ├─ ContributionSnapshot
            ├─ RuntimeDiagnostics
            └─ EngineDiagnostics
```

`PluginRegistry` 只管理安装、版本、期望状态和审计；`ContributionDirectory` 只管理域内贡献；runtime
adapter 只管理自己的制品物化和资源；Engine 只管理目标、变更编排与发布。任何类型同时承担其中两类职责，
都属于边界泄漏。

### 2.1 配置树、服务图与资源归属

托管变更在同一个长期 `RuntimeDomain` 内收敛，不通过创建另一整个 domain 更新配置。
纯内核可以创建多个互相隔离的同级 domain，但它们不是配置分组，不形成跨域依赖，也不是托管更新协议。
插件系统内部存在四种不同关系，不能合并画成一棵“插件依赖树”：

```text
1. 配置条目树（声明归属、启停与隔离策略继承）

   root
     ├─ group-a [message: local]
     │    ├─ provider-a
     │    └─ consumer-a
     └─ group-b [message: local]
          ├─ provider-b
          └─ consumer-b

2. Java artifact DAG（物化和替换前校验）

   provider ──requires──> contract <──requires── consumer

3. Service dependency graph（RuntimeDomain 内动态解析）

   agent ──requires ModelService──> model-provider
     │
     └──requires ToolCatalog──────> tool-catalog
                                      │
                                      └──requires Store──> store-provider

4. Scope ownership tree（只决定关闭与清理）

   domain root
     ├─ plugin instance scope
     │    └─ invocation / nested scope
     └─ built-in plugin scope
```

`DesiredInputGraph` 保存配置条目树；requires/provides 契约由目标制品 catalog 提供。实例挂载后，`ServiceRegistry` 为每个
`(ServiceKey, realm)` 槽位选择 effective provider，由此形成实际服务依赖图。该图支持链式依赖、共享
provider、扇入和扇出，不要求是树。`ContributionDirectory` 是按 identity/kind 建立的路由索引，也不是
依赖图；贡献随 owner Scope 撤销，其当前事实投影到已发布视图。
组上的局部 realm 由该组拥有并被子条目继承；不同组各有独立 realm。组不创建实例 ID 命名空间，
include 边界才创建命名空间。改变组归属或继承策略可能影响其子树，但不会自动重启其他配置组。

场景覆盖如下：

| 场景 | 表达方式 | 结果 |
|---|---|---|
| 多级服务链、菱形依赖、多个 consumer 共享 provider | domain 内 Service graph | 内核按实际 provider 变化传递收敛，不重建无关实例 |
| provider 缺失 | 实例进入 PENDING，诊断列出 `waitingFor` | `ACTIVE_REQUIRED` 表示目标未达成；`PENDING_ALLOWED` 允许该状态 |
| provider 替换或消失 | effective provider identity/epoch 变化 | dependent 先按旧快照清理，再重新解析和激活 |
| Java 共享 contract 与多制品依赖 | Java artifact DAG | 缺失、版本冲突和环在创建活动 ClassSpace 前拒绝 |
| session、tenant、request、嵌套插件资源 | Scope ownership tree | 父 Scope 关闭时递归关闭子树，不改变服务依赖方向 |
| Java 与 Node 能力共同对外发布 | 同域 ContributionDirectory | 支持统一快照、revision 调用和受影响条目排空 |
| 任意 Java Service 直接注入 Node 进程 | 无隐式跨进程 Service graph | 不支持；必须定义显式 contribution/RPC adapter 与 schema |
| 跨 domain 服务或事件依赖 | 无表达入口 | 明确禁止；多个配置组在同域内通过 realm 控制可见性 |
| 硬服务依赖环且没有外部 provider 打破环 | 所有相关实例保持 PENDING | 不伪造拓扑序；诊断说明缺失依赖与目标达成情况 |

## 3. Runtime 内核

### 3.1 核心概念

| 概念 | 职责 | 不承担 |
|---|---|---|
| `FibraRuntime` | root 所有者、唯一 lifecycle lane、运行域创建 | 配置、制品、宿主路由 |
| `RuntimeDomain` | 域内服务、事件、Scope 与插件实例可见性；支持长期动态更新 | 配置解析、制品选择与跨域依赖 |
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
  -> 发布域内可见状态并通知依赖者
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
- 激活 epoch 由有序的依赖提供者身份和配置修订组成，不以注册 token 代替提供者身份。启动完成只能
  确认实际执行的 epoch；期间收到新配置或不同提供者时，先完成旧激活的清理，再执行最新目标。
  同一提供者在启动期间短暂撤销又恢复、且配置未变时保留惯性，不伪造一次额外重启。
- 启动和停止前各让出一个 lifecycle tick，避免同步重入改变 Cordis 可观察时序。
- `update/restart` 可以清除启动错误；依赖自然回归本身不复活 FAILED 实例。
- `settled()` 表示当前状态转换已经落地；稳定 PENDING 可以 settled，但不等于 ACTIVE。
- `updatePrepared` 只接受当前 definition 对象的预校验配置，与 `update(config)` 共用状态机但不重复
  校验。使用独立方法名，避免合法 null 配置与 Prepared 形成重载歧义。
- 同一逻辑配置变更涉及多个既有实例时，`RuntimeDomain.updateBatch` 先校验整组实例归属、definition、
  存活状态和实例唯一性，再在同一个 lifecycle turn 内登记全部配置修订，之后才允许任一实例开始收敛；
  单实例 `update/updatePrepared` 复用同一登记路径。该边界把 DSH 同一 JavaScript 调用栈“先写目标、微任务
  后重载”的行为契约显式化，不依赖调用线程与 lifecycle 线程的调度竞争。
- 批量登记前失败原样返回且不应用任何目标；登记后各实例独立收敛，任一实例失败以
  `PLUGIN_BATCH_UPDATE_FAILED` 标记，但不回滚已登记目标或已发生的运行副作用。批量完成同时等待域依赖图
  和全部目标实例稳定，不把“整组登记”描述为多实例原子生效。
- 终态事件发布前必须先清除实例的转换中标志，使同步状态观察者重采样时看到稳定 PENDING、ACTIVE、
  FAILED 或 DISPOSED；每次转换的完成信号绑定该转换自己的句柄，状态观察者同步发起下一次更新时不能让
  旧结果写入新句柄。不能在事件后静默改变收敛条件，否则既有 `settled()` 等待者可能永久等待下一次通知。
- dispose、update、关闭和注册都提供可等待、幂等的完成结果。

### 3.4 Service、Event 与 Effect

- `ServiceKey<T>` 使用稳定名称和 Java 契约类型；realm 是独立维度。同一域、同一 realm 的同名异型
  服务直接拒绝。制品替换必须清理已退休契约类型的引用；不能让长期 domain 永久钉住旧 Java `Class`。
- `find` 返回当前可用服务，`require` 要求服务存在；不使用 boolean 参数隐藏查询语义。
- 会产生调用方资源的服务通过 `ServiceRef` 与 `InvocationContext` 显式携带调用者资源 `Context`，不使用
  ThreadLocal 或动态代理猜测所有权。`InvocationContext.caller()` 是能力解析语境，决定 realm、intercept、
  logger 与当前插件；内部资源 `Context` 决定 `effects()` 的实际 owner，`scope()` 只报告该 owner 所在的
  生命周期 Scope。普通服务调用的资源 `Context` 就是 caller，因此插件内调用创建的资源仍归插件实例；
  宿主经贡献目录调用时，前者固定为贡献注册 owner，资源 `Context` 固定为临时 invocation Scope 的根
  Context，二者连同 cancellation 沿嵌套 `ServiceRef` 原样传播。两者必须属于同一 RuntimeDomain，公共
  构造入口在接纳调用前校验，禁止跨域登记资源。
- `EventKey` 固定稳定名称、listener 类型和 `EventMode`。支持 `EMIT/PARALLEL/SERIAL/BAIL/WATERFALL`；
  调用方式与 key mode 不一致时直接拒绝。
- 事件诊断的历史类型描述只保留类型名与 mode，不能永久持有退休的 listener Class。活动监听器必须
  与当前 key 的接口类型一致；最后一个监听器注销后，允许同类型名的新装载器接口接管。已捕获派发
  快照仍只调用自己捕获的监听器，不改变捕获时机，也不转向同名新注册。
- `once` 的调用权归监听器注册本身所有，而不是归某次派发快照所有。异步派发重叠、重复订阅或
  同步重入时，每个注册最多开始一次调用；调用抛错或开始后取消不恢复调用权。过滤、提前截断或
  在到达该监听器前取消不能消耗调用权；普通监听器保持既有快照行为。
- 同一 effect 内严格逆序串行清理；实例顶层 effects 并发启动并 all-settled。
- 异步 effect 逐项 `request(1)`。dispose 不丢弃已经请求的在途元素；元素到达后先归属，再停止请求并
  清理。
- 服务对象不会因为实现 `AutoCloseable` 就被推测性关闭。外部容器拥有的对象只注册 binding；Fibra
  拥有的资源必须显式登记 disposer。
- 协作取消使用 owner 持有的 `CancellationSource` 与只读 `CancellationToken` 分离表达；插件不能反向
  取消调用者。token 随 `ToolRequest` 进入并通过派生 `InvocationContext` 沿 `ServiceRef` 传播。显式 token
  取消允许业务返回稳定的 aborted 结果；直接取消 Reactor 订阅只触发 invocation Scope 清理，不伪造一个
  已无人接收的结果。两条路径都必须等待其拥有资源的清理边界，不能把订阅消失等同于进程已经退出。
  已启动的远端调用是 invocation Scope 拥有的 `DrainingDisposable`：调用方结果订阅、原请求终态和实例
  sidecar 终态是三个不同边界。逐请求取消至多发送一次协议通知，并继续接收原请求的成功或失败终态；
  通知已经发出不代表远端已经停止。请求 deadline 先进入同一逐请求取消流程并等待明确的取消宽限，只有
  宽限耗尽、协议故障、心跳失败或异常退出才升级为实例级故障并终止 sidecar。升级前同实例的其他请求
  继续运行；升级后受影响请求共同等待受管进程范围静默，不能用 aborted 隐藏远端业务失败或清理失败。

## 4. Engine 与局部动态更新

### 4.1 变更协调与资源所有权

Engine 长期持有一个运行域、一个贡献目录和各 runtime 的资源所有者。配置变化比较稳定实例身份、
definition、配置和有效继承策略：新增实例挂载，删除或停用实例撤销；仅配置变化使用实例更新协议；
definition 或有效隔离归属变化重挂受影响实例。没有变化的实例、effects、ClassLoader 和 Node 进程保留。
服务 provider 变化引起的消费者停止和重新激活由内核依赖协议驱动，Engine 不另建第二套服务调度器。
同一 `ChangeSet` 中所有仅配置变化的既有实例组成一次域级目标批量登记，再由内核依赖图独立收敛；
新增和重挂实例仍按所有权先决顺序处理。Engine 只把 `PLUGIN_BATCH_UPDATE_FAILED` 识别为“目标已全部登记、
实例收敛失败”，继续捕获并发布实际状态；登记前错误直接终止协调，不能通过读取某个实例状态猜测失败阶段。

制品变更先校验完整目标依赖图，再按变化制品及其反向依赖闭包准备资源。Java 的已链接契约类型要求
消费者装载器随所依赖契约一起替换；不能只更新 provider 的 ClassLoader。未受影响的装载器继续使用。
adapter 负责计算其运行时的物化影响范围并返回 catalog 与资源身份，Engine 负责实例协调与唯一视图发布；
adapter 不持有另一份宿主发布指针。Node 进程归插件实例的受管资源范围，目录撤销及调用排空后才能清理。

adapter 创建长期 `RuntimeResourceOwner`，每次制品变更创建并登记一个有界 `RuntimeResourceUpdate`。
update 的准备结果包含受影响制品集合、目标 catalog 及 definition 到制品的归属；目标 catalog 复用
未受影响 definition 和装载器的原对象。Java 在旧、新制品 DAG 边的并集上计算反向依赖闭包，
资源 identity 表示实际装载对象，不能用制品 revision 代替（依赖升级也会导致消费者装载器重建）。

预绑定期间只允许受影响闭包的新旧资源暂时共存，不创建另一整个运行域。`adopt()` 仅转移内存中的
资源所有权：新资源交给长期 owner，被替换旧资源交给 update；不执行 I/O、插件启动或关闭。
adopt 前关闭 update 只清理本次准备资源，adopt 后关闭只清理被替换旧资源，借用的未受影响资源
始终归长期 owner。Engine 先登记句柄再准备，并在旧实例安全清理后才允许旧资源关闭。
这不是通用事务参与者协议，不提供发布指针或运行态回滚。Java 预绑定可能执行入口类初始化、
入口构造器和 definition 构建，不能承诺完全没有制品代码副作用；插件 factory/start 不属于预绑定。

资源句柄在取得后、执行后续可失败动作前登记所有者；准备失败不使资源失去归属。停止与清理按以下
先决顺序进行，实际服务消费者也必须完成对旧 provider 激活快照的清理：

```text
受影响贡献停止接入 -> 排空已接受调用 -> 清理实例及其子资源
                 -> 关闭不再使用的 runtime 资源 -> 释放制品引用
```

同级独立资源逆序尝试关闭并聚合失败；先决层失败不得释放其仍依赖的下一层资源。保留失败资源身份、
引用与清理结果，不得仅从活动索引删除后宣告成功。准备和关闭缓存完整终态，重复关闭不掩盖首次失败。
尚在准备的资源关闭必须等待准备结束，已关闭资源不得再次取得资源。失败尚未处理完毕时，不接受会
叠加替换资源的后续变更；不能靠无限积累候选资源继续运行。

`DrainingDisposable` 是 owned resource 的专用排空契约，不是通用事务 hook。沿现有 Scope、插件和
effect 所有权闭包先冻结准入，再启动排空；同步回调及迟到异步资源不能绕过该边界。全部相关排空完成
后才启动普通清理；provider 还须等待实际消费者的旧 activation 清理，不等待其下一次激活。普通
effect 的逆序与告警隔离语义保留，但其失败句柄及真实依赖仍被持有，不能以消费者已退出活动索引为由
提前释放 provider。每个 runtime owner 自己保护失败资源的先决依赖，不能因一个 owner 失败跳过独立 owner。

此屏障只服务于实际受管调用和资源关闭顺序，不引入全域停机、第二份依赖调度图或通用补偿协议。
DSH 已有 effect 所有权、在途清理复用和服务撤销后的消费者等待；Fibra 直接采用这些原则，额外的
调用排空和失败资源保留用于避免关闭仍被调用使用的 Java 装载器、Node 进程及插件资源。
逐项源码与采用边界见[插件依赖、装载与更新的源码基线](../references/2026-09-09-plugin-dependency-baselines.md#资源调用与保存问题的采用边界)。

Engine 关闭先在线性化的命令准入边界停止接收新请求，等待所有已接受命令及其结果终态落地，再关闭
运行域、贡献目录、runtime 资源及持久存储。不能直接取消 command loop 的订阅，也不能在准备
尚未结束时缓存一个遗漏后续资源的关闭成功结果。正常关闭不取消已接受命令。
关闭操作独立持有排空与清理链，调用方中断只终止自身等待，不取消关闭、不跳过排空，也不污染共享关闭终态。
内部关闭采用可组合的异步完成链，不阻塞等待另一条关闭任务；清理推进不依赖宿主通知线程池。
命令结果投影在 command loop 内完成并冻结，结果与 `PublishedRuntime.views()` 的宿主通知异步交付，
不占用命令完成或关闭完成的调用栈；通知回调中关闭 Engine 或重复关闭不能形成自等待。订阅者取消
结果通知不取消 Engine 已接受并持有的命令。

### 4.2 PublishedRuntime 与 PublishedView

`PublishedRuntime` 是稳定宿主对象，内部只原子替换一个不可变 `PublishedView`，其中包含 Engine 状态、
贡献快照、运行诊断与 Engine 诊断。`viewRevision` 标识任意已发布事实变化；保存的目标 revision
标识声明内容。运行域 identity 不因局部更新而变化，不再使用 generation revision 表示目标或调用权。

宿主调用必须携带选择贡献时观察到的 expected view revision。准入须同时确认当前视图未过期、
目标贡献仍是该注册身份且开放，并登记在途调用。视图检查与条目准入之间的竞争必须重新复核；
失败返回明确的 stale-revision 或已撤销结果，不能悄悄转向同名的新 handler。旧快照不是永久调用权。

一次调用在目标 domain 内创建临时调用 Scope。贡献 handler 必须在注册 owner 的 Context 中解析服务和策略，
但其 `InvocationContext.effects()` 及通过服务调用创建的资源必须归临时调用 Scope。成功、失败或取消后都必须
等待该 Scope 的异步清理完成，再释放在途计数，最后异步向宿主交付结果；宿主回调不能占用生命周期线程
或未释放的调用租约。
取消订阅不取消已经启动的清理。Engine 关闭后拒绝新调用；局部变更仅停止受影响条目的接入，
无关条目的已接受调用不被取消，也不因为其他插件更新而等待整个 domain 排空。
远端 handler 必须在发送请求前先把请求资源登记到该调用 Scope；登记失败不得发送。结果订阅取消后，
请求资源仍由 Scope 的排空链推进到原请求终态，或推进到实例级终止并确认受管范围静默，最后才释放贡献租约。

调用 Scope 的“关闭流程结束”不等于“资源全部释放成功”。Engine 通过域内按真实 Scope 后代关系
筛选的 `cleanupFailures(scope)` 核验本次调用，不能把同名 Scope 或其他并发调用的失败混入。
清理失败须使对应贡献撤销并以明确排空失败结束，保留 provider 资源；不能正常释放租约冒充成功，
也不能靠永久挂起租约隐藏失败。一个已取得的调用句柄只能执行一次，关闭后的延迟订阅不得启动 handler。

视图是同一观测时点的事实投影，不是整个生命周期切换的原子事务。局部更新过程中的 PENDING、
失败、贡献暂时撤销和目标未达成必须可观察，不能保留一份虚假 ACTIVE 快照冒充仍可调用。

### 4.3 诊断投影

诊断是已发布运行事实的一部分，不从全局注册表或多个时点临时拼接：

| 投影 | 必需事实 |
|---|---|
| `EngineSnapshot.instances` | Engine 实际持有的声明实例、完整声明 ID、状态、声明的达成要求与 `requirementSatisfied` |
| `RuntimeDiagnostics` | 整个 domain 的实例事实，包括动态子插件；instance/definition identity、owner/父实例、状态、依赖、`waitingFor`、failure |
| `RuntimeDiagnostics.services` | `ServiceKey`、effective provider 与 shadowed providers |
| `RuntimeDiagnostics.events` | 事件名称、mode、listener type，以及 listener owner/order/once/global |
| `ContributionSnapshot` | 目录 revision、contribution id/kind/descriptor、provider/owner、注册身份及可调用状态 |
| `EngineDiagnostics` | 已保存目标 revision、变更阶段、受影响实例/资源、排空与清理失败、目标是否达成、mutation gate 与 Engine failure |

这些 DTO 只描述结果，不暴露 Fiber、Context、ClassLoader、Process、RPC channel 或 registration 句柄。
声明的达成要求不能按 instanceId 与整个 domain 的实例列表连接：动态子插件可能没有配置声明，
不同 Scope 中也可能使用相同局部 ID。达成规则仅应用于 Engine 持有的声明实例；全域诊断保留动态
实例事实，不为它们虚构声明策略。快照的 `requirementSatisfied` 与运行协调的状态验证共用同一规则。
运行实例具有 Runtime 内唯一、创建时分配且不复用的 identity；配置局部 ID 和 Scope 名称不承担
运行身份。声明实例状态与全域诊断从同一次域采样按 identity 投影，不能分别读取句柄的可变状态。
目录采样前后的单调 revision 必须相同，才能与该域采样组成 PublishedView；竞争时让出执行权后
重采，不持有目录锁等待 lifecycle lane，也不以紧循环阻塞命令队列。仅刷新 Engine 变更阶段时，
复用上一份完整运行事实，不单独替换其中的诊断或实例状态。

`RuntimeDomain.snapshots()` 提供整个域的最新不可变事实，覆盖动态子插件加入、退出、状态变化，以及
服务和事件监听器变化；不能只订阅 Engine 声明实例的状态。服务变化即使没有改变实例的 PENDING
状态，也必须更新依赖和 `waitingFor`。快照在 lifecycle lane 上生成，通知异步交付，允许合并中间
状态；它是事实流而非完整生命周期审计日志。域关闭时发布最终事实并完成流。Engine 将域事实与
贡献事实变化汇入同一个发布入口，诊断变化也推进 view revision。

### 4.4 ChangeSet 与持久目标

artifact、config 和联合 deployment 共用同一个 command loop。`ChangeSet` 只是一次受管变更的内部
执行计划，不是开放给任意资源参与者的两阶段提交或事务日志框架：

```text
observe -> validate / prepare affected artifacts / bind changed inputs
        -> save deployment target -> reconcile affected instances
        -> observe convergence / publish views -> retire unused resources
```

`ReplaceConfigContext` 走同一 command loop，但不是部署目标写入：先以候选 context 对当前 raw graph
完整求值并绑定，全部预检成功后才切换内存中的 context/evaluation，再差量 reconcile。它不创建 runtime
resource update、不写 `EngineStateStore`、不改变 target revision；view revision 与 context revision 独立
推进。求值或绑定失败时旧 context、evaluation、目标、实例、effects 与 PublishedView 全部保持不变。

- prepare 读取并冻结输入，完成制品摘要、依赖图和受影响声明的配置绑定；不执行插件启动，不拆旧运行态。
- reconcile 调用实例生命周期协议，并等待实际依赖图收敛；按声明要求判断目标达成，合法 PENDING
  不能一律当成失败。该阶段不是可回滚的预检，启动或清理失败必须报告实际状态。
- 所需不可变制品必须先可靠保存，目标清单只引用已完整保存且校验通过的内容；制品保存本身不选择
  活动版本。重复保存同一内容不得覆盖或删除既有对象。
- 制品先复制到操作独占的暂存位置，完整校验后才发布稳定对象；已有对象须校验后复用。
  准备失败、撤销或恢复只清理该操作自己的暂存资源，不删除可能被其他准备操作引用的共享对象。
  完整但未被目标引用的对象可以保留，不为失败清理引入通用 GC 或共享对象回滚。
- `EngineStateStore` 原子替换一份完整目标清单并确认落盘，随后 Engine 协调运行态并发布事实视图。
  成功响应须同时满足目标已保存、要求已达成及结果视图已发布；失败结果也必须区分目标是否保存、
  哪些实例已经改变与后续恢复条件，保存成功不是运行时已经可用的同义词。
- 保存目标前失败只清理新准备的资源；保存后不得因启动、发布或清理错误反写旧目标。清理按资源依赖逐层进行，
  前一层失败时保留后续先决资源；独立同级资源仍全部尝试并聚合失败。
- 排空与回收不决定保存的目标内容。回收失败进入健康诊断并关闭后续变更准入，不伪造旧路由恢复。

DSH 的配置 Entry 在应用失败时会尝试恢复旧配置；这里不自动反写已保存目标，是为了让进程内结果与
重启后读取的目标一致，避免引入第二次可能失败的目标提交。修正配置或恢复旧版本须提交显式新目标；
这是一项明确取舍，不表示 DSH 的恢复方案不合理，也不把 Fibra 描述为具有更强的运行态回滚能力。

`DeploymentManifest` 选择完整 artifact revision 集合和完整声明图，包括顺序、稳定实例身份、所属
分组、启停意图、配置、隔离和发布要求。停用声明同样保存，不要求对应 definition 已安装。部署
revision 为规范编码的内容摘要，与 source 和 view revision 分离。清单携带显式格式版本
及内容校验，不保存 `Class`、绑定后的配置或运行对象，也不把环境中的来源路径当成部署身份。

目标文件写入与替换由一个所有者执行：同目录临时文件写全并 force，原子替换后 force 目录，成功后
才确认保存。创建所需目录的所有者负责持久化目录链。读取同时验证格式、完整性和制品引用；不支持
原子替换或可靠同步的存储环境明确拒绝持久模式，不退回普通覆盖写。内存模式只承诺进程内行为。

恢复直接读取完整目标并重新建立 catalog、绑定配置、激活和发布，不回放插件生命周期，也不读取
多个日志或 current 指针推测活动版本。制品目录没有独立的活动选择真源；卸载先从目标集合移除，
物理回收必须等所有运行资源释放引用后进行。首次空存储可从配置源初始化，已有目标不能被启动时的
文件源、默认空配置或 watcher 静默覆盖；后续导入必须是显式或已启用的源刷新变更。

自动源刷新必须显式启用。watcher 只观察最近一次成功采集得到的来源目录并产生可合并 dirty signal，
周期 resync 负责发现丢失通知；两者都把重新采集提交到唯一 command loop，不建立第二条配置应用队列。
Engine 单独记录最近一次已接受的 source revision，它不随 Registry 管理变更改写；来源未变化时不得
重复保存或覆盖管理目标。读取、解析、结构校验或在当前 context 中求值失败，均保留 last-good 目标、
实例和 effects，公开来源失败
但不关闭 mutation gate，也不把仍然达成的目标误报为失效；恢复为相同 revision 时只清除来源错误，
不重新协调实例。关闭先停止新来源信号准入，等待已接受刷新落地，再关闭 watcher 和运行资源。

| 故障点 | 处理与恢复 |
|---|---|
| 目标替换前失败 | 旧目标与旧运行态不变，清理新准备的资源；尚未引用的不可变内容不成为活动制品 |
| 替换可能发生、但同步或确认失败 | 不报告成功、不猜测未保存；关闭变更准入，保留新旧目标所需内容，存储重新可靠读取前不继续写入 |
| 目标已保存、协调尚未结束时崩溃 | 重启按保存的目标重建；不承诺崩溃前未完成的调用仍能收到响应 |
| 目标已保存、启动或清理失败 | 发布实际状态及未达成要求，保留必要资源，明确失败与恢复条件；不声称整批回滚 |
| 已达成目标后回收失败 | 报告残留资源与故障，不倒退已保存目标 |
| 目标损坏、引用缺失或重建失败 | 启动或恢复明确失败；不自动回退旧版本、不用当前源文件猜测修复 |

运行诊断中的变更阶段不是重启恢复日志。操作审计独立记录，失败须可观察，但不能把已经成功的
部署返回成失败，也不能反向改变目标；不保证部署结果与审计记录恰好一次或原子持久化。业务若要求
强审计，应在宿主层另行定义协议，不能偷偷扩大 Fibra 的提交边界。

审计结果以 `TargetSaveState` 区分 `NOT_SAVED`、`SAVED`、`UNCONFIRMED`，与操作是否达成分别记录。
成功操作只允许 `SAVED`；`SAVED` 仍可对应未达成操作。文件审计写完整条记录并同步后才确认，
写入或同步失败后当前仓库停止追加，保留证据至关闭；重开拒绝残缺记录，不自动截断或改写。
投递失败通过 `PluginRegistry.auditFailures()`、查询快照及操作返回快照诊断；`watch()` 仅跟随 Engine
事实变化，不承诺为审计失败另发通知，也不把审计诊断与运行事实伪装成一次原子采样。

### 4.5 一致性保证与非承诺

Fibra 保证：

- 输入预检失败不改变正在运行的实例；局部生命周期失败如实发布，不冒充原子回滚；
- 同一逻辑变更的既有实例配置目标先整组登记再开始收敛，组内不会以旧目标启动新的 activation；
- `EngineSnapshot`、贡献快照、运行域诊断和 Engine 诊断来自同一个 view revision；
- 新调用只进入当前开放的贡献注册，已接受调用计入该注册的排空直到完成或被明确终止；
- 未受影响实例、运行资源和在途调用不会因为其他插件配置变更而重建或中断；
- 已保存部署目标、已登记资源清理和恢复结果可查询；
- Java-only、Node-only 和 Java+Node 变更使用同一个目标保存、协调和事实发布协议。

Fibra 不承诺：

- 回滚插件已经发送的邮件、网络请求、数据库提交等任意外部副作用；
- 与外部数据库、消息系统或浏览器客户端组成分布式 ACID 事务；
- 通用多参与者持久事务、部署与审计原子提交、任意插件运行状态的序列化恢复；
- 多实例更新的原子生效、失败后旧实例状态的无损复原、替换服务期间始终可用；
- 远程调用 exactly-once；
- 对非可信插件提供安全沙箱；
- 所有在线客户端与服务端视图在同一瞬间切换。

## 5. 模块与依赖边界

依赖方向如下，箭头表示左侧依赖右侧：

```text
fibra-core                -> fibra-api
fibra-config              -> fibra-api
fibra-artifact            -> 无其他 Fibra 模块
fibra-bridge              -> fibra-api
fibra-engine              -> fibra-core + fibra-config + fibra-artifact + fibra-bridge
fibra-runtime-java        -> fibra-engine + fibra-artifact + fibra-api
fibra-runtime-node        -> fibra-engine + fibra-artifact + fibra-bridge + fibra-api
fibra-registry            -> fibra-engine
fibra-spring              -> fibra-api + fibra-engine
fibra-spring-boot-starter -> fibra-spring + fibra-registry + fibra-runtime-java
```

`fibra-plugin-archetype` 只生成依赖 `fibra-api` 的独立插件工程。`fibra-parity-tests`、
`fibra-benchmarks`、`fibra-example` 和 verification 不发布为运行时模块。

| 模块 | 唯一主要职责 | 禁止承担 |
|---|---|---|
| `fibra-api` | 稳定插件与宿主契约 | Runtime 实现、装载框架、Spring、业务类型 |
| `fibra-core` | RuntimeDomain、Scope、插件生命周期、服务、事件、effect | 配置、制品、Engine、文件格式 |
| `fibra-config` | desired model、repository 端口与编译 | ClassLoader、Runtime 修改、Engine 事务 |
| `fibra-artifact` | 运行时中立制品身份、校验、不可变内容保存与引用释放后的回收 | Java/Node 私有物化、活动部署选择、config、Engine |
| `fibra-bridge` | 通用贡献身份、Scope 归属、撤销、快照与调用适配 | 具体贡献类型、制品安装、Engine 事务 |
| `fibra-engine` | runtime 端口、command、ChangeSet、单一持久目标、PublishedView | 具体 runtime、Spring、宿主业务、通用事务协调器 |
| `fibra-runtime-java` | manifest、依赖图、隔离 ClassSpace、Java 插件物化 | Node、config、宿主业务 |
| `fibra-runtime-node` | Node package、sidecar、JSON-RPC endpoint | Java ClassLoader、config、具体业务类型 |
| `fibra-registry` | 安装、版本、期望状态与审计控制面 | runtime 私有对象、业务贡献目录 |
| `fibra-spring` | Spring 宿主服务显式收集与 Engine 启动桥接 | Engine 装配、制品事务、宿主业务 |
| `fibra-spring-boot-starter` | 默认 composition root | 业务规则与场景协议 |

禁止新增 `common/shared/utils` 发布模块承接边界不清的代码。跨 Fibra 框架模块且属于公开语义的类型
进入 `fibra-api`；业务产品的跨插件/宿主契约进入该产品已发布的 API 模块，不能反向污染框架 API。
纯实现复用不足以成为新模块。

## 6. 输入、运行时与宿主适配

### 6.1 Config 与 Artifact

`fibra-config` 把文件或程序化声明采集为不可变、有序的 `DesiredInputGraph` 条目树，区分插件、分组
和 include。每个节点保留本地 identity、enabled、realm 和 intercept；插件另持 definition、literal
config 与 publication requirement。patch 在采集时应用，但不能抹掉容器、归属和本地开关。
源路径与 source revision 单独用于采集和诊断，恢复不读取源文件。程序化变更同样构造这种输入，
不覆盖只读文件源，不把 live handle、POJO、`Class` 或绑定后的配置当成持久声明。

include 补丁采用 DSH 的条目补丁模型：`id` 定位，`plugin` 可选且只作名称保护；其余声明字段按键
浅覆盖，显式 `null` 保留。`insert` 接收条目列表，无 `id` 时追加到被 include 文档的根列表，
有 `id` 时追加到对应 group 的子列表。位置移动走已有树编辑接口，不另设补丁位置语言。

补丁执行前复制输入并建立当前文档的 ID 索引，索引递归分组但不跨 include；按序执行，仅显式
insert 的新节点补入索引。普通子列表整体覆盖产生的新后代不加入本次索引，与 DSH 固定基线一致。
覆盖缺少 ID、目标未匹配、名称保护不符或插入目标不是 group 时产生诊断并继续后续补丁；未知补丁
字段、ID/插入列表控制形状、最终条目结构及重复 ID 仍拒绝编译。跳过的覆盖值不进入有效声明，
不额外复制声明校验器检查这些未应用的值；匹配后的实际输入仍接受原有结构和配置绑定校验。
诊断保留来源与补丁位置，Engine 读取输入时记录告警，
避免启动和 Registry 只取 PublishedView 时静默丢失。移除或修改补丁必须从原始输入重新应用，
不得污染文件解析结果。采用边界和对应源码见[配置装配证据](../references/2026-09-09-plugin-dependency-baselines.md)。

本地 ID 在所在 include 命名空间内唯一，索引覆盖该空间中所有分组后代；完整 ID 由 include 父链
派生，不再持久化一份可能失配的 parentId/完整 ID 副本。有效启用是本地开关与祖先开关的合取；
策略取父链上最近声明者，派生结果保留声明 owner。`realm: true` 使用 owner 的局部身份，非空字符串使用
域内命名身份，两类身份不得碰撞；`false/null` 遮蔽祖先策略并回到默认 realm。intercept 按键整体覆盖，
不深合并；`null` 去掉该键的配置层策略，恢复 definition 的默认值。非法策略在输入阶段拒绝，不能
等插件已开始挂载后才报错。上下文必须按服务名称完整派生策略，不能仅保留当前插件声明的
requires/provides，否则动态子插件会丢失继承。

有效停用的 include 不读取文件，未采集内容与已采集的空内容明确区分。已采集的子树停用后仍保存；
未采集 include 若因自身启用、祖先启用或移动而变为有效启用，必须在修改运行态前拒绝并要求补齐内容。
补齐通过显式重新采集源或提交完整子树完成，恢复不暗中读取来源，不把缺失内容当作空组。

树编辑显式指定父节点：新增追加，已有同 ID 条目只允许在原父节点下替换；改变归属必须使用 move。
移除容器会移除整棵子树。同一 include 命名空间内移动保留完整 ID，跨 include 移动则使子树完整 ID
随命名空间改变，运行期按旧身份移除、新身份挂载处理，不承诺跨命名空间移动保留实例状态。

跨边界字面值使用 `fibra-api` 中封闭的 `LiteralValue`：null、boolean、string、有限精确 number、保序
list 和字符串键 object。容器递归不可变，对象键规范排序，数字规范化；规范编码必须与插入顺序、机器
路径和 Java 对象身份无关。配置、部署清单和公开描述快照使用同一数据边界，禁止浅拷贝冒充不可变。

每个 desired 节点另保留原始 `when` 与局部 `context`。`enabled` 是持久管理意图；`when` 是在当前
运行上下文中派生有效状态的条件，二者与祖先有效状态合取。局部 context 从根到叶浅覆盖，宿主
`ConfigContextSnapshot` 位于最外层；Engine 注入只读的 `/entry/id` 与 `/entry/parentId`，因此配置不得
声明顶层 `entry`。raw graph、宿主上下文和 `DesiredEvaluation` 三者分离，求值不得改写原始表达式。
source revision 只标识采集内容，context revision 单独标识运行上下文，二者都不混入持久目标 revision。

条件与配置模板使用受限 `LiteralValue` AST：`$ref` 按 RFC 6901 JSON Pointer 读取上下文，`$defined`
判断路径是否存在，`$eq` 做字面值严格相等，`$not`、`$all`、`$any` 组合布尔条件，`$if` 惰性选择
分支，`$literal` 转义恰好一个保留操作符键的普通对象。操作符对象必须恰有一个键；条件结果必须是
boolean，不做 truthy 转换；数组索引只接受规范 ASCII 十进制形式。求值最大深度为 100，不允许脚本、
反射或宿主函数。程序化 builder、文件编译和持久清单解码都先校验 AST 结构；缺失引用等依赖当前
上下文的错误在求值时报告，并关联完整 entry ID。

patch 先作用于 raw `when`、`context` 和 `config`，完整结构校验后才求值。祖先或本节点条件为 false
时跳过后代条件及插件配置求值，避免在错误的局部上下文中提前解释叶子配置。`include.enabled` 仍控制
是否采集文件；`include.when` 只控制已经采集并持久保存的子树是否进入运行态，不能作为延迟读取文件
的开关。这是长期单目标与离线恢复要求下的 Fibra 执行边界。

行为契约采用 DSH 的条件配置、局部上下文和惰性求值语义，但不把 DSH 的 JavaScript 执行器带入
Java core。当前八个操作符足以覆盖已知场景，且让采集、持久化和 Engine 预检共享同一确定性模型。
若真实插件需要解析字符串表达式、静态类型检查或持续扩展操作符，再以 CEL 替换内部 evaluator；
在此之前不承担 CEL、protobuf 与缓存栈的依赖和版本治理成本。

条目根插件主动停用采用显式意图，不从 `PluginInstance` 的 `DISPOSED` 或 `FAILED` 事实反推管理目标。
插件通过自身 `Context` 的 `Plugins.requestDisable()` 提交异步请求；core 只接受 `STARTING` 或 `ACTIVE`
实例，并把精确实例身份交给域内控制面。Engine 在唯一 command lane 上再次校验该对象仍是当前托管
条目根、raw `enabled` 仍为 true 且当前条件有效，然后以执行时的最新 desired revision 构造
`withEnabled(entryId, false)`，复用既有 ChangeSet 先保存完整目标、再排空并关闭该条目。请求不返回
完成句柄，避免插件在 `start()` 内等待自身所属 Engine 命令形成死锁；重复请求和已被替换的旧实例
按身份 CAS 静默丢弃。动态子插件、直接 `dispose()`、普通失败、配置更新导致的重挂载、祖先停用和
Engine 关闭都不写回 desired。自停用不是 Registry 用户操作，不伪造审计历史；Registry 从
PublishedView 观察结果。Node 仅接受 sidecar 的严格 `fibra.disable` JSON-RPC notification，且通知方
不能提供目标 ID；Java wrapper 代其提交同一意图，sidecar 不做进程终身去重，使目标保存失败后可由
插件再次请求，重复意图由 Engine 的身份和最新目标校验收敛。sidecar 继续服务到目标保存并收到既有
`fibra.stop`。这保留 DSH“只有条目根主动行为才持久停用”的契约，同时满足 Fibra target-first、
失败可见和局部排空边界。

绑定使用准备后的目标 catalog 与程序内建 definition；启动时绑定全部有效启用插件，更新时只绑定
新增或受影响的插件。有效停用声明不查找 definition、不绑定配置，允许保留尚未安装的插件。
本次受影响输入全部绑定、校验成功后才保存目标并改变运行态，避免可预检的配置错误造成部分停启。
绑定结果由 Engine 临时持有，不引入另一份公开或持久化图。typed config 与 requires/provides 的
`ServiceKey<Class>` 不进入持久清单或宿主快照，也不跨已替换的 ClassLoader 复用。

配置校验器可以返回规范化后的值，不要求幂等。`PluginDefinition.prepare(config)` 只校验一次，
产生构造受控的 `PluginDefinition.Prepared<C>`，不创建插件、不注册资源；`Plugins.mount(id, prepared)`
消费该结果并创建实例，不重复校验。更新同样消费与该 definition 身份匹配的 Prepared，不把已经
规范化的 config 再交回校验器；每次新输入只 prepare 一次。Prepared 只属于产生它的 definition
及装载器生命周期，不是可序列化的部署输入。

`DesiredCompilation.entrySources` 单独保存实例的来源路径，用于绑定失败的首次诊断。路径不进入
`DesiredInputEntry`、输入图相等性或部署内容摘要。宿主实例快照返回输入图中的 `LiteralValue`，
不能从运行实例的可变 typed config 反推声明。

`fibra-artifact` 只管理通用 identity、版本、摘要、不可变内容及其准备和回收状态。它不知道 JAR、npm、
ClassLoader 或 Process。配置与 artifact 互不依赖，由 Engine 在 ChangeSet 中对齐。

程序内建 definition 与 runtime catalog 合并后供目标输入绑定；语法解析和声明采集不持有 catalog 的
类型对象。绑定端口不向输入源暴露制品路径、运行时句柄或运行实例。

#### Profile、配置 Bundle 与插件包

Fibra 保留 DSH `profile` / `bundle` 的组合职责，但不复制其 npm、pnpm 或 `package.json` 实现。
DSH 中已安装 package、bundle patch、profile 组合和最终运行配置是四个不同层次；Fibra 必须保持同样
的分离，不能把扫描到的插件目录直接当作本次运行目标，也不能把配置 bundle 混同为插件 JAR。

| DSH 职责 | Fibra 落点 |
|---|---|
| package 的物理安装 | `ArtifactPackage`、`ArtifactStore` 与 Registry 安装记录 |
| bundle 提供的有序配置 patch | `DesiredInputGraph` 的 include 与条目 patch |
| profile 选择 bundle 及其顺序 | 命名配置入口按声明顺序组合配置 bundle |
| profile 声明的完整 package 选择 | 相邻的 `<profile>.artifacts.yaml` 输入清单，只列相对候选目录的包路径 |
| profile 的 package 依赖闭包 | `DeploymentManifest` 的 artifact revision 集合及 runtime 制品依赖图 |
| profile 合成后的活动配置 | Engine 保存的单一完整部署目标 |
| 应用运行态 | 长期 `RuntimeDomain` 中按目标差量协调的实例与资源 |

这里的 profile 是一次宿主启动选择的应用组合，不是 Spring Profile，也不是新的运行域。正式 CLI 每次
启动选择一个 profile；profile 名称只决定配置入口和持久数据命名空间，不进入 artifact identity、实例
identity 或 service realm。配置 bundle 是普通、可复用的配置源片段，以 include 和 patch 表达实例、
分组、realm、条件及局部覆盖；组合顺序必须由 profile 显式声明，不能依赖目录遍历顺序。插件包只携带
代码、私有依赖和运行时声明，安装插件包本身不隐式启用实例，也不自动注入配置 bundle。

正式分发由根 reactor 中的顶层 `fibra-distribution` 聚合模块装配，不放入 example 或 acceptance。
根目录 `mvn clean package` 与 `mvn -pl fibra-distribution -am package` 均直接生成
`fibra-distribution/target/fibra-<version>/` 和同级 `fibra-<version>-bin.zip`，不需要人工复制、改名或补充
依赖。发行目录采用下列职责布局：

```text
fibra-<version>/
  LICENSE                           项目许可证
  THIRD_PARTY_NOTICES.md            第三方声明
  bin/fibra                         POSIX 启动脚本
  lib/                              CLI 与宿主库
  plugins/<plugin-id>/              候选插件包
    plugin.properties               包布局描述
    lib/                            Java payload 与私有依赖，或 Node payload 目录
  runtime/bin/node                  目标平台 Node 运行件
  runtime/bin/rg                    目标平台 ripgrep 运行件
  runtime/bin/bash                  固定转发到目标系统 /bin/bash
  config/profiles/<profile>.yaml    命名组合入口
  config/profiles/<profile>.artifacts.yaml  完整候选包选择输入
  config/bundles/*.yaml             可复用配置片段
  data/profiles/<profile>/          首次启动时创建的持久目标、制品与运行数据
```

ZIP 不预置 `data/`，首次启动从空数据目录建立所选 profile 的完整目标。`bin/fibra` 只根据自身真实目录定位
`lib/`、`plugins/`、`config/`、`runtime/` 与默认 data 根，不能嵌入构建机或仓库绝对路径；从任意工作目录
启动时语义相同。发行包携带构建目标平台的 Node 与 `rg`，要求目标系统提供 Java 21 和 `/bin/bash`；
`runtime/bin/bash` 是固定系统边界，不接受构建时绝对路径覆盖。ZIP 内不得出现符号链接、`target/`、
`.DS_Store`、绝对路径或 `..` 路径。

插件包根必须是普通目录且整棵树不含符号链接；外层 `plugin.properties` 只允许三个字段：

```properties
formatVersion=1
runtime=java
payload=lib/plugin.jar
```

字段缺失、重复、未知或格式版本不支持均拒绝；`payload` 必须是包根内部已存在的独立文件或目录，不能
为包根本身、绝对路径或越界路径。外层描述只解决运行时选择和 payload 定位，不重复声明插件 ID、版本、
依赖或入口；这些仍以 Java/Node payload 内部 manifest 为唯一事实。探测返回整个包根作为待安装 source，
`ArtifactStore` 校验并复制完整目录，runtime 在受管副本中重新读取外层描述和内部 manifest，并核对保存
记录中的 runtime、ID 与版本，不能保留对候选目录的运行依赖或提供裸 JAR/旧 Node 目录 fallback。

Java payload 是含 `META-INF/fibra/plugin.yaml` 的主 JAR；同包 `lib/*.jar` 按规范文件名顺序加入该插件的
唯一 ClassLoader，主 JAR 固定最先，私有依赖不进入宿主或其他插件 ClassLoader。主 JAR 和纳入该
ClassLoader 的私有依赖 JAR 若 `MANIFEST.MF Class-Path` 非空则拒绝，避免 URLClassLoader 隐式引入未受管
路径。Node payload 必须是目录，
其中 `fibra-plugin.yaml` 和相对 entrypoint 一同被保存；entrypoint 不能是绝对路径或逃出 payload。

`<profile>.yaml` 保持现有配置条目数组语法，不增加包管理字段或 profile 顶层对象；相邻的
`<profile>.artifacts.yaml` 是必需的字符串数组，例如：

```yaml
- fibra-fs
- fibra-fs-local
- fibra-tool-fs
```

每项是相对所选候选插件目录的包根路径，不是 artifact ID。清单不重复声明 ID、runtime、版本、入口或
依赖；这些事实仍由 `PluginArtifactProbe` 通过标准包及其内部 manifest 读取。清单必须显式列出本次
目标的全部制品，包括所需依赖包，不根据配置 definition 名猜测闭包，不执行版本范围求解。未列出的
候选目录完全忽略；清单顺序仅保留输入与诊断顺序，不取代 runtime 的实际依赖顺序。

缺失、空文件、`null`、非数组、非字符串或空白项均拒绝；`[]` 是唯一明确的空制品选择。路径按候选根
规范化，拒绝绝对路径、根本身、越界路径、URL、通配模式及重复规范路径；解析真实路径后再次检查
候选根边界与重复路径，拒绝通过中间符号链接逃出候选根。包本身仍遵守上述无符号链接约束。不同包路径
探测出相同 artifact ID 也拒绝。清单读取沿用配置文件的大小、嵌套、字符串和条目数量限制。

`fibra-cli` 解析 profile、配置根、候选插件目录和数据目录，并把显式包路径清单交给统一 probe；
`fibra-config` 负责 include、patch、条件及
配置树编译；Registry 负责安装、版本、期望状态和审计；Engine 只接收完整 desired graph 与 artifact
选择，不感知 profile/bundle 的文件组织；runtime adapter 只解释目标引用的 payload；distribution 只
提供默认目录和正式组合内容。不得新增与 `DesiredInputGraph`、`DeploymentManifest` 平行的
`BundleManager`、`ProfileRuntime` 或第二套活动状态。

首次所选 profile 的持久存储为空时，配置入口由 `DesiredStateRepository` 采集、组合，包清单由惰性的
`InitialArtifactSource` 采集；两者经同一个 ChangeSet 建立初始完整目标，不能先逐个安装再提交配置。
空配置数组与空制品数组构成合法空目标。已有完整目标时，重启必须直接恢复该目标，不读取两个 profile
源文件或候选目录；它们即使缺失也不影响恢复，默认 profile 或分发升级不得静默覆盖保存目标。

显式 `apply` 重新采集配置与完整包清单，验证后通过 Registry 的 deployment 请求一次性替换目标；
不与当前安装集合取并集。此时列出的候选包必须存在，不能缺失时隐式回退到已安装版本。修改候选包不
自动升级目标，只有显式 apply 或 Registry upgrade 才选择新内容。`refresh` 与明确启用的自动源刷新
只刷新配置图、保留当前制品选择，不读取包清单或扫描候选目录；不能把它们描述成完整 apply 的同义词。

Registry install、upgrade、uninstall 只改变当前保存目标，不回写 profile 或包清单；后续完整 apply
明确以输入清单替换这些命令式制品变更。安装不修改实例启用意图或配置 bundle，但可能补齐已有启用
声明所缺的制品，从而使该声明达成。输入文件是下一次导入来源，`DeploymentManifest` 仍是唯一保存
目标，不增加活动选择指针或第二安装数据库。切换 profile 等价于选择另一套持久数据命名空间并启动
新的宿主进程，不在同一 Engine 内实现整代切换或回滚。

正式 CLI 以 Picocli 4.7.7 提供同一棵一次性/交互命令树，以 JLine 4.4.3 `jdk11` 制品提供 Java 21
终端输入；不引入 Spring Shell 或第二宿主生命周期。命令面固定为 `plugins list/install/upgrade/
uninstall/enable/disable`、`tools list/invoke`、`apply` 和 `repl`。REPL 只在入口建立一次
`CliHost`，每行复用同一 Engine、Registry、PublishedRuntime 和持久资源；REPL 内不允许改变 profile
或根目录，切换命名空间须启动另一宿主进程。插件与工具输出使用确定性单行 JSON，usage、宿主启动、
业务/调用、revision 冲突、变更关闭和宿主关闭分别使用稳定退出码 2、3、4、5、6、7。

`tools list` 从单个不可变 `PublishedView` 读取 descriptor；`tools invoke` 使用该 view 的 revision 调用
同一 `PublishedRuntime`，不直接访问插件实例或 Node sidecar。CLI 为每次调用建立独立
`CancellationSource`；正常命令结束、REPL 退出或 JVM shutdown 均先请求取消 CLI 所有在途调用，再由
Engine 的既有 drain/Scope/资源所有权边界完成关闭，不能强制跳过清理。
真实进程收到 `SIGTERM` 时同样走该关闭路径：启动器以 `exec` 交出进程身份，宿主先停止接入并取消在途
调用，等待工具终态、Engine drain 和受管进程范围静默，再结束 JVM；不能只杀直接 payload PID 或把信号
退出当作清理完成。
工具返回契约采用“provider 成功产物、Harness 调用终态、外部协议投影”三层边界。`ToolResult` 只表示
provider 已成功产生的不可变结果，由有序 `content` 与可选 `structuredContent` 组成；后者使用任意
`LiteralValue`，严格区分缺省与显式 JSON `null`。本期只有文本内容块具备 producer、wire 与宿主消费的
完整闭环，不以无类型 map 预演 image、audio 或 resource。`ToolOutcome` 在 Harness 工具调用边界把
`ToolResult` 归一为成功，把明确的 `ToolException` 归一为带稳定 `ToolFailureCode`、消息和文本内容的
失败；未知贡献、revision 冲突、畸形输出、断链及未知异常仍属于宿主或协议失败，不猜测成工具业务错误。
Engine、通用 contribution 和 runtime adapter 不识别工具终态类型。

该分层采用 DSH 的实际执行模型：工具 body 返回 canonical JSON 成功值或抛错，`ToolRuntime` 才生成判别式
成功/失败；DSH 的 MCP bridge 收到远端 `isError=true` 也先进入同一抛错/归一化路径，而不是把 MCP
传输对象当作工具 body。Fibra 内部 `fibra.tool` Node wire 使用独立 schema v2：成功输出只传必需
`content` 与可选 `structuredContent`，明确工具失败仍通过版本化远端业务错误传输；v1 不保留兼容分支。

外部 MCP 以当前最新正式规范 `2026-07-28` 为适配基线。MCP adapter 只在协议边界增加
`resultType="complete"`、`isError` 与协议 `_meta`，并把 Fibra 终态映射为 `CallToolResult`；
`structuredContent` 可为任意 JSON 值，若工具声明 output schema 则适配器必须校验。MCP 的
`InputRequiredResult(resultType="input_required")` 是调用尚未终结时的多轮交互 envelope，不是成功值或
工具失败；未来由 Harness 交互编排层管理 `inputRequests`、不透明 `requestState` 与重提，不能塞进
`ToolResult` 或 `ToolOutcome`。同理，`viewRevision`、provider identity、请求/trace ID、退出码、重试、
耗时、Throwable 和 DSH 的 presentation meta 均属于调用 envelope、诊断或 adapter，不进入结果领域对象。

其他主流协议验证了同一边界，但服务于不同层次。A2A `1.0.1` 面向 Agent 间异步任务：`Task`、
`Message`、`Artifact`、`INPUT_REQUIRED/AUTH_REQUIRED`、订阅和推送属于 Tool 之上的 Agent/Task 编排层；
单次工具失败只有在 Agent 不再重试、改计划或追问时才可能使 Task 进入 `FAILED`。ACP v1 与 AG-UI
主要承载客户端可观察的调用 ID、pending/in-progress/terminal 状态、权限/补充输入、增量内容及界面事件；
OpenAI Responses 和 Anthropic Tool Use 的 `call_id/tool_use_id`、流式序号与 `is_error` 同样属于调用
关联或协议投影。未来 adapter 必须建立独立的 Harness invocation envelope 和 Agent task envelope，不能
把这些协议字段反向塞进 `ToolResult`/`ToolOutcome`，也不能把每次内部工具输出机械提升为 A2A Artifact。
文件、多模态、diff、terminal 等内容只有在资源所有权、传输和宿主消费形成完整闭环时才增加明确的
`ToolContent` 变体，不设置无类型协议透传字段。

CLI 投影使用 `content`、可选 `structuredContent`、`isError`、失败时的 `error {code,message}` 以及独立
`viewRevision`；直接 `tools invoke` 的明确工具失败仍使用业务失败退出码 4，不为每个工具码发明另一套
进程退出码。该 JSON 是 CLI envelope，不冒充 MCP JSON-RPC response，也不机械携带 MCP 的
`resultType` 或 `_meta`。

### 6.2 Java Runtime

标准 Java 制品使用 `META-INF/fibra/plugin.yaml`：

- executable 制品显式声明唯一 entrypoint；
- contract-only 制品省略 entrypoint，只作为依赖图和 ClassSpace 节点；
- 每制品独立 ClassLoader，按显式依赖图委派；父优先前缀先查 parent，父加载器没有该类时再查本制品与
  声明依赖。宿主实际导出的公共契约因此仍由 parent 唯一定义，动态 contract 不因使用同一产品命名空间
  而被误当成宿主必备类；
- 禁止扫描全部 class 猜入口，不生成 extension index，不维护第二套插件状态机；
- 替换变化制品及其反向依赖闭包，闭包外装载器保留；旧类型仍被实例、服务槽或调用持有时不得回收；
- close-and-collect 必须有可观察门禁。ClassLoader 只提供类型隔离，不是安全沙箱。

### 6.3 Node Runtime

Node 插件作为受管 sidecar，通过版本化 JSON-RPC 协议参与同一 `PluginRuntimeAdapter`：

- 预检完成包与声明校验；实例激活负责进程启动、握手及能力与 schema 协商，失败属于运行收敛失败；
- endpoint 先适配为通用 contribution，再进入域内目录；
- 超时、取消、心跳、stderr、异常退出和消息边界必须结构化上报。`defaultRequestTimeout` 是原请求执行
  deadline，`requestCancellationTimeout` 是发送逐请求取消后等待原请求终态的宽限，两者不能合并；
- 单次请求取消或执行超时不能直接关闭共享 sidecar。宽限内远端结算只结束该请求；宽限耗尽才把 sidecar
  标记为实例级故障，停止其准入并沿统一关闭屏障终止全部受影响请求；
- RPC 与进程所有权分离：`NodeSidecar` 只处理协议，内部 `NodeProcessUnit` 启动监督器并持有一个可等待的
  受管进程范围；RuntimeDomain 只等待该范围静默，不枚举或缓存瞬时后代 PID；
- retire 固定执行“停止接入、关闭 RPC stdin、等待协作退出、软终止、强终止、确认范围静默、清理会话目录”；
  监督器必须在自身退出前写出范围静默终态，Java 侧在进程退出后校验该证明；调用方线程中断不得跳过
  等待。缺少证明、明确失败或监督器仍未退出均作为清理失败传播到请求 Scope 与实例清理，并保留会话目录，
  不能完成 pending 请求或 runtime participant 冒充范围已经静默；
- POSIX payload 在独立进程组中运行，按 PGID 发信号并检查进程组消失；Windows 使用
  `taskkill /PID <pid> /T /F` 作为公开的较弱后端。后代主动离开进程组、Windows breakaway、监督器不可执行
  清理或机器失效不在本地 sidecar 的保证内，非可信插件必须交给 container、Job Object 或外部 sandbox；
- Engine snapshot 不暴露 Process、channel 或协议对象。

`ProcessHandle.descendants()` 只能用于诊断快照，不能成为生命周期所有权来源。此段只定义 Node runtime：
它维持最小监督器、POSIX PGID 与 Windows `taskkill` 的既有边界，不使用 Linux user-systemd scope 或 Windows
Job Object，也不新增 npm/Cordis 依赖。`fibra-subprocess-local` 是独立的 Java provider，才采用 DSH 的
provider-managed range；两者不得因同样处理子进程而混为一套实现。

### 6.4 Registry、Bridge 与 Spring

`PluginRegistry` 向宿主提供 install/upgrade/enable/disable/uninstall 和查询接口，并明确区分 artifact、
desired、observed 三类状态；observed 只来自 `PublishedView.engine()`。

`ContributionDirectory` 位于长期运行域内。Java handler 与 Node endpoint 由场景 adapter 转成相同
`ContributionKind`；Fibra 不规定 Tool、Agent 等 kind，也不规定外部名称渲染。撤销贡献、拒绝新调用和
排空在途调用先于 Scope 与 runtime 资源关闭。

贡献路由冻结的是成员集合，不是永久调用权。每次订阅在同一接入临界区确认目录和条目仍开放，再登记
在途调用；成功、失败和取消各释放一次。撤销后的旧路由不能重新取得调用权，未订阅的 Publisher 不占用
资源。目录保留已撤销但尚未排空的条目，重复关闭必须共享同一个完成结果，不能提前宣告回收完成。

`fibra-spring` 提供显式 key/type 的服务 bridge。`@FibraService` Bean 由 Spring 容器拥有，exporter
仅在 Engine 启动前将 binding 收集到 `HostServiceRegistry`；Engine 启动时冻结该收集表，并把 binding
复制到长期 `RuntimeDomain`。`ServiceRegistration.dispose()` 仅在冻结前移除待收集 binding，启动后不会
动态撤销已发布服务；运行域关闭与在途排空仍由 Engine 的 Scope 所有权树负责。
`fibra-spring-boot-starter` 收集所有 `PluginRuntimeAdapter` Bean，装配一个 Engine、Registry 和
PublishedRuntime；不能把 Engine 写死为 Java-only。
starter 只通过标准 `AutoConfiguration.imports` 发现，默认组件均使用 `@ConditionalOnMissingBean`，配置
通过 `fibra.storage-root` 与 `fibra.source.refresh-interval` 绑定；不引入另一套 JSON、`.env` 或 Spring
Profile 解释。`SmartLifecycle` 在宿主服务完成预注册后启动 Engine，并在 Spring 关闭阶段调用同一个
Engine 关闭入口；不得为 Spring 嵌入场景另建 JVM shutdown hook 或绕过 Engine 的排空结果。

默认 ArtifactStore 与 EngineStateStore 在 Engine 工厂内部创建，成功构造后由 Engine 唯一负责关闭，
不另注册为容器自动销毁的 Bean。工厂失败时释放尚未移交的存储，保留原异常及各项关闭失败。
显式提供自定义存储 Bean 时，同样将关闭所有权交给 Engine，须声明 `@Bean(destroyMethod = "")`；
容器不能在 Engine 因清理失败保留资源后，再独立释放对应存储锁。普通宿主服务 Bean 的容器所有权不变。

非 Spring 宿主遵循同一启动边界和所有权规则：外部服务在 Engine 启动前通过 `HostServiceRegistry`
显式登记；Engine 启动时冻结并复制 binding，不取得外部对象的关闭所有权。启动后不提供动态
host-binding 变更入口。

## 7. 公开使用边界

### 7.1 纯内核 API

公共契约按职责分组：

```java
public interface Scope extends AutoCloseable {
    String name();
    Context context();
    Scope openChild(String name);
    boolean sharesDomainWith(Scope other);
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
    Context withRealm(String serviceName, Object label);
    Context withIntercept(ServiceKey<?> key, Object value);
    Context withIntercept(String serviceName, Object value);
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
- desired source revision、部署目标 revision 与 view revision 分开表达，不能共用一个模糊 revision。
- 失败必须定位到采集、校验、资源准备、目标保存、排空、实例协调、事实发布或资源回收阶段。
- `PENDING` 必须公开 `waitingFor`；服务必须能追溯 provider/owner；事件和贡献必须能追溯 owner/domain。
- mutation gate 关闭、目标保存未确认、受影响调用排空超时和资源回收失败都属于公开健康事实。

JMH 只覆盖单 JVM 内可稳定重复的 core、贡献目录和排除持久介质后的变更编排热路径。文件复制、
ClassLoader、sidecar 启动、JSON-RPC、Spring 启动和 HTTP 请求使用真实集成或分发验证，不能用单机微基准
代表端到端性能。

## 9. 非目标

以下能力不属于 vNext，未来必须由真实需求和新的架构决策引入：

- 远程插件市场、自动下载和信任策略；
- OSGi/ModuleLayer 或非可信插件沙箱；
- 浏览器/WebView runtime、client graph、HMR 与 UI slot 协议；
- LangChain4j、Spring AI、Tool、Agent、Session 等业务模型；
- 对 Cordis/DSH 配置文件格式的逐字兼容或在 Java core 中执行 JavaScript；配置组合与条件求值的
  使用场景仍须有行为等价的输入机制，不得以语法不同为由删除能力；
- 内嵌数据库和通用持久事务框架；
- 没有独立消费者支撑的通用 timer、任务调度、多 provider 或万能 runtime driver。

Java Harness 只是验证 built-in definition、EngineCommand、PublishedView、贡献适配和宿主服务所有权的
参考场景，详见[Java Harness 接入设计](./2026-09-07-fibra-java-harness-integration.md)，不定义 Fibra 的
产品方向。

## 10. 验收与证据

最终交付必须同时满足：

- 71 项 Cordis 原始行为与 44 项 Fibra 额外回归逐项通过，不能用新增测试数量抵扣；
- DSH 固定基线的配置装配与动态管理逐项映射源码、场景和测试；未覆盖的能力明确列为未完成，
  不能用上述内核测试或“接口存在”替代功能等价证明；
- RuntimeDomain 域间隔离、长期域内差量更新、PublishedView 一致投影和受影响调用排空通过；
  改变一个实例时，无关实例、ClassLoader、Node PID、effects 与在途调用保持；
- artifact/config/联合 deployment 使用同一 ChangeSet，覆盖准备资源清理失败、目标保存边界、mutation gate
  与按清单重建；验证重复内容、半份写入、替换后同步失败、成功保存后崩溃、损坏及缺失引用；
- 审计失败不改变成功部署结果，但能被诊断；不以丢失错误实现 best-effort；
- Java 使用真实 JAR 验证依赖图、资源委派和 ClassLoader 回收；
- Node 使用真实进程验证握手、超时、取消、心跳、异常退出与进程树终止；
- 公开 API 签名、模块依赖、Spring、示例、archetype、外部消费和可复现分发门禁通过；
- CLI profile 首次启动与完整 apply 验证必需的制品清单、严格字符串数组和路径边界；缺失清单不能
  清空目标，`[]` 配置加 `[]` 制品合法，额外候选包不被探测或安装，重复路径及重复内部 ID 明确失败；
- profile 完整 apply 覆盖 A+B 到 B+C 的制品、实例与持久目标一致性；install 不改启用意图、不回写源，
  后续 apply 明确替换命令式选择；候选换版不自动升级，删除源及候选目录后仍从保存目标恢复，配置
  refresh 不读取制品清单或改变制品集合；
- 全仓无 PF4J、旧 loader、`Engine.runtime()`、共享可变 ContributionBridge 或兼容转发残留。

交付判定以这些不变量的实际覆盖为准，不能仅凭既有测试数量或历史绿色构建认定完成。逐项行为映射见
[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)；全项目还必须通过启动期间目标变化、
撤销后旧路由、嵌套实例、关闭与提交交错、局部制品替换的类型一致性及持久目标重建的确定性验证。

开发阶段使用本地依赖缓存执行受影响测试，必要时运行全仓验证；空依赖仓库的外部分发验证留到
最终交付统一执行一次，发现分发问题时才针对修复重新验证，不因每次逻辑修改重复下载依赖。

2026-09-13 的最终发行阶段已在 macOS 26.6.2 arm64、Zulu JDK 21.0.2、Maven 3.9.9 上完成以下独立证据：
`mvn --offline -pl fibra-distribution -am clean verify` 对 25 个相关 reactor 模块通过，并在仓库外解压 ZIP
执行真实命令；`scripts/verify-reproducible-release.sh` 对 26 个正式 Maven 发布物、ZIP 字节和发行目录
路径/类型/权限/SHA 完成 clean、再次 clean 与非 clean 三轮一致性比较；`scripts/verify-distribution.sh`
使用仓库内固定 settings 和互相隔离的空 Maven 本地仓，先 clean/deploy 26 个正式发布物，再仅从临时发布
仓单独构建 `fibra-distribution`，最后完成 ZIP 外部启动、消费者、Spring 与 archetype 验证。根目录原始
`mvn --offline clean package` 也已对 50 个 reactor 模块通过，并自动产生完整发行目录与 ZIP；最终
`mvn --offline clean verify` 对同一 50 模块通过，发行模块在 verify 阶段再次完成仓库外真实验收。该空仓门禁
不读取本机 Maven 用户 settings，也不允许聚合 POM、distribution、acceptance、example、parity 或
benchmark 混入发布仓。`ApiSignatureBaselineTest` 另以定向命令通过，最终全仓、公开 API 与文档一致性
结果记录在
[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)。

最终独立审核未发现 P0/P1；审核指出的运行件版本探测非零退出码、REPL 停用/恢复结果断言和 `SIGTERM`
及时退出证明三个 P2 已在同一实现/测试提交中关闭。最终 ZIP 门禁因此具备 10 秒退出截止，不再可能把工具
自然超时误记为排空成功；Node、ripgrep 或 Bash 版本探测失败也会直接阻止装配。

平台边界不随本机绿色结果扩大：本次 ZIP 携带的是 macOS arm64 目标运行件；Windows 文件发布、Job
Object 与 Linux user-systemd 仍只记录实现、注入测试和既有 Ubuntu 构建证据，不宣称已在对应目标平台
完成最终 ZIP 实机门禁。

### 10.1 框架交付与多插件应用验收

先完成插件系统本身的行为等价、公开调用、资源生命周期、配置管理和恢复门禁，再实现应用插件。
应用跑通不能替代框架交付，应用暴露的底座问题必须回到所属模块修复，不得通过示例专用旁路掩盖。
应用只经公开 API 装配、管理和调用；需要真实 JAR 或 Node 进程的路径不能用内建插件替代证明。

按以下顺序实现可实际运行的通用场景，其业务契约、工具协议和数据存储均留在应用层：

| 场景 | 能力 | 多插件验证重点 |
|---|---|---|
| `tool-fs` | 文件访问后端与文件工具分离 | 多服务就绪、共享 provider、撤销与恢复、调用期间卸载 |
| `tool-fs-search` | 文件名匹配与全文搜索 | 共享子进程服务、可选能力、超时与取消 |
| `tool-shell` | 命令执行、输出与退出码 | 多级依赖、在途调用排空、进程及子进程清理 |
| 配置存储 | JSON 键值保存、读取和变更通知 | 正式 `tool-storage` 公开 load/put/remove/changes、多消费者共享、事件监听回收、隔离与重启读取 |

每个场景都提供宿主调用入口和失败路径验证，并记录变更前后无关插件的实例身份、资源及状态，
用于核实生命周期影响范围，不能只断言最终结果相同。文件和 Shell 验证使用专用临时目录及受控命令，
不依赖模型账号、外部网络服务或真实用户数据。JSON 存储属于应用插件，不引入 Engine 数据库。
具体 provider 可以采用许可证合适、维护成熟且行为边界可验证的第三方 Java 库，依赖只进入对应 provider
制品，不进入 `fibra-api`、宿主可见工具契约或动态 contract；采用第三方实现不能改变本节的 DSH 行为契约，
也不能绕过 Fibra 的 Service、Scope、取消和排空模型。JDK 原生能力已能完整兑现时不额外引入依赖。
参考 DSH 固定提交 `a66e4702047846cdaa10c66c9d3df3951f5ea70d` 的 `packages/fs/tool-fs`、
`packages/fs/tool-fs-search`、`packages/shell/tool-bash` 及 `packages/storage`；
参考其插件协作方式，不将应用场景覆盖误称为整个 DSH 业务产品的等价实现。

正式插件统一由根级 `fibra-plugins` 聚合，不放在 `fibra-example`；聚合 POM 不发布，正式产品子模块直接
继承根 `com.sstlfsj:fibra`，并各自发布 main/source/javadoc；acceptance 子树及其测试 JAR 不发布，也不
生成 source/javadoc。DSH 把 `fs`、`subprocess`、`shell`、`storage`
契约分别发布，Fibra 也保持这四条契约边，不使用一个大 contract JAR；否则任一契约升级都会扩大 Java
artifact 反向依赖闭包，破坏无关 ClassLoader 保留。正式模块如下：

```text
fibra-plugins
  ├─ fibra-tool-api/
  ├─ fibra-plugins-fs/                    （领域聚合，不发布）
  │  ├─ fibra-fs/
  │  ├─ fibra-fs-local/
  │  ├─ fibra-tool-fs/
  │  └─ fibra-tool-fs-search/
  ├─ fibra-plugins-subprocess/            （领域聚合，不发布）
  │  ├─ fibra-subprocess/
  │  └─ fibra-subprocess-local/
  ├─ fibra-plugins-shell/                 （领域聚合，不发布）
  │  ├─ fibra-shell/
  │  ├─ fibra-shell-local/
  │  └─ fibra-tool-shell/
  ├─ fibra-plugins-storage/               （领域聚合，不发布）
  │  ├─ fibra-storage/
  │  ├─ fibra-storage-json/
  │  └─ fibra-tool-storage/
  └─ fibra-plugins-acceptance/            （真实组合验收，不发布）
     └─ fibra-plugins-acceptance-host/
```

领域 POM 和总聚合 POM 都只声明 `modules`，不充当子模块 parent；全部正式发布制品仍直接继承根
`com.sstlfsj:fibra`。每个目录名与其 POM 的 artifactId 一致；目录按业务归属组织，但 artifactId 才是
稳定的发布和 manifest 身份：例如 search 归入文件领域，同时仍只通过 subprocess 契约执行 `rg`，
目录层级不产生运行时依赖。

`fibra-tool-api` 是宿主可见的 DTO、`ContributionKind` 与可选 `ResultSpillStore` 服务契约，由父加载器
提供；工具插件只把它作为 `provided` 依赖，JAR 不打包副本。`fibra-fs`、`fibra-subprocess`、
`fibra-shell`、`fibra-storage` 是无 entrypoint 的
contract-only 插件制品；provider 和 consumer 以 `provided` 构建依赖及 manifest `requires` 共享对应
契约类型，不把 contract class 打进自身 JAR。vNext 的动态 contract 尚未承诺跨版本二进制兼容，正式插件
因此对同一发布列使用 `${project.version}` 精确约束，不使用 `*` 掩盖契约错配。宿主只通过
`PluginRegistry` 部署/启停插件，并通过
`PublishedRuntime` 查看和调用贡献，不注入专用 catalog，也不取得插件 Service 或内部 `Context`。构建
测试检查插件 JAR 的 manifest、依赖边和重复 class。`fibra-tool-storage` 是与其他工具同等的正式发布
consumer，`fibra-plugins-acceptance` 只保留组合宿主；`fibra-example` 至多组合已发布插件，不拥有正式插件
源码。

正式产品类型使用 `com.sstlfsj.fibra.plugins.*`：其中 `tool-api` 由宿主 classpath 提供，四个动态 contract
由各自制品加载器定义。父优先查找成功时不得由插件私有副本遮蔽；父加载器不存在的动态 contract 则必须
继续沿 manifest 依赖图解析，不能把父优先实现成整个包名前缀的导出白名单。

这里不把应用改写成 Node 插件：Fibra 的 Core Service 不做隐式跨进程注入，若为此额外建立宿主转发
RPC，反而会绕过本节要验收的服务依赖、realm 和 Scope 所有权。Node sidecar 仍由框架级真实进程门禁验证。

```text
Artifact requires（每条边都由 manifest 声明）
  fibra-fs-local / fibra-tool-fs ──requires──> fibra-fs
  fibra-subprocess-local / fibra-tool-fs-search ──requires──> fibra-subprocess
  fibra-shell-local / fibra-tool-shell ──requires──> fibra-shell
  fibra-shell-local ──requires──> fibra-subprocess
  fibra-storage-json / fibra-tool-storage ──requires──> fibra-storage

RuntimeDomain Service graph
  fibra-fs-local ──FileSystem──> fibra-tool-fs
                 └─ResultSpillStore（tool-api 中的可选服务）──> fibra-tool-fs-search
  fibra-subprocess-local ──Subprocess──> fibra-tool-fs-search
                         └─Subprocess──> fibra-shell-local ──Shell──> fibra-tool-shell
  shared realm:   fibra-storage-json-shared ──ConfigStore/event──> tool-storage-a、tool-storage-b
  isolated realm: fibra-storage-json-isolated ──ConfigStore/event──> tool-storage-c

贡献发布
  fibra-tool-fs / fibra-tool-fs-search / fibra-tool-shell / fibra-tool-storage
    ──ContributionRegistrar──> ContributionDirectory ──snapshot/routes──> PublishedRuntime ──调用──> host
```

采用 DSH 行为契约时只保留本节业务范围内的语义：

| 能力 | 必须保留的行为 | 本次不冒充已覆盖的 DSH 能力 |
|---|---|---|
| 文件 | UTF-8 文本、1-based offset、正整数上限、空文件/目录/非文本边界；元数据版本不读取文件内容；原子写并保留既有 POSIX mode/Windows ACL；默认唯一字面量编辑和显式 replace-all，编辑先校验 freshness，再全量拒绝 NUL，并以 LF 规范化匹配和恢复原换行风格 | 图片、附件、观察策略、授权升级与 UI 渲染 |
| 搜索 | `rg --no-config` 直接 argv；glob 搜索隐藏/忽略文件并排除 VCS 元数据，grep 保持 ripgrep 默认 ignore/hidden 语义；退出码 1 表示空结果，非法模式/超时/取消/原始输出溢出明确失败；可选 spill 缺失或普通保存失败不改变搜索成功，但 spill 期间发生的调用取消仍以 `ABORTED` 结束 | 打包所有平台的 ripgrep 二进制、展示卡片和会话级 spill 所有权；示例由配置提供可执行文件并在启动时验证 |
| Shell | 每次 fresh shell、显式 workdir、分离 stdout/stderr/exit code；非零退出是结果，超时与取消终止受管进程树；模型文本明确标记 stderr、空输出、截断、signal 和非零 exit | 后台 job、审批、沙箱策略和 DSH 环境变量注入 |
| JSON 配置 | 缺失文件视为空并延迟物化；完整文档原子持久化；损坏或版本不匹配明确失败；失败写不改变内存或发事件，后续写仍可继续；事件只在持久化成功后发出；关闭拒绝新操作并排空在途写；重启读取；正式 `tool-storage` 公开 `load`、`put`、`remove` 与 `changes`，其中变更是实例内不回放的最多 64 条快照，溢出以 `dropped` 标识 | per-record、SQLite、领域 schema/migration 和跨进程事件推送 |

四条动态契约只表达本期真实 consumer 需要且能完整兑现的能力，不提前复制 DSH 的 PTY、后台进程、
sandbox 或流式协议。`FileSystem` 的每项操作都接收 `InvocationContext`，使用稳定的 `FsErrorCode`
区分不存在、目录、非文本、过大、权限、陈旧观察、未观察、编辑歧义、I/O 与取消；写入与编辑分别用闭合
intent 显式区分无条件执行和版本保护，取消只能在原子发布前生效。阻塞文件 I/O 统一进入受控阻塞调度器；
变更按稳定 target key 串行，无关目标不共用写锁，等待锁期间仍响应调用取消。原子写在目标同目录创建
POSIX `0700` 私有 staging 目录和 `0600` 临时文件，写全并同步后才发布；发布是提交点，之后的 staging
清理失败只留下 owner-only 残留，不能反转成功。POSIX 替换恢复既有 mode；Windows 替换在写内容前复制并
保护既有 DACL，关闭临时文件后调用 `ReplaceFileW`，若目标只在最终替换竞态中消失则以已复制的 DACL
原子重建。stock JNA 的 JNI 符号绑定固定包名，不能用 bytecode relocation 改名；它只以 optional 依赖和
原包名私有打进 `fibra-fs-local`，由插件 ClassSpace 隔离，不能进入 contract 或宿主的传递依赖面。当前 macOS
门禁覆盖注入式 Win32 调用次序、错误映射和接线路径，不把未执行的 Windows 原生调用宣称为实机验证。
`Subprocess.spawn` 返回调用者 Scope
所有的 `ProcessUnit`：`done()` 结算直接进程及已收集输出，
`waitForExit()` 必须等待整个受管进程单元静默，`terminate()` 和资源清理均幂等。deadline 与调用取消的
先发生原因由 consumer 分类，不由 subprocess provider 猜测。

`Shell.run` 对非零退出、超时和调用方取消都返回 `ShellResult`；`timedOut` 与 `aborted` 互斥，启动或
基础设施失败才抛 `ShellException`。工具层把超时、取消和终止失败投影为稳定的 `ToolFailureCode`，并且
只有在 `ProcessUnit.waitForExit()` 证明树静默后才允许调用结算。`ConfigStore` 的写入按 store 实例串行，
`subscribe` 返回可主动取消且幂等的句柄，实现同时把该句柄登记到传入的 `InvocationContext.effects()`；
通知不回放、只在持久成功后按 revision 顺序发出，listener 异常被记录并隔离，不能反向推翻已经提交的写。
关闭与订阅取消或在途写交错时，已接受写及其通知完成后再释放介质，关闭后新操作统一失败。
JSON provider 在同目录写临时文件并同步内容，再原子 rename；POSIX 上随后同步父目录。若 rename 已提交而父目录
同步失败，不能把内存和事件留在旧 revision：本次写仍按已提交结果更新并发事件，同时记录 durability warning。
Windows 的目录同步只作能力探测和 best-effort，未实测平台不得据此声称断电持久性已经验证。

`subprocess-local` 是搜索和 Shell 共用的唯一进程 seam；工具 consumer 不自行 `ProcessBuilder`。每次调用
创建一个 `ProcessUnit`，并在启动前把其排空/终止动作登记到服务收到的 `InvocationContext` resource Scope。
该 Java provider 采用 provider-managed range：Windows 由 Job Object 管理，Linux 在 user manager 可用时由
`systemd-run --user --scope --collect` 创建 transient scope；Linux manager 或 Job Object 不可用时记录一次明确
告警并回退到较弱 supervisor。Darwin 没有等价的系统范围，保持 PGID supervisor 的较弱边界；`setsid`、重父化
或 breakaway 后代可能逃逸，`waitForExit()` 不能在该回退路径承诺 managed-range 静默。范围 owner 仍通过受管
supervisor 维持 stdin 生存租约，JVM 异常退出时以 EOF 触发清理；终止必须等待选定范围静默后结算，不能只依赖
一次 `ProcessHandle.descendants()` 快照。`fibra-subprocess-local` 当前 74 项测试覆盖选择、范围生命周期、
超时、取消、父进程先退出及后代清理；Windows Job 与 Linux scope 以注入 seam 覆盖实现路径，当前 macOS
环境不把它们写成 Windows 或 Linux 实机通过，Linux user-systemd 的实机门禁仍待最终执行。配置事件与
`ConfigStore` 使用相同 realm；测试必须同时证明同 realm 的两个 consumer 共享一个 provider，以及另一个 realm
的 provider 和 consumer 对同名 key 隔离，不能把全局静态 listener 当作事件总线。

`tool-fs-search` 放在 `fs/` 只是业务归属；固定 DSH 源码明确不注入 `fs`，因此它在 Fibra 也不声明
`requires fibra-fs`。格式化结果 spill 通过父加载器唯一的 `ResultSpillStore` 做可选服务查询；缺少 provider
或普通保存失败都只失去 spill 引用，不能让已经成功的搜索失败；保存等待期间到达的 caller cancellation
在格式化完成前重新检查并覆盖成功结果。

### 10.2 固定源码基线

实现原则是采用 DSH 固定源码的行为契约，使用 Fibra 的唯一命令入口、长期运行域与差量协调模型落地；
不复制 DSH 的 Node 运行时结构，也不以技术栈差异删减已纳入范围的使用场景。

设计结论使用以下固定源码基线：

| 来源 | 固定基线 | 证据 |
|---|---|---|
| Fibra 0.4.x | `02fe4b5dcd7b1052203d2027c9808931dceddb65` | 旧实现问题只用于解释最终边界，不进入主叙事 |
| cordiverse/cordis | `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` | [Cordis 行为证据](../references/2026-09-09-cordis-behavior-evidence.md) |
| DeepSeek Harness | `b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`、`a66e4702047846cdaa10c66c9d3df3951f5ea70d` | [插件依赖、装载与更新基线](../references/2026-09-09-plugin-dependency-baselines.md) |
| cordis4j | `6cfd56e684fb403ded952afc09eddb49a5228494`、设计契约 v2.13 | [cordis4j 设计证据](../references/2026-09-11-cordis4j-design-evidence.md) |

DSH 的插件行为基线构成功能等价门禁；其他来源用于解释取舍，不自动扩大实现承诺。两篇用户提供的解读文章作为问题清单
收录在[源码参考目录](../references/README.md)，结论仍以固定源码、测试与本设计为准。

## 11. Fibra CLI 完成路线与上层 Agent 产品边界

本节定义 vNext 交付完成后的演进路线。它不改变第 1 至 10 节已经完成的范围，也不把后续待办计入
当前版本完成度。后续不再把 DSH 的 Agent 产品能力继续堆入 Fibra 仓库，而采用与 Cordis/DSH 相同的
上下层关系：Fibra 负责通用运行时、插件宿主和完整 CLI 框架；另建上层 Agent 产品项目，依赖 Fibra
发布物并组合 Model、Agent、Session、MCP、Skill、Workflow、UI 等产品能力。

```text
Cordis                              Fibra
  通用插件运行时                      通用插件运行时
       │                              + Engine / Registry / Artifact
       │                              + Java / Node runtime
       │                              + profile / bundle / distribution
       │                              + 可嵌入的完整 CLI 框架
       ▼                                      │
DSH                                         ▼
  Agent 产品、插件与客户端             上层 Agent 产品项目
                                    Agent 产品、插件、CLI 组合与客户端
```

Fibra 比 Cordis 承担更多通用交付职责，但不能因此跨入 Agent 产品领域。新增产品能力是否通用，不以“多个
项目可能用到”判断，而以它是否只管理插件宿主自身来判断：Engine、Registry、加载器、调用发布、CLI
会话和分发属于 Fibra；模型语义、会话消息、审批策略、Agent loop、MCP、Skill 和 UI 属于上层产品。

DSH 能力基线继续固定为 `0.1.2-rc.1` 的提交
`a66e4702047846cdaa10c66c9d3df3951f5ea70d`。AgentCLI 与 PaiCLI 只提供 Java 终端交互参考；二者来源和
历史不同、当前实现高度接近，不构成第二套产品架构基线。吸收源码已经证明的行为，不复制 DSH 的 Node
包布局、Cordis Loader 和 pnpm 安装流程，也不复制 AgentCLI/PaiCLI 的单体 `ToolRegistry` 或斜杠命令树。

### 11.1 两个项目的责任边界

#### Fibra 仓库

Fibra 的后续范围到“可被另一个产品仓库直接复用的完整 CLI 框架”为止：

- 保持已经完成的 RuntimeDomain、Engine、Registry、Artifact、配置、Java/Node runtime、
  `PublishedRuntime`、Spring starter 和正式 distribution；
- 完成一次性命令与 REPL 共用的 CLI 应用模型、终端交互、调用取消、信号、输出协议和静态组合 SPI；
- 保留已交付的 fs、fs-search、subprocess、shell、storage 和 tool API，作为通用正式插件、真实验收场景
  和上层产品可直接消费的发布物，不移动、不复制源码；
- 提供仓库外消费者门禁，证明上层产品只依赖声明过的发布制品即可创建自己的 CLI 和 distribution；
- 不新增 Model、Agent、Session、MCP、Skill、Goal、Todo、Plan、Compaction、Sandbox、Jobs、ACP 或 UI
  业务模块，不把这些类型加入 `fibra-api`、`fibra-cli-api` 或 core。

`fibra-runtime-node` 继续属于 Fibra。它是 Node sidecar 的通用 runtime adapter：校验 Node 插件制品与
manifest，托管进程和心跳，以 JSON-RPC 执行握手、启动、调用、取消、停用和停止，并把宿主已知的
`ContributionKind` endpoint 注册到同一个 `ContributionDirectory`。它不是通用 Node SDK，也不是任意
Java Service/Event 的透明跨进程注入层；上层项目的 Node provider 只有在定义了宿主可见 contribution
契约及 codec 后才能复用它。需要内部 Service graph 的产品插件默认使用同进程 Java contract/provider，
除非以后为一个已经确认的跨进程场景单独设计协议。

现有四组正式插件留在 Fibra，是因为它们已经构成通用文件、进程、Shell 和 KV 能力，同时承担动态
contract、provider、consumer、ClassLoader、进程排空和发行门禁的框架验收。这个既有事实不构成继续把
DSH 产品插件加入 Fibra 的先例。

#### 上层 Agent 产品项目

上层项目单独拥有父 POM、版本、源码仓库、CLI 入口、默认 profile、插件集合、发行 ZIP 和验收账本。
它只消费 Fibra 正式发布物，不能通过相对源码目录、reactor 模块、构建机绝对路径或历史 Maven 缓存读取
Fibra 内部实现。该项目负责：

- Model、Agent、Session、审批、MCP、Context、Skill、附件、Goal、Todo、Plan、Compaction；
- Sandbox、Jobs、Terminal/PTY、Web、LSP、Webhook、Schedule 等产品插件；
- Subagent、Workflow、API、SDK、ACP、Web UI、渐进渲染和产品可观测性；
- 产品自己的静态 CLI 命令组合、默认插件选择、凭据策略和 distribution。

上层项目可以依赖 Fibra 的 fs/search/shell/storage 发布物，但产品 profile 是否默认启用由上层项目决定。
产品插件必须像现有 Fibra 正式插件一样进入 RuntimeDomain 生命周期，经 `PublishedRuntime` 向产品宿主
发布贡献；不得因为分仓而改用静态注册表、Spring 扫描、直接 ClassLoader 或旁路 RPC。

### 11.2 Fibra CLI 框架的完成定义

当前 `fibra-cli` 已有固定管理命令、一次性执行、共享 Engine 的 REPL、稳定 JSON 输出、profile 路径、
关闭 hook 和真实发行入口，但仍是一个封闭应用：命令树、`CliHost`、REPL 和调用管理没有形成可供上层
产品组合的公开边界，交互历史、补全、高亮和调用级 `Ctrl+C` 也尚未完成。

CLI 完成后采用以下模块边界：

| 模块 | 类型 | 职责 |
|---|---|---|
| `fibra-cli-api` | 新的宿主可见公开 API | CLI 应用构建、静态命令模块、调用上下文、取消、输出通道和退出状态契约 |
| `fibra-cli` | CLI 实现与参考入口 | Picocli/JLine 实现、Fibra 固定管理命令、REPL、默认宿主创建和 `main` |
| `fibra-distribution` | Fibra 参考发行 | 通用 Fibra CLI、正式基础插件、默认 profile 和仓库外验证 |
| 上层产品的 CLI 模块 | 外部消费者 | 依赖 `fibra-cli-api`/`fibra-cli`，静态增加 Agent 产品命令并提供自己的 `main` |

`fibra-cli-api` 不重新抽象 Picocli：静态命令组合可以明确使用 Picocli `CommandSpec` 或命令对象，Picocli
因此是该场景 API 的受控公开依赖；JLine 类型和 renderer 实现仍是 `fibra-cli` 私有细节。API 至少提供：

- `CliApplication` 或等价 builder，用于选择根命令元数据、添加静态命令模块并启动一次性或 REPL 模式；
- 每次命令调用独有的 `CliInvocation`，携带 cancellation、deadline、stdout/stderr 模式及只读 profile
  信息；不能暴露 RuntimeDomain `Context` 或 Engine 内部对象；
- 受控的宿主能力入口，使产品命令只能取得 `PluginRegistry`、`PublishedRuntime` 和必要的路径视图；
- contribution kind/Node 映射扩展点，使上层项目能识别自己的宿主可见贡献类型，但不能修改 Fibra 已发布
  kind 或绕过 `ContributionDirectory`；
- 不暴露 JLine 类型的终端会话与独占租约：产品命令可查询 TTY/颜色/尺寸能力，在受控范围内暂停 line
  reader、成为租约期内唯一输入读取者、读取按键/字节、进入和恢复 raw mode、接收 resize/interrupt、
  写屏并请求 redisplay；关闭 invocation 会取消阻塞读取，非交互环境明确返回不支持，租约关闭或异常时
  由框架恢复终端属性和提示符；
- 统一的 usage、启动、业务调用、revision 冲突、关闭和取消退出状态；人类与机器输出走明确分离的通道。

产品命令在应用构建时静态加入命令树，不由运行时插件直接贡献 CLI 命令。这样补全和帮助在启动后稳定，
插件卸载不会让正在解析的命令树变化；动态内容只作为参数候选或已发布贡献出现。Spring 继续只是宿主
适配器，不引入 Spring Shell，也不允许应用上下文扫描生成工具或命令。

### 11.3 DSH 能力盘点与项目落点

下表记录 DSH 固定源码中已经核实的主要能力，以及在新的双项目边界中的落点。“按需”表示只有真实场景
和验收样例出现后才创建模块，不能据此预建空接口。

| 能力域 | DSH 固定基线中已核实的行为 | 当前状态 | 后续落点 |
|---|---|---|---|
| Boot、package、bundle、profile | 环境分层、配置组合、启动审计；安装包、bundle patch、profile 和最终配置分层 | Fibra 已完成 | 继续由 Fibra 提供，上层项目只声明自己的 profile/bundle/package |
| 插件生命周期 | 依赖驱动激活、异步 effect、服务撤销等待、条件/isolate、局部更新、主动停用 | Fibra 已完成 | 所有上层产品插件复用，不建产品私有容器 |
| fs、搜索、shell、subprocess、storage | contract/provider/tool 分层；搜索经 subprocess；进程树排空；具名存储与变更事件 | Fibra 已完成本期范围 | 保留 Fibra 正式发布物；图片、Jobs、PTY、Sandbox 不冒充已完成 |
| CLI 交互 | DSH 有 commands/questions/approval；AgentCLI/PaiCLI 有 JLine 历史、补全、高亮和 renderer | Fibra 部分完成 | F1 至 F4 完成通用 CLI；Agent questions/approval/renderer 留上层项目 |
| Model | provider 路由、模型发现、配置解析和流式适配 | 未实现 | 上层项目动态 contract/provider 插件 |
| Agent | Agent factory、轮次/步骤、模型流与工具调度；完整实现依赖 Session | 未实现 | 上层项目 Agent 插件；P1 使用 invocation 内状态闭合 Model/Tool，P2 再接入独立 Session |
| Tool 流水线 | schema、作用域、guard、pre/execute/post/result 和多种呈现 | Fibra 已有公开工具调用 | 通用调用留 Fibra；Agent 选择、审批和产品呈现留上层项目 |
| Session | 追加日志、冻结事实、投影、fork、flush；持久化和投影分离 | 未实现 | 上层项目独立 Session contract/provider，不复用 EngineStateStore 或 ConfigStore |
| MCP | stdio/Streamable HTTP、工具同步、撤销和有限重连 | 未实现 | 上层项目 Tool adapter 插件；首期不声称 resources/prompts 全覆盖 |
| Context、Skill、附件、spill | 文件系统 skill、指令/引用上下文、附件和超长结果外置 | Fibra 仅有可选 spill seam | 上层项目业务插件；必要时消费 Fibra `ResultSpillStore` |
| Goal、Todo、Plan、Compaction | 目标、待办、计划模式、上下文压缩和 checkpoint 协作 | 未实现 | 上层项目基于 Session 事实的独立插件 |
| Approval 与权限 preset | ask/never、调用级审批与审计；组合 shell/session/approval | 未实现 | 上层产品 policy 插件和 CLI/外部 responder；不进入 Fibra CLI core |
| Sandbox | policy 与平台 backend 分离，报告 full/partial 或拒绝 | 未实现 | 上层项目 contract/provider；不能把 Fibra 进程树管理称为沙箱 |
| Jobs、Terminal、PTY | 内存 JobRegistry、owner 控制、输出/等待/取消；persistent shell 和 PTY | 未实现 | 上层项目独立插件；DSH `jobs-local` 不持久，首期不承诺重启续跑 |
| Web、LSP、Webhook、Schedule | 均为独立包和 adapter | 未实现 | 上层项目按需插件，没有真实调用方前不创建 |
| Subagent、Workflow | 多种 subagent provider、worker/workflow/tool | 未实现 | 上层项目在单 Agent 生命周期稳定后实现 |
| API、SDK、ACP、Web UI | gateway/remotes、journal stream、JSON-RPC、ACP、浏览器连接和 UI slot | 未实现 | 上层项目宿主/客户端 adapter，不进入 Fibra core |
| Observability | invariant、session telemetry、stats、token meter、启动诊断 | Fibra 框架诊断已完成 | Fibra 保持框架诊断；上层项目随产品阶段交付业务遥测 |

两处边界以源码而不是名称判断：DSH 搜索没有注入 `fs` 或 `shell`，所以上层项目复用 Fibra search 时
仍只依赖 subprocess；固定基线的 UI renderer 会自行创建 SlotRegistry，不能依据旧 README 虚构
`slots/sessions/layout` 注入关系。DSH OTel 实现核实的是日志导出，不得扩写为 traces/metrics 全栈。

### 11.4 Fibra 仓库实施阶段

后续先完成 F1 至 F4。F4 通过前不创建上层 Agent 产品仓库，以免产品代码反向塑造尚未稳定的 CLI SPI。

#### F1：公开 CLI 组合边界

- 新建 `fibra-cli-api`，从当前封闭 `FibraCli`/`CliHost` 中提取最小应用构建、静态命令模块、调用上下文、
  输出、退出状态和受控终端租约；`fibra-cli` 继续提供默认实现和现有固定命令。
- 一次性命令与 REPL 必须经过同一命令树和 invocation 创建路径；上层命令只能使用 Registry、
  `PublishedRuntime` 和声明的 profile/path 视图。
- 建立仓库外临时 Maven 消费者，只依赖已安装到隔离仓库的 Fibra 发布物，增加一个静态测试命令，调用
  一个已发布工具并通过 fake/dumb terminal 验证租约恢复，证明无需包私有类型和 reactor。

退出条件：公开 API 签名门禁通过；现有 CLI 命令和 distribution 无行为回归；外部消费者从空 Maven
依赖仓构建、启动并完成扩展命令与真实工具调用。

#### F2：安全历史、补全与终端降级

- 从 Picocli `CommandSpec` 生成静态补全；工具名等动态候选只读取当前 `PublishedView`，插件变更成功后
  刷新，不引入第二工具目录。
- 吸收 AgentCLI/PaiCLI 的 JLine persistent history、高亮和 dumb-terminal fallback。当前 REPL 内可保留
  完整内存历史；持久历史绝不保存 `tools invoke --input` 原值、未来凭据参数或被标记的敏感值，不安全
  命令只留下不可重放的脱敏摘要。
- 仅在人类终端 stderr 显示 profile、workspace、工具数量和 revision 摘要；JSON stdout 保持机器可解析，
  管道和非 TTY 不因 renderer 改变。

退出条件：真实 TTY 验证历史、补全、高亮、窄终端和重启；使用未被测试工具写入业务数据的敏感样本，
确认 CLI 历史文件及 CLI 自身诊断输出中不可检出原值；dumb terminal、管道输入、一次性命令和仓库外
distribution 回归通过。不得扫描 workspace 或 storage 后把用户明确要求保存的内容误判为历史泄漏。

#### F3：调用级取消与信号

- REPL 中第一次 `Ctrl+C` 取消当前 invocation，等待其 Scope 排空，输出稳定取消结果后返回提示符；空闲
  时只清空当前输入，不关闭 Engine。
- 非交互调用收到 `SIGINT` 时取消当前调用并排空后以 130 退出；`SIGTERM` 继续走已经验证的宿主关闭
  路径。重复信号不能绕过受管资源清理。
- 取消、排空超时、业务失败和宿主关闭失败保持不同投影；上层静态命令自动获得同一 invocation 语义。

退出条件：真实 TTY 中取消后可继续调用另一命令；无关插件实例、ClassLoader、Node PID、effects 和在途
调用保持；子进程范围静默；外部 `SIGINT`、`SIGTERM`、退出码和产品扩展命令夹具通过。

#### F4：CLI 框架冻结与交付门禁

- 统一 help/version、机器 JSON、人类 stderr、颜色/无颜色、非 TTY、关闭时序和异常映射；renderer 只处理
  通用命令状态，不加入 Agent token、tool progress 或 session 事件。
- 冻结通用终端租约的所有权、raw mode、resize、interrupt、redisplay 和异常恢复语义；用一个不含 Agent
  业务的外部渐进输出消费者验证普通按键、方向键与 ESC 输入、调用取消、租约释放后 REPL 恢复，证明
  上层无需取得 JLine 私有对象、另读 `System.in` 或另建终端循环。
- 为 `fibra-cli-api` 建立版本和兼容性规则；删除提取过程中被替代的包私有旁路，不保留双入口。
- 更新 Fibra distribution、外部消费者、Spring 宿主和 archetype 证据，证明 CLI API 可嵌入且默认 CLI
  仍可独立运行。

退出条件：定向测试、公开 API 签名、仓库外消费者、根 `mvn clean verify`、可复现制品和空 Maven 仓
分发门禁全部通过；权威架构和既有验收账本已回填；独立审核无未关闭的高优先级问题。此时 Fibra CLI
框架宣布完成，Fibra 不再沿本路线增加 Agent 产品功能。

### 11.5 上层 Agent 产品项目实施阶段

F4 完成后再创建新仓库。项目名称、坐标和首个模型 provider 在建仓时单独确认；本设计只固定职责和顺序，
不提前把临时名称写成公开 API。

```text
上层 Agent 产品项目
  ├─ product-api/                 Agent、Model、Session 等宿主可见场景 API
  ├─ product-cli/                 基于 Fibra CLI SPI 的静态产品命令
  ├─ product-plugins/             contract、provider、consumer 与 policy 插件
  ├─ product-distribution/        自有 profile、插件选择、启动器与 ZIP
  └─ product-acceptance/          真实模型、工具、重启、信号和外部解压验收
```

默认依赖顺序如下；每一阶段完成并提交后才进入下一阶段：

| 阶段 | 交付内容 | 关键退出条件 |
|---|---|---|
| P0 独立建仓 | 父 POM、CLI 组合、distribution、验收骨架；只消费 Fibra 发布物 | 隔离空 Maven 仓构建；仓库外 ZIP 启动；调用 Fibra 正式工具 |
| P1 Model + Agent | 场景 API、一个真实模型 provider、invocation 内单 Agent loop、system prompt、Tool bridge、最小审批 | 不依赖 Session 完成真实模型选取和调用文件或 Shell 工具；拒绝无 effect；取消无泄漏 |
| P2 Session | 追加事实、JSONL provider、projection、flush、fork/checkpoint、重启恢复 | 工具调用后重启恢复；三个故障点不产生半完成调用；损坏明确失败 |
| P3 MCP | stdio/Streamable HTTP client、工具同步、撤销、有限重连 | 真实 MCP server 发现/调用/取消；停用后路由排空和子进程清理 |
| P4 Context + Skill | instructions、文件系统 skill、引用、时间；按需附件和 spill | profile/realm 隔离；显式升级；旧会话来源可解释；敏感信息不落盘 |
| P5 Goal + Todo + Plan + Compaction | 基于 Session 事实的长任务插件和 token 投影 | 跨重启恢复；压缩不丢未完成调用；Plan 切换不重启无关插件 |
| P6 Sandbox + Jobs + Terminal | 独立 policy/provider、后台任务、owner 权限、按需 PTY | 平台强度不夸大；任务/PTY 关闭排空；`jobs-local` 不冒充持久任务 |
| P7 事件流与 renderer | 有序事件信封、断线续接、JSONL、人类渐进渲染和 TUI | 慢消费者、终态、取消竞态、窄终端和非 TTY 门禁通过 |
| P8 Subagent + Workflow + adapters | 受管子 Agent、DAG、API、SDK、ACP、Spring bean、Web UI | 父取消排空所有子资源；provider 局部替换；仓库外真实消费 |

Web、LSP、Webhook、Schedule 和第二种模型协议属于按需垂直阶段，不占预留空模块。它们必须复用已经稳定
的 Tool、Session、取消、profile 和 distribution 契约，并用真实调用证明需求后才进入路线。

### 11.6 上层产品不可破坏的 Fibra 契约

- 产品插件只通过 Fibra RuntimeDomain 的 Service、Event、Effect 和 contribution 参与运行；产品宿主只经
  Registry、`PublishedRuntime` 和 CLI API 使用能力，不能取得内部 `Context`。
- Model、Agent、Session 等 API 是上层产品发布的场景契约，不进入 Fibra。动态 provider 的 contract
  ClassLoader 所有权必须唯一；宿主需要识别的 DTO 由产品宿主父加载器提供，插件以 `provided` 依赖且不
  打包副本。
- Node 产品插件只发布 `fibra-runtime-node` 能解析的宿主可见 contribution；不能假设 JSON-RPC sidecar
  自动参与任意 Java Service 依赖图。新增跨进程 service 协议必须有独立契约、取消、排空和版本门禁。
- 产品 CLI 命令在构建时静态组合，运行时插件只贡献业务能力和动态候选，不直接改变命令树或 Spring
  ApplicationContext。
- `SessionStore`、模型记忆、CLI history、`ConfigStore` 和 `EngineStateStore` 各自只有一个事实来源；
  不能共享格式、revision、恢复入口或把一个存储包装成另一个。
- 直接工具、Agent 工具和 MCP 工具调用传递同一类 cancellation、deadline、调用 Scope 和稳定失败码；
  本地取消不能被描述为远端已经停止，审批不能被描述为 OS 沙箱。
- 插件显式升级、停用或候选目录变化继续遵守 Fibra 差量更新不变量：无关实例、ClassLoader、Node PID、
  effects 和在途调用保持；保存目标不因目录变化静默改变。
- DSH 的 `jobs-local` 是内存实现，OTel 基线只核实日志导出，UI slot 属客户端组合；上层项目文档不得把
  这些边界扩大为重启续跑、全栈 telemetry 或 core UI 能力。

### 11.7 提交、验证与文档留痕

Fibra 的 F1 至 F4 仍只维护本文和既有行为验收账本，不新建平行 spec/plan。每阶段使用一个“契约、实现与
测试”提交；只有发行内容变化时再单独提交 distribution/外部验收，只有决定或证据变化时再提交文档。
提交前运行受影响模块的 `mvn --offline -pl <modules> -am test`；公开 API 变化运行签名门禁，涉及发行时
追加 `mvn --offline -pl fibra-distribution -am clean verify`。全仓、可复现制品和空 Maven 仓门禁只在
F4 统一执行一次，不在每个阶段重复下载。

上层项目建立后拥有自己的权威架构文档和行为验收账本，不把产品实现证据回填成 Fibra 已实现能力。
每个 P 阶段同样要求实现与测试同提交、真实 provider 验收、仓库外 ZIP 验收和独立审核；发布里程碑从
空 Maven 仓先取得 Fibra 正式发布物，再单独构建上层项目，以证明两仓边界真实成立。

下一次实现从 F1 开始。F1 至 F4 完成以前，不创建 Model、Agent、Session、MCP 或其它 DSH 产品模块；
F4 的外部消费者和空仓门禁通过后，才创建上层 Agent 产品项目并进入 P0。
