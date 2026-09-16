# Fibra Client Foundation P0 Implementation Plan

> 权威架构：[2026-09-15-fibra-client-foundation-architecture.md](../specs/2026-09-15-fibra-client-foundation-architecture.md)

**状态：** 2026-09-17 架构复审后重排。Task 9 旧实现已暂停；当前先完成 Task 7 的新架构契约与真实
compile-only walking skeleton。不得从旧 Task 9 继续补 `HostPreparedArtifact`、`fibra-runtime-host` 或
`NodeExecutionRuntime`。

**最终边界：** Fibra 提供统一 runtime SPI、严格 client protocol/API、Java/Node runtime 与 conformance
fixtures；浏览器 adapter、runner、transport、Web loader、resource cache 和 renderer 留在产品仓库。

**技术栈：** Java 21、Reactor、Jackson 3、Maven 3.9.9、TypeScript、pnpm、Node test runner。Fibra 正式
发布物不依赖 React、DOM、Playwright 或产品 transport。

## 1. 当前事实与状态

| Task | 当前状态 | 结论 |
|---|---|---|
| 1 | 已完成但权威内容已重写 | 旧“架构已冻结”结论撤回；本计划和架构文档成为新真源 |
| 2 | 部分保留、待重排发布集合 | Maven/npm 隔离门可保留；正式 npm 只留纯 API/protocol |
| 3 | 部分保留、Task 7 硬切字段 | 严格 codec、数值与错误结构保留；外部 unit 围栏改为 `unitTargetRevision` |
| 4 | 部分保留 | `client-api` 的纯 SPI 保留；生命周期 actor/runner 不再是 Fibra 正式实现 |
| 5 | 历史证据 | 浏览器技术可行性结果仅作参考，不再是 Fibra release gate |
| 6 | 已完成、保留 | `PluginPackage`、facet、精确依赖与 package store 方向有效 |
| 7 | 进行中 | 双 runtime 契约冻结已撤回；改为单一 `RuntimeDriver` 和 compile-only walking skeleton |
| 8 | 待开始 | 实现 Java/Node driver、generation 与 lease 所有权 |
| 9 | 暂停并重写 | Engine、持久目标与全局生命周期硬切 |
| 10 | 待开始 | 真实 Java + Node + external execution SPI 风险门 |
| 11 | 待开始 | 管理面/调用方硬切与 Host 重启恢复 |
| 12 | 待开始 | 发行、API、npm、CI 与独立消费者 |
| 13 | 待开始 | 全量验收、文档一致性和三类独立审查 |

当前安全检查点为 `5e9df97`。其后的未提交内容按以下规则处置：

- 保留并完成：`ScopeView` 及 Java root scope ownership 测试；
- 撤回：根 POM 中 `fibra-runtime-host`、整个 `fibra-runtime-host/` 草稿；
- 重写：`NodeExecutionRuntime` 草稿，最终并入统一 Node driver；
- 不作为架构前提：现有 `fibra-runtime-client`、client runtime/Web/React 代码；按 Task 7/12 迁移纯契约并
  删除正式实现；
- 所有文件在删除或迁移前先由本计划给出归属，并在对应 task 的同一提交完成测试和文档更新。

## 2. 阶段与 checkpoint 纪律

1. 每个 Task 都从 RED/compile failure 或明确的结构门开始；契约测试必须包含真实类型组合，不能只用反射和
   fake 验证方法形状。
2. 一个 checkpoint 只有在该 Task 列出的定向测试、停止条件搜索和 diff 审查通过后才提交。
3. 不保留兼容 reader、旧新 adapter、双写状态、双 Registry、runtime 选择开关或弃用重载。
4. 保存前阶段不得启动插件实例、Node sidecar、远端 session 或网络；保存后失败不得回滚 target 或恢复旧
   handler。
