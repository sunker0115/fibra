# Fibra vNext 最终目标架构

日期：2026-09-07

状态：第 1–10 节与第 11 节 F1–F4 的原始交付已完成；2026-09-17 已按 Client Foundation 最终架构重写
制品、持久目标和 runtime owner 相关段落。跨执行域插件模型及其 P0 的字段级契约仍以
[2026-09-15 Client Foundation 权威架构](./2026-09-15-fibra-client-foundation-architecture.md)为准。

本文是已完成 vNext 与 CLI F1–F4 的权威记录，定义该交付态的系统边界、运行模型、模块职责和验收标准；
它不再定义 client foundation 的最终形态或实施顺序。外部实现的源码对拍统一收录在
[源码参考目录](../references/README.md)。

## 1. 目标与边界

Fibra vNext 是一套面向受信任动态插件的托管运行底座。它允许 Java 原生插件、Node sidecar 和程序内建
插件进入同一个期望状态、生命周期、发布和诊断模型，同时保留纯内核嵌入方式。

交付以满足实际场景的最小完整架构为目标，不以比参考项目更多的组件或更强的限制作为增强。
以 DSH `0.1.5-rc.2`（`c291e7961a515f6d7af9304e7fd1d257929aef26`）的插件系统为固定架构与行为基线，
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
5. 配置和 package 只是输入事实，不能直接修改 Runtime；所有托管变更统一编译为完整 `DeploymentTarget`
   和全局 execution-unit DAG。
6. Java、Node 与产品提供的 external runtime 通过唯一 `RuntimeProvider/RuntimeDriver` SPI 参与变更；Engine
   不接触 ClassLoader、Process、session 或 JSON-RPC 私有句柄。
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
外层宿主 -> PluginRegistry / EngineCommand -> FibraEngine -> RuntimeDriver

运行域内能力面
built-in / Java plugin -> Context -> Service / Event / Effect -> plugin

发布面
RuntimeDomain + ContributionDirectory -> PublishedView -> PublishedRuntime -> 外层宿主
```

对象关系如下：

```text
FibraEngine
  ├─ command loop                         托管变更的唯一写入口
  ├─ DeploymentTargetStore                单一完整目标的保存与读取
  ├─ PluginPackageStore / desired input    不可变 package 与候选配置采集
  ├─ RuntimeDriver[]                      Java、Node 与 external runtime owner
  ├─ FibraRuntime
  │    └─ lifecycle lane                  core 状态的唯一写入口
  ├─ RuntimeDomain                        长期运行，局部变更实例和服务
  │    ├─ Scope ownership tree            实例、嵌套插件与调用资源
  │    └─ ContributionDirectory           注册、撤销与条目调用排空
  ├─ runtime generations                  按全局 unit DAG 准备、激活与回收
  └─ PublishedRuntime                     宿主唯一能力入口
       └─ AtomicReference<PublishedView>
            ├─ EngineSnapshot
            ├─ ContributionSnapshot
            ├─ RuntimeDiagnostics
            └─ EngineDiagnostics
```

`PluginRegistry` 只管理安装、版本、期望状态和审计；`ContributionDirectory` 只管理域内贡献；runtime
driver 只管理自己的静态资源与 execution generation；Engine 只管理目标、全局 DAG、变更编排与发布。任何类型同时承担其中两类职责，
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
definition、配置和有效继承策略。完整 target 先编译为 entry-keyed execution units 与全局依赖 DAG；package
facet 依赖同时决定静态资源闭包和 unit 依赖。新增、删除、配置、definition、realm/intercept、capability、
provider contract 或依赖边变化都通过旧新编译输入计算 affected closure，不能把 artifact identity 当 unit key。

每个 runtime provider 创建一个长期 driver。一次变更先创建 inert candidate，`prepareAsync()` 只解析与绑定
受影响输入，`seal()` 只把 driver-private 准备结果转成交由 Engine 持有的 generation；保存成功后才
`reconcileAsync()` 启动 units。Java 的 ClassLoader/definition/binder 只留在 Java driver，Node payload、
sidecar/session 只留在 Node driver；Engine 只看公共 plan、observation 与生命周期端口。

Java 在旧、新静态依赖边并集上计算 replacement，并按精确 wiring identity 跨 attempt 复用未变化 class
space；Node 同样按 payload identity 租用静态资源。retained unit 保持原 `unitTargetRevision` 与
`runtimeInstanceId`，replacement unit 分配新 execution identity。driver 不持有另一份 desired、发布指针或
恢复数据库。

资源句柄在取得后、执行后续可失败动作前登记所有者；准备失败不使资源失去归属。停止与清理按以下
先决顺序进行，实际服务消费者也必须完成对旧 provider 激活快照的清理：

```text
受影响贡献停止接入 -> 排空已接受调用 -> 清理实例及其子资源
                 -> 关闭不再使用的 runtime generation -> 释放 package/facet lease
```

同级独立资源逆序尝试关闭并聚合失败；先决层失败不得释放其仍依赖的下一层资源。保留失败 generation、
lease 与清理结果，不得仅从活动索引删除后宣告成功。准备和关闭缓存完整终态，重复关闭不掩盖首次失败。
cleanup 失败关闭 mutation gate 并请求宿主受控退出，不能靠无限积累候选资源继续运行。

`DrainingDisposable` 是 owned resource 的专用排空契约，不是通用事务 hook。沿现有 Scope、插件和
effect 所有权闭包先同步关闭 unit 独占的 `ContributionAdmission`，再排空已接受调用，随后按反依赖顺序
drain/stop units 并 retire generations。同步回调及迟到异步资源不能绕过该边界。普通 effect 的逆序与
告警隔离语义保留，但失败句柄及真实依赖仍被持有，不能以 unit 已退出活动索引为由提前释放 provider。

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
`current()` 是当前事实的唯一长期强引用；`views()` 是不重放历史的热变化流，慢订阅者可合并中间事实。
需要无空窗地覆盖“变更已完成”与“后续变化”时，宿主先建立 `views()` 订阅，再读取 `current()`；
不得以 replay 缓存上一代含插件 descriptor 的快照，否则 Engine 存活期间会额外保留已退役 ClassLoader。

宿主调用必须携带选择贡献时观察到的 expected view revision。准入须同时确认当前视图未过期、
目标贡献仍是该注册身份且开放，并登记在途调用。视图检查与条目准入之间的竞争必须重新复核；
失败返回明确的 stale-revision 或已撤销结果，不能悄悄转向同名的新 handler。旧快照不是永久调用权。

保留 `viewRevision + registrationIdentity` 两项检查，是为了让选择、解析和准入使用同一份已发布事实，
而不是因为已有实现必须兼容。无关事实更新也可能使尚未准入的调用过期，宿主须重新选择；已准入调用
不受这类更新影响。没有真实冲突率证据前，不增加独立 InvocationAuthority、策略版本或第二套调用凭证。

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

### 4.4 Generation 编排与持久目标

package、config 和联合 deployment 共用同一个 command loop。Engine 先编译不可变 candidate 和全局 unit
DAG，再把各 runtime slice 交给对应 driver；这不是开放给任意资源参与者的两阶段提交或事务日志框架：

```text
observe -> validate / prepare affected facets / bind changed entries
        -> seal complete candidate -> save deployment target -> promote candidate as current
        -> close old admission -> drain / stop old units -> activate new units
        -> observe convergence / publish views -> retire released generations
