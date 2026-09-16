# Fibra Client Foundation 权威架构与 P0 验证设计

状态：2026-09-17 架构复审后重新打开，旧“已冻结、无需修改”结论撤回。本文是 Fibra 逻辑插件、
跨执行域 SPI 与 Client Foundation P0 的当前权威设计；只有第 12 节的重新冻结门全部通过后，状态才可改为
“已冻结”。实施期间不保留旧新兼容层、双格式、双状态源或按开关选择的两套 runtime。

本文同时纠正 2026-09-15 版本中的两个方向性错误：

1. Fibra 不实现或发布浏览器 runner、Web loader、renderer adapter、transport/carrier；它们属于上层产品。
2. Fibra 不分别注册 `ArtifactRuntime` 与 `ExecutionRuntime`；一个 `RuntimeId` 只有一个完整
   `RuntimeDriver` owner。

## 1. 最终目标与边界

Fibra 是通用插件执行内核，不是 Desktop/Web 产品框架。它负责：

- 唯一的逻辑 `PluginPackage`、package selection、desired target 和 Registry 控制面；
- Java、Node 以及上层提供的其它 runtime 使用的统一 `RuntimeDriver` SPI；
- package/facet 依赖解析、完整目标保存、全局执行 DAG 和统一生命周期编排；
- contribution 注册、调用准入、在途排空及远端调用所需的通用 codec registry；
- 外部 execution 所需的严格值对象、身份围栏和 conformance fixtures；
- Java 与 Node 的第一方 runtime 实现。

Fibra 不负责：

- Electron、Tauri、浏览器标签页或原生窗口启动；
- Host 侧产品 client adapter、session registry、HTTP/WebSocket/IPC/MessagePort carrier；
- 浏览器 runner、ESM loader、resource cache、React/Vue/Svelte/DOM renderer；
- 产品路由、布局、主题、Agent、Session、Model、MCP、审批及长会话数据面；
- 非可信 JavaScript 的安全沙箱。

产品可以实现并注册 `RuntimeId("client")` 或其它自有 runtime。该实现必须消费 Fibra SPI、唯一 target、
统一 contribution gateway 和生命周期编排，不能建立第二 Registry、第二 desired state 或按名称重试调用。

“核心只提供 SPI”不等于 Fibra 不验证外部 execution。Fibra 维护不发布的 conformance fixture，证明公开 SPI
可以完成 prepare、activate、调用、drain、stop 和 stale fence 拒绝；真实 Chromium/Electron/React 与产品
transport 的证据由产品仓库提供，不能由 Fibra fixture 冒充。

## 2. 开源参照与本项目取舍

| 参照 | 借鉴 | 不照搬 | Fibra 结论 |
|---|---|---|---|
| OSGi Bundle Wiring | content revision、current wiring、仍被引用的旧 wiring 与依赖闭包 refresh 分离 | OSGi resolver、service registry 和完整 bundle 状态机 | 明确 candidate/current/retiring generation；旧 generation 在 execution/invocation lease 清零前不能 retire |
| VS Code extension host | extension host 类型、running location 与具体 host manager 分离 | IDE 协议、URI、Web extension packaging | `RuntimeId` 是唯一 owner；`ExecutionTarget` 只是 placement/capability，不能当注册键 |
| Wasmtime Module/Instance | 编译产物与实例化/执行分阶段 | Wasm runtime、序列化格式 | runtime candidate 可在保存前准备和校验，但任何插件实例/sidecar/external session 只能在保存后启动 |
| DSH/Cordis | Scope/effect 所有权、Host/client 两侧生命周期、renderer 后置装配 | 把 Cordis/React runner 下沉 Fibra | 浏览器 runner 和 renderer 保持产品侧，Fibra 只冻结通用 SPI 与围栏 |
| Codex app-server | 核心拥有稳定协议类型，transport 可替换 | Agent/Session 业务协议 | Fibra 可发布 client protocol/API，但不实现产品 transport |

参照项目只用于验证分层和状态机，不替 Fibra 证明自己的组合可执行性。重新冻结前必须使用本项目真实
Java/Node runtime 和外部 execution fixture 跑通 walking skeleton。

## 3. 最终模块与依赖方向