5. 同一问题连续两次没有通过且没有新根因证据时停止，更新本文和权威架构后再继续。
6. 最终完成必须以根 reactor、发行、空仓消费者、API baseline、文档一致性和独立审查的实际输出证明。
7. Task 7–11 是一次公开 SPI 硬切批次：Task 7 删除旧 SPI 后，下游可在当前分支暂时不编译，但不得发布、
   合并或声称 root 绿色；必须连续推进到 Task 11 全部前沿通过。若实际迁移无法维持清晰前沿，可以合并这些
   task 的实现提交，但不能引入兼容层换取中间绿色。

## Task 7：冻结单一 RuntimeDriver 契约与 compile-only walking skeleton

**当前状态：进行中。** 权威架构与本计划已重写；代码契约和 walking skeleton 尚未实现。

### 目标

- 一个 `RuntimeId` 只注册一个 `RuntimeProvider/RuntimeDriver`；
- `ExecutionTarget` 只表示 placement/capability，不作为 runtime key；
- Java 与 Node 私有 prepared 数据始终留在各自 driver；
- `DeploymentTarget` 纳入完整 `ConfigContextSnapshot`，删除 context-only 持久旁路；
- Engine 保留一个全局 DAG，并把确定性 runtime slices 交给 drivers；
- Fibra 正式模块不依赖浏览器、DOM、React、浏览器 URL/Fetch API、WebSocket 或 Electron；Java runtime
  为 ClassLoader 使用 `java.net.URL` 不属于浏览器边界泄漏；
- 先完成 browser/runtime 边界清理，正式 npm 只剩纯 API/protocol；
- 使用不发布的 external RuntimeProvider module 参与 compile-only 组合。

### Files

- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTarget.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTargetCompiler.java`
- Modify: `fibra-config/src/main/java/com/sstlfsj/fibra/config/PluginDefinitionRef.java`
- Modify: `fibra-config/src/main/java/com/sstlfsj/fibra/config/DesiredInputEntry.java`
- Modify: built-in package definitions、config codecs and all `PluginDefinitionRef` construction tests
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeProvider.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeDriver.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeCandidate.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PreparedRuntimeGeneration.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeUnitGeneration.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimePlan.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionUnitPlan.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DefinitionBindingPlan.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeHostServices.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/HostCapabilitySnapshot.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/HostTerminationPort.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/HostTerminationRequest.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeDriverSnapshot.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/CompiledRuntimeSlice.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentCandidate.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/CompiledDeployment.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DurableTargetToken.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/AttemptRole.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/AttemptPhase.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeProvider.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriver.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeProvider.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeDriver.java`
- Delete after algorithm migration: `ArtifactRuntime.java`、`ExecutionRuntime.java`、`PreparedArtifact.java`、
  `PreparedArtifactUpdate.java`、`ExecutionTargetPlan.java`、`ExecutionUpdate.java`、`ExecutionHandle.java`、
  `ArtifactResources.java`、`JavaArtifactRuntime.java`、`NodeArtifactRuntime.java`
- Modify: `pom.xml`，移除 `fibra-runtime-host`/`fibra-runtime-client`，加入两个非发布 verification modules
- Delete: `fibra-runtime-host/`
- Delete after moving pure descriptors/fixtures: `fibra-runtime-client/`
- Create: `client/packages/client-protocol/`，从 `client-runtime/src/protocol.ts` 迁移纯 type/codec
- Modify: Java/TypeScript client protocol，资源与生命周期围栏使用创建 unit 时固定的
  `unitTargetRevision`，不把 current global target revision 当成 retained unit revision
- Delete: `client/packages/client-runtime/`、`client/packages/client-runtime-web/`、
  `client/packages/client-react/`、`verification/client/risk-gate/`
- Modify: `client/package.json`、`client/pnpm-workspace.yaml`、`client/pnpm-lock.yaml`、
  `client/tsconfig*.json`、`client/scripts/check-core-boundaries.mjs`