```

配置上下文是持久 desired 的组成部分。任何会改变 enabled、config、realm 或 intercept 求值的上下文变更，
都必须提交包含完整 `ConfigContextSnapshot` 的新 `DeploymentTarget`，参与 canonical target digest，并走同一
save/reconcile 路径；不存在 `ReplaceConfigContext` 这种只切换内存求值而不推进 target revision 的旁路。
保存前先以候选 snapshot 对 raw graph 完整求值和绑定；失败时旧 target、evaluation、实例、effects 与
PublishedView 全部保持不变。

- prepare 读取并冻结输入，完成制品摘要、依赖图和受影响声明的配置绑定；不执行插件启动，不拆旧运行态。
- save 成功后 Engine 只在 command lane 内原子提升完整 candidate 为 current；随后同步关闭 retirement batch
  的新准入，按反依赖顺序 drain/stop 旧 units，再按依赖顺序 activate 新 units。retained units 原样保留，旧
  generation 仅在其全部 unit、invocation 与 resource lease 释放后 retire；不能跳过 promote 或在旧准入仍开放时
  启动 replacement。
- reconcile/activate 调用实例生命周期协议，并等待实际依赖图收敛；按声明要求判断目标达成，合法 PENDING
  不能一律当成失败。该阶段不是可回滚的预检，启动或清理失败必须报告实际状态。
- 所需不可变制品必须先可靠保存，目标清单只引用已完整保存且校验通过的内容；制品保存本身不选择
  活动版本。重复保存同一内容不得覆盖或删除既有对象。
- 制品先复制到操作独占的暂存位置，完整校验后才发布稳定对象；已有对象须校验后复用。
  准备失败、撤销或恢复只清理该操作自己的暂存资源，不删除可能被其他准备操作引用的共享对象。
  完整但未被目标引用的对象可以保留，不为失败清理引入通用 GC 或共享对象回滚。
- `DeploymentTargetStore` 原子替换一份完整目标并返回可核验 token；Engine 只凭该 token promote 已 seal 的
  candidate，随后按上述旧/新 unit 顺序协调运行态并发布事实视图。成功响应须同时满足目标已保存、要求已达成及
  结果视图已发布；失败结果也必须区分目标是否保存、哪些实例已经改变与后续恢复条件，保存成功不是运行时已经
  可用的同义词。
- 保存目标前失败只清理新准备的资源；保存后不得因启动、发布或清理错误反写旧目标。清理按资源依赖逐层进行，
  前一层失败时保留后续先决资源；独立同级资源仍全部尝试并聚合失败。
- 排空与回收不决定保存的目标内容。回收失败进入健康诊断并关闭后续变更准入，不伪造旧路由恢复。

candidate 已 promote、运行已收敛且 retirement batch 已完成的目标，即使包含可观察的插件 FAILED 或未满足
声明要求，仍可通过显式新目标纠正。candidate seal/promote、目标保存确认或 unit drain/stop/retire 任一失败，
都不能据“资源仍有 owner”推断为安全，必须封锁后续变更并保留实际失败事实。不得用统一 finally retire 或
一律重开 gate 掩盖这一区别。
失败诊断分别表达原始执行阶段、目标保存确认与清理失败，不从拼接后的错误文本反推控制决策；保存事实的
公共契约不得让 Engine 反向依赖 Registry 实现，也不为此引入通用事务框架。

DSH 的配置 Entry 在应用失败时会尝试恢复旧配置；这里不自动反写已保存目标，是为了让进程内结果与
重启后读取的目标一致，避免引入第二次可能失败的目标提交。修正配置或恢复旧版本须提交显式新目标；
这是一项明确取舍，不表示 DSH 的恢复方案不合理，也不把 Fibra 描述为具有更强的运行态回滚能力。

`DeploymentTarget` 选择完整 package revision 集合、raw desired graph 与 `ConfigContextSnapshot`，因此
条件求值、稳定 desired entry identity、启停意图、配置、隔离和发布要求共享一个 revision/digest。停用
声明同样保存，不要求对应 definition 已安装。target 不保存 `Class`、绑定后的 typed config、runtime 私有
对象或环境来源路径。

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
fibra-cli-api             -> fibra-api + fibra-bridge
fibra-cli                 -> fibra-cli-api + fibra-engine + fibra-registry + fibra-runtime-java + fibra-runtime-node + fibra-tool-api
fibra-spring              -> fibra-api + fibra-engine
fibra-spring-boot-starter -> fibra-spring + fibra-registry + fibra-runtime-java + fibra-runtime-node
fibra-client-protocol     -> fibra-api
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
| `fibra-engine` | RuntimeDriver SPI、command、全局 unit DAG、单一持久目标、PublishedView | 具体 runtime、Spring、宿主业务、通用事务协调器 |
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
Java `ManagedPluginControl` 与 Node `fibra.disable` 都只向 `RuntimeHostServices.requestDisable` 提交
`RuntimeUnitFence + reason`。Engine 在唯一 command lane 校验 runtime、unit、unitTargetRevision 与
runtimeInstanceId 仍指向 current unit，再从当前 durable target 构造仅关闭该 entry 的完整 replacement。
先保存新 target，再同步封闭 route admission 并排空该 unit；保存失败保留原 target/unit 并允许重试。
动态子插件、直接 `dispose()`、普通失败和 Engine 关闭都不写回 desired。迟到的 retiring generation 请求
被 fence 丢弃，不能错误关闭 replacement unit。

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

`fibra-artifact` 只管理通用 identity、版本、facet、摘要和不可变 package 内容。它不知道 JAR、npm、
ClassLoader 或 Process。配置与 package 互不依赖，由 Engine 在 `DeploymentTarget` 编译中对齐。

程序内建 definition 与 runtime catalog 合并后供目标输入绑定；语法解析和声明采集不持有 catalog 的
类型对象。绑定端口不向输入源暴露制品路径、运行时句柄或运行实例。

#### Profile、配置 Bundle 与插件包

Fibra 保留 DSH `profile` / `bundle` 的组合职责，但不复制其 npm、pnpm 或 `package.json` 实现。
DSH 中已安装 package、bundle patch、profile 组合和最终运行配置是四个不同层次；Fibra 必须保持同样
的分离，不能把扫描到的插件目录直接当作本次运行目标，也不能把配置 bundle 混同为插件 JAR。

| DSH 职责 | Fibra 落点 |
|---|---|
| package 的物理安装 | `PluginPackage`、`PluginPackageStore` 与 Registry selection |
| bundle 提供的有序配置 patch | `DesiredInputGraph` 的 include 与条目 patch |
| profile 选择 bundle 及其顺序 | 命名配置入口按声明顺序组合配置 bundle |
| profile 声明的完整 package 选择 | 相邻的 `<profile>.packages.yaml`，只列相对候选目录的 package 路径 |
| profile 的 package/facet 依赖闭包 | `DeploymentTarget` selection 与全局 facet/unit DAG |
| profile 合成后的活动配置 | Engine 保存的单一完整 `DeploymentTarget` |
| 应用运行态 | 长期 `RuntimeDomain` 中按目标差量协调的 units 与 generations |

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
  plugins/<plugin-id>/              候选逻辑 package
    fibra-package.yaml              package 身份、版本、facets 与依赖
    lib/                            Java payload 与私有依赖
  runtime/bin/node                  目标平台 Node 运行件
  runtime/bin/rg                    目标平台 ripgrep 运行件
  runtime/bin/bash                  固定转发到目标系统 /bin/bash
  config/profiles/<profile>.yaml    命名组合入口
  config/profiles/<profile>.packages.yaml   完整候选 package 选择输入
  config/bundles/*.yaml             可复用配置片段
  data/profiles/<profile>/          首次启动时创建的持久目标、制品与运行数据
```

ZIP 不预置 `data/`，首次启动从空数据目录建立所选 profile 的完整目标。`bin/fibra` 只根据自身真实目录定位
`lib/`、`plugins/`、`config/`、`runtime/` 与默认 data 根，不能嵌入构建机或仓库绝对路径；从任意工作目录
启动时语义相同。发行包携带构建目标平台的 Node 与 `rg`，要求目标系统提供 Java 21 和 `/bin/bash`；
`runtime/bin/bash` 是固定系统边界，不接受构建时绝对路径覆盖。ZIP 内不得出现符号链接、`target/`、
`.DS_Store`、绝对路径或 `..` 路径。

插件包根必须是普通目录且整棵树不含符号链接；唯一根清单 `fibra-package.yaml` 严格声明 `format`、`id`、
`version` 与非空 `facets`。每个 facet 显式声明 `id`、`role`、`runtime`、`target`、`payload`、精确 facet
dependencies 与 required capabilities。所有字段封闭，payload 必须位于包根内，package/facet 摘要只由
受控内容计算，清单不得自报摘要。`PluginPackageStore` 以完整 package 为事务单位保存和恢复；runtime
只读取属于自己的 facet payload，不再探测第二种包格式。

Java facet payload 是含 `META-INF/fibra/plugin.yaml` 的主 JAR；同 package 的私有依赖按明确 payload 布局加入该插件的
唯一 ClassLoader，主 JAR 固定最先，私有依赖不进入宿主或其他插件 ClassLoader。主 JAR 和纳入该
ClassLoader 的私有依赖 JAR 若 `MANIFEST.MF Class-Path` 非空则拒绝，避免 URLClassLoader 隐式引入未受管
路径。JAR 内 runtime-local descriptor 只保留 Java `entrypoint`；逻辑 package id/version/dependencies 只在
根清单出现。Node facet payload 必须是目录，其中 `fibra-plugin.yaml` 只声明 protocol、definitionId、
相对 entrypoint 与 contributions；entrypoint 不能是绝对路径或逃出 payload。

`<profile>.yaml` 保持现有配置条目数组语法，不增加包管理字段或 profile 顶层对象；相邻的
`<profile>.packages.yaml` 是必需的字符串数组，例如：

```yaml
- fibra-fs
- fibra-fs-local
- fibra-tool-fs
```

每项是相对所选候选插件目录的包根路径，不是 plugin ID。清单不重复声明 ID、runtime、版本、入口或
依赖；这些事实由 `PluginPackage.read` 从根清单和内容树严格读取。清单必须显式列出本次
目标的全部 packages，包括所需依赖包，不根据配置 definition 名猜测闭包，不执行版本范围求解。未列出的
候选目录完全忽略；清单顺序仅保留输入与诊断顺序，不取代 runtime 的实际依赖顺序。

缺失、空文件、`null`、非数组、非字符串或空白项均拒绝；`[]` 是唯一明确的空制品选择。路径按候选根
规范化，拒绝绝对路径、根本身、越界路径、URL、通配模式及重复规范路径；解析真实路径后再次检查
候选根边界与重复路径，拒绝通过中间符号链接逃出候选根。包本身仍遵守上述无符号链接约束。不同包路径
解析出相同 plugin ID 也拒绝。清单读取沿用配置文件的大小、嵌套、字符串和条目数量限制。

`fibra-cli` 解析 profile、配置根、候选插件目录和数据目录，并把显式 package 路径清单交给
`PluginPackageStore`；
`fibra-config` 负责 include、patch、条件及
配置树编译；Registry 负责安装、selection、期望状态和审计；Engine 只接收完整 desired graph、selection
与 config context，不感知 profile/bundle 的文件组织；runtime driver 只解释目标引用的 facet payload；
distribution 只提供默认目录和正式组合内容。不得新增与 `DesiredInputGraph`、`DeploymentTarget` 平行的
`BundleManager`、`ProfileRuntime` 或第二套活动状态。