```text
fibra-api/                       Java 插件 API 与只读 ScopeView
fibra-core/                      Context、Scope、RuntimeDomain 等进程内内核
fibra-artifact/                  PluginPackage、facet、不可变 package store
fibra-bridge/                    contribution directory 与 kind/codec registry；不依赖 Engine
fibra-engine/                    target、compiler、RuntimeDriver SPI、generation coordinator、remote invoker
fibra-registry/                  package/desired 管理用例与审计
fibra-runtime-java/              Java 制品、ClassLoader、definition、Host RuntimeDomain 执行
fibra-runtime-node/              Node 制品、sidecar、contribution 与执行生命周期
fibra-client-protocol/           Java 外部 execution wire 值对象与严格 codec；无 transport
client/
  packages/client-api/           纯 TypeScript SPI/type；无 runner
  packages/client-protocol/      纯 TypeScript wire type/codec；无 transport
verification/client/             private/non-published conformance fixture
```

正式发布集合不包含：

- `fibra-runtime-host`；Host 是 execution target，不是 runtime 类型；
- `fibra-runtime-client`；具体 client runtime 由产品提供；
- `client-runtime`、`client-runtime-web`、`client-react`；
- 浏览器 loader、resource cache、transport 或 renderer。

依赖方向固定为：

```text
api/core/artifact/bridge
          ↑
       engine
       ↑   ↑
runtime-java  runtime-node

product-client-runtime ──→ engine + bridge + client-protocol
```

`fibra-engine` 不依赖 runtime 实现；Java、Node 和产品 runtime 不互相依赖。composition root 只注册
`RuntimeProvider`。Maven/架构测试必须同时证明禁止箭头和合法箭头上所需数据确实可传递，不能只做负向依赖
检查。

## 4. 逻辑 package、facet 与持久目标

用户安装单位是不可变 `PluginPackage`：

```text
PluginPackage
├── pluginId
├── version
├── packageDigest
└── facets[]
    ├── facetId
    ├── runtimeId
    ├── executionTarget
    ├── payloadDigest
    ├── dependencies[]
    └── requiredCapabilities[]
```

身份含义固定为：

| 身份 | 含义 |
|---|---|
| `PluginId` | 用户可见逻辑插件和统一管理边界 |
| `FacetId` | package 内稳定、唯一的 facet 身份 |
| `RuntimeId` | payload 解释器和完整生命周期 owner；也是 runtime 注册键 |
| `ExecutionTarget` | placement/capability，例如 `host`、`client:web`；允许多个 runtime 共用 |
| `ArtifactId` | 一个 package revision 内 facet payload 的内容身份 |

`fibra-package.yaml` 是 plugin/facet/dependency 的唯一清单。runtime-local descriptor 只描述入口和 runtime
私有元数据，不再次声明逻辑身份或求解版本。旧 `plugin.properties` 单 artifact 格式直接拒绝，不保留读取器。

`PluginPackageStore` 先以 content-addressed 原子事务发布不可变 package，再由目标选择引用它。发布 package
不等于启用；publish 后、target save 前崩溃只留下未选择 orphan，不能删除可能被其它 target 或 generation
引用的内容。

唯一持久目标为：

```text
DeploymentTarget
├── targetRevision
├── targetDigest
├── packageSelections
├── desiredGraph
└── configContext
```

任何属于用户/宿主持久 desired、并会改变 enabled、config、realm 或 intercept 的输入都必须进入同一个
`DeploymentTarget`，并参与 canonical `targetDigest`。删除 `ReplaceConfigContext` 这种改变执行但不保存目标
的旁路。相同内容提交是 no-op，不分配 revision；A→B→A 的第二个 A digest 可相同，但 revision 必须递增。
`targetDigest` 只标识持久 desired 内容，不承诺在不同 Host/runtime 版本上产生相同物理计划。每次编译另产出
进程内编译身份 `compiledFingerprint`，覆盖 targetDigest、runtime contract identities 和参与编译的 Host
capability snapshot；它参与 no-op/失效判断，但不是第二个持久 desired identity，也不作为保存 CAS。Host capability snapshot 必须是
不可变、可 canonical 编码的编译输入；能力变化若可能改变 placement、unit 或依赖计划，必须触发重新编译，
不能只对旧 unit reconcile。

`ConfigContextSnapshot` 是不可变 `LiteralValue.ObjectValue`，只允许规范 wire 值；codec 对 key 顺序、数字、
Unicode 和缺失/显式 null 使用与 target digest 相同的 canonical 规则。raw desired graph 与 snapshot 一起持久化，
evaluated graph 只在编译结果中存在。evaluator、binder 与 driver 只能读取 snapshot、package metadata 和显式
runtime/capability inputs，不得读取环境变量、系统属性、当前时间、随机数或实时配置；宿主要使用这些值时，
必须先捕获进 snapshot 并提交新 target。