- Create: `verification/client/external-runtime-fixture/pom.xml`
- Create: `verification/client/external-runtime-fixture/src/main/java/.../ExternalFixtureRuntimeProvider.java`
- Create: `verification/client/runtime-composition/pom.xml`，只依赖 Engine、Java/Node drivers 和 external fixture，
  设置 `maven.deploy.skip=true`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/DeploymentTargetContextContractTest.java`
- Test: `verification/client/runtime-composition/src/test/java/.../RuntimeDriverCompositionTest.java`
- Test: `verification/client/runtime-composition/src/test/java/.../RuntimeDriverCompileWalkingSkeletonTest.java`

### 步骤

1. 先写 TypeScript package boundary RED，并建立专用 Maven verification module 的 compile-failure skeleton；
   不先删除旧包或修改 lockfile。
2. 将 transport-neutral `ResourceDescriptor(path,digest,byteLength)`、严格 codec，以及字段格式、path 规范化和
   canonical 编码测试迁入 Java/TypeScript `client-protocol`；`fibra-client.yaml` 的 entryModule/parser 属产品
   browser runtime，连同 URL/loader 实现删除，不在 external fixture 复制。实际资源字节的 byteLength/SHA-256
   复算、未声明文件拒绝和 target 已 save/promote 后每次 browser lifecycle prepare/装载重验属于产品 P1
   browser runtime 强制验收，不得误写成保存前 `RuntimeCandidate.prepareAsync` 或 protocol 已证明；
   fixture 只用公开 protocol 对象表达资源计划。
3. 迁移纯 npm API/protocol，再删除 browser/runtime/React 正式模块与旧风险门；重新生成 lockfile，并以
   boundary test 与 pack 内容证明只剩纯契约。
4. 写 Java RED 测试：
   configContext 改变 digest/revision；definition/unit 双射完整；Java private prepared type 不出现在 Engine
   公共签名；snapshot canonical codec 区分缺失/null 并稳定处理 key/数字/Unicode；evaluator/binder/driver
   不能读取未进入 snapshot 的环境变量、系统属性、时间或随机值。
5. 删除双 runtime SPI 的冻结测试和类型，建立新 SPI、immutable plans、`HostTerminationPort` 与 Java/Node
   compile-only drivers；
   `createCandidate` 无 I/O，`prepareAsync` 可 materialize 受信 definition，但不启动执行。
6. 用真实 Java/Node descriptor、ClassLoader/payload 和独立 external provider fixture 跑 compile-only walking
   skeleton；记录启动计数为 0，全局 dependency-first/reverse order 在分区后不丢失。
7. 全仓检查正式生产模块和 npm tarball 没有 client/Web 产品实现依赖。

### 门禁

```text
mvn -pl verification/client/runtime-composition -am test
pnpm --dir client install --offline --frozen-lockfile
pnpm --dir client run build
pnpm --dir client run test
pnpm --dir client run lint:boundaries
pnpm --dir client --filter @sstlfsj/fibra-client-api pack
pnpm --dir client --filter @sstlfsj/fibra-client-protocol pack
if rg -n "ArtifactRuntime|ExecutionRuntime|HostPreparedArtifact|fibra-runtime-host|fibra-runtime-client" fibra-engine/src/main fibra-runtime-java/src/main fibra-runtime-node/src/main pom.xml; then exit 1; fi
if rg -n "com\\.microsoft\\.playwright|java\\.net\\.http\\.WebSocket|Electron|org\\.w3c\\.dom" fibra-engine/src/main fibra-client-protocol/src/main; then exit 1; fi
```

两个反向断言的生产源码结果必须为空；`lint:boundaries` 另外检查 npm 依赖、DOM lib、React、Playwright、
Electron、Blob 与浏览器全局。`reactor` 和 Java ClassLoader 所需 `java.net.URL` 不属于违规命中。

### 停止条件

- 需要 metadata bag、类型 token、runtime cast 或实现模块互相依赖；
- compile-only 测试必须启动插件、sidecar、session 或网络；
- context 改变实际 plan 却没有进入 target digest/revision；
- external execution fixture 必须引入浏览器 runner 才能完成编译；
- 删除 browser/runtime 模块后纯 API/protocol 独立 pack 无法使用。

## Task 8：实现 Java/Node driver、generation 与资源所有权

**依赖：** Task 7 全部门禁通过。

### 目标

- Java 制品、ClassLoader、definition 物化、配置绑定和 Host execution 统一由
  `fibra-runtime-java` driver 拥有；
- Node 制品、sidecar 和 contribution 统一由 `fibra-runtime-node` driver 拥有；
- candidate/current/retiring unit 所有权显式；generation 在副作用前持有全部私有资源 lease；
- Java `Context.scope()` 只暴露 `ScopeView`，instance root 只能由 runtime 关闭。

### Files

- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/Context.java`
- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/InvocationContext.java`
- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/Scope.java`
- Create: `fibra-api/src/main/java/com/sstlfsj/fibra/ScopeView.java`
- Modify: `fibra-core/src/main/java/com/sstlfsj/fibra/internal/DefaultContext.java`
- Modify: `fibra-core/src/main/java/com/sstlfsj/fibra/internal/DefaultScope.java`
- Modify: `fibra-runtime-java/.../JavaRuntimeProvider.java` 与 `JavaRuntimeDriver.java`，实现 unit generation、
  Host RuntimeDomain execution 和私有 resource leases