首次所选 profile 的持久存储为空时，CLI 同时读取配置入口与 package 选择，先把 packages 原子发布到
store，再提交一个包含完整 selection、desired graph 与 config context 的 target。
空配置数组与空制品数组构成合法空目标。已有完整目标时，重启必须直接恢复该目标，不读取两个 profile
源文件或候选目录；它们即使缺失也不影响恢复，默认 profile 或分发升级不得静默覆盖保存目标。

显式 `apply` 重新采集配置与完整包清单，验证后通过 Registry 的 deployment 请求一次性替换目标；
不与当前安装集合取并集。此时列出的候选包必须存在，不能缺失时隐式回退到已安装版本。修改候选包不
自动升级目标，只有显式 apply 或 Registry upgrade 才选择新内容。不存在后台 profile watcher 或隐式
refresh；候选文件变化只有下一次显式 `apply` 才进入目标。

Registry install、upgrade、uninstall 只改变当前保存目标，不回写 profile 或包清单；后续完整 apply
明确以输入清单替换这些命令式制品变更。安装不修改实例启用意图或配置 bundle，但可能补齐已有启用
声明所缺的制品，从而使该声明达成。输入文件是下一次导入来源，`DeploymentTarget` 仍是唯一保存
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
Engine、通用 contribution 和 runtime driver 不识别工具终态类型。

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
- contract-only 制品省略 entrypoint，只作为依赖图和类型装载节点；省略入口不等于声明 exports；
- 每制品独立 ClassLoader，按显式依赖图委派；父优先前缀先查 parent，父加载器没有该类时再查本制品与
  声明依赖。宿主实际导出的公共契约因此仍由 parent 唯一定义，动态 contract 不因使用同一产品命名空间
  而被误当成宿主必备类；
- 禁止扫描全部 class 猜入口，不生成 extension index，不维护第二套插件状态机；
- 替换变化制品及其反向依赖闭包，闭包外装载器保留；旧类型仍被实例、服务槽或调用持有时不得回收；
- Java driver 的 prepared generation 私有持有 Loaded 图与精确静态资源 lease；Engine 只编排 candidate、current
  与 retiring 的 public unit roles。未受影响 unit 原样保留；被替换 unit stop 后只释放自身 lease，最后一个 lease
  清零前不得关闭共享 ClassLoader。依赖关系记录实际节点 identity，不按 artifact id 把新旧代连在一起，不另建
  ClassSpace 生命周期；
- 排空和旧实例清理完成后按真实依赖逆序关闭。成功关闭的节点立即解除入口、loader 与依赖强引用；失败
  节点保留自身及必需先决资源，独立成功节点不因同级失败继续被保留。已结束的 unit/generation 只保留元数据
  事实，不经被替换 generation、已结束的异常或缓存结果长期引用插件对象；
- Engine 的长期启动缓存只持有启动完成事实，不持有第一份含插件 descriptor 的 PublishedView。当前
  发布视图仍由正常发布所有权持有；调用方主动保留旧视图、Class 或插件对象不属于框架可回收保证；
- prepare 按每个 artifact 的实际本地 classpath 校验有效二进制类名：主 JAR 与 lib JAR 一并计入，多 release
  JAR 按当前 JVM 的有效类解析，忽略 module-info；同一 artifact 的主 JAR/lib JAR 或不同 lib JAR 之间
  的同名有效类须拒绝并报告两个来源，不能让本地 URL 顺序决定代码版本。实际由 parent-first 命中的宿主
  类仍按宿主归属处理，父优先仅在 parent 实际可解析时成立，保留 parent miss 的动态回退；
- 不同 artifact loader 可以各自定义同名类，包括依赖相连的插件分别携带同一库的相同或不同版本。
  插件自身代码使用自身 loader 的本地定义；依赖方自身缺类时，继续按 manifest 中 `requires` 的声明顺序
  查询依赖 loader，第一条成功路径决定该次直接查找。这个确定顺序只提供隔离和解析规则，不是版本求解：
  不同 loader 定义的同名 `Class` 不可互换，跨插件方法签名、Service 或 DTO 使用的类型必须来自同一个
  宿主或 contract artifact owner。若一个调用方必须同时直接操作同 FQCN 的两个不兼容版本，应在插件
  构建时 relocation/shading；本期不增加 OSGi 式 package import/export 或版本 wiring；
- close-and-collect 以真实 JAR、loader identity 和 WeakReference/ReferenceQueue 取得回收证据，活动
  loader 作为存活对照；close 不等于 JVM 已卸载，不对任意插件承诺 GC 截止，不清理未经证明的全局缓存。
  ClassLoader 只提供类型隔离，不是安全沙箱。

### 6.3 Node Runtime

Node 插件作为受管 sidecar，通过版本化 JSON-RPC 协议参与 Node `RuntimeDriver`：

- 预检完成包与声明校验；实例激活负责进程启动、握手及能力与 schema 协商，失败属于运行收敛失败；
- endpoint 先适配为通用 contribution，再进入域内目录；
- 超时、取消、心跳、stderr、异常退出和消息边界必须结构化上报。`defaultRequestTimeout` 是原请求执行
  deadline，`requestCancellationTimeout` 是发送逐请求取消后等待原请求终态的宽限，两者不能合并；
- 单次请求取消或执行超时不能直接关闭共享 sidecar。宽限内远端结算只结束该请求；宽限耗尽才把 sidecar
  标记为实例级故障，停止其准入并沿统一关闭屏障终止全部受影响请求；
- 一个实例级 session 协调器拥有唯一异步关闭屏障；内部 RPC 部件负责 framing、pending、取消、超时和
  心跳，`NodeProcessUnit` 负责监督器与受管范围。它们是 Node 后端内部责任，不扩展公共 SPI，也不形成
  通用 transport 框架。RuntimeDomain 只等待范围静默，不枚举或缓存瞬时后代 PID；
- 数据通道、payload 退出、supervisor 退出和范围静默是独立事实。监督器独占一个真正可关闭的 fd 1
  输出流转发原始 payload stdout，禁止混用特殊 `process.stdout` 包装器；自然 stdout end 才 flush 并
  物理关闭输出，不能由 payload exit 或范围静默提前截断。stderr 独立读到终态；payload outcome 与范围
  结果写入最终结构化状态，Java 单独观察 supervisor 退出，不引入多路复用 envelope。范围失败且 payload
  尚未结算时 outcome 明确为空，不能捏造启动失败或退出码；已知启动失败且范围静默则可以正常清理；
- 停止准入不停止读侧排空。进程退出回调只登记事实，完整尾帧继续交付，残留半帧以协议错误结束；
  强制截断读取必须呈现 transport 不完整，不能伪装自然 EOF。阻塞 stdin 写入与 flush 不占用定时器、
  session 状态锁或关闭线程；读、写、定时器回调只触发异步关闭，不同步等待或 join 自身；
- retire 停止接入并请求协作关闭，独立截止推动软终止、强终止与范围确认，不以先取得 writer 锁为前提。
  监督器在退出前写出范围终态，Java 在退出后校验；调用方中断不取消这条共享关闭链。缺少证明、明确
  失败或监督器仍未退出均传播到请求 Scope 与实例清理，并保留会话目录；
- 请求结果、请求资源清理与 session 清理分别结算。协议结果可以先失败，但原请求终态或受管范围静默
  尚未证明时，不得成功完成请求资源清理、释放调用租约或 runtime participant。启动与握手的资源所有权
  必须覆盖启动失败和订阅取消，不能只依赖 doOnError 补清理；
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
`fibra-spring-boot-starter` 装配 Java/Node `RuntimeProvider`、一个 Engine、Registry 和 PublishedRuntime；
产品若需要 external runtime，由产品 composition root 显式注册额外 provider，starter 不包含产品实现。
starter 只通过标准 `AutoConfiguration.imports` 发现，默认组件均使用 `@ConditionalOnMissingBean`，配置
通过 `fibra.storage-root` 与 `fibra.source.refresh-interval` 绑定；不引入另一套 JSON、`.env` 或 Spring
Profile 解释。`SmartLifecycle` 在宿主服务完成预注册后启动 Engine，并在 Spring 关闭阶段调用同一个
Engine 关闭入口；不得为 Spring 嵌入场景另建 JVM shutdown hook 或绕过 Engine 的排空结果。

默认 `PluginPackageStore` 与 `DeploymentTargetStore` 在 Engine 工厂内部创建，成功构造后由 Engine 唯一负责关闭，
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
- 具体浏览器/WebView 壳、HMR 与产品 UI slot 协议；框架无关 client foundation 已不再属于本项非目标，
  其独立模块、wire contract 与验收以 2026-09-15 Client Foundation 规格为准；
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
- package/config 联合 deployment 使用同一 `DeploymentTarget` 与 generation 编排，覆盖准备资源清理失败、目标保存边界、mutation gate
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

2026-09-13 的发行与独立审核结果只证明当时的旧发布边界，不能作为 2026-09-17 RuntimeDriver/package
硬切后的完成证据。当前正式边界为 28 个 Maven 制品和两个纯契约 npm 包，必须重新执行根 reactor、
可复现发行、独立 Maven/npm 消费者、正式归档内容和文档一致性门；发布清单与命令以
[发布与构建基线](../../release.md)为准。在这些门和新的独立审查全部关闭前，不沿用旧的“无 P0/P1”结论。

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
参考 DSH 固定提交 `c291e7961a515f6d7af9304e7fd1d257929aef26` 的 `packages/fs/tool-fs`、
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
| DeepSeek Harness | 架构契约 `0.1.5-rc.2`、`c291e7961a515f6d7af9304e7fd1d257929aef26`；`b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed` 与 `a66e4702047846cdaa10c66c9d3df3951f5ea70d` 仅为历史对拍 | [插件依赖、装载与更新基线](../references/2026-09-09-plugin-dependency-baselines.md) |
| cordis4j | `6cfd56e684fb403ded952afc09eddb49a5228494`、设计契约 v2.13 | [cordis4j 设计证据](../references/2026-09-11-cordis4j-design-evidence.md) |