`PluginDefinitionRef` 固定为 `pluginId + facetId + definitionId`。definitionId 只需在一个 facet 内唯一；
Engine 在保存前拒绝重复、缺失、未启用引用、依赖环和 package gate 绕过。

内建 definitions 也必须属于显式 `BuiltInPluginPackage`。其 package revision 与实际发布二进制/声明绑定；
Host 升级后旧 revision 不可用时必须明确启动失败，不能以同一 revision 静默运行新代码。

## 5. 单一 RuntimeDriver 模型

删除公开的双模型：

- `ArtifactRuntime`；
- `ExecutionRuntime`；
- `PreparedArtifact`；
- `PreparedArtifactUpdate`；
- `ExecutionTargetPlan`；
- `ExecutionUpdate`/`ExecutionHandle`。

最终 SPI 只有一个 owner。下列是签名级语义，具体 Java 类型可以使用等价 record/接口，但不得减少信息：

```text
RuntimeProvider
  id() -> RuntimeId
  create(RuntimeHostServices) -> RuntimeDriver

RuntimeDriver
  id() -> RuntimeId
  probe(PluginFacetSource) -> Mono<RuntimeArtifactInspection>
  inspect(ManagedFacet) -> Mono<RuntimeArtifactInspection>
  createCandidate(RuntimeTargetSlice) -> RuntimeCandidate
  snapshot() -> RuntimeDriverSnapshot
  closeAsync() -> Mono<Void>

RuntimeCandidate
  prepareAsync() -> Mono<Void>
  preparedPlan() -> RuntimePlan
  seal(CompiledRuntimeSlice) -> PreparedRuntimeGeneration
  closeAsync() -> Mono<Void>

PreparedRuntimeGeneration
  units() -> Map<ExecutionUnitKey, RuntimeUnitGeneration>
  abortAsync() -> Mono<Void>
  retireAsync() -> Mono<Void>

RuntimeUnitGeneration
  plan() -> ExecutionUnitPlan
  reconcileAsync(lifecycleOperationId) -> Mono<ExecutionObservation>
  closeAdmission() -> void
  drainAsync(lifecycleOperationId, deadline) -> Mono<ExecutionObservation>
  stopAsync(lifecycleOperationId, deadline) -> Mono<ExecutionObservation>
  snapshot() -> ExecutionObservation

RuntimeHostServices
  requestReconcile(runtimeId, unitKeys, reason) -> void
  requestRecompile(reason) -> void

HostTerminationPort
  requestTermination(HostTerminationRequest) -> void
```

契约约束：

- `createCandidate` 只能创建 inert bookkeeping，不得 I/O 或取得尚未登记的外部资源。Engine 必须在同一
  command-lane 调用栈内把返回值加入 `DeploymentCandidate`，之后才允许订阅 `prepareAsync`；
- `prepareAsync` 可以读取 payload、创建 ClassLoader、执行受信 `definition()`、绑定并校验配置，但不得调用
  `Plugin.start()`、启动 Node sidecar 或连接外部 execution；
- `definition()` 是受信 Java package 的 metadata 构造契约，必须确定、无 I/O、无线程、无注册且不得取得
  `Context`/Scope/网络/进程能力；第一方 runtime 通过 contract tests 验证。Java 非可信代码不在安全模型内，
  因而这里保证的是受管生命周期零副作用，不宣称 JVM 能沙箱化恶意 definition；
- `preparedPlan()` 只暴露 Engine 编排需要的公共计划。每个 `ExecutionUnitPlan` 至少包含 unit key、
  runtimeId、executionTarget、artifact identity、跨 unit dependencies 和逻辑 provenance；每个
  `DefinitionBindingPlan` 至少包含完整 `PluginDefinitionRef`、desired entry id、所属 unit key 和
  publication requirement。Engine 用 definition/unit 双射校验引用唯一性、plan 覆盖和 publication 要求；
  bound config、factory、ClassLoader 等仍是 driver 私有状态；
- ClassLoader、Java definition、Node descriptor、client URL/session 等私有对象永不离开对应 driver；
- `seal` 在保存前执行，必须同步且无 I/O；它把 candidate 私有资源一次性转移给完全 inert 的
  `PreparedRuntimeGeneration`。`abortAsync()` 只允许用于尚未 promote 的 sealed generation：它先同步封闭
  所有 unit 准入，再释放这些从未启动的 unit leases 和全部私有资源。任一 seal 失败或 target save 明确失败时，
  Engine 对已 sealed generations 调用 abort，对未 sealed candidates 调用 close；两者都必须幂等并聚合失败；