- Modify: `fibra-runtime-node/.../NodeRuntimeProvider.java` 与 `NodeRuntimeDriver.java`，实现 sidecar generation、
  contribution 与私有 resource leases
- Delete after behavior migration: old `JavaPluginRuntimeAdapter`、`NodePluginRuntimeAdapter`
- Test: scope ownership、candidate failure、generation lease、真实 Java/Node start/stop tests

### 必测场景

- candidate 部分 prepare 失败、取消、关闭失败；
- `definition()` 可在保存前执行，但 `Plugin.start()` 与 Node process 启动计数仍为 0；
- definition contract 无 I/O、线程、注册或 Context/Scope 能力；
- seal 后其它 driver 失败或显式 abort 时，sealed generation 释放全部未启动 unit leases 和私有资源；
- unit 只有在测试 harness 显式 promote 后调用 `reconcileAsync(operationId)` 才启动真实 Java instance/Node
  sidecar；save/promote 的顺序断言归 Task 9；
- 旧 invocation 尚未 drain 时旧 ClassLoader/payload close 次数为 0；
- drain/stop 的重复、迟到回复按各自 lifecycleOperationId 拒绝；
- reverse dependency stop 与 resource retire；
- cleanup failure 保留 retiring generation 并关闭 mutation gate；
- plugin 无法关闭 root scope，child scope 可关闭且 root 级联。

### 门禁

```text
mvn -pl fibra-api,fibra-core,fibra-runtime-java,fibra-runtime-node -am test
```

## Task 9：硬切 Engine、持久目标与全局生命周期

**依赖：** Task 8 全部门禁通过。

### 目标

- Engine 生产路径只使用 `RuntimeDriver`；
- `DeploymentTarget` 是 selections + desired + configContext 的唯一持久格式；
- 唯一顺序为 prepare → validate → seal → save → promote → close admission → drain → stop → activate → retire；
- 保存后失败保留新 target 和真实 observed；save-unconfirmed 关闭 mutation gate；
- save-unconfirmed 同时关闭全部 managed contribution 准入并通过 `HostTerminationPort` 一次性请求受控 Host 退出；
- `ReconcileCurrent` 在同一 targetRevision 下替换失败 unit closure，不伪造 revision或并存第二 current。
- capability/runtime contract 改变计划时保守重编译全部 current units；仅可用性变化才 reconcile 旧 plan。

