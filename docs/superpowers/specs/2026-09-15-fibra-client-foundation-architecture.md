# Fibra Client Foundation 权威架构与 P0 验证设计

状态：2026-09-17 架构复审后重新打开，旧“已冻结、无需修改”结论撤回。本文是 Fibra 逻辑插件、
跨执行域 SPI 与 Client Foundation P0 的当前权威设计；只有第 12 节的重新冻结门全部通过后，状态才可改为
“已冻结”。实施期间不保留旧新兼容层、双格式、双状态源或按开关选择的两套 runtime。

本文同时纠正 2026-09-15 版本中的四个方向性错误：

1. Fibra 不实现或发布浏览器 runner、Web loader、renderer adapter、transport/carrier；它们属于上层产品。
2. Fibra 不分别注册 `ArtifactRuntime` 与 `ExecutionRuntime`；一个 `RuntimeId` 只有一个完整
   `RuntimeDriver` owner。
3. `ExecutionUnit` 不是 facet/artifact 的别名；一个启用的 desired entry 对应一个可独立启停的 unit，多个
   entry 可以共享同一 facet 的 ClassLoader/payload generation lease。
4. definition 身份和内建 package 不能靠 artifactId、facetId 或匿名 catalog 隐式推导；runtime 必须显式产出
   definition metadata，内建实际对象仍由对应 driver 私有持有。

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
| Terraform Provider/Resource | provider 显式声明 resource type/schema，配置中的每个 resource block 是独立实例 | Terraform state、RPC 和 provider 生态 | facet 必须显式声明 definition；每个 desired entry 形成独立 execution unit，不能把 artifact 当实例 |
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
fibra-parity-tests/              跨 runtime、公开 SPI、宿主重启的整仓测试；不发布
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
私有元数据，不再次声明 plugin/facet 身份或求解版本，但必须显式产出该 facet 提供的 `definitionId`；不得以
artifactId、facetId、文件名或入口类名作为隐式 definitionId。Java 动态 facet 由受信 `definition().name()`
产出一个 definition；Node P0 descriptor 必填一个 `definitionId`；同一 definition 可被多个 desired entry
引用。旧 `plugin.properties` 单 artifact 格式直接拒绝，不保留读取器。

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
capability snapshot；它参与 no-op/失效判断，但不是第二个持久 desired identity，也不作为保存 CAS。Host
capability snapshot 必须是不可变、可 canonical 编码的编译输入；key 存在即表示 capability 可用，value 只是
不可变描述，即使值为 `false` 也仍表示“存在”，不可用必须删除 key。每个 active execution unit 的准入要求是
其 owner facet 加上传递静态 facet 依赖闭包中全部 `requiredCapabilities` 的并集；contract-only facet 不生成
伪 unit，但只要位于该闭包就会约束真实 consumer。能力变化若可能改变 placement、unit、binding 或依赖计划，
必须触发全量重新编译，不能只对旧 unit reconcile；失败的新 attempt 不得改写 current generation 冻结的能力
观察。

`ConfigContextSnapshot` 是不可变 `LiteralValue.ObjectValue`，只允许规范 wire 值；codec 对 key 顺序、数字、
Unicode 和缺失/显式 null 使用与 target digest 相同的 canonical 规则。raw desired graph 与 snapshot 一起持久化，
evaluated graph 只在编译结果中存在。evaluator、binder 与 driver 只能读取 snapshot、package metadata 和显式
runtime/capability inputs，不得读取环境变量、系统属性、当前时间、随机数或实时配置；宿主要使用这些值时，
必须先捕获进 snapshot 并提交新 target。