- Engine 在保存前把所有 runtime prepared generations 与 retained units 聚合成一个 `DeploymentCandidate`。
  保存确认后只由 Engine 串行 lane 原子移动这个聚合对象的角色，不再逐 driver 调用 commit，因此不存在
  multi-driver partial commit；
- unit generation 在产生任何执行副作用前已经持有其全部 runtime-private artifact/resource leases；
- `closeAdmission` 同步、幂等、不可失败；drain/stop/retire 的终态可重复观察；
- 一个 `RuntimeId` 只能注册一个 provider，重复注册直接失败；缺少目标所需 runtime 时在保存前拒绝。

`RuntimeCandidate.closeAsync()` 在 seal 前释放候选；seal 后 candidate 已失去所有权，重复关闭只等待同一空终态。
sealed generation 在 promote 前只由 `DeploymentCandidate` 持有，不把 unit 暴露给 current/retiring；因此
`abortAsync()` 可以原子释放全部 unit leases。promote 后禁止 abort，只能按 drain/stop/retire 路径回收。
每个 `RuntimeUnitGeneration` 持有其 `PreparedRuntimeGeneration` 的私有 lease。差量更新可以保留某些旧 unit，
因此一个 prepared generation 可以跨多个 deployment attempts 存活；它不因此成为第二 current attempt。
`retireAsync()` 只有在最后一个 unit lease 释放且 driver-private invocation/resource leases 清零后才能释放物理
资源。任何 unchecked contract violation 都使 Engine 进入 fail-stop：关闭全局准入、保留真实资源现场并
受控重启；不得尝试继续下一次变更。

`fibra-runtime-java` 同时拥有 Java facet 探测、class space、ClassLoader、definition 物化、配置绑定、内建
definitions 和 Host `RuntimeDomain` 执行。`fibra-runtime-node` 同时拥有 Node payload 与 sidecar。不存在
跨 runtime 读取 `JavaPreparedArtifact`，也不存在 `fibra-runtime-host`。

## 6. 编译结果与全局 DAG

Engine 的唯一非持久编译结果为 `CompiledDeployment`：

```text
CompiledDeployment
├── DeploymentTarget
├── compiledFingerprint
├── resolved facet graph
├── evaluated desired graph
├── dependency-first order
├── reverse dependency order
├── affected closure
├── retained unit plans
└── RuntimePlan[]
```

编译分为三层：

1. Engine 解析 package selections、facet references 和全局 facet DAG；
2. Engine 根据当前 attempt 与新 target 计算 affected closure；未受影响 unit 连同其原
   `RuntimeUnitGeneration` 原样保留，只有受影响闭包交给各 driver 准备 candidate；
3. 各 driver 返回受影响 units 的公共 plan 与 definition bindings；
4. Engine 把 retained plans 与 candidate plans 合并，校验 definition/unit 双射、plan 覆盖、全局 DAG、
   publication requirement 和全部依赖，形成 `CompiledDeployment`；
5. Engine 把每个 driver 的 `CompiledRuntimeSlice` 交回 candidate seal，得到 inert prepared generations。

runtime 分区不能丢掉全局顺序。activate 按 dependency-first，close admission/drain/stop 按 reverse order。
跨 runtime dependency 未 ACTIVE 时，其 dependents 保持 PENDING；独立子图可以继续并发布精确 observed。

差量更新的所有权单位是 `RuntimeUnitGeneration`，不是整份 deployment。新 attempt 直接保留未受影响 unit
对象；retirement batch 只包含被替换的旧 units。driver 的 prepared generation 可以拥有多个 units 和共享
私有资源；被替换 unit stop 后只释放自己的 lease，仍被 retained unit 引用的 prepared generation 继续存活，
最后一个 lease 清零后才 retire。这样无关 ClassLoader、Node PID、外部 assignment 和 contribution
registration 保持原身份，不需要 adopt/复制整代。

`RuntimeHostServices.requestReconcile(runtimeId, unitKeys, reason)` 只用于不改变计划的可用性变化，例如外部
execution 上线或可重试故障消失。Engine 把它序列化进唯一 command lane，读取当前 unit snapshots，并按全局
DAG 重试对应 units 及其 dependent closure。driver 不得自行启动其它 runtime 的 dependent，也不得直接修改
desired/observed 聚合。