### Files

- Rewrite: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTargetCodec.java`
- Rename: `EngineStateStore` → `DeploymentTargetStore`
- Rename: `FileEngineStateStore` → `FileDeploymentTargetStore`
- Delete: `ReplaceConfigContext.java`
- Delete after migration: `DeploymentManifest.java`、`DeploymentManifestCodec.java`
- Create/Rewrite: `DurableTargetState`、deployment attempt、retirement batch、Engine diagnostics/snapshot/phase
- Test: full save/failure/retry/restart-state matrix

### 必测场景

- same digest no-op；A→B→A revision；configContext A→B→A；
- prepare/definition/binder/validator/plan 失败不保存；
- save 明确失败不启动；save-unconfirmed 不 promote、不继续服务并进入受控退出；
- 某 driver seal 后另一 driver seal 失败、全部 seal 后 save 明确失败，均 abort sealed generations 且零 lease 泄漏；
- abort/close 失败保留资源现场、关闭 mutation/contribution gate 并请求 Host termination，不接受下一变更；
- save 后 Java/Node/external execution 失败保留新 target；
- 同 digest 但 compiledFingerprint 改变时不走 no-op，使用同一 durable token 重编译全部 current units；
- 全局 dependency-first activate 与 reverse stop；
- drain timeout、stop failure、retire failure；
- fatal 路径先封 mutation/contribution admission、发布 observed，再记录一次逻辑 request；Engine 只在独立
  notification lane 调用 `HostTerminationPort`。端口抛错、阻塞或尝试重入不得阻塞 command lane、重开 gate
  或产生第二 request；
- current reconcile retry 先清理旧失败 closure，同 revision、新 runtimeInstanceId；
- 旧格式直接拒绝。

### 门禁

```text
mvn -pl fibra-engine,fibra-runtime-java,fibra-runtime-node -am test
if rg -n "PluginRuntimeAdapter|ArtifactRuntime|ExecutionRuntime|RuntimeResources|ReplaceConfigContext" fibra-engine/src/main; then exit 1; fi
```

搜索结果必须为空。

## Task 10：真实 Java + Node + external execution SPI 风险门

**依赖：** Task 9 全部门禁通过。

### 目标

- 真实 Engine、文件 package/state store、动态 Java JAR、Node sidecar 和不发布的 external runtime fixture
  跑通一个逻辑 package；
- contribution kind/codec 只有一个 registry，远端调用只经 `RemoteContributionInvoker`；
- 外部 runtime fixture 只消费公开 SPI，不进入生产 composition root 或发行物。

### Files

- Create/Modify: `fibra-bridge` contribution kind registry 与 LiteralValue codecs；不得依赖 Engine
- Create: `fibra-engine/.../RemoteContributionInvoker.java`，组合 bridge registry 与 `PublishedRuntime`
- Extend: `verification/client/external-runtime-fixture/` with lifecycle、offline/online wake-up、resource lease
- Create: `verification/client/host/pom.xml`，依赖 Engine、Java/Node drivers、external fixture 和测试库，设置
  `maven.deploy.skip=true`
- Create: `verification/client/host/src/test/...` Java/Node/external execution vertical tests
- Modify: root `pom.xml`，加入 `verification/client/host`，并为全部 verification modules 设置
  `maven.deploy.skip=true`
- Test: cross-runtime package、call fence、offline PENDING、disable/upgrade、resource lease

### 步骤

1. 在 bridge 先写 duplicate kind、unknown codec 和 registry route lease RED 测试，再实现唯一 registry/codecs。
2. 在 Engine 写 stale view/registration、closed admission、route drain 与 invoker codec RED 测试，再实现
   `RemoteContributionInvoker`。
3. 扩展 external fixture，但仍只依赖公开 Engine/bridge/protocol；由 `requestReconcile` 把上线事件送回 Engine
   lane，不允许 fixture 自行启动 dependent runtime。
4. 以真实 package/state store 安装包含 Java、Node、external facets 的 package，验证保存前启动计数为 0。
5. 保存后启动真实 Java/Node，驱动 external fixture 从 PENDING 到 ACTIVE，再执行调用、升级、停用和退役。
6. 让 external fixture 发出 plan-affecting `requestRecompile`，证明不保存 target、revision/token 不变，Java、
   Node、external 全部 current units 获得新 runtimeInstanceId，旧 units 按反依赖 drain/stop/retire。
7. 注入 driver B seal 失败及已 sealed driver A abort 失败，证明零执行启动、资源现场保留、全局准入关闭；
   verification Host 消费 `HostTerminationPort`，证明独立 notification lane 只触发一次并实际结束受管 Host
   lifecycle；不得在 Engine 内调用 `System.exit`。
8. 检查 fixture 只存在于 test/verification dependency graph，distribution 和生产 composition root 均不引用。

### 必测场景

- Java、Node、external facets 的精确跨包依赖；
- Java/Node contribution 真正启动并由同一 `PublishedRuntime` 调用；
- external fixture 完成 assignment、activate、call、drain、stop、observed；
- 旧 host/view/registration/runtimeInstance/operation 全部拒绝；
- target revision 推进但 external unit 被 retained 时，旧 `unitTargetRevision` + 同 runtimeInstance 的精确 tuple
  仍合法；unit 替换后该 tuple 立即失效；
- external execution 离线时 PENDING，不阻塞 target save/Host ready；
- disconnect 不冒充 stop；
- plan-affecting capability/runtime contract 变化重编译全部 current units，不保存新 target；
- 部分 seal 后失败会 abort；abort 失败进入 fail-stop 并通过 notification lane 一次性请求 Host termination；
- disable/upgrade 为封准入 → drain → reverse stop → dependency-first activate → retire。

### 停止条件

- Java 或 Node 仍是 stub；
- remote call 绕过 `PublishedRuntime`；
- 为通过门禁把真实浏览器、React、Web loader 或 transport 放进 Fibra 正式模块；
- fixture 被 production composition root 依赖。

### 门禁

```text
mvn -pl fibra-bridge,verification/client/external-runtime-fixture,verification/client/host -am test
if rg -n "external-runtime-fixture|verification/client" fibra-*/pom.xml fibra-distribution scripts/verify-distribution.sh; then exit 1; fi
```

第二条在生产 POM/distribution 中必须无命中。

## Task 11：迁移管理面与调用方，并验证 Host 重启恢复

**依赖：** Task 10 全部门禁通过。

### 依赖前沿

1. `fibra-config,fibra-registry`
2. `fibra-cli,fibra-spring,fibra-spring-boot-starter`
3. `fibra-example,fibra-benchmarks`
4. `fibra-parity-tests,fibra-plugin-archetype,fibra-plugins,fibra-distribution`

每个前沿必须独立全绿后才能进入下一个，不用兼容 API 让未迁移下游假绿。

### Files

- Rewrite: `fibra-registry` package/desired use cases and persistence projections
- Modify: `fibra-config` definition refs、target construction and codecs
- Modify: `fibra-cli`、`fibra-spring`、`fibra-spring-boot-starter` composition roots
- Modify: examples、benchmarks、parity、archetype、plugins、distribution manifests and tests
- Delete: anonymous catalog、`InstallArtifact/UninstallArtifact`、old manifest readers/version solver and remaining
  `PluginRuntimeAdapter` call sites
- Create: `verification/client/host/src/test/java/.../HostRestartRecoveryTest.java`，复用 Task 10 的真实 Host fixture，
  只依赖 reactor production modules 与 verification external provider

### 步骤与前沿门

1. Registry/config 硬切 package 与 entry 管理语义，运行第一前沿；
2. CLI/Spring composition root 只注册 Java/Node providers，并分别消费 `HostTerminationPort` 协调 CLI runtime
   或 Spring application context 退出，运行第二前沿；
3. examples/benchmarks 不得保留旧 builder/catalog convenience，运行第三前沿；
4. parity/archetype/plugins/distribution 切换唯一 package 格式，运行第四前沿；
5. 最后在 verification Host 运行两 Host 真实进程重启测试并检查残留 PID/process tree。

```text
mvn -pl fibra-config,fibra-registry -am test
mvn -pl fibra-cli,fibra-spring,fibra-spring-boot-starter -am test
mvn -pl fibra-example,fibra-benchmarks -am test
mvn -pl fibra-parity-tests,fibra-plugin-archetype,fibra-plugins,fibra-distribution -am test
mvn -pl verification/client/host -am test -Dtest=HostRestartRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false
```

### 管理语义

- package install/upgrade/enable/disable/uninstall；
- desired entry upsert/enable/disable/move/remove；
- 插件自停用只作用于当前 entry；
- package gate 关闭撤销全部 facets/entries；
- CLI/Spring 只默认装配 Java/Node，产品 runtime 由产品 composition root 显式注册。

### 重启必测场景

- Host A 保存并运行真实 Java、Node、external fixture；
- 使用同一 package/state store 启动 Host B；
- target digest/revision 保持，host/runtime/operation/registration/Node PID 全部更新；
- 旧 session、ack、call result、resource request 全部拒绝；
- 动态 ClassLoader、Node sidecar 和 contributions 从持久 target 重建；
- 保存后 execution 失败的 current target 在重启时重新收敛；
- package 缺失/损坏时失败可观察且 target 不被改写；
- save-unconfirmed 以磁盘事实恢复。

### 停止条件

- 任一前沿只能靠旧 reader、重载、adapter 或双写编译；
- 重启恢复读取旧进程对象、复用旧 runtimeInstanceId 或手工修复 store；
- Registry 可绕过 package gate 直接管理裸 definition；
- distribution 仍生成 `plugin.properties` 单 artifact 布局。

## Task 12：发行、API、npm、CI 与独立消费者

**依赖：** Task 11 全部门禁通过。

### 正式发布集合

Maven 精确发布 28 个 artifacts：

```text
fibra-api
fibra-core
fibra-config
fibra-artifact
fibra-engine
fibra-bridge
fibra-runtime-java
fibra-runtime-node
fibra-registry
fibra-cli-api
fibra-cli
fibra-spring
fibra-spring-boot-starter
fibra-plugin-archetype
fibra-client-protocol
fibra-tool-api
fibra-fs
fibra-fs-local
fibra-tool-fs
fibra-tool-fs-search
fibra-subprocess
fibra-subprocess-local
fibra-shell
fibra-shell-local
fibra-tool-shell
fibra-storage
fibra-storage-json
fibra-tool-storage
```

- 保留 npm：`@sstlfsj/fibra-client-api`、`@sstlfsj/fibra-client-protocol`；
- 不发布：`fibra-runtime-host`、`fibra-runtime-client`、client runner/Web/React、verification fixtures。

npm 两包版本必须与 Maven release version 完全一致；`-SNAPSHOT` 只允许 build/test/pack，不 publish。稳定版本
发布 `latest` tag，预发布版本使用其 qualifier tag。release workflow 使用 npm trusted publishing/OIDC 与
provenance；凭据或 provenance 不可用时阻断发布，不回退为仓库长期 token。

### 必须修改

- `scripts/verify-distribution.sh`
- `scripts/verify-reproducible-release.sh`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- Java `ApiSignatureBaselineTest`
- Create: `client/api-baseline/client-api.d.ts`、`client/api-baseline/client-protocol.d.ts`
- Create: `verification/distribution/runtime-provider-application/pom.xml` Java external RuntimeProvider 空仓消费者
- Create: `verification/client/package-consumer/`，只安装两个 npm tarball 的 TypeScript API/protocol 消费者
- Create: `scripts/verify-client-packages.sh`，在临时目录 pack、安装、编译 package consumer 并检查 tar 内容
- Create: `scripts/verify-published-contents.sh`，按精确发布清单解压 JAR/tarball 并执行禁止内容断言
- distribution ZIP、archetype、独立消费者与 release 文档

### 门禁

- Maven 空临时仓部署后的 Java/Node/CLI/Spring 消费者和只依赖正式 artifacts 的 external
  `RuntimeProvider` 消费者；
- npm `pack` 后从临时目录安装，只有 dist/declaration/license/metadata；
- npm 无 `workspace:*`、源码入口、仓库绝对路径或 private fixture；
- TypeScript 独立消费者只用两个 tarball 验证 API/protocol 类型与 codec；Java 消费者单独证明 external
  RuntimeProvider SPI 可消费，不能用 TypeScript tarball 替代；
- Maven headless 门在没有 Node/pnpm 时通过；npm 门不读 reactor classpath；
- distribution/reproducible/API baseline 使用上述精确 28 artifacts 与两个 npm 包；
- 解压每个 Maven JAR/npm tarball，确认不含 verification、runner、Web loader、React、Playwright 或 transport
  实现；
- CI 分离 Maven、npm、发行、可复现构建。

```text
mvn -pl fibra-parity-tests,fibra-distribution -am verify
pnpm --dir client install --offline --frozen-lockfile
pnpm --dir client run build
pnpm --dir client run test
pnpm --dir client run lint:boundaries
scripts/verify-client-packages.sh
scripts/verify-distribution.sh
scripts/verify-reproducible-release.sh
scripts/verify-published-contents.sh
```

## Task 13：全量验收、文档一致性和独立审查

**依赖：** Task 12 全部门禁通过。

### 全量命令与证据

- 根 reactor `mvn clean verify`；
- Java/Node/external execution 真运行集成；
- Host 重启恢复；
- npm frozen-lockfile build/test/pack；
- Maven/npm 独立消费者；
- `scripts/verify-distribution.sh`；
- `scripts/verify-reproducible-release.sh`；
- Java/TypeScript API baseline；
- `git diff --check`；
- 旧模型、兼容分支、产品浏览器代码的全仓搜索；
- 文档一致性审查；
- 架构、代码、发行三路独立审查实际 diff、测试输出和制品。

最终至少执行：

```text
mvn clean verify
mvn -pl verification/client/host -am test
pnpm --dir client install --offline --frozen-lockfile
pnpm --dir client run build
pnpm --dir client run test
pnpm --dir client run lint:boundaries
scripts/verify-client-packages.sh
scripts/verify-distribution.sh
scripts/verify-reproducible-release.sh
scripts/verify-published-contents.sh
git diff --check
if rg -n "PluginRuntimeAdapter|ArtifactRuntime|ExecutionRuntime|ReplaceConfigContext|fibra-runtime-host|fibra-runtime-client" --glob '**/src/main/**' --glob 'pom.xml' .; then exit 1; fi
```

### 文档统一边界

所有 README、vNext、产品架构、Client Foundation 架构、计划、API/release 文档和来源审计必须一致：

- Fibra：SPI、协议、唯一 Engine/RuntimeDriver、Java/Node、发行契约；
- 产品：browser adapter、runner、transport、Web loader、renderer、React/Electron。

### 完成条件

- 所有 Task 状态和 commit/checkpoint 回填；
- 无未归属工作树改动；
- 全部阶段门有本次运行证据；
- 三路独立审查无未关闭 P0/P1/P2；
- 只有此时才能把权威架构改为“已冻结”、把 P0 标记完成并合并。

任一门禁未实际运行、证据范围小于声明范围、fixture 被生产依赖、发行集合与文档不一致，均不得宣告完成。