DSH 的插件行为基线构成功能等价门禁；其他来源用于解释取舍，不自动扩大实现承诺。两篇用户提供的解读文章作为问题清单
收录在[源码参考目录](../references/README.md)，结论仍以固定源码、测试与本设计为准。

## 11. Fibra CLI 完成路线与上层 Agent 产品边界

本节定义第 1–10 节交付完成后的演进路线。F1 已完成公开 CLI 组合边界，F2 已完成安全历史、补全与终端
降级，F3 已完成调用级取消与信号协调，F4 已完成 CLI 框架冻结与最终交付门禁。第 1–10 节
已有的固定管理命令、一次性执行、REPL 与 CLI/ZIP 验收只证明当时的封闭 CLI，不能冒充 F1 证据；F1
由本节列出的命令代、显式准入身份、动态 Java command、终端租约和仓外消费者测试单独证明。
后续不再把 DSH 的 Agent 产品业务能力继续堆入 Fibra 仓库。Fibra 负责通用运行时、插件宿主、完整 CLI
框架，以及 2026-09-15 规格定义的框架无关 client foundation；另建上层 Agent 产品项目，消费这些发布物并
组合 Model、Agent、Session、MCP、Skill、Workflow 与具体 UI 产品能力。

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
上层产品可以同时提供 CLI 与 Desktop，但二者只是同一产品 Host 的两个入口和呈现面：都连接同一个
`PluginRegistry`、Engine、持久目标、`PublishedRuntime` 与 Session 事实源，不能各自维护插件启停、版本、
配置或调用路由。

DSH 能力基线继续固定为 `0.1.5-rc.2` 的提交
`c291e7961a515f6d7af9304e7fd1d257929aef26`。AgentCLI 与 PaiCLI 只提供 Java 终端交互参考；二者来源和
历史不同、当前实现高度接近，不构成第二套产品架构基线。吸收源码已经证明的行为，不复制 DSH 的 Node
包布局、Cordis Loader 和 pnpm 安装流程，也不复制 AgentCLI/PaiCLI 的单体 `ToolRegistry` 或斜杠命令树。
固定版本、提交、源码位置和“直接证明/ Fibra 自定增强”的证据分级见
[后续架构真源与外部参考审计](../references/2026-09-13-architecture-source-audit.md)。

### 11.1 两个项目的责任边界

#### Fibra 仓库

Fibra 的后续范围包括可被另一个产品仓库复用的完整 CLI 框架和 framework-neutral client foundation：

- 保持已经完成的 RuntimeDomain、Engine、Registry、Artifact、配置、Java/Node runtime、
  `PublishedRuntime`、Spring starter 和正式 distribution；
- 完成一次性命令与 REPL 共用的 CLI 应用模型、终端交互、调用取消、信号、输出协议、bootstrap 命令和
  动态 command contribution SPI；
- 保留已交付的 fs、fs-search、subprocess、shell、storage 和 tool API，作为通用正式插件、真实验收场景
  和上层产品可直接消费的发布物，不移动、不复制源码；
- 提供仓库外消费者门禁，证明上层产品只依赖声明过的发布制品即可创建自己的 CLI 和 distribution；
- 按 2026-09-15 Client Foundation 规格提供逻辑插件多 facet、统一 `RuntimeDriver` SPI、client protocol/API
  和不发布的 conformance fixture；产品拥有具体 client RuntimeDriver、transport、browser runner、Web loader、
  renderer、窗口壳和业务数据流；
- 不新增 Model、Agent、Session、MCP、Skill、Goal、Todo、Plan、Compaction、Sandbox、Jobs、ACP 或 UI
  业务模块，不把这些类型加入 `fibra-api`、`fibra-cli-api` 或 core。

`fibra-runtime-node` 继续属于 Fibra。它是 Node sidecar 的通用 runtime driver：校验 Node 插件 facet 与
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
- 产品自己的极薄 CLI/bootstrap、command 与 UI 插件、默认插件选择、凭据策略和 distribution。

上层项目可以依赖 Fibra 的 fs/search/shell/storage 发布物，但产品 profile 是否默认启用由上层项目决定。
产品插件必须像现有 Fibra 正式插件一样进入 RuntimeDomain 生命周期，经 `PublishedRuntime` 向产品宿主
发布贡献；不得因为分仓而改用静态注册表、Spring 扫描、直接 ClassLoader 或旁路 RPC。

### 11.2 Fibra CLI 框架的完成定义

当前 `fibra-cli` 已有固定管理命令、一次性执行、共享 Engine 的 REPL、稳定 JSON 输出、profile 路径、
关闭 hook 和真实发行入口。F1 已把应用元数据、bootstrap command、动态 command descriptor、调用上下文、
输出、退出状态和终端租约提取为公开组合边界；F2 已提供安全持久历史、高亮、运行时补全和 dumb-terminal
降级；F3 已交付调用级 `Ctrl+C`、raw lease `0x03` 与外部信号协调；F4 已冻结 `CliSession`、应用原始输入、
resize、redisplay、渐进 renderer、终端恢复及公开 CLI API 兼容性。

F1 起采用以下模块边界：

| 模块 | 类型 | 职责 |
|---|---|---|
| `fibra-cli-api` | 宿主可见公开 API | CLI 应用构建、bootstrap 命令、动态 command contribution、调用上下文、取消、输出通道、退出状态和受控终端租约契约 |
| `fibra-cli` | CLI 实现与参考入口 | Picocli/JLine 实现、Fibra 固定管理命令、REPL、默认宿主创建和 `main` |
| `fibra-distribution` | Fibra 参考发行 | 通用 Fibra CLI、正式基础插件、默认 profile 和仓库外验证 |
| 上层产品的 CLI 模块 | 外部消费者 | 依赖 `fibra-cli-api`/`fibra-cli`，只提供产品 `main`、bootstrap 元数据与 renderer 宿主 |

`fibra-cli-api` 不暴露或重新抽象 Picocli。bootstrap 与动态命令都声明 Fibra 的不可变 descriptor 和
结构化 handler；`fibra-cli` 为每次操作构造 lane-local Picocli `CommandSpec`。Picocli、JLine 和 renderer
实现均是 `fibra-cli` 私有细节，不能跨 ClassLoader 或公共 API 传递。F1 公开 API 提供：

- `CliApplication` builder，用于选择根命令元数据和添加静态 bootstrap command；`fibra-cli` 的
  `CliSession` 借用调用方已有的 `PublishedRuntime`，在同一执行 lane 启动一次性命令或 REPL；
- 每次命令调用独有的 `CliInvocation`，携带 cancellation、stdout/stderr 输出、受控 terminal 及只读 profile
  信息；不能暴露 RuntimeDomain `Context` 或 Engine 内部对象；
- `CliCommandDescriptor`、结构化参数值和本地 command contribution kind；Java 插件可贡献命令路径、参数、
  帮助、敏感标记和补全事实，执行仍经 `PublishedRuntime.invoke` 获得 revision、取消与排空语义。F1 不为
  Node 命令臆造 wire codec；只有出现真实 Node command 消费者并冻结版本化协议后才扩展该 kind；
- 不暴露 JLine 类型的终端会话与单 lane 独占租约：非交互调用稳定返回 `UNSUPPORTED`，同 lane 重入返回
  `BUSY`，关闭后返回 `CLOSED`；invocation 结束时框架强制释放遗忘的租约。raw mode、resize、interrupt、
  redisplay 和阻塞读取取消仍由 F3/F4 定义，F1 不冒充已完成；
- 统一的 usage、启动、业务调用、revision 冲突、关闭和取消退出状态；人类与机器输出走明确分离的通道。

根 help/version、profile 解析和插件管理等 bootstrap 命令静态存在；Agent、Session、MCP 等产品命令由
运行时插件贡献。CLI 先解析最小启动参数并打开 host，再捕获一个 `PublishedView` 的 command descriptor、
贡献注册身份与 view revision，形成 Fibra 定义的不可变命令代。一次解析，以及由该次解析触发的 help、
usage 与补全，只能使用这组已捕获事实，过程中不得重新读取最新 view。

“不可变命令代”只指 Fibra descriptor、贡献注册身份和 revision。Picocli `CommandSpec` 是带有
`addSubcommand`、`removeSubcommand`、usage setter 等可变操作的解析对象，不是并发只读快照。实现必须从
捕获的 Fibra 命令代构造受单次操作或单条 CLI lane 约束的 `CommandSpec`，不得把它作为命令代身份、跨线程
发布后继续修改，或允许插件直接取得并修改。

解析开始不等于调用已准入，也不取得旧 route 租约。只有执行边界通过
`PublishedRuntime` 以捕获的 revision 和贡献注册身份完成准入后，invocation 才持有 route 租约并参与
排空；尚未准入而 view revision 已变化，或贡献已撤销时，稳定返回 stale/revoked，不调用旧 handler，
也不转向同名新 handler。已经准入的 invocation 继续使用获准 route，直到调用 Scope 清理完成后才释放
租约。REPL 每次读新行前可以采用最新已发布命令代；同一行的解析、help、补全和执行选择仍固定在该次
捕获上。Spring 继续只是宿主适配器，不引入 Spring Shell，也不允许应用上下文扫描生成工具或命令。