runtime contract 或 capability 变化只要可能改变 placement、unit、binding 或依赖计划，就必须调用
`requestRecompile(RecompileReason)`；reason 可记录来源 RuntimeId，但不能缩小安全范围。Engine 对当前 durable
target 重新捕获全部编译输入并比较 `compiledFingerprint`；变化时保守地把全部 current units 作为 affected
closure，按 `ReconcileCurrent` 的 replacement 流程全量重编译，使用同一 `DurableTargetToken` 且不重写 target。
P0 不实现 per-runtime 影响映射，避免聚合 fingerprint 更新后漏掉其它 runtime。相同 targetDigest 的 no-op 只有
在 fingerprint 也相同且 current 已满足时成立；否则必须重编译。

## 7. 持久目标、运行 attempt 与唯一状态机

持久 desired 与进程内执行 attempt 是两类事实，不能再用一个 CURRENT 混写：

```text
DurableTargetState = ABSENT | PRESENT | UNCERTAIN
AttemptRole        = CANDIDATE | CURRENT | RETIRING | RETIRED
AttemptPhase       = REGISTERED | PREPARING | VALIDATING | READY_TO_SAVE |
                     SAVING | PROMOTING | RECONCILING | DRAINING |
                     STOPPING | RELEASING | SETTLED | FAILED
```

`PRESENT` 表示 `DeploymentTargetStore` 已证明的完整 target；它可以暂时没有可运行 attempt，例如 Host 启动
prepare 失败。`CURRENT` 只表示当前进程为该 durable target 选择的 unit generation map，不表示全部 ACTIVE。
P0 同时最多一个 candidate attempt、一个 current attempt 和一个 retirement batch。retirement 清理失败时
mutation gate 关闭，不堆叠更多替换。

新 target 的唯一生产路径为：

1. 先做同 digest + compiledFingerprint no-op 判断；两者相同且 current 已满足时直接返回；
2. 创建 `CANDIDATE:REGISTERED` 和全部 runtime candidates，再执行 `PREPARING`；
3. materialize definition、绑定配置、构造并验证 `CompiledDeployment`；
4. seal 所有 candidates，聚合新 units、retained units 和全部资源 lease，进入 `READY_TO_SAVE`；
5. `DeploymentTargetStore.save(expectedRevision, candidateTarget)` 原子 CAS，并返回不可伪造的
   `DurableTargetToken`；
6. Engine 串行 lane 只移动内存所有权：以 token 将整个 `DeploymentCandidate` 原子提升为 current attempt，
   未受影响 units 原样保留，被替换旧 units 进入 retirement batch；此步不调用 driver、不执行 I/O；
7. 同步关闭 retirement batch 中全部旧 contribution 的新准入；
8. 按反依赖顺序等待旧 route/invocation/resource-read drain，再 stop 旧 units；
9. 按依赖顺序对新 units 调用 `reconcileAsync`；外部 execution 不在线时快速得到 PENDING，不能阻塞 Host
   ready，其 dependents 也保持 PENDING；
10. 旧 unit leases 清零后 retire 对应 prepared generations；retirement batch 清空后 Engine 回到稳定态。

保存后的 activate 失败不回滚 target、不恢复旧准入，只在 durable target 下发布 FAILED/PENDING observed。
当外部 execution 上线时，driver 经 `requestReconcile` 唤醒 Engine；Engine 对同一 unit generation 分配新的
operationId 并继续 DAG 收敛。执行过插件代码后进入 FAILED 的 unit 不得原地复用；显式
`ReconcileCurrent` 或 plan-affecting `requestRecompile` 先为失败/受影响 unit 及 dependent closure 准备新的
candidate units，再使用当前
`DurableTargetToken` 走同一 promote → close admission → drain → stop → reconcile → retire 流程，但跳过
target save。新 units 必须获得新的 runtimeInstanceId，旧 attempt 清理失败时不允许并存第二个 current。

生命周期 lease 链固定为：

```text
route invocation/resource read
        ↓
RuntimeUnitGeneration
        ↓
driver-private artifact/resource generation
```

Engine 不通过 `Set<ArtifactId>` 或一次 `handles()` 快照猜测所有权。旧 unit 尚有 execution、调用或资源读取
时，driver 不得关闭其 ClassLoader、payload 或其它物理资源。drain deadline 到期只表示关闭失败：本地可安全
强制终止的进程可以被 driver 终止，但不可强杀的 in-process invocation 必须继续持有 lease；不得并发 stop
插件代码或 retire 资源。失败 unit 标为 FAILED/ORPHANED、mutation gate 关闭，并保留现场等待受控 Host 退出。