`PluginDefinitionRef` 固定为 `pluginId + facetId + definitionId`。definitionId 只需在一个 facet 内唯一。
`PluginSelection.enabled=false` 是持久 package gate：raw desired entry 保留在 `DeploymentTarget`，但该 package
的全部 facets/entries 不进入 active execution；重新启用后原 entries 可以恢复。Engine 在保存前拒绝重复引用、
缺少 selection、selection revision 与动态 package 或 built-in digest 不匹配，以及 active entry 的
package/facet/definition 不存在、依赖环或 package gate 绕过；disabled gate 下的 dormant entry 不触发 runtime
descriptor/definition prepare，重新启用时才严格校验。不得把 disabled selection 本身当作非法 target。

每个启用的 desired entry 对应一个 `ExecutionUnitKey`，P0 直接使用该 entry 的全局完整 id；
`DefinitionBindingPlan` 与 `ExecutionUnitPlan` 必须一一对应。一个 facet 被多个 entry 引用时生成多个 unit；这些
unit 可以共享 driver-private artifact/ClassLoader/payload generation，但各自拥有 config、instance root、
runtimeInstanceId、准入和生命周期。facet/artifact 依赖只负责静态资源闭包；需要进入执行 DAG 时，确定性展开为
被依赖 facet 的全部 active units，contract-only facet 没有 unit。

内建 definitions 也必须属于显式 package 级 `BuiltInPluginPackage`。Engine 中的该类型只包含
plugin/version/digest 与非空 `BuiltInFacet[]`；每个 facet 只含 facetId、runtimeId、executionTarget、精确 facet
dependencies、requiredCapabilities 和 definitionIds 纯 metadata。`RuntimeProvider` 暴露完整 package metadata，
并私有保存实际 `PluginDefinition`/factory。P0 一个 built-in package 的全部 facets 必须属于声明它的同一个
provider/runtime；不实现跨 provider package fragment 合并。内建 facet 参与与动态 facet 相同的 capability、
全局 DAG、definition/unit 校验和 runtime slice，不能由 Engine 匿名 catalog 或旁路挂载。每个 facet 的稳定
artifact identity 由 packageDigest+facetId 派生，不得把 package 当作单 facet。package revision 与实际发布
二进制/全部声明绑定；Host 升级后旧 revision 不可用时必须明确启动失败，不能以同一 revision 静默运行新代码。

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
  contractIdentity() -> String
  builtInPackages() -> List<BuiltInPluginPackage>
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
  hostInstanceId() -> String
  scope() -> ScopeView
  releaseScope(Scope) -> Mono<Void>
  contributionKinds() -> ContributionKindRegistry
  openContributionAdmission(ExecutionUnitKey) -> ContributionAdmission
  remoteContributions() -> RemoteContributionInvoker
  nextIdentity(namespace) -> String
  requestReconcile(Set<RuntimeUnitFence>, reason) -> void
  requestObservationRefresh(RuntimeUnitFence) -> void
  requestDisable(RuntimeUnitDisableRequest) -> void
  requestRecompile(RuntimeRecompileReason) -> void

HostTerminationPort
  requestTermination(HostTerminationRequest) -> void