### 11.3 DSH 能力盘点与项目落点

下表记录 DSH 固定源码中已经核实的主要能力，以及在新的双项目边界中的落点。“按需”表示只有真实场景
和验收样例出现后才创建模块，不能据此预建空接口。

| 能力域 | DSH 固定基线中已核实的行为 | 当前状态 | 后续落点 |
|---|---|---|---|
| Boot、package、bundle、profile | 环境分层、配置组合、启动审计；安装包、bundle patch、profile 和最终配置分层 | Fibra 已完成 | 继续由 Fibra 提供，上层项目只声明自己的 profile/bundle/package |
| 插件生命周期 | 依赖驱动激活、异步 effect、服务撤销等待、条件/isolate、局部更新、主动停用 | Fibra 已完成 | 所有上层产品插件复用，不建产品私有容器 |
| fs、搜索、shell、subprocess、storage | contract/provider/tool 分层；搜索经 subprocess；进程树排空；具名存储与变更事件 | Fibra 已完成本期范围 | 保留 Fibra 正式发布物；图片、Jobs、PTY、Sandbox 不冒充已完成 |
| CLI 交互 | DSH 的 commands/questions/approval 均为插件；AgentCLI/PaiCLI 有 JLine 历史、补全、高亮和 renderer | Fibra F1–F4 已完成 | F1 已交付 bootstrap、不可变命令代与动态 Java command；F2 已交付安全历史、交互补全、高亮与 dumb-terminal 降级；F3 已交付调用级取消、raw lease 与进程信号协调；F4 已冻结 `CliSession`、resize、redisplay、渐进 renderer 和兼容性；产品 command/questions/approval/Agent renderer 均留上层插件 |
| Model | provider 路由、模型发现、配置解析和流式适配 | 未实现 | 上层项目动态 contract/provider 插件 |
| Agent | Agent factory、轮次/步骤、模型流与工具调度；完整实现依赖 Session | 未实现 | 上层项目 Agent 插件；先以 invocation 内状态闭合 Model/Tool，再按产品架构真源接入独立 Session |
| Tool 流水线 | schema、作用域、guard、pre/execute/post/result 和多种呈现 | Fibra 已有公开工具调用 | 通用调用留 Fibra；Agent 选择、审批和产品呈现留上层项目 |
| Session | 追加日志、冻结事实、投影、fork、flush；持久化和投影分离 | 未实现 | 上层项目独立 Session contract/provider，不复用部署目标存储或 ConfigStore |
| MCP | stdio/Streamable HTTP、工具同步、撤销和有限重连 | 未实现 | 上层项目 Tool adapter 插件；首期不声称 resources/prompts 全覆盖 |
| Context、Skill、附件、spill | 文件系统 skill、指令/引用上下文、附件和超长结果外置 | Fibra 仅有可选 spill seam | 上层项目业务插件；必要时消费 Fibra `ResultSpillStore` |
| Goal、Todo、Plan、Compaction | 目标、待办、计划模式、上下文压缩和 checkpoint 协作 | 未实现 | 上层项目基于 Session 事实的独立插件 |
| Approval 与权限 preset | ask/never、调用级审批与审计；组合 shell/session/approval | 未实现 | 上层产品 policy 插件和 CLI/外部 responder；不进入 Fibra CLI core |
| Sandbox | policy 与平台 backend 分离，报告 full/partial 或拒绝 | 未实现 | 上层项目 contract/provider；不能把 Fibra 进程树管理称为沙箱 |
| Jobs、Terminal、PTY | 内存 JobRegistry、owner 控制、输出/等待/取消；persistent shell 和 PTY | 未实现 | 上层项目独立插件；DSH `jobs-local` 不持久，首期不承诺重启续跑 |
| Web、LSP、Webhook、Schedule | 均为独立包和 adapter | 未实现 | 上层项目按需插件，没有真实调用方前不创建 |
| Subagent、Workflow | 多种 subagent provider、worker/workflow/tool | 未实现 | 上层项目在单 Agent 生命周期稳定后实现 |
| API、SDK、ACP、Web UI | gateway/remotes、journal stream、ACP、浏览器连接、renderer 和 UI slot 都由插件组合 | 未实现 | Fibra 提供框架无关 client foundation；上层项目负责 gateway、业务数据流、窗口壳和具体 renderer/UI 插件 |
| Observability | invariant、session telemetry、stats、token meter、启动诊断 | Fibra 框架诊断已完成 | Fibra 保持框架诊断；上层项目随产品阶段交付业务遥测 |

两处边界以源码而不是名称判断：DSH 搜索没有注入 `fs` 或 `shell`，所以上层项目复用 Fibra search 时
仍只依赖 subprocess；固定基线的 UI renderer 会自行创建 SlotRegistry，不能依据旧 README 虚构
`slots/sessions/layout` 注入关系。DSH OTel 实现核实的是日志导出，不得扩写为 traces/metrics 全栈。

### 11.4 Fibra 仓库实施阶段

F1–F4 已完成。上层 Agent 产品只能消费冻结后的发布制品，不能让产品代码反向塑造 CLI 框架。

#### F1：公开 CLI 组合边界（已完成，2026-09-13）

- 已新建 `fibra-cli-api`，从封闭 `FibraCli`/`CliHost` 中提取最小应用构建、bootstrap 命令、动态
  command contribution、调用上下文、输出、退出状态和受控终端租约；`fibra-cli` 继续提供默认实现和
  现有固定命令。
- 一次性命令与 REPL 已经过同一命令代和 invocation 创建路径；CLI 先解析最小启动参数，再从
  `PublishedView` 的 command contributions 捕获 descriptor、贡献注册身份和 revision 后建树。解析、
  help、补全固定使用这次捕获；执行前必须以同一身份/revision 经 `PublishedRuntime` 准入。尚未准入即
  发生代变化时返回 stale/revoked，不调用旧 handler，也不转向同名新 handler。command handler 只取得
  `CliCommandRequest` 中的参数、取消、输出、terminal 和 profile/path 视图，不取得 `Context`、Engine、
  Registry 或 `PublishedRuntime`；插件管理仍由 Fibra 固定管理命令和唯一 Host 控制面负责。
- 已扩展仓库外临时 Maven 消费者，只依赖安装到隔离仓库的 Fibra 发布物，加载真实 Java command
  插件，执行其动态命令、停用并验证命令消失，同时调用一个已发布工具；仓内公开路径测试通过
  fake/dumb terminal 验证成功与失败后的租约恢复，证明无需包私有类型和 reactor。

完成证据：公开 API 签名门禁覆盖 `fibra-cli-api` 与 `FibraCli` 启动门面；现有 CLI 命令和 distribution
无行为回归；契约测试证明解析中更新、
help/补全中更新、执行准入前撤销和同名贡献替换均不跨代，且只有已准入 invocation 持有租约并参与排空；
外部消费者从空 Maven 依赖仓构建、启动并完成扩展命令与真实工具调用。

#### F2：安全历史、补全与终端降级（已完成，2026-09-13）

- bootstrap 补全从捕获的 Fibra descriptor/revision 派生，并可使用同次操作内受约束的 Picocli
  `CommandSpec` 渲染；动态命令及工具名候选来自同一个已捕获 `PublishedView`。插件变更成功后原子发布
  新的 Fibra 命令代，不在原 `CommandSpec` 上原地并发修改，也不引入第二命令或工具目录。
- 吸收 AgentCLI/PaiCLI 的 JLine persistent history、高亮和 dumb-terminal fallback。JLine history 只接收
  可安全重放的命令；`tools invoke --input` 原值以及 descriptor 中 `sensitive=true` 的当前或未来凭据参数，
  在内存与持久历史中都只留下不可重放的脱敏摘要。敏感参数位置按 DSH schema `role('secret')`/
  `recordInput` 的显式声明思路处理：option 后的值即使以 `-` 开头也属于敏感位置。CLI 诊断仅替换实际敏感值，
  保留非敏感错误上下文；不得采用 Codex `save-all` 历史或 best-effort 正则作为正确性边界。
- 仅在人类终端 stderr 显示 profile、workspace、工具数量和 revision 摘要；JSON stdout 保持机器可解析，
  管道和非 TTY 不因 renderer 改变。