## 8. 失败、保存边界与重启恢复

| 失败点 | 持久 target | 处理 |
|---|---|---|
| prepare/validate/plan | 不变 | 逆序关闭 candidate；清理成功后可继续，失败则关闭 mutation gate |
| seal/save 明确失败 | 不变 | 不 promote、不启动；abort 已 sealed generations，关闭未 sealed candidates；任一 abort/close 失败立即 fail-stop、保留现场并请求 Host termination |
| save-unconfirmed | `UNCERTAIN` | 立即关闭全部 managed contribution 新准入和 mutation gate，保留 current/candidate 资源到有界关闭，发布 `TARGET_SAVE_UNCERTAIN`，进入受控 Host 退出；本进程不得继续服务或猜测磁盘事实 |
| save 后 activate 失败 | 新 target | 旧准入不恢复；继续清理 retirement batch 并在 durable target 下发布 FAILED/PENDING |
| drain/stop/retire 失败 | 新 target | 保留 retirement batch、lease 与失败事实；关闭 mutation gate并进入有界 Host 关闭 |

Engine 不直接调用 `System.exit`。composition root 必须提供 `HostTerminationPort`；Engine 在封闭 mutation gate、
全部 managed contribution 准入并发布 fatal observed 后，以一次性
`HostTerminationRequest(hostInstanceId, reason, phase, targetRevision?)` 请求宿主退出。Engine 先在 command lane
记录一次性逻辑 request，再由独立 notification lane 调用端口，绝不内联执行宿主回调。端口本身也必须快速、
非阻塞地把请求转交宿主自有 executor/event loop 后返回；禁止同步关闭 Host、调用任何 Engine API 或等待 Engine
termination。端口抛错、阻塞超时或重复通知都不能阻塞 command lane、重开准入或产生第二逻辑 request；Engine
保持 fail-stop。verification Host、CLI、Spring 和产品 Host 分别在 lane 外消费该请求并协调自己拥有的 HTTP、
Session、进程与 application context 关闭。

重启只恢复持久 `DeploymentTarget`，不恢复 candidate、retiring、observed、runtimeInstanceId 或 operation
ledger。启动流程为：

```text
new hostInstanceId
→ load target
→ receive DurableTargetToken
→ verify selected packages/built-ins
→ create candidates
→ prepare/validate
→ seal and promote in memory without re-saving
→ reconcile with new runtimeInstanceId/operationId
```

crash 在 save 前恢复旧 target；原子 save 后任何点 crash 都恢复新 target。旧 session、ack、call result 和
resource request 因新 `hostInstanceId` 与新 runtimeInstanceId 自动失效。持久 target 合法但 package 缺失或
prepare 失败时仍是 `DurableTargetState.PRESENT`，但没有 current attempt；target observed 为 FAILED，不伪造
一个不存在的 CURRENT generation，也不篡改 target。控制面仍可提交一个新的完整 replacement target。

## 9. Contribution gateway 与外部 execution SPI

`fibra-bridge` 提供唯一不可变 `ContributionKindRegistry`：

- kind name 全局唯一，重名直接失败；
- resolver 返回注册 contribution 时使用的同一 `ContributionKind` 实例；
- 只有显式提供 `LiteralValue` codec 的 remote-capable kind 能出现在外部协议；
- Node 与产品 client runtime 共用同一 registry，不再维护私有 resolver。

`fibra-engine` 中唯一的 `RemoteContributionInvoker` 组合 bridge registry 与 `PublishedRuntime`，接收：

```text
kind name + ContributionId + expectedViewRevision + registrationIdentity + LiteralValue input
```

它解析同一 kind/codec 后调用 `PublishedRuntime.invoke`，并把结果编码为 `LiteralValue`。未知 kind、旧 view、
旧 registration、已关闭 admission 和 codec 错误都返回稳定失败；不得按名字落到新 handler，也不得自动重放
可能有副作用的调用。

Fibra 的 client protocol 只冻结 transport-neutral 值对象、严格 codec 和以下身份：

| 身份 | 作用 |
|---|---|
| `hostInstanceId` | 隔离 Host 重启前后的全部外部事实 |
| `targetRevision` | 持久 desired 代次 |
| `unitTargetRevision` | 创建该 unit generation 的 target revision；retained unit 保持原值 |
| `clientExecutionId` | 产品 runtime 的一次外部连接 |
| `runtimeInstanceId` | 一个 execution unit 的本次实例 |
| `lifecycleOperationId` | 一次生命周期操作 |
| `viewRevision + registrationIdentity` | contribution 调用准入 |