```

契约约束：

- `RuntimeProvider` 是可复用、配置不可变的进程级 factory，不是 driver 或 Host 生命周期 owner。每次顺序调用
  `create(RuntimeHostServices)` 必须返回只绑定本次 Host 的全新、相互独立 driver；provider 不得缓存 services、
  driver 或任何 Host 可变资源。并发 `create` 不在 P0 保证内。Engine 独占每个 driver 的关闭权，创建失败后同一
  provider 仍可用于下一次 Host 构造；
- `createCandidate` 只能创建 inert bookkeeping，不得 I/O 或取得尚未登记的外部资源。Engine 必须在同一
  command-lane 调用栈内把返回值加入 `DeploymentCandidate`，之后才允许订阅 `prepareAsync`；
- `RuntimeTargetSlice` 必须携带该 runtime 的动态 facets、内建 package metadata 和本次 affected desired
  entry ids，以及 Engine 已从全局图展开的不可变 unit dependency map；driver 只能为这些 entry 建 unit，
  不得自行重复推断依赖，但可为共享资源准备其完整静态依赖闭包；
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
- `ExecutionUnitKey` 不得使用 artifactId。P0 使用全局 desired entry id，因此同一 artifact/facet 的两个 entry
  必须得到两个 unit，并可以独立 disable、replace、drain 和 stop；
- ClassLoader、Java definition、Node descriptor、client URL/session 等私有对象永不离开对应 driver；
- `seal` 在保存前执行，必须同步且无 I/O；它把 candidate 私有资源一次性转移给完全 inert 的
  `PreparedRuntimeGeneration`。`abortAsync()` 只允许用于尚未 promote 的 sealed generation：它先同步封闭
  所有 unit 准入，再释放这些从未启动的 unit leases 和全部私有资源。任一 seal 失败或 target save 明确失败时，
  Engine 对已 sealed generations 调用 abort，对未 sealed candidates 调用 close；两者都必须幂等并聚合失败；
- Engine 在保存前把所有 runtime prepared generations 与 retained units 聚合成一个 `DeploymentCandidate`。
  保存确认后只由 Engine 串行 lane 原子移动这个聚合对象的角色，不再逐 driver 调用 commit，因此不存在
  multi-driver partial commit；
- unit generation 在产生任何执行副作用前已经持有其全部 runtime-private artifact/resource leases；
- lease 成功释放后，仍可能被 generation、命令 lane 或诊断结构引用的已结束 unit 只能保留公共计划与终态
  metadata，必须断开 bound definition、ClassLoader、Node payload、resolved config 和私有异常图；释放失败则保留
  完整资源所有权与失败现场，不能先清引用再假装成功；
- `closeAdmission` 同步、幂等、不可失败；drain/stop/retire 的终态可重复观察；
- driver 创建的 unit child Scope 必须通过 `RuntimeHostServices.releaseScope` 关闭。Host 在普通
  `Scope.closeAsync()` 完成后继续核验该 Scope 及真实后代的资源清理失败；只有两者都成功，driver 才能释放
  ClassLoader、payload、sidecar 或 generation lease。清理失败必须保留真实 owner 并使 retirement fail-stop，
  不能因为 core 的普通关闭错误隔离语义而把残余资源误判为已释放；
- 每个 execution unit 必须从 Host services 取得独立 `ContributionAdmission`。目录在同一个锁域内登记 route 与
  admission owner；`closeAdmission` 必须同步撤销该 unit 的全部 route，并与并发/迟到 register 互斥，不能先改
  driver 私有布尔值、再在异步 drain 中逐条撤销。`drainAsync` 只等待已接受调用，不关闭插件、Scope、sidecar
  或 handler 私有资源；资源释放属于 stop/retire；
- 一个 `RuntimeId` 只能注册一个 provider，重复注册直接失败；缺少目标所需 runtime 时在保存前拒绝。
  `contractIdentity` 与 built-in metadata 一起进入 `compiledFingerprint`；相同 runtimeId 但契约身份变化必须
  触发全量 recompile，不能只 reconcile 旧 plan；
- `requestObservationRefresh` 只表示当前 unit 的运行事实可能改变。请求必须携带
  `RuntimeId + ExecutionUnitKey + unitTargetRevision + runtimeInstanceId` 的精确 `RuntimeUnitFence`；Engine 在
  唯一 command lane 校验该 fence 仍属于 current unit，之后只重新采样并发布 observed，不保存 target、
  不 replace/reconcile unit。一次 publish 对每个 unit 只调用一次 `snapshot()`，同一份冻结样本同时生成
  PublishedView 与 `targetSatisfied`，不得因二次采样发布互相矛盾的事实；
- Engine 启动期间的并发订阅共享同一次 bootstrap，并各自收到该次 bootstrap 的精确 `PublishedView`；长期
  启动协调只保留完成或失败事实，成功后改为读取当前视图，失败后只保留不含插件对象的失败投影，不得缓存
  第一份 view、插件 descriptor 或原始异常图；
- `requestDisable` 是持久 desired 命令，不是 availability wake-up。它只携带精确 `RuntimeUnitFence + reason`；
  Engine 校验当前代次后，以当前 durable target 生成仅关闭该 desired entry 的完整 replacement target，继续
  走正常 save/promote/lifecycle。不得接受 driver 传入任意 target patch，也不得降级为 reconcile；

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

1. Engine 合并 package store 与 providers 声明的纯 built-in metadata，解析 selections、facet references、
   active desired entries 和全局 facet DAG；
2. Engine 根据当前 attempt 与新 target 计算 affected closure；未受影响 unit 连同其原
   `RuntimeUnitGeneration` 原样保留；按 `pluginId/facetId` 把 affected entry ids 和所需静态 facet 闭包交给各
   driver 准备 candidate；
3. 各 driver 显式校验 definitionId，为每个 affected desired entry 返回且只返回一个 unit 和一个 binding；
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

共享静态资源还必须跨 attempt 复用，不能只保证同一 candidate 内共享。Java driver 按 package
revision/digest、facetId、精确 dependency static-resource identities 和 provider `contractIdentity` 建立私有
不可变资源键与真实 lease pool；局部替换 A、保留 B 时，B 及公共依赖 C 的 ClassLoader/wiring 身份必须保持，
A 必须取得相同静态 wiring。ClassLoader、definition 或 lease handle 不得暴露给 Engine。Node payload 使用
同样的跨 attempt 内容身份与 lease 原则。

unit 依赖从 facet DAG 展开时，一个依赖 facet 的全部 active units 按 `ExecutionUnitKey` 排序加入；没有 active
unit 的 contract-only facet只参与 driver-private静态资源依赖，不产生伪 execution unit。这个展开规则保守但
确定，避免同一 facet 多实例时通过“第一个实例”或 artifactId 猜测依赖。

affected closure 必须先分别展开旧、新完整 unit dependency map。unit 新增、删除、plan 内容变化以及同一
unit 的依赖集合变化都作为 seed，再分别沿旧图和新图取 dependent closure 并求并集。因此 `a -> facet B` 时，
新增、删除或 gate 掉 `b2` 都会替换 a；不能只沿旧 unit DAG 处理新增 unit，也不能只沿新图处理已删除 unit。
此外，每个 unit 的编译输入身份必须包含从其 owner facet 出发的精确静态 facet 传递闭包；闭包中的
package revision、facet identity、artifact identity 或依赖边变化都先把直接消费该闭包的 unit 作为 seed，
再沿上述旧/新 unit DAG 扩散。contract-only facet 即使没有 active unit，也不能因此从 affected 判断中消失；
它仍不产生伪 execution unit，也不被塞进 `ExecutionUnitPlan.dependencies`。

`RuntimeHostServices.requestReconcile(Set<RuntimeUnitFence>, reason)` 只用于不改变计划的可用性变化，例如
外部 execution 上线，或 ACTIVE 后失活的 current units 请求原子替换。一个外部连接事件可以同时影响多个
assignments，因此 fences 是不可拆分的批次故障域；Engine 把整批请求序列化进唯一 command lane，逐项校验
`RuntimeId + ExecutionUnitKey + unitTargetRevision + runtimeInstanceId` 仍精确属于 current。缺失、错误、过期
或已退役 fence 无副作用；有效 FAILED units 及其 dependent closure 在同一 replacement 中重建，其余仍匹配
原 fence 的有效 units 再按全局 DAG reconcile。driver 不得自行启动其它 runtime 的 dependent，也不得直接
修改 desired/observed 聚合。

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

本地进程或外部 execution 在 ACTIVE 后失活时，driver 必须先把对应 unit 观察改为 FAILED、同步封闭它的
`ContributionAdmission`，再调用 `requestReconcile`。Engine 看到 FAILED unit 时走上述 replacement 流程，不能
对已经执行过插件代码的旧 unit 原地再次 `reconcileAsync`。仅 sidecar 私有 failure 或仅发 wake-up 而仍发布
ACTIVE 都是契约违例。尚未到达 ACTIVE 的确定性启动失败只发布 FAILED 并由当前 deployment attempt 返回，
不得自动请求 replacement；否则同一 durable target 会形成无限重建风暴。Node sidecar 在 `fibra.start`
成功后、contribution 注册或 ACTIVE 提交前退出，也属于启动失败：driver 必须保存该终止事实、立即封闭
admission，并与 ACTIVE 提交在同一同步判定中二选一，不能消费一次性终止信号后再发布死亡进程为 ACTIVE。

不改变 plan、也不要求 replacement 的插件内部状态变化走 `requestObservationRefresh`。Java driver 的 unit
`snapshot()` 必须从当前 `PluginInstance` 实时派生 PENDING/ACTIVE/FAILED 与失败原因，不能缓存启动完成时的
结果；根实例 state subscription 由 unit scope 持有，unit 关闭时自动取消。Engine 对已被替换、retiring 或
identity 不匹配的 refresh fence 无副作用。这个通道只刷新运行事实，不能暗中修正 desired target。

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

crash 在 save 前恢复旧 target；原子 save 后任何点 crash 都恢复新 target。核心能够直接证明旧
`viewRevision + ContributionId + registrationIdentity` 完整调用 tuple，以及旧 `RuntimeUnitFence` 在新 Host
中失效。产品 runtime 的旧 session、ack、call result 和 resource request 还必须由产品侧 gateway 使用
`hostInstanceId + clientExecutionId + unitTargetRevision + runtimeInstanceId` 单独验证；Fibra Host fixture
不得冒充这部分证据。持久 target 合法但 package 缺失或
prepare 失败时仍是 `DurableTargetState.PRESENT`，但没有 current attempt；target observed 为 FAILED，不伪造
一个不存在的 CURRENT generation，也不篡改 target。控制面仍可提交一个新的完整 replacement target。

## 9. Contribution gateway 与外部 execution SPI

`fibra-bridge` 提供唯一不可变 `ContributionKindRegistry`：

- kind name 全局唯一，重名直接失败；
- resolver 返回注册 contribution 时使用的同一 `ContributionKind` 实例；
- 只有显式提供 `LiteralValue` codec 的 remote-capable kind 能出现在外部协议；
- Node 与产品 client runtime 共用同一 registry，不再维护私有 resolver。

`ContributionCodec` 的 descriptor/input/output wire 边界统一使用 `LiteralValue`，不得在公共 gateway 暴露
`Object`/`Map` 数据袋或同时支持两套 wire 形态。Node RPC 等 runtime-private carrier 在自己的边界显式执行
`LiteralValue.toJava()`/`LiteralValue.of(...)`；这不产生第二套 contribution codec。

`fibra-engine` 中唯一的 `RemoteContributionInvoker` 组合 bridge registry 与 `PublishedRuntime`，接收：

```text
kind name + ContributionId + expectedViewRevision + registrationIdentity + LiteralValue input
```

它解析同一 kind/codec 后调用 `PublishedRuntime.invoke`，并把结果编码为 `LiteralValue`。未知 kind、旧 view、
旧 registration、已关闭 admission 和 codec 错误都返回稳定失败；不得按名字落到新 handler，也不得自动重放
可能有副作用的调用。

`ContributionId.providerInstanceId` 固定取 desired entry id，是跨 unit replacement 与 Host 重启保持的业务身份；
Java `PluginInstance.id()` 同样取该 desired entry id。`runtimeInstanceId` 只标识一次 execution generation，专用于
`RuntimeUnitFence`、assignment 与产品资源授权，绝不能泄漏为 contribution provider 名称或替代
`ContributionId`。Java `PluginInstance.identity()` 仍是进程内对象身份，也不进入远端调用 tuple。三者不得互换。

Node 等远端 contribution 的每次请求必须把 runtime-private request 作为调用 `Scope` 拥有的
`DrainingDisposable` 登记。下游订阅取消只发出远端取消请求，不能立即释放 route invocation lease；只有远端
成功、失败或取消宽限终态到达后，调用 Scope 才完成排空，unit 才能从 `DRAINING` 进入 `STOPPING`。这条所有权
链与 sidecar 主动 stop 分离，禁止用 Reactor subscriber 已取消冒充远端工作已结束。

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

每个外部 `Assignment` 必须显式携带自己的 `unitTargetRevision`。Snapshot 中的全局 `targetRevision` 只描述完整
持久目标，不能用来推导 assignment 代次；否则 retained unit 在 target 10→11 后的新连接无法构造合法资源和
生命周期围栏。

`Assignment` 还必须显式携带 `desiredEntryId + definitionId + resolved config`。`pluginId + facetId` 只标识静态
制品来源，不能标识 execution unit；同一 facet 的两个 entry 即使共用 `entryModule`、payload 和 resources，也
必须以各自 entry 身份、runtimeInstanceId、unitTargetRevision 和规范 `LiteralValue` config 独立启动。产品 runtime
不得从 snapshot 全局 revision 推导 unit 身份，不得通过私有 transport 字段补配置，也不得把配置烘焙进资源。

Fibra 正式 npm 包只保留纯 `client-api` 与 `client-protocol`。任何 lifecycle actor、runner、Web loader、
React adapter 或真实 transport 都不得进入这些包。

`client-api` 只冻结产品 runner 需要消费的 factory 契约：加载一个 assignment 的 entry module 后，产品侧必须
在 `ClientEntryModule.definitions[]` 中找到且只找到一个匹配 `definitionId` 的
`ClientModuleDefinition`，再为该 assignment 调用一次 `create(ClientInstanceContext)`。context 一次性绑定
`desiredEntryId`、`definitionId`、`unitTargetRevision`、`runtimeInstanceId`、resolved `config`、独立 `scope`
和受 fence 约束的 `host` caller；返回的 `ClientModule` 生命周期方法 `prepare/activate/drain/stop` 均无参数。
同一 entry module 可以声明多个 definitions，同一 definition 可以为多个 assignments 创建独立实例，但不能
使用全局 registry 或跨 generation 缓存实例。definition 查找、重复/缺失拒绝、模块装载、调用编排和实例回收
都属于产品 runner，不在 Fibra 正式包中实现。

## 10. Composition root 与关闭所有权

composition root 注册 provider，不传入已经启动的 runtime：

```text
FibraEngine.builder(...)
  .hostServices(hostServices)
  .contributionKinds(kinds)
  .hostTerminationPort(hostTerminationPort)
  .runtimeProvider(javaProvider)
  .runtimeProvider(nodeProvider)
  // 仅产品宿主注册 productClientProvider
  .build()