完成证据：同代 command/tool 补全、敏感持久历史、重启、dumb/管道、stderr 摘要和仓外 ZIP 验收已通过，
详情见[行为验收账本第 8 节](../references/2026-09-11-behavior-verification-ledger.md#8-f2-安全历史补全与终端降级证据)。真实 TTY 验证历史、补全、高亮、窄终端和重启；使用未被测试工具写入业务数据的敏感样本，
确认 CLI 历史文件及 CLI 自身诊断输出中不可检出原值；dumb terminal、管道输入、一次性命令和仓库外
distribution 回归通过。直接桌面 TTY 在 F4 冻结前仍须复验；本次自动化环境的 P2 接受理由同见账本。不得扫描
workspace 或 storage 后把用户明确要求保存的内容误判为历史泄漏。

#### F3：调用级取消与信号（已完成，2026-09-13）

三个入口已经分开识别，再汇入同一个幂等 invocation 取消与排空协调器：

- 普通 REPL 由 JLine `LineReader` 读取命令行时，键入 `Ctrl+C` 产生输入中断，只清空当前编辑缓冲并继续
  会话；此时尚无已准入 invocation，不创建 route 租约、不触发 Engine 关闭。
- 已准入 invocation 持有 raw terminal lease 时，租约进入 raw mode；读取到字节 `0x03` 后将其转换为
  当前 invocation 的取消请求，不把该字节交给业务输入。租约停止新读，以限时非阻塞读取唤醒等待，并
  幂等恢复进入 raw mode 前的终端属性；CLI 等待 handler 返回，而 `PublishedRuntime` 继续等待 invocation
  Scope、route、远端终态和受管资源清理完成，随后才允许 REPL 读取下一条命令。
- 外部 `SIGINT` 与 `SIGTERM` 都独立于 JLine 行编辑器注册：首次信号原子关闭本进程的新 invocation 准入，
  且在 signal handler 返回前同步完成该准入屏障；可能阻塞的协作取消则交给异步关闭 worker，覆盖全部
  已经开始的 CLI invocation。其中只有经 `PublishedRuntime` 准入的调用持有 route 并进入
  Engine Scope 排空。一个从首次信号开始计时的 5 秒截止覆盖取消发送、完成屏障和当前 CLI 所拥有
  `CliHost` 的关闭全过程，而不是只限制其中一次 future 等待。正常完成与信号共用原子退出仲裁：正常完成
  先关闭信号准入后才进入普通 close；已接受的信号则独占退出码和关闭链，不能被主线程的旧结果覆盖。
  当前 Fibra CLI 只有 owner 运行形态，不存在可附着到共享 Host 的 client 模式，因此不预建 owner/non-owner
  公共抽象；未来若增加该运行形态，必须另行定义所有权契约。`SIGINT` 成功排空后退出 130，`SIGTERM`
  成功排空后退出 0；排空超时强制退出 8，宿主关闭失败退出 7。首个信号决定结果，重复或竞态信号只复用
  同一取消、排空与关闭过程，不二次关闭或跨过完成屏障。

取消、排空超时、业务失败和宿主关闭失败保持不同投影；bootstrap、动态 command contribution 与工具调用
都使用同一 CLI invocation 协调器。框架只把“handler 成功但 token 已取消”或不携带其它失败的纯取消异常
投影为 130；handler 显式返回的非成功状态、业务异常与 `suppressed` 清理失败优先保留，插件无需捕获
terminal 中断并手写取消状态。动态命令仍只在 `PublishedRuntime` 准入后取得 route 租约。AgentCLI/
PaiCLI 的 `Future.cancel(true)` 与 DSH command 的 abort-race 只能证明立即停止
等待/请求取消，不能作为 Fibra 已等待 invocation Scope、route 租约和远端终态排空的证据。

完成证据：`CliReplTest`、`CliTerminalControllerTest`、`CliInvocationCoordinatorTest`、
`CliProcessSignalHandlersTest`、`CliProcessShutdownTest` 与 `FibraCliTest` 覆盖三种入口、raw 属性恢复、阻塞读取
唤醒、纯取消投影、显式失败状态与 `suppressed` 清理失败保持、signal handler 返回前停止准入、正常/信号
仲裁、阻塞取消回调与阻塞 Host close 的总截止、重复信号、完成屏障及退出码；
`PublishedRuntimeLeaseTest` 与既有 Node/Scope 取消测试证明
route、远端终态和受管资源按所有权排空，不以参考项目的立即取消替代。原生 xterm PTY 实测普通编辑态
`Ctrl+C` 清行后可执行动态命令，raw lease `0x03` 取消后可再执行动态命令并以 0 退出。发行 ZIP 仓外门禁
以真实进程信号验证 `SIGINT=130`、`SIGTERM=0`，两者均在截止内停止 shell payload、supervisor 与叶子进程；
外部 Java 插件 `external-cli read-key` 验证只依赖发布 API 即可取得 raw lease、收到取消并恢复下一条命令。
受影响模块、公开 API 基线、根 `mvn clean verify`、仓外 ZIP 真实信号与原生 xterm PTY 均通过；包含最新
F3 改动的空 Maven 仓、五类仓外消费者和 archetype 全套隔离门禁按阶段约定留到 F4 完成后统一执行，
此前 F1/F2 的空仓结果不抵扣该最终门禁。详细映射见
[行为验收账本第 9 节](../references/2026-09-11-behavior-verification-ledger.md#9-f3-调用级取消与信号证据)。

#### F4：CLI 框架冻结与交付门禁

实现状态：已完成。直接证据见
[行为验收账本第 10 节](../references/2026-09-11-behavior-verification-ledger.md#10-f4-cli-框架冻结与最终交付证据)。

F4 先冻结以下最终所有权，不能按现有类的缺口逐项补方法：

| 对象 | 所有者 | 关闭边界 |
|---|---|---|
| `CliApplication`、命令 descriptor、可选 input handler | 应用或插件 | 不拥有 Host、runtime、terminal 或调用；只描述不可变应用与命令事实，handler 只作为应用文本入口适配器 |
| `CliSession` | 嵌入方 | 借用一个既有 `PublishedRuntime` 和 profile；独占一个执行 lane，拥有本会话的 invocation 协调、终端会话、行编辑器、输出仲裁、历史和关闭屏障；不关闭 Engine、Registry、Host 或调用方 streams |
| 参考 `FibraCli` | Fibra 进程入口 | 创建并拥有本地 `CliHost`，安装进程信号并组合固定管理命令；只通过与 `CliSession` 共用的私有执行实现调用应用命令，不再兼作自定义应用的嵌入入口 |
| invocation | `CliSession` 的单次命令或应用文本提交 | 只在提交进入 handler 时创建；直接绑定自己的 cancellation、`CliOutput` 和 `CliTerminal`，不得通过进程级“当前调用”猜测身份；结束前关闭输出准入、释放 renderer/terminal，再等待已经通过 `PublishedRuntime` 准入的 route 与 Scope 排空 |
| 物理 terminal 与 JLine | `fibra-cli` | 插件不得取得 JLine 类型、另读 `System.in`、安装原生信号或另建终端循环；系统终端由会话拥有并在会话关闭时恢复，dumb 模式只借用 streams |
| terminal lease 与 renderer | 当前 invocation / 框架 terminal lane | invocation 独占 lease；框架拥有 raw mode、按键解码、resize 观察、重绘合并、Display、取消和恢复；renderer 只拥有自身状态及可跨线程请求 render/finish 的控制柄 |

`CliSession` 是最终唯一公开嵌入入口。它位于 `fibra-cli`，因为工厂参数可以直接使用公开
`PublishedRuntime`；`fibra-cli-api` 继续只保存插件可见的应用、命令、调用、输出和终端契约，不增加
Engine 依赖。会话的 terminal 选择是显式能力：机器/dumb 模式下所有 invocation 的 terminal 都稳定返回
`UNSUPPORTED`；选择系统终端后，同一能力同时提供给一次性命令和 REPL，不能只让 REPL 特殊取得租约。
参考 `FibraCli.main/run(String[], ...)` 保留 owner 语义；自定义 `CliApplication` 不能再经参考入口隐式创建
第二个 Host，旧双入口、公开 Picocli `Callable/call()` 形状和对应包私有旁路直接删除，不留兼容转发。

`CliApplication` 未配置 input handler 时保持命令型 REPL：每行捕获命令代并由 Picocli 解析。配置 input
handler 后，JLine 提交的完整原始文本必须在 trim、shell 分词、`exit`/`quit` 判断和命令解析之前直接交给
应用；不捕获命令代、不安装通用命令补全/高亮，也不写通用命令历史。每次文本提交仍是一个有限
invocation，空闲编辑期没有 invocation。slash command、prompt 历史、对话 journal 与重连均属于上层
Agent/Session 插件；input handler 只做稳定适配，不缓存插件实现，动态能力仍经 `PublishedRuntime` 精确
准入，也不得递归调用同一 `CliSession.execute()` 破坏单 lane。应用通过 `CliInputResult` 显式请求继续或
退出 REPL；框架不保留特殊退出字符串，handler 也不靠异常或 lane 内自关闭退出。

`CliSession` 可以与 CLI 进程同寿命并跨越任意多个有限 invocation，但它不是产品 `AgentSession`，不保存
对话事实、turn、模型上下文、流式游标或断线续接状态。一次流式命令在当前 invocation 存活期间由上层
生产者更新 renderer 自有状态，再通过线程安全控制柄请求重绘；`execute()` 保持阻塞不妨碍 terminal lane
继续消费输入、resize、取消和合并后的刷新事件。后台生产者不得直接触碰 JLine、`Display` 或物理终端，
也不得在 handler 返回后继续使用旧输出柄。跨 turn 持久化与重连使用上层产品架构第 9.2 节的 Session journal；
不能用一个永不结束的 CLI invocation 冒充长会话，否则插件撤销和 route/Scope 排空将永久受阻。

一个会话只有一条终端状态机：

```text
IDLE -> LINE_EDITING -> INVOCATION -> RAW_RENDERER -> INVOCATION -> LINE_EDITING
  |          |              |              |                         |
  +----------+--------------+--------------+-------------------------+
                             close/cancel
                                  |
                       STOPPING -> RESTORING -> CLOSED
```

这里的单 lane 以物理 terminal 为边界，不是进程级全局锁。不同 `CliSession` 各自拥有 terminal、输入
所有权、renderer、输出队列、lease 与恢复状态，可以借用同一 `PublishedRuntime` 并发执行；关闭或毒化
terminal A 不取消、阻塞或污染 terminal B。同一个物理输入源只能由一个活动会话读取，尤其不能让两个
`systemTerminal()` 会话同时争抢 `System.in`。Codex 的 `EventBroker` 只证明同一 crossterm/stdin 事件源必须
单读者，并明确多个 event stream 同时轮询会互相偷取输入；它不证明所有物理终端应共享一个全局 broker。
未来 P0 的多个远端 CLI 客户端各自在本地拥有此状态机，服务端只共享会话事实、PublishedRuntime/gateway
与调用排空，不集中接管各客户端的编辑缓冲或终端模式。

- `LineReader`、renderer `Display` 和物理输入永不并发读取或绘制。普通 REPL `Ctrl+C` 只清编辑缓冲；raw
  `0x03` 取消与 lease 绑定的精确 invocation；外部 `SIGINT/SIGTERM` 仍由 owner 入口的 F3 协调器关闭
  准入和取消会话，不交给 JLine 猜测。会话在调用 JLine `readLine()` 前先安装只负责中断当前读取线程的
  临时 INT handler，再发布编辑态；因此停止不会落在 JLine 尚未安装自身 handler 的空窗。三者共享取消
  token 与最终排空屏障，但不混淆入口语义。
- renderer 生命周期固定为 `start -> (resize/input/render)* -> stop`；回调只由 terminal lane 串行调用。
  首帧前先报告实际正数 `columns/rows`，WINCH 只标脏，lane 再读取最新尺寸并合并重复 resize/render 请求。
  `close()`、EOF、取消或任一回调失败都必须进入同一恢复链；初始化阶段也在恢复边界内。lease 关闭只有在
  Display 清理、bracketed paste、keypad、flush、raw attributes 和 resize handler 均独立尝试后才完成；
  恢复失败不能被伪装成取消，且永久封锁该物理 terminal 的后续 `openInvocation/acquire`。
- bracketed paste 必须在 renderer 获取物理终端后启用并在同一恢复链中成对关闭；begin/end 之间的全部
  内容作为一个 paste 事件交付，`CRLF`/`CR` 归一为 `LF`。其中的换行、`0x03` 和 slash 都是字面文本，
  不得触发 Enter、取消或命令路由。单键事件只报告当前固定源码与 PTY 能明确证明的 Shift/Control；普通
  大写字符不得反推 Shift，也不预留无法产生的 Alt。无法区分的传统终端输入不得伪造修饰键事实。
- renderer 返回完整不可变物理帧；行数和按显示单元格计算的列宽必须适配最近尺寸，光标使用零基单元格
  坐标。字符串只允许普通文本与 ANSI SGR，其他 C0/C1 控制符、CSI/OSC 或换行直接拒绝；`NO_COLOR`、
  dumb 和非 TTY 路径移除 SGR 且不输出其它 ANSI。
- `CliOutput` 绑定 invocation 生命周期，保留 stdout/stderr 语义；关闭准入后旧 handler 不能在下一条编辑
  行或会话关闭后继续写。renderer 占有 Display 时，合法的并发输出由同一 terminal lane 暂停画面、写入
  原通道并重绘，不能直接破坏物理屏幕。会话级异步人类消息只进入 stderr 呈现通道：行编辑时使用 JLine
  `printAbove` 保留缓冲与光标，renderer 期间进入同一事件队列。同一物理 terminal 的所有已经准入消息
  都登记为会话内在途
  呈现，编辑态进入、离开及会话关闭均等待其完成；停止后到达的消息明确拒绝，不得越过关闭或写入下一轮
  编辑。机器 stdout 永不承载 prompt、profile 摘要、颜色或 terminal 控制序列。
- `CliSession.close()` 是幂等的单一完成屏障：原子停止 execute/行读取准入，唤醒编辑器或 renderer，取消
  本会话调用，等待 handler、诊断投影、输出队列、route/Scope 排空和 terminal 恢复，再关闭本会话拥有的
  JLine terminal；重复 close 等待同一共享结果，失败时每个观察入口生成独立异常包装，不能重复抛同一
  `Throwable` 触发 Java 自抑制。关闭会话 A 不取消会话 B，也不关闭二者借用的 Host/runtime；调用方
  stdin/stdout/stderr 只 flush、不 close。

F4 同时统一 help/version、机器 JSON、人类 stderr、颜色/无颜色、非 TTY、关闭时序和异常映射；renderer
只处理通用命令状态，不加入 Agent token、tool progress 或产品 Session 事件。用一个不含 Agent 业务的
仓库外渐进消费者验证一次性和 REPL 的普通字符、方向键、ESC、明确修饰键、bracketed paste、resize、
异步 `printAbove`、调用取消、并发输出、失败恢复与租约释放；同一消费者还验证应用原始输入在未配对引号、
空白和 `exit` 文本下不被框架改写，证明上层无需取得 JLine 私有对象或旁路框架。

CLI 发布契约按同一 Fibra 版本管理：`fibra-cli-api` 与 `fibra-cli` 使用根 `revision` 同版本发布，动态
插件与嵌入方对当前 vNext 发布使用精确版本，不用范围或 `*` 掩盖契约错配。两个模块的全部 public/protected
类型由 `javap -protected` 基线冻结；任何公开签名或语义变化必须在同一提交更新架构、签名基线、契约测试
和仓外消费者。当前始终使用 `0.5.0-SNAPSHOT`，不为打磨升级版本，也不保留旧 API、协议或模块兼容层。
签名基线是显式契约变更的检查门禁，不是阻止最终架构重构的护栏；不得为了通过旧基线而保留废弃重载。

更新 Fibra distribution、外部消费者、Spring 宿主和 archetype 证据，证明 CLI API 可嵌入且默认 CLI
仍可独立运行。跨进程 Host 发现、认证、Host lifetime lease、具体 transport 和远端 terminal 不属于 F4，
也不属于 Fibra client runtime 实现；它们由产品装配按唯一 Host 架构实现。Fibra P0 只提供通用 SPI、
protocol/API 与围栏，不能从同进程
`PublishedRuntime`/`CliSession` 反推为已经存在。

完成证据：定向测试、公开 API 签名、仓库外消费者、根 `mvn clean verify`、可复现制品和空 Maven 仓
分发门禁全部通过；权威架构和既有验收账本已回填；独立审核无未关闭的高优先级问题。Fibra CLI 框架
据此完成，Fibra 不再沿本路线增加 Agent 产品功能。

### 11.5 上层 Agent 产品边界与权威真源

F4 完成后先进入 Fibra Client Foundation P0，再进入上层 Agent 产品实施。本节不再定义 client foundation
或产品业务阶段编号、顺序、模块清单或退出条件；前者唯一真源是
[2026-09-15 Client Foundation 权威架构](./2026-09-15-fibra-client-foundation-architecture.md)，后者以
[基于 Fibra 的 CLI + Desktop Agent 产品架构与实施路线](./2026-09-13-fibra-based-agent-product-architecture.md)
为准。任何阶段调整不回填第二套阶段表到 vNext。

vNext 只保留边界：上层产品单独拥有仓库、版本、CLI/Desktop 入口、产品插件、Session 事实源和发行物；
它只消费 F4 后发布的 Fibra 制品，不能取得 Engine 内部 `Context`、建立第二插件控制面，或把产品实现证据
回写成 Fibra F1–F4 已完成。Web、LSP、Webhook、Schedule 和第二种模型协议等是否进入路线，也只由上述
产品架构真源按真实消费者决定。

### 11.6 Client Foundation 与产品长会话边界

本节只保留 vNext 与后续工作的接口边界，不再保存 client foundation 的重复设计。逻辑 `PluginPackage`、
多 facet、Host/client execution、wire identity、`targetDigest/targetRevision`、框架无关 runtime 和 P0
验收全部以
[2026-09-15 Client Foundation 权威架构](./2026-09-15-fibra-client-foundation-architecture.md)
为唯一真源。

vNext 已交付的 `PublishedRuntime`、贡献租约、调用 Scope、排空和关闭语义继续作为底座；当前实现已经按新规格
硬切为单一 `RuntimeDriver`、逻辑多 facet package 与完整 `DeploymentTarget`。上层产品仍独占 Agent、
Session、Model、审批、窗口壳、具体 renderer 和长会话 journal，不能把这些业务事实下沉进 Fibra client
协议。

#### 桌面启动与页面打开

Fibra 通用 CLI 仍不隐式打开页面。Electron、Tauri、浏览器标签页、远程 Web 和 headless server 的启动、
认证、窗口及 Host 退出策略由上层产品决定；产品消费 Fibra RuntimeDriver SPI 与 client protocol，自行实现
transport、browser runner 和 renderer，同时不得复制目标选择、generation、围栏或 Registry。Host ready
不等待 client 在线，UI ready 由 client execution observed 单独表达。
完整产品启动与 Session 关闭策略只在
[上层产品架构](./2026-09-13-fibra-based-agent-product-architecture.md)维护。

#### 产品 client runtime 的边界

Fibra 只发布 RuntimeDriver SPI 与 client protocol/API，另维护不发布的 conformance fixture。产品 client runtime 执行 Host
下发的生命周期和资源计划，维护 session、transport、browser Scope/effect 并回报 observed；它不扫描、
安装、选版本、保存 desired 或成为 Session 事实源。具体 identity、消息和关闭契约不在本文重复定义。
Electron + React 只是首个产品实现，不是 `client:web` 的定义。

#### 统一插件管理与全形态插件

逻辑插件、Host/command/client facets、package gate、唯一 target 和 Registry 用例全部归 Fibra。CLI 与
产品管理页面只能投影同一组管理用例；不存在前端专用 Registry、版本选择器、profile 或恢复数据库。具体
包格式、依赖解析、保存边界和离线 observed 规则只由 2026-09-15 规格定义，本文不维护第二份字段清单。

#### 开源参照与 Fibra 取舍

DSH/Cordis、VS Code、Grafana、Theia 与 Codex app-server 的证据、版本和“不照搬”边界统一维护在
[外部参考审计](../references/2026-09-13-architecture-source-audit.md)。Fibra client foundation 不定义
JavaFX `Node`、React component、路由、slot、browser loader、transport 或产品长会话数据面；这些类型和
实现只能存在于产品 runtime/renderer adapter 内。

长会话继续由上层产品拥有持久 Session journal、turn 生命周期和有界事件读取。除非出现独立真实消费者并
证明现有 unary contribution 无法满足，否则 Fibra 不预建通用 streaming contribution。该业务路线以
[上层产品架构](./2026-09-13-fibra-based-agent-product-architecture.md)为准。

### 11.7 上层产品不可破坏的 Fibra 契约

- 产品插件只通过 Fibra RuntimeDomain 的 Service、Event、Effect 和 contribution 参与运行；产品宿主只经
  Registry、`PublishedRuntime` 和 CLI API 使用能力，不能取得内部 `Context`。
- Model、Agent、Session 等 API 是上层产品发布的场景契约，不进入 Fibra。动态 provider 的 contract
  ClassLoader 所有权必须唯一；宿主需要识别的 DTO 由产品宿主父加载器提供，插件以 `provided` 依赖且不
  打包副本。
- Node 产品插件只发布 `fibra-runtime-node` 能解析的宿主可见 contribution；不能假设 JSON-RPC sidecar
  自动参与任意 Java Service 依赖图。新增跨进程 service 协议必须有独立契约、取消、排空和版本门禁。
- 产品 bootstrap 命令在构建时静态存在，业务 CLI 命令由运行时 command contribution 插件发布；CLI
  从捕获的 descriptor、贡献身份和 revision 构造 Fibra 不可变命令代。Picocli `CommandSpec` 只是受限的
  可变解析对象，插件不能直接取得或修改它，也不能修改 Spring ApplicationContext。
- `SessionStore`、模型记忆、CLI history、`ConfigStore` 和 `DeploymentTargetStore` 各自只有一个事实来源；
  不能共享格式、revision、恢复入口或把一个存储包装成另一个。
- 直接工具、Agent 工具和 MCP 工具调用传递同一类 cancellation、deadline、调用 Scope 和稳定失败码；
  本地取消不能被描述为远端已经停止，审批不能被描述为 OS 沙箱。
- 插件显式升级、停用或候选目录变化继续遵守 Fibra 差量更新不变量：无关实例、ClassLoader、Node PID、
  effects 和在途调用保持；保存目标不因目录变化静默改变。
- CLI 与 Desktop 只是一套产品底层上的两个入口：它们必须共享同一个 Fibra Engine、Registry、持久目标、
  `PublishedRuntime`、SessionStore 和诊断投影，不能按“前端/后端”建立两套插件管理或配置保存。
- 桌面 bootstrap 只拥有进程、窗口、ready 等待和关闭协调；client runtime 只执行同一 Engine 下发的不可变
  目标和生命周期指令，二者都不能建立插件版本选择、desired state 或 Session 事实的第二来源。
- DSH 的 `jobs-local` 是内存实现，OTel 基线只核实日志导出，UI slot 属客户端组合；上层项目文档不得把
  这些边界扩大为重启续跑、全栈 telemetry 或 core UI 能力。

### 11.8 提交、验证与文档留痕

Fibra 的 F1 至 F4 只维护本文和既有行为验收账本，没有新建平行 spec/plan。各阶段当时采用包含契约、实现、
测试、发行适配与证据回填的独立提交。2026-09-13 的 F4 证据覆盖当时 27 个正式制品、50 模块 reactor、发行
ZIP、五类仓外消费者、真实 PTY、archetype 和三轮可复现门禁；这些数字与结论只保留为历史记录，不代表
2026-09-17 Client Foundation 硬切后的发布集合或完成状态。当前 28 个 Maven 制品、两个 npm tarball、独立
消费者与全量门禁以第 10 节和[发布与构建基线](../../release.md)为准，必须在最终工作树重新取证。

上层项目建立后拥有自己的权威架构文档和行为验收账本，不把产品实现证据回填成 Fibra 已实现能力。
每个 P 阶段同样要求实现与测试同提交、真实 provider 验收、仓库外 ZIP 验收和独立审核；发布里程碑复用
已有 `~/.m2` 解析 Fibra 正式发布物，再在仓外目录单独构建上层项目，并核对解析制品与临时发布目标字节，
以证明两仓边界真实成立。

Fibra F1–F4 的历史阶段已完成；Fibra Client Foundation P0 当前正在执行，进度与完成条件以
[实施计划](../plans/2026-09-15-fibra-client-foundation-p0.md)为准，未通过其最终门禁与独立复审前不得沿用 F4
的完成结论。Model、Agent、Session、MCP 或其它 DSH 产品模块不回填到 Fibra 仓库。

### 11.9 vNext 收口与 `0.5.0-SNAPSHOT` 底座打磨

`codex/fibra-vnext` 完成交付文档收口且同一提交的 Linux CI 全绿后，以保留阶段提交历史的方式合并
`main`，不 squash；后续底座工作从更新后的 `main` 新建 `codex/0.5.0-hardening`。该分支不继续承载
Agent 产品业务工作。最终目标是在 `0.5.0-SNAPSHOT` 上完成下列六项底座打磨：以本权威架构和固定参考源码
为依据，保留长期 RuntimeDomain、唯一控制面、差量更新、四种关系分离与 Scope 所有权；必要时直接重构
API、协议和模块，不保留兼容层、不以局部补丁替代责任分离。所有变更以真实失败、引用路径、测量或仓外
消费者证据证明必要性，不因“最终架构”扩张到 Agent 产品、容器/远端实现或通用秘密模型。

打磨顺序由真实风险和测量结果驱动，不为候选能力预建抽象：

| 顺序 | 范围 | 主要内容 | 完成证据 |
|---|---|---|---|
| 1 | CI 与短超时诊断 | 在现有全量门禁外补齐短时卡死、关闭竞态和真实 TTY 失败的现场捕获；失败清理前保留进程树、JVM 线程栈、终端属性和有限输出尾部 | 故意挂起的有界 fixture 能稳定产出诊断并按截止退出；正常门禁无额外失败或遗留进程 |
| 2 | 生命周期、变更与恢复 | 对 Scope/Effect 晚到资源、注册与清理失败、重复关闭，以及 prepare、artifact/target save、reconcile、retire 的崩溃点做故障注入 | 不发布半成品、不提前释放资源；重启按已保存目标收敛，未确认写关闭 mutation gate，诊断指出失败阶段 |
| 3 | 调用准入与排空 | 压实 revision、registration identity、in-flight lease、invocation Scope、取消、调用中停用和 cleanup failure 的可观察边界 | stale、revoked、cancelled、cleanup-failed 可稳定区分；无关更新不终止已接受调用，受影响资源等待真实终态 |
| 4 | Java/Node 长稳与性能 | 重复安装、同版本重装、依赖闭包升级、sidecar 异常、半帧、心跳和进程树；测量 ClassLoader、线程、文件句柄、PID、堆、更新与调用延迟 | 长循环资源曲线不持续增长；受管范围最终静默；优化必须由基线和回归阈值证明，不削弱一致性保证 |
| 5 | 公共扩展面与契约套件 | 以 canonical manifest、最小 SPI、typed config、Contribution、稳定错误码和仓外 Java/Node 消费者形成第三方插件套件，不测试废弃版本兼容 | 独立插件可执行安装、配置、启停、升级、重装、调用中卸载和泄漏检查，并输出结构化报告；不取得 Engine 内部类型 |
| 6 | 安全与供应链边界 | 校验 digest、制品目录、依赖图、诊断脱敏和 trusted Java、受管 sidecar、容器/远端三档执行策略 | 篡改和越界制品 fail-closed；secret 不进入日志与诊断；ClassLoader 不被描述为安全沙箱 |

N1/N2/J1/J2/E1 与 V1 的实现、测试和历史证据已经提交；#33/#34/#36 暴露的启动结果投影竞态由确定性回归
覆盖，修复提交 `98ccd53` 的
[GitHub Actions #37](https://github.com/sunker0115/fibra/actions/runs/34920221889) 曾在同一 HEAD 通过当时的
短超时、全量构建、27 制品可复现比较和仓外分发消费者。该证据只说明 Client Foundation 硬切前的 Java/Node
底座里程碑，不证明当前 28+2 发布边界已完成。历史执行进度见
[Java/Node 与整体底座打磨计划](../plans/2026-09-14-java-node-stability-hardening.md)；当前进度、门禁与停止条件
以 2026-09-15 Client Foundation 规格和实施计划为准。Windows 仍保持未实测声明，产品业务路线仍在独立
产品文档中推进。

底座可保留一个仓库外的薄上层夹具，验证真实调用方不绕过 `PublishedRuntime`、不取得内部 `Context`；
该夹具不是 Agent 产品实现。产品业务 P1–P8 仍在独立项目和独立权威文档中推进。