具体产品 runtime 负责 execution session、assignment、authorized resource gateway、transport 和 carrier。
资源授权围栏固定为 `hostInstanceId + clientExecutionId + unitTargetRevision + runtimeInstanceId`，并校验精确
`ResourceDescriptor(path,digest,byteLength)`；生命周期回复再附 `lifecycleOperationId`。这里的 execution
session 不是产品 Agent/业务 Session。读取期间必须持有对应 unit/resource generation lease。URL、端口、
签名、cookie、origin 不进入 DeploymentTarget、digest 或 Fibra snapshot contract。

`unitTargetRevision` 不是当前全局 target revision 的别名。Engine 只接受仍属于 current 或 retirement batch 的
精确 unit tuple；未受影响 unit 在 target 10→11 时保留原 runtimeInstanceId、assignment 与
`unitTargetRevision=10`，但它的 route 是否仍可准入由当前 PublishedView 决定。新建或替换 unit 使用 11。
因此 retained unit 不被全局 revision 误杀，也不能凭旧 revision 访问已退出的 unit。

Fibra 正式 npm 包只保留纯 `client-api` 与 `client-protocol`。任何 lifecycle actor、runner、Web loader、
React adapter 或真实 transport 都不得进入这些包。

## 10. Composition root 与关闭所有权

composition root 注册 provider，不传入已经启动的 runtime：

```text
FibraEngine.builder(...)
  .contributionKinds(kinds)
  .hostTerminationPort(hostTerminationPort)
  .runtimeProvider(javaProvider)
  .runtimeProvider(nodeProvider)
  // 仅产品宿主注册 productClientProvider
  .build()
```

Engine 创建共享 `RuntimeDomain`、`ContributionDirectory`、`ContributionKindRegistry` 和
`RemoteContributionInvoker` 后，再以只读 `RuntimeHostServices` 创建 drivers。services 只暴露受限
registrar/invoker/id factory 与 `ScopeView`；不得暴露可关闭根 Scope、目录或 domain。

Java 插件 `Context.scope()` 返回实际不实现 `Scope/AutoCloseable` 的 `ScopeView`。插件只能关闭自己
`openChild()` 获得的 child scope；runtime generation 独占 instance root 关闭权，避免插件等待销毁正在启动的
自己。

关闭顺序固定为：

```text
关闭 mutation gate
→ generations 封准入、drain、stop、retire
→ runtime drivers 逆序关闭
→ contribution directory
→ runtime domain
→ FibraRuntime
→ stores
```

构造中途失败也必须按已经取得所有权的逆序关闭，不能泄漏共享 domain 或 driver。

## 11. Registry 语义

package 与 instance 管理必须分开：

- package：install、upgrade、enable、disable、uninstall；
- desired entry：upsert、enable、disable、move、remove；
- 插件自停用只作用于当前 desired entry，不得隐式关闭整个 package；
- package gate 关闭时，其全部 facets 和 entries 一起撤销；
- 所有操作最终都生成一个完整 `DeploymentTarget`，不存在裸 definition 或 runtime-only 旁路。

Registry 只投影 package、durable target、current attempt 和 observed，不建立第二状态机。CLI、Spring、UI 管理
页面只能调用相同用例。

## 12. P0 重新冻结门

### P0-A：compile-only 可组合性

使用真实 Java driver、Node driver 和测试域 external runtime fixture，证明：

- Java 与 Node 同为 `ExecutionTarget("host")` 时仍按不同 RuntimeId 正确注册；
- Java 私有 ClassLoader/definition 不离开 Java driver；
- compile-only 阶段不启动插件、sidecar、session 或网络；
- 全局 DAG 经过 runtime 分区后仍完整；
- 缺少 runtime provider 在保存前拒绝；外部 execution 仅离线则保存后 observed=PENDING；
- Engine 与正式发布物不依赖 DOM、React、Playwright、浏览器 URL/Fetch API 或产品 transport；Java runtime
  为 ClassLoader 使用 `java.net.URL` 不属于浏览器边界泄漏。

### P0-B：真实 Engine 风险门

使用真实文件 package/state store、动态 Java JAR、Node sidecar 与 external runtime fixture 验证：