```

Engine 创建共享 `RuntimeDomain`、`ContributionDirectory`、`ContributionKindRegistry` 和
`RemoteContributionInvoker` 后，再以只读 `RuntimeHostServices` 创建 drivers。services 只暴露受限
unit admission factory、kind registry、invoker、Host identity/id factory、`ScopeView` 与受 Host 校验的
child Scope 释放操作；不得暴露可关闭根 Scope、目录或 domain。Java driver 在每个 unit instance root 中只发布该 unit 的
`ContributionAdmission` 作为 `ContributionServices.REGISTRAR`；Node 和 external driver 也使用各自 admission，
不得直接使用 directory 或共享无 owner 的 registrar。

`FibraEngine.Builder.build()` 成功后，Engine 同时取得传入 `PluginPackageStore`、
`DeploymentTargetStore`、共享 runtime domain/directory 和其创建的所有 drivers 的关闭所有权；正常关闭按
逆序释放。构造中途失败也必须关闭已经取得的 drivers、共享资源和两个 stores。调用方不得再单独关闭或复用
这些对象构造第二个 Engine。
`RuntimeProviderRegistry` 是 Engine 包内的 composition 实现细节，不属于公共 SPI；外部 composition root 只向
`FibraEngine.Builder` 注册 providers，不得批量创建并自行持有 drivers。
Java 的 `ContributionServices.REGISTRAR` 与 `ManagedPluginControl.KEY` 都是 Host 注入的 per-unit 服务：driver
必须从 instance root context 为这两个 key 派生同一个 unit-local realm，在该派生 context 中注册并挂载根插件，
使多个 Java units 可同时拥有各自 registrar/control 而不冲突。插件声明的业务 `ServiceKey` 不得被顺手隔离，
仍留在正常 realm 中按 definition 的 require/provide 语义跨 Java units 解析。

`HostServiceRegistry` 仍是所有 composition root 共用的显式宿主服务构造面。它只在 Engine 启动前收集
`ServiceKey + value`，bootstrap 时冻结并发布到长期 `RuntimeDomain` 根；所有 Java unit scope 通过正常服务
继承读取同一 binding。Engine 不取得宿主对象的关闭所有权，启动后不支持动态增删。Spring exporter、CLI 和
非 Spring Host 都走该入口，不能在 Java driver、Spring adapter 或匿名 catalog 中各建一套注入路径。

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
- Java `ManagedPluginControl` 与 Node `fibra.disable` 必须汇入同一个 Host→Engine entry-disable gateway；请求携带
  `RuntimeId + ExecutionUnitKey + unitTargetRevision + runtimeInstanceId`，Engine 在唯一 command lane 校验仍是
  当前 unit 代次后，基于当前 durable target 生成“selections/configContext 不变、仅该 entry 本地
  enabled=false、targetRevision+1”的完整 replacement 并正常 save/promote，不得把自停用映射为 reconcile；
- retiring/已替换 unit 的迟到自停用、重复请求或目标中已停用/已删除的 entry 必须无副作用；save 失败保留
  当前 unit 与旧 durable target、公开失败诊断并允许同一当前代次重试，不能旁路持久化；
- package gate 关闭时，其全部 facets 和 entries 一起撤销；
- 所有操作最终都生成一个完整 `DeploymentTarget`，不存在裸 definition 或 runtime-only 旁路。

`install(source, enabled)` 先严格读取 package 元数据并发布不可变内容，再以显式 `enabled` 创建 selection；
pluginId、version、revision 和 runtime 不接受调用者重复声明，发布本身不推导启用。`upgrade(source)` 要求包内
pluginId 已有 selection，并原样保留旧 gate；相同 revision 与完整 target 内容按 Engine 的 no-op 规则处理。
`uninstall(pluginId)` 仅移除 selection，不物理删除不可变内容；raw desired 中仍有任意 entry 引用该 package
时必须拒绝，包括 disabled entry，调用者须先移除这些 entries。target 保存失败留下的已发布 package 不回滚删除。

完整 deploy 接收已发布的 selections、raw desired graph 和 configContext；增量管理用例从当前 durable target
派生完整 replacement，并以 targetRevision 做 CAS。`ReconcileCurrent` 只重试当前 target，不另存 target。
审计 `succeeded` 表示命令被 Engine 接受、目标事务完成，不表示全部 unit ACTIVE；执行结果单独投影 observed。
审计保存事实按本次前后 durable revision/digest 与 `EngineChangeException` 判断，no-op 和 reconcile 记为
`NOT_APPLICABLE`，不得将前一次发布视图中的 `SAVED` 误记为本次保存。

Registry 只投影 package、durable target、current attempt 和 observed，不建立第二状态机。CLI、Spring、UI 管理
页面只能调用相同用例。

## 12. P0 重新冻结门

### P0-A：compile-only 可组合性

使用真实 Java driver、Node driver 和测试域 external runtime fixture，证明：

- Java 与 Node 同为 `ExecutionTarget("host")` 时仍按不同 RuntimeId 正确注册；
- Java 私有 ClassLoader/definition 不离开 Java driver；
- compile-only 阶段不启动插件、sidecar、session 或网络；
- 同一 Java/Node facet 被两个 desired entry 引用时生成两个独立 unit，二者共享静态 generation lease 但
  config/runtimeInstance/lifecycle 独立；Node descriptor 缺少或重复 `definitionId` 必须在保存前失败；
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
- external RuntimeProvider fixture 中同一 facet 的两个 desired entries 以不同 resolved config 形成两个独立
  units/activations，二者不得合并或互串配置；
- 独立 client API/protocol conformance 从正式 `host.snapshot` codec 结果只取公开 Assignment 字段，为两个
  entries 精确选择 definitions、创建独立 modules，并在全新 session 重复绑定；该门不实现浏览器 loader；
- remote call 只经同一 `RemoteContributionInvoker`；
- 离线 external execution 不阻塞 Host ready。

### P0-C：真实重启恢复

同一 package/state store 启动第二个 Host，证明：

- targetDigest/revision 与 `ContributionId` 业务身份保持；host/runtime/operation/view 身份重新分配；
  registration 属于新目录，数值允许从初值重新计数，不能单独作为跨 Host 围栏；
- Java ClassLoader 与 Node sidecar 从持久 target 重建；
- 旧 view/registration/ContributionId 完整 tuple 与旧 `RuntimeUnitFence` 被核心拒绝；产品 session、ack、
  call result、resource request 的拒绝由产品 runtime/gateway 以同一正式身份契约另行证明，不计入 Fibra Host
  fixture 已完成证据；
- 保存后执行失败的 current target 会重新尝试收敛；
- package 缺失/损坏时失败可观察且 target 不被篡改；
- built-in selection digest 与 provider metadata 完全相同时恢复；provider 缺失、旧 digest 不再提供、metadata
  与私有 definitions 不匹配时启动失败可观察且 target 不被篡改；发布二进制或声明变化必须改变 built-in
  digest 与 provider contract identity；
- save-unconfirmed 只由 restart/load 消歧；该故障注入由 Engine/store 定向测试证明，Host 进程门只消费已经
  落盘的确定事实，不伪造 store 内部不确定窗口；
- `HostTerminationPort` 在 fatal observed 发布后只通知一次；回调抛错、超时或尝试重入均不重开 gate，真实
  Host 关闭在 Engine lane 外完成。

### P0-D：调用方、发行与独立消费

- Registry、CLI、Spring、examples、parity、archetype、plugins 和 distribution 全部硬切；
- 根 reactor、API baseline、可复现发行、空 Maven 仓 Java RuntimeProvider 消费者和 npm tarball 消费者
  全部通过；
- npm 正式包只有纯 API/protocol，发行物不含 runner/Web/React/transport；
- external RuntimeProvider fixture 只证明公开 SPI；client API/protocol conformance 只证明 Assignment 字段、
  codec 与 factory 绑定。二者都不冒充真实浏览器证据。产品仓以正式 Fibra 发布物实现和验证真实 browser
  runtime，是产品 P1 前置门，不是 Fibra P0 完成门；
- 文档一致性与架构、代码、发行三类独立审查无未关闭 P0/P1/P2。

## 13. 可证伪与停止条件

出现任一情况必须停止实现并回到本架构：

- 需要 `HostPreparedArtifact`、metadata bag、运行时 cast 或 runtime 实现间依赖才能传递私有对象；
- Engine 同时保留 ArtifactRuntime/ExecutionRuntime 或旧 PluginRuntimeAdapter；
- `ExecutionTarget` 再次作为 runtime 注册键；
- artifactId/facetId 再次被当作 ExecutionUnitKey 或隐式 definitionId；
- 内建 Java definition 由 Engine catalog 持有或绕过 Java driver 生命周期；
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
