# Fibra vNext 最终目标架构

日期：2026-09-07

状态：设计已定稿；实现与最终交付验收进行中（2026-09-12）

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

## 4. Engine 与局部动态更新

### 4.1 变更协调与资源所有权

Engine 长期持有一个运行域、一个贡献目录和各 runtime 的资源所有者。配置变化比较稳定实例身份、
definition、配置和有效继承策略：新增实例挂载，删除或停用实例撤销；仅配置变化使用实例更新协议；
definition 或有效隔离归属变化重挂受影响实例。没有变化的实例、effects、ClassLoader 和 Node 进程保留。
服务 provider 变化引起的消费者停止和重新激活由内核依赖协议驱动，Engine 不另建第二套服务调度器。

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
- 超时、取消、心跳、stderr、异常退出和消息边界必须结构化上报；
- RPC 与进程所有权分离：`NodeSidecar` 只处理协议，内部 `NodeProcessUnit` 启动监督器并持有一个可等待的
  受管进程范围；RuntimeDomain 只等待该范围静默，不枚举或缓存瞬时后代 PID；
- retire 固定执行“停止接入、关闭 RPC stdin、等待协作退出、软终止、强终止、确认范围静默、清理会话目录”；
  只有整个受管范围静默后 runtime participant 才算结束；
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
- 全仓无 PF4J、旧 loader、`Engine.runtime()`、共享可变 ContributionBridge 或兼容转发残留。

交付判定以这些不变量的实际覆盖为准，不能仅凭既有测试数量或历史绿色构建认定完成。逐项行为映射见
[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)；全项目还必须通过启动期间目标变化、
撤销后旧路由、嵌套实例、关闭与提交交错、局部制品替换的类型一致性及持久目标重建的确定性验证。

开发阶段使用本地依赖缓存执行受影响测试，必要时运行全仓验证；空依赖仓库的外部分发验证留到
最终交付统一执行一次，发现分发问题时才针对修复重新验证，不因每次逻辑修改重复下载依赖。

2026-09-12 的 48 模块离线 clean verify、24 个正式发布制品的可复现比较和空仓外部消费者结果仅是
前一阶段证据；正式 `fibra-tool-storage`、正式宿主 CLI 与 ZIP 分发结构继续纳入后，必须按本节门禁重新
执行全仓、可复现和空仓分发验证，不能以该历史结果关闭交付。71 项 Cordis 与 44 项 Fibra 回归仍按独立
账本计数；Windows 文件发布仅记录实现、注入测试和制品证据，不扩大为未执行的实机声明。逐项证据与
平台边界见[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)。

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
一次 `ProcessHandle.descendants()` 快照。`fibra-subprocess-local` 当前 72 项测试覆盖选择、范围生命周期、
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