- package publish 与 target activation 是两个 commit point；
- no-op、A→B→A、完整 configContext digest/revision；
- prepare/validate/save/promote/reconcile/drain/stop/retire 全路径；
- driver A seal 后 driver B seal 失败、以及全部 seal 后 save 明确失败时，已 sealed generations 全部 abort；
- abort/close 失败立即 fail-stop、保留现场并请求 Host termination；
- 保存前失败零持久变更，保存后失败保留新 target；
- plan-affecting capability/runtime contract 变化重编译全部 current units；纯可用性变化只 reconcile；
- dependency-first activate 与 reverse stop；
- old route/resource lease 清零前制品不关闭；
- retained external unit 跨 global target revision 后，其 `unitTargetRevision` tuple 仍可精确访问；unit 替换后旧
  tuple 立即拒绝；
- remote call 只经同一 `RemoteContributionInvoker`；
- 离线 external execution 不阻塞 Host ready。

### P0-C：真实重启恢复

同一 package/state store 启动第二个 Host，证明：

- targetDigest/revision 保持，host/runtime/operation/registration 身份重新分配；
- Java ClassLoader 与 Node sidecar 从持久 target 重建；
- 旧 session、ack、call result、resource request 全部拒绝；
- 保存后执行失败的 current target 会重新尝试收敛；
- package 缺失/损坏时失败可观察且 target 不被篡改；
- save-unconfirmed 只由 restart/load 消歧。
- `HostTerminationPort` 在 fatal observed 发布后只通知一次；回调抛错、超时或尝试重入均不重开 gate，真实
  Host 关闭在 Engine lane 外完成。

### P0-D：调用方、发行与独立消费

- Registry、CLI、Spring、examples、parity、archetype、plugins 和 distribution 全部硬切；
- 根 reactor、API baseline、可复现发行、空 Maven 仓 Java RuntimeProvider 消费者和 npm tarball 消费者
  全部通过；
- npm 正式包只有纯 API/protocol，发行物不含 runner/Web/React/transport；
- Fibra conformance fixture 只证明外部 RuntimeProvider 契约，不冒充真实浏览器证据。产品仓以正式 Fibra
  发布物实现和验证真实 browser runtime，是产品 P1 前置门，不是 Fibra P0 完成门；
- 文档一致性与架构、代码、发行三类独立审查无未关闭 P0/P1/P2。

## 13. 可证伪与停止条件

出现任一情况必须停止实现并回到本架构：

- 需要 `HostPreparedArtifact`、metadata bag、运行时 cast 或 runtime 实现间依赖才能传递私有对象；
- Engine 同时保留 ArtifactRuntime/ExecutionRuntime 或旧 PluginRuntimeAdapter；
- `ExecutionTarget` 再次作为 runtime 注册键；
- configContext 改变执行却不改变完整 target digest/revision；
- 保存前启动插件实例、Node sidecar 或外部 session；
- 保存后失败通过回滚 target 或恢复旧 handler 冒充成功；
- execution/invocation/resource lease 未清零即关闭旧制品；
- 外部调用绕过 `PublishedRuntime`/`RemoteContributionInvoker`；
- Fibra 正式模块出现浏览器 runner、Web loader、React、transport 或产品 session 状态；
- 为迁移保留旧格式 reader、双写、双 Registry 或兼容 adapter；
- 重启恢复依赖旧进程对象、旧 PID 或手工修复持久文件；
- 局部反射/shape 测试被再次当作跨模块可执行证明；
- 同一根因连续两次验证失败且没有新的证据。

## 14. 既有成果处置

可以保留并重新验收：

- Task 6 的逻辑 package/facet、精确依赖和 package store；
- 严格 client protocol 的值编码、身份字段和错误结构；
- Java class space/index/descriptor 算法；
- Node descriptor/payload 校验及既有 sidecar 行为；
- `PublishedRuntime`、route lease、Engine 串行 command loop、文件 store 原子性；
- `ScopeView` 方向。

必须撤回或重写：

- Task 7 的双 runtime SPI 冻结；
- Task 8 的 `ArtifactResources + adopt` 所有权模型；
- `fibra-runtime-host`、`HostPreparedArtifact` 和当前 `NodeExecutionRuntime` 草稿；
- `fibra-runtime-client` 生产实现；
- Fibra 正式发布 `client-runtime`、Web loader、React adapter 与 transport 的路线；
- Task 9—13 的旧阶段和“无 P0/P1/P2”结论。

Git 历史保留原实现与审查记录，不在当前文档旁保留已废弃设计。实施计划必须按本架构重写，而不是追加一个
补丁任务。
