# Fibra Client Foundation P0 Implementation Plan

> 权威架构：[2026-09-15-fibra-client-foundation-architecture.md](../specs/2026-09-15-fibra-client-foundation-architecture.md)

**状态：** 2026-09-20 P0 实现范围已重新关闭并在当前工作树本地冻结。当前仍为
`0.5.0-SNAPSHOT`，未执行正式版本号、tag、deploy/publish、合并或推送。此前通用
`AttemptRole/AttemptPhase` 和 `EngineDiagnostics.phase` 将 Engine operation、candidate/current 与
retirement batch 的状态所有权压扁；本轮已完成角色专用状态、精确 failure subject、可修正 bootstrap、
连接纵切和全部本地冻结门，不再沿用旧模型或旧证据。
不得恢复 `HostPreparedArtifact`、`fibra-runtime-host`、`fibra-runtime-client` 或独立
`NodeExecutionRuntime`，也不得为状态模型硬切保留兼容 API。

**最终边界：** Fibra 提供统一 runtime SPI、严格 client protocol/API、Java/Node runtime 与 conformance
fixtures；浏览器 adapter、runner、transport、Web loader、resource cache 和 renderer 留在产品仓库。

**技术栈：** Java 21、Reactor、Jackson 3、Maven 3.9.9、TypeScript、pnpm、Node test runner。Fibra 正式
发布物不依赖 React、DOM、Playwright 或产品 transport。

## 1. 当前事实与状态

| Task | 当前阶段 | 已取得证据与未关闭门 |
|---|---|---|
| 1–5 | 历史处置见下文 | 早期产物只保留仍符合当前架构的证据；被替代内容不得复活 |
| 6 | Green | package/facet/store 模型有效；最终集成与发行证据随 Task 13 统一关闭 |
| 7 | Integrated | 单一 `RuntimeDriver`、entry-key unit、显式 definition 与 compile-only 边界已在新状态模型上复验 |
| 8 | Integrated | Java/Node driver、静态 generation、不可变 fence 与显式 disposer/lease 所有权已复验 |
| 9 | Integrated | Engine aggregate 已硬切为 operation/candidate/current/retirement 独立 owner；旧通用 attempt API 已删除 |
| 10 | Integrated | 真实 Java/Node/external 生命周期及 Engine current→Java codec→TypeScript client 连接纵切通过 |
| 11 | Restarted | 全仓调用方完成硬切；可修正 bootstrap 与两 Host 重启恢复通过 |
| 12 | Integrated | API baseline、Maven/npm 独立消费者、client tarball 与三轮可复现制品通过；正式发布未执行 |
| 13 | Integrated | 完整本地门、文档一致性和架构/实现/发行复核通过；P0 已本地关闭冻结 |

本轮迁移起点检查点为 `66cf499`；其后的 Task 7–13 作为一个公开 SPI 原子硬切批次在当前分支统一提交，按以下
规则处置：

- 保留并完成：`ScopeView` 及 Java root scope ownership 测试；
- 撤回：根 POM 中 `fibra-runtime-host`、整个 `fibra-runtime-host/` 草稿；
- 重写：`NodeExecutionRuntime` 草稿，最终并入统一 Node driver；
- 不作为架构前提：现有 `fibra-runtime-client`、client runtime/Web/React 代码；按 Task 7/12 迁移纯契约并
  删除正式实现；
- 所有文件在删除或迁移前先由本计划给出归属，并在对应 task 的同一提交完成测试和文档更新。

## 2. 阶段与 checkpoint 纪律

1. 每个 Task 都从 RED/compile failure 或明确的结构门开始；契约测试必须包含真实类型组合，不能只用反射和
   fake 验证方法形状。
2. 一个 checkpoint 只有在对应定向测试、停止条件搜索和 diff 审查通过后才提交；原子批次内部 Task 7/8
   只有工作树局部门，不形成 checkpoint，首次可提交 checkpoint 是 Task 9 Engine/旧 SPI 同步切换完成后。
3. 不保留兼容 reader、旧新 adapter、双写状态、双 Registry、runtime 选择开关或弃用重载。
4. 保存前阶段不得启动插件实例、Node sidecar、远端 session 或网络；保存后失败不得回滚 target 或恢复旧
   handler。
5. 同一问题连续两次没有通过且没有新根因证据时停止，更新本文和权威架构后再继续。
6. 最终完成必须以根 reactor、发行、空仓消费者、API baseline、文档一致性和独立审查的实际输出证明。
7. Task 7–11 是一次公开 SPI 原子硬切批次。Task 7/8 可在工作树内部短暂同时存在尚未接线的新类型与待删除
   旧类型，但这只是同一变更的迁移顺序，不得形成 commit、发布物、feature flag 或兼容入口；Task 9 切换
   `FibraEngine` 的同一原子 checkpoint 删除旧双 SPI，随后必须连续推进到 Task 11 全部下游前沿通过。整个批次
   完成前不得发布、合并或声称 root 绿色，也不能用兼容层换取中间绿色。
8. 每项结论必须维护“事实 → owner → 刺激 → seam → oracle → 测试 → 命令 → 本次状态”证据链。状态只允许
   `Specified → Executable → RED → Green → Integrated → Restarted → Released` 逐级提升；局部单测通过不能写成
   integrated，Host 重启未跑不能写成 restarted，独立消费者与制品未通过不能写成 released。
9. 删除旧行为时同步维护替代证据：每个被删测试必须映射到新契约测试、明确由其它门覆盖，或说明该行为已被
   设计删除。不得仅凭旧类名搜索为零推断行为已迁移。

### 2.1 本轮状态模型硬切顺序

1. 先重写权威架构的状态所有权、failure subject、bootstrap 可修正性与连接边界，撤回过早的关闭结论；
2. 建立角色并存红测试：candidate PREPARING 不覆盖 current SETTLED，promote 后 candidate id 成为 current id，
   老 current id 成为 retirement source，retained unit 只属于新 current；
3. 硬切公开 API，删除 `AttemptRole`、`AttemptPhase`、`AttemptSnapshot`、`EngineDiagnostics.phase`、无身份顶层
   units/retiring 投影与全部兼容入口；引入 role-specific snapshots、`EngineOperationSnapshot`、
   `TargetConvergence` 与 `FailureFact`；
4. 将 `FibraEngine` 内部重构为实际拥有资源的 `CandidateAttempt`、`CurrentAttempt` 和
   `RetirementBatch`，删除平行 id/phase/map 字段；
5. 所有 runtime SPI 经统一契约边界处理同步抛错、null publisher、异步失败和 timeout；采样与
   `publish()` 分离，每个失败显式指向 Engine/target/operation/candidate/current/retirement/unit；
6. 重写 bootstrap 恢复：可信 target 因 package/provider/digest/prepare 失败且清理成功时保持
   `RUNNING + PRESENT + BLOCKED`、无 current，并允许完整 replacement；只有 store/所有权/清理不可信时
   `FAIL_STOP`；
7. Registry、Spring、CLI、parity、distribution consumers 与 API baseline 全部硬切，不保留双投影；
8. 建立真实 Engine current → 正式 `host.snapshot` → Java codec → TypeScript decode →
   `ClientModuleDefinition` 实例化的纵向门，证明 candidate 不泄漏且 retained/retirement fence 正确；
9. 重跑定向测试、根 reactor、client package、可复现构建、架构边界、API baseline、diff 检查和独立审查；
   全部证据归属同一最终工作树后才能重新冻结。

## 3. 历史完成项：Task 1–6

本节恢复 2026-09-15 初版计划的目标、证据和后续处置，用于解释 Task 7 的输入。它不是当前实现指令；当前
要求以权威架构和下文 Task 7–13 为准，不得据此恢复已撤回的浏览器 runtime、runner、Web loader、renderer、
transport 或第二套生命周期控制面。旧完整正文可由
`git show 5e9df977:docs/superpowers/plans/2026-09-15-fibra-client-foundation-p0.md` 审计；提交 `66cf499`
在架构重开时错误地删除了详细追踪，只保留状态摘要，本节纠正该追踪缺口而不复活旧设计。

### Task 1：初版架构冻结与文档清理

- 原目标：建立 client foundation 规格，统一仓库、发布物、进程、协议和控制面边界。
- 保留结果：形成了架构审计、模块边界和“阶段门必须有真实证据”的追溯材料。
- 替代关系：2026-09-17 复审撤回“浏览器执行下沉 Fibra”和“架构已冻结”的结论。当前只保留统一
  `RuntimeDriver` SPI、纯 client API/protocol 与 conformance fixture；产品仓拥有浏览器执行。
- 证据：`86eac429`（`docs: define Fibra client foundation architecture`）；替代提交 `66cf499`
  （`docs: reopen client foundation runtime architecture`）。
- 历史处置：已完成，但内容已被现行权威架构替代，不能充当当前冻结门。

### Task 2：初版 Maven/npm 发布边界

- 原目标：建立独立 Java client protocol/runtime 模块和 npm workspace，证明 Java 与前端构建隔离。
- 保留结果：Java/npm 生命周期独立、workspace 隔离和可执行边界测试仍有效。
- 替代关系（已删除且禁止恢复）：`fibra-runtime-client`、`client-runtime`、`client-runtime-web`；
  `client-react` 也已删除且禁止恢复，不再进入正式发布；
  npm 只保留 `@sstlfsj/fibra-client-api` 与 `@sstlfsj/fibra-client-protocol`，最终证据归 Task 12。
- 证据：`8a0edeaa`、`9049019d`、`430e8824`；checkpoint `5ad8225`。
- 历史处置：已完成，发布集合已由 Task 12 重排。

### Task 3：严格且传输中立的 client protocol

- 原目标：实现版本化 Java wire 值对象与严格无状态 codec，覆盖结构错误、阶段身份、精确数值、资源
  descriptor 和 Java/TypeScript 共享 fixture。
- 保留结果：严格 codec、tagged literal、规范 decimal、稳定错误结构、transport-neutral descriptor 与共享
  fixture 均继续有效。
- 替代关系：当前 Assignment 必须显式携带 `desiredEntryId`、`definitionId`、`unitTargetRevision`、
  `runtimeInstanceId` 和 resolved config；不得由 carrier 私设字段或全局 target revision 推导 unit 身份。
- 证据：`5eef9843`、`1f91e1bc`、`773ae45b`、`01b9151c`、`6dfe8153`、`37bc52b6`、`f859b6f5`；
  checkpoint `b4c332da`。
- 历史处置：核心编码规则保留，公开字段和 unit fence 由 Task 7、10、12 重新验收。

### Task 4：framework-neutral TypeScript client core

- 历史原目标（已废弃，不得恢复）：实现无 DOM/React/Electron 依赖的 API、Scope/effect 所有权、client
  lifecycle actor 与资源缓存。
- 保留结果：纯 TypeScript API、只读 Scope/所有权和 wire interoperability 的结论继续有效。
- 替代关系：lifecycle actor、instance executor 与 resource cache 属于产品 runtime；Fibra 正式 npm 仅提供
  factory API 与 protocol，不实现 runner。
- 证据：`851c241b`、`e4ab90ac`、`f1893dba`、`1293efe2`。
- 历史处置：探索实现已撤回为产品侧责任；纯 API/protocol 由 Task 12 重新形成发布证据。

### Task 5：P0-A 浏览器技术栈可行性门

- 历史原目标（已废弃，不得恢复）：用 Web ESM loader、DOM/React adapter 和 Chromium fixture 验证资源
  完整性、Scope 清理、围栏与 CSP。
- 保留结果：证明产品侧浏览器实现可行；资源复核、清理和 CSP 场景可供产品仓复用。
- 替代关系：该 fixture 不能证明 Fibra 发布边界，也不再是 Fibra release gate；真实浏览器/Electron/React
  验收属于产品 P1，Fibra P0 只证明公开 external runtime SPI。
- 证据：`ef06d7d4`、`c572f279`、`ab2db0d2`。
- 历史处置：仅作历史探索证据，不计入当前 Fibra P0 release gate。

### Task 6：隔离的逻辑 PluginPackage 模型

- 原目标：以严格 `fibra-package.yaml` 建立逻辑安装单位与多 facet 模型，按受控文件树计算内容身份，并明确
  拒绝旧 `plugin.properties` 单 artifact 格式。
- 保留结果：`PluginPackage`、facet、精确逻辑依赖、内容摘要、双快照和 `PluginPackageStore` 原子事务均与
  当前架构一致。
- 替代关系（旧 `ArtifactRuntime/ExecutionRuntime` 双模型已废弃且禁止恢复）：当前由单一
  `RuntimeDriver`、完整 `DeploymentTarget` 与 generation/lease 消费，禁止恢复兼容 reader。
- 证据：`bd1747ca`（逻辑 package 模型）、`7d6aa3ea`（原子 package store）。
- 当前阶段：Green；最终集成和发行证据由 Task 9–13 统一关闭。

## Task 7：冻结单一 RuntimeDriver 契约与 compile-only walking skeleton

**当前阶段：Integrated。** 三 runtime compile-only walking skeleton、Node 显式 `definitionId`、
entry-key unit/definition 双射、跨 runtime DAG 与保存前零启动已在 Task 9 删除通用 attempt 类型后的
最终工作树重跑通过。

### 目标

- 一个 `RuntimeId` 只注册一个 `RuntimeProvider/RuntimeDriver`；
- provider 显式提供 `contractIdentity` 与纯 built-in package metadata，实际 built-in definitions 留在 driver；
- provider 是配置不可变、可顺序复用的 factory；每次 Host 创建独立 driver，provider 不缓存 Host/driver
  可变状态，Engine 独占 driver 的关闭权；
- `ExecutionTarget` 只表示 placement/capability，不作为 runtime key；
- Java 与 Node 私有 prepared 数据始终留在各自 driver；
- `DeploymentTarget` 纳入完整 `ConfigContextSnapshot`，删除 context-only 持久旁路；
- Engine 保留一个全局 DAG，并把确定性 runtime slices 交给 drivers；
- `ExecutionUnitKey` 使用全局 desired entry id；一个 entry 对应一个 unit/definition binding，同 facet 多 entry
  共享静态资源 lease 但生命周期独立；
- Fibra 正式模块不依赖浏览器、DOM、React、浏览器 URL/Fetch API、WebSocket 或 Electron；Java runtime
  为 ClassLoader 使用 `java.net.URL` 不属于浏览器边界泄漏；
- 先完成 browser/runtime 边界清理，正式 npm 只剩纯 API/protocol；
- 使用 `fibra-parity-tests` 中只依赖公开 SPI 的 external RuntimeProvider fixture 参与 compile-only 组合，
  不为测试 fixture 创建新的顶层 Maven 模块。

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
- 不归属 Task 7：通用 `AttemptRole/AttemptPhase` 已被后续架构复审否决，由 Task 9 硬切删除；
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeProvider.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriver.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeProvider.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeDriver.java`
- Modify: Node strict descriptor，新增必填 `definitionId`，禁止从 artifactId/facetId 隐式推导
- Rewrite: package 级 `BuiltInPluginPackage` + 多 `BuiltInFacet` 纯 metadata；每 facet 显式声明
  runtime/target/dependencies/capabilities/definitionIds，Java provider 私有持有实际 definitions
- Remove in the same Task 9 atomic checkpoint after algorithm migration: `ArtifactRuntime.java`、
  `ExecutionRuntime.java`、`PreparedArtifact.java`、`PreparedArtifactUpdate.java`、`ExecutionTargetPlan.java`、
  `ExecutionUpdate.java`、`ExecutionHandle.java`、`ArtifactResources.java`、`JavaArtifactRuntime.java`、
  `NodeArtifactRuntime.java`
- Modify: `pom.xml`，移除 `fibra-runtime-host`/`fibra-runtime-client`；测试 fixture 归入既有
  `fibra-parity-tests`，不增加非正式顶层模块
- Remove in Task 9: `fibra-runtime-host/`
- Remove in Task 9 after moving pure descriptors/fixtures: `fibra-runtime-client/`
- Create: `client/packages/client-protocol/`，从 `client-runtime/src/protocol.ts` 迁移纯 type/codec
- Modify: Java/TypeScript client protocol，资源与生命周期围栏使用创建 unit 时固定的
  `unitTargetRevision`，不把 current global target revision 当成 retained unit revision
- Delete: `client/packages/client-runtime/`、`client/packages/client-runtime-web/`、
  `client/packages/client-react/` 与旧 client risk gate
- Modify: `client/package.json`、`client/pnpm-workspace.yaml`、`client/pnpm-lock.yaml`、
  `client/tsconfig*.json`、`client/scripts/check-core-boundaries.mjs`
- Create: `fibra-parity-tests/src/test/java/.../ExternalFixtureRuntimeProvider.java`，只使用 Engine/bridge 公开 SPI
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/DeploymentTargetContextContractTest.java`
- Test: Engine、Java、Node 各所有者模块的 compile-only 契约测试，以及 `fibra-parity-tests` 跨 runtime 组合测试

### 步骤

1. 先写 TypeScript package boundary RED，并在既有所有者模块和 `fibra-parity-tests` 建立 compile-failure
   skeleton；不先删除旧包或修改 lockfile。
2. 将 transport-neutral `ResourceDescriptor(path,digest,byteLength)`、严格 codec，以及字段格式、path 规范化和
   canonical 编码测试迁入 Java/TypeScript `client-protocol`；`fibra-client.yaml` 的 entryModule/parser 属产品
   browser runtime，连同 URL/loader 实现删除，不在 external fixture 复制。实际资源字节的 byteLength/SHA-256
   复算、未声明文件拒绝和 target 已 save/promote 后每次 browser lifecycle prepare/装载重验属于产品 P1
   browser runtime 强制验收，不得误写成保存前 `RuntimeCandidate.prepareAsync` 或 protocol 已证明；
   fixture 只用公开 protocol 对象表达资源计划。
3. 迁移纯 npm API/protocol，再删除 browser/runtime/React 正式模块与旧风险门；重新生成 lockfile，并以
   boundary test 与 pack 内容证明只剩纯契约。
4. 写 Java RED 测试：
   configContext 改变 digest/revision；definition/unit 双射完整；同 facet 两个 entry 产生两个 entry-keyed
   units 且共享私有静态 generation；Java private prepared type 不出现在 Engine
   公共签名；snapshot canonical codec 区分缺失/null 并稳定处理 key/数字/Unicode；evaluator/binder/driver
   不能读取未进入 snapshot 的环境变量、系统属性、时间或随机值；built-in package 含两个 facets 时按
   facet identity、依赖和 capability 参与同一全局 DAG，singular package artifact、缺失 facet metadata 或
   跨 provider package fragment 直接拒绝。
5. 删除双 runtime SPI 的旧冻结测试，建立新 SPI、immutable plans、`HostTerminationPort` 与 Java/Node
   compile-only drivers；旧生产类型只在 Task 9 切换 `FibraEngine` 的同一原子 checkpoint 删除；
   `createCandidate` 无 I/O，`prepareAsync` 可 materialize 受信 definition，但不启动执行。
6. 用真实 Java/Node descriptor、ClassLoader/payload 和独立 external provider fixture 跑 compile-only walking
   skeleton；desired graph 必须包含完整 refs，记录启动计数为 0；facet DAG 确定性展开到 entry-keyed units，
   全局 dependency-first/reverse order 在分区后不丢失。
7. 全仓检查正式生产模块和 npm tarball 没有 client/Web 产品实现依赖。

### 门禁

```text
mvn -pl fibra-engine,fibra-runtime-java,fibra-runtime-node,fibra-parity-tests -am test
pnpm --dir client install --offline --frozen-lockfile
pnpm --dir client run build
pnpm --dir client run test
pnpm --dir client run lint:boundaries
pnpm --dir client --filter @sstlfsj/fibra-client-api pack
pnpm --dir client --filter @sstlfsj/fibra-client-protocol pack
if rg -n "com\\.microsoft\\.playwright|java\\.net\\.http\\.WebSocket|Electron|org\\.w3c\\.dom" fibra-engine/src/main fibra-client-protocol/src/main; then exit 1; fi
```

浏览器边界反向断言的生产源码结果必须为空；`lint:boundaries` 另外检查 npm 依赖、DOM lib、React、
Playwright、Electron、Blob 与浏览器全局。旧双 SPI 的全仓反向断言在 Task 9 原子切换后执行。`reactor` 和 Java
ClassLoader 所需 `java.net.URL` 不属于违规命中。

### 停止条件

- 需要 metadata bag、类型 token、runtime cast 或实现模块互相依赖；
- compile-only 测试必须启动插件、sidecar、session 或网络；
- context 改变实际 plan 却没有进入 target digest/revision；
- driver 需要用 artifactId/facetId 猜 definitionId 或把 artifactId 当 unit key；
- external execution fixture 必须引入浏览器 runner 才能完成编译；
- 删除 browser/runtime 模块后纯 API/protocol 独立 pack 无法使用；
- 新旧 SPI 需要跨越 Task 9 checkpoint、以兼容入口或发布物形式长期共存。

## Task 8：实现 Java/Node driver、generation 与资源所有权

**依赖：** Task 7 公共 SPI 稳定。由于真实 walking skeleton 必须消费本 Task 的 driver，Task 7/8 在同一工作树
原子批次内交错实现；验收顺序仍先关闭 Task 7 compile-only 门，再关闭本 Task 生命周期门。

**当前阶段：Integrated。** Java/Node 定向生命周期、真实 contribution admission、
ACTIVE 后失活替换、不可变 unit fence、显式 disposer 和资源 lease 门已在新的
candidate/current/retirement owner 模型上重跑通过。

### 目标

- Java 制品、ClassLoader、definition 物化、配置绑定和 Host execution 统一由
  `fibra-runtime-java` driver 拥有；
- Node 制品、sidecar 和 contribution 统一由 `fibra-runtime-node` driver 拥有；
- candidate/current/`RetirementBatch` unit 所有权显式；generation 在副作用前持有全部私有资源 lease；
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
- Java/Node 同 facet 多 desired entry 形成独立 unit/config/instance；共享 ClassLoader/payload 只有一份物理资源，
  任一 unit retire 不得提前释放仍被其它 unit 持有的 generation lease；
- 共享静态 generation 跨 attempt 复用：局部替换 A、保留 B 时，B 与公共依赖 C 的 ClassLoader/wiring 身份
  保持，A 复用同一 wiring；最后一个跨 attempt lease 释放前不得关闭；
- definition contract 无 I/O、线程、注册或 Context/Scope 能力；
- seal 后其它 driver 失败或显式 abort 时，sealed generation 释放全部未启动 unit leases 和私有资源；
- unit 只有在测试 harness 显式 promote 后调用 `reconcileAsync(operationId)` 才启动真实 Java instance/Node
  sidecar；save/promote 的顺序断言归 Task 9；
- 旧 invocation 尚未 drain 时旧 ClassLoader/payload close 次数为 0；
- Engine 并发首次启动订阅者收到同一精确 bootstrap view；长期启动协调不持有第一份 view、descriptor 或原始
  失败异常图；成功释放的 Java/Node unit 即使仍被 generation/command lane 引用，也不再持有 definition、
  ClassLoader、payload 或 resolved config；
- Node 远端 request 由 invocation Scope 作为 `DrainingDisposable` 持有；取消只发请求，远端终态前 route
  lease 不释放且 unit 不进入 stop；
- drain/stop 的重复、迟到回复按各自 lifecycleOperationId 拒绝；
- reverse dependency stop 与 resource retire；
- cleanup failure 保留 `RetirementBatch` 与对应 generations，并关闭 mutation gate；
- 真实 Scope disposer 失败即使被 core 普通关闭语义隔离，Host 的 `releaseScope` 仍必须识别并保留
  ClassLoader/payload owner；
- Node sidecar 在 start 成功后、contribution 注册完成前退出时必须封准入并返回 FAILED，不能发布死亡进程为
  ACTIVE，也不能因从未 ACTIVE 而触发 replacement 风暴；
- plugin 无法关闭 root scope，child scope 可关闭且 root 级联。

### 门禁

```text
mvn -pl fibra-api,fibra-core,fibra-runtime-java,fibra-runtime-node -am test
```

## Task 9：硬切 Engine、持久目标与全局生命周期

**依赖：** Task 7 公共 SPI 稳定、Task 8 driver 契约可消费。Engine 编译器与 driver 互相提供真实计划数据，
因此允许在同一未提交原子批次内交错实现；Task 9 checkpoint 前仍必须依次通过 Task 7、8 全部门禁。

**当前阶段：Integrated。** 旧双 SPI 与通用 attempt 投影已删除；持久目标、全局 unit DAG、角色专用
aggregate、结构化 failure subject、纯投影及可修正 bootstrap 已在同一最终工作树通过定向与全量门禁。

### 目标

- Engine 生产路径只使用 `RuntimeDriver`；
- `DeploymentTarget` 是 selections + desired + configContext 的唯一持久格式；
- Engine operation、candidate、current 与 retirement batch 分别持有自己的类型与 phase，不能交叉投影；
- 唯一顺序为 prepare → validate → seal → save → promote → close admission → drain → stop → activate → retire，
  但顺序只由 `EngineOperationStage` 编排，不写入不属于该 owner 的 phase；
- 保存后失败保留新 target 和真实 observed；save-unconfirmed 关闭 mutation gate；
- save-unconfirmed 同时关闭全部 managed contribution 准入并通过 `HostTerminationPort` 一次性请求受控 Host 退出；
- `ReconcileCurrent` 在同一 targetRevision 下替换失败 unit closure，不伪造 revision或并存第二 current。
- capability/runtime contract 改变计划时保守重编译全部 current units；仅可用性变化才 reconcile 旧 plan。
- capability snapshot 只作为每次编译冻结进 `RuntimeTargetSlice` 的输入：key 存在即可用，value 只作描述；
  active unit 校验其传递静态 facet 闭包，失败 attempt 不改变 current observation；
- `requestReconcile` 使用不可拆分的 `Set<RuntimeUnitFence>`；Engine 拒绝 missing/wrong/stale/retired fence，
  原子替换有效 FAILED 闭包，再唤醒仍匹配的非失败 units。
- `RuntimeUnitGeneration.fence()` 暴露创建时分配的不可变完整 fence；运行时不得在首次 reconcile 时才生成身份；
- retirement batch 具有独立 `batchId`，不能以 source attempt id 冒充 batch 身份；
- `FailureFact.subject` 使用带身份的封闭类型显式指向 Engine/target/operation/candidate/current/retirement/unit；
  unit 同时携带直接 owner id 与完整 fence，不从字段存在性推断，也不保留通用 kind + 可空 id 兼容形态；
- runtime SPI 统一适配同步抛错、null publisher、异步失败与 timeout；
- observation 采样与投影分离，`publish()` 是不调用 runtime、不改状态的纯函数；
- `RUNNING + PRESENT + BLOCKED` 表达可信持久目标无法 bootstrap 但可由完整 replacement 修正，
  不伪造 FAILED current。

### Files

- Rewrite: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTargetCodec.java`
- Rename: `EngineStateStore` → `DeploymentTargetStore`
- Rename: `FileEngineStateStore` → `FileDeploymentTargetStore`
- Delete: `ReplaceConfigContext.java`
- Delete after migration: `DeploymentManifest.java`、`DeploymentManifestCodec.java`
- Delete in this same checkpoint: `ArtifactRuntime.java`、`ExecutionRuntime.java`、`PreparedArtifact.java`、
  `PreparedArtifactUpdate.java`、`ExecutionTargetPlan.java`、`ExecutionUpdate.java`、`ExecutionHandle.java`、
  `ArtifactResources.java`、`PluginRuntimeAdapter.java`、`RuntimeResources.java`、`RuntimeResourceOwner.java`、
  `RuntimeResourceSnapshot.java`、`RuntimeResourceUpdate.java`、`JavaArtifactRuntime.java`、
  `NodeArtifactRuntime.java`、`fibra-runtime-host/`、`fibra-runtime-client/`
- Modify: root `pom.xml`，在同一 checkpoint 移除 `fibra-runtime-host` 与 `fibra-runtime-client` 的 module 和
  dependencyManagement
- Delete: `AttemptRole.java`、`AttemptPhase.java`、`AttemptSnapshot.java`、`EngineDiagnostics.phase`、无身份顶层
  units/retiring 投影及兼容 API
- Create/Rewrite: `EngineState`、`DurableTargetState`、`TargetConvergence`、`EngineOperationStage`、
  `EngineOperationSnapshot`、`CandidatePhase/Snapshot`、`CurrentPhase/Snapshot`、
  `RetirementPhase/BatchSnapshot`、`FailureFact/FailureSubject`、Engine diagnostics/snapshot
- Create/Rewrite internal aggregate: `CandidateAttempt`、`CurrentAttempt`、`RetirementBatch` 及统一 runtime invocation boundary
- Test: full save/failure/retry/restart-state matrix

### 必测场景

- same digest no-op；A→B→A revision；configContext A→B→A；
- compiledFingerprint 覆盖每个 provider contractIdentity、built-in metadata 与 capability snapshot；
- affected closure 比较旧、新完整 unit dependency map；同 facet 新增、删除或 gate 第二个 entry 时，依赖该
  facet 的 retained unit 也必须被替换；
- 依赖-only/contract-only facet 的精确传递闭包进入 consumer unit 编译输入身份；闭包中任一 package
  revision、facet/artifact identity 或依赖边变化先命中直接 consumer，再沿旧/新 unit DAG 扩散；不伪造 unit；
- package gate disabled 保存成功并撤销该 package 的全部 active units，但 raw desired entries 保留；缺少
  selection、revision/digest 不匹配或绕过 gate 的 active entry 在保存前拒绝；
- prepare/definition/binder/validator/plan 失败不保存；
- candidate PREPARING/VALIDATING/SAVING 时 current 仍是原 phase，current snapshot failure 显式归属 current，
  candidate 不被误标为失败；
- promote 后 candidate attemptId 成为 current attemptId，老 current attemptId 成为 retirement sourceAttemptId，
  retirement 获得独立 batchId，retained unit 只属于新 current；
- current 与 retirement 同时存在时 phase/observations 互不覆盖；`publish()` 不触发任何 runtime 调用；
- `reconcileAsync` 同步抛错、异步失败、null publisher 和 timeout 均映射到精确 current/unit；
- 同 generation 中一个 unit 被替换而另一个保留时，被替换 unit 的显式 disposer/私有 lease 必须完成，保留 unit
  的运行身份与 ClassLoader 不变；GC 回收只作补充观测，不作为释放门禁；
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
- bootstrap 的 package/provider/digest/prepare 失败且清理成功时无 current、target=`PRESENT`、
  convergence=`BLOCKED`、Engine=`RUNNING`，完整 replacement 可修正；清理失败则 `FAIL_STOP`；
- 旧格式直接拒绝。

### 门禁

```text
mvn -pl fibra-engine,fibra-runtime-java,fibra-runtime-node -am test
if rg -n "PluginRuntimeAdapter|ArtifactRuntime|ExecutionRuntime|RuntimeResources|ReplaceConfigContext|AttemptRole|AttemptPhase|AttemptSnapshot" fibra-engine/src/main; then exit 1; fi
```

搜索结果必须为空。

## Task 10：真实 Java + Node + external execution SPI 风险门

**依赖：** Task 9 全部门禁通过。

**当前阶段：Integrated。** 真实 package/target store、Java/Node/external 运行、围栏和
Engine current → 正式 `host.snapshot` → Java/TypeScript 契约纵切已在新 aggregate/投影 API 上重新取证。

### 目标

- 真实 Engine、文件 package/state store、动态 Java JAR、Node sidecar 和不发布的 external runtime fixture
  跑通一个逻辑 package；
- contribution kind/codec 只有一个 registry，远端调用只经 `RemoteContributionInvoker`；
- 外部 runtime fixture 只消费公开 SPI，不进入生产 composition root 或发行物。
- client 连接层只消费 current assignments，不泄漏 Engine operation/candidate/retirement 状态机；
- 从真实 Engine current 生成正式 `host.snapshot`，并经 Java codec 与 TypeScript 解码完成
  `ClientModuleDefinition` 绑定，不用手写 fixture 冒充 Engine 投影。

### Files

- Create/Modify: `fibra-bridge` contribution kind registry、unit-owned `ContributionAdmission` 与 LiteralValue
  codecs；不得依赖 Engine
- Create: `fibra-engine/.../RemoteContributionInvoker.java`，组合 bridge registry 与 `PublishedRuntime`
- Modify: `RuntimeHostServices`，只读暴露 host identity、共享 kind registry、unit admission factory 与 remote
  invoker；禁止暴露 directory/root Scope/domain
- Modify: Engine builder/bootstrap 保留唯一 `HostServiceRegistry` 构造面；启动时冻结到长期 RuntimeDomain，
  Java unit 只通过 scope 继承读取，Spring 不得保留未接线的空 bridge
- Modify: Java driver，在每个 unit scope 中发布该 unit admission 为 `ContributionServices.REGISTRAR`，并在
  drain 前同步封闭全部真实 route
- Modify: Node provider/driver，删除私有 `NodeContributionKindResolver`，使用共享 registry/admission；ACTIVE
  sidecar 失活必须发布 FAILED 并请求 replacement，不能原地复用
- Modify: Java/TypeScript client protocol，`Assignment` 必填自身 `unitTargetRevision`、`desiredEntryId`、
  `definitionId` 与规范 resolved config
- Extend: `fibra-parity-tests/src/test/java/.../verification/external/` with lifecycle、offline/online wake-up、
  resource lease
- Create: `fibra-parity-tests/src/test/java/.../verification/host/` Java/Node/external execution vertical tests；
  继续使用既有整仓测试模块，不增加独立 fixture 模块
- Test: cross-runtime package、call fence、offline PENDING、disable/upgrade、resource lease

### 步骤

1. 在 bridge 先写 duplicate kind、unknown codec、unit admission 并发 register/同步封准入、双 route drain RED
   测试，再实现唯一 registry/codecs/admission；公共 codec wire 只允许 `LiteralValue`。
2. 在 Engine 写 stale view/registration、closed admission、route drain 与 invoker codec RED 测试，再实现
   `RemoteContributionInvoker`。
3. Java/Node 先接入同一 unit admission 与 registry；真实 Java 插件必须从 Context service 取得 registrar，
   Node ACTIVE 后失活必须撤销 route、发布 FAILED 并由 Engine replacement。随后扩展 external fixture，但仍只
   依赖公开 Engine/bridge SPI；由 `requestReconcile` 把上线事件送回 Engine
   lane，不允许 fixture 自行启动 dependent runtime。
4. 以真实 package/state store 安装包含 Java、Node、external facets 的 package，验证保存前启动计数为 0。
5. 保存后启动真实 Java/Node，驱动 external fixture 从 PENDING 到 ACTIVE，再执行调用、升级、停用和退役。
6. 让 external fixture 发出 plan-affecting `requestRecompile`，证明不保存 target、revision/token 不变，Java、
   Node、external 全部 current units 获得新 runtimeInstanceId，旧 units 按反依赖 drain/stop/retire。
7. 注入 driver B seal 失败及已 sealed driver A abort 失败，证明零执行启动、资源现场保留、全局准入关闭；
   parity Host fixture 消费 `HostTerminationPort`，证明独立 notification lane 只触发一次并实际结束受管 Host
   lifecycle；不得在 Engine 内调用 `System.exit`。
8. 检查 fixture 只存在于 test/verification dependency graph，distribution 和生产 composition root 均不引用。

### 必测场景

- Java、Node、external facets 的精确跨包依赖；
- Java/Node contribution 真正启动并由同一 `PublishedRuntime` 调用；
- Java contribution 的 `providerInstanceId` 与 `PluginInstance.id()` 必须等于稳定 desired entry id；每代变化的
  `runtimeInstanceId` 只进入 fence/observed，不得暴露为 contribution provider 名称；
- external fixture 完成公开 RuntimeProvider 的 prepare/seal、activate、call、drain、stop、observed、
  config 隔离和 fence；它不生产 transport/client Assignment；
- 旧 host/view 与旧 runtimeInstance/operation 围栏全部拒绝；registration 只与当前 view 和稳定
  ContributionId 组成完整准入 tuple，数值本身允许在新目录重复；
- retirement 开始后，旧 unit 的多条 route 必须在同一次同步 admission close 中全部拒绝；已有调用仍能有界
  drain，插件/Scope/sidecar 不得在调用完成前释放；
- 动态 Java 插件只能通过 unit scope 中的同一 registrar 注册 contribution；Node 不得保留私有 kind resolver；
- ACTIVE Node sidecar 异常退出必须进入 FAILED、封准入并换新 runtimeInstance，不能永久保持 ACTIVE；
- target revision 推进但 external unit 被 retained 时，旧 `unitTargetRevision` + 同 runtimeInstance 的精确 tuple
  仍合法；unit 替换后该 tuple 立即失效；
- 同一 external facet 的两个 desired entries 使用不同 resolved config 时，必须形成两个独立 units、activation
  和 runtimeInstance；
- client API/protocol conformance 必须从正式 `host.snapshot` codec 结果仅取 Assignment 的公开字段，精确选择
  `ClientModuleDefinition`，为两个 entries 创建独立 modules，并在全新 session 重复绑定，证明不依赖 fixture
  私有字段或跨 session 实例缓存；该 snapshot 必须由真实 current 投影，candidate 不产生
  assignment，retirement 不作为新 assignment 重新下发；
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
mvn -pl fibra-bridge,fibra-parity-tests -am test
if rg -n "ExternalFixtureRuntimeProvider" fibra-*/src/main fibra-distribution; then exit 1; fi
```

第二条在生产源码与 distribution 中必须无命中。

## Task 11：迁移管理面与调用方，并验证 Host 重启恢复

**依赖：** Task 10 全部门禁通过。

**当前阶段：Restarted。** Registry/config、CLI/Spring/Boot 及其余下游已全部硬切角色专用公开投影；
两 Host 重启已覆盖无 current 的 `RUNNING + PRESENT + BLOCKED` bootstrap 修正路径。

### 依赖前沿

1. `fibra-config,fibra-registry`
2. `fibra-cli,fibra-spring,fibra-spring-boot-starter`
3. `fibra-example,fibra-benchmarks`
4. `fibra-parity-tests,fibra-plugin-archetype,fibra-plugins,fibra-distribution`

每个前沿必须独立全绿后才能进入下一个，不用兼容 API 让未迁移下游假绿。

### Files

- Rewrite: `fibra-registry` package/desired use cases and persistence projections
- Rewrite: Registry/Spring/CLI/parity/distribution 的 Engine snapshot 消费，只使用 role-specific
  snapshots、`TargetConvergence` 和 `FailureFact`，不复制第二状态机
- Modify: `fibra-config` definition refs、target construction and codecs
- Modify: `fibra-cli`、`fibra-spring`、`fibra-spring-boot-starter` composition roots
- Modify: examples、benchmarks、parity、archetype、plugins、distribution manifests and tests
- Delete: anonymous catalog、`InstallArtifact/UninstallArtifact`、old manifest readers/version solver and remaining
  `PluginRuntimeAdapter` call sites
- Create: `fibra-parity-tests/src/test/java/.../verification/host/HostRestartRecoveryTest.java`，复用 Task 10 的真实
  Host fixture，只依赖 reactor production modules 与 parity test fixture

### 步骤与前沿门

1. Registry/config 硬切 package 与 entry 管理语义，运行第一前沿；
2. CLI/Spring composition root 只注册 Java/Node providers，并分别消费 `HostTerminationPort` 协调 CLI runtime
   或 Spring application context 退出，运行第二前沿；
3. examples/benchmarks 不得保留旧 builder/catalog convenience，运行第三前沿；
4. parity/archetype/plugins/distribution 切换唯一 package 格式，运行第四前沿；
5. 最后在 parity Host fixture 运行两 Host 真实进程重启测试并检查残留 PID/process tree。

```text
mvn -pl fibra-config,fibra-registry -am test
mvn -pl fibra-cli,fibra-spring,fibra-spring-boot-starter -am test
mvn -pl fibra-example,fibra-benchmarks -am test
mvn -pl fibra-parity-tests,fibra-plugin-archetype,fibra-plugins,fibra-distribution -am test
mvn -pl fibra-parity-tests -am test -Dtest=HostRestartRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false
```

### 管理语义

- package install/upgrade/enable/disable/uninstall；
- install 只收 source 与显式 enabled，身份从严格 package 元数据取得；upgrade 只收 source，要求已有
  selection 并保留 gate，同 revision 的完整 target 可 no-op；
- uninstall 只删 selection，不物理删除不可变内容；任何 raw entry 引用（含 disabled）都必须先移除；
- 完整 deploy 接收已发布 selections、raw desired 与 configContext；增量用例以 durable targetRevision CAS
  派生完整 target，target 保存失败不删除已发布内容；
- audit succeeded 表示 Engine 接受命令，不代表 unit ACTIVE；失败/不确定保存按 EngineChangeException
  记录，成功按前后 target revision/digest 判定，no-op 与 ReconcileCurrent 记 NOT_APPLICABLE；
- desired entry upsert/enable/disable/move/remove；
- 插件自停用只作用于当前 entry；Java `ManagedPluginControl` 与 Node `fibra.disable` 走同一 Host→Engine gateway，
  以 `RuntimeId + unit key + unitTargetRevision + runtimeInstanceId` 拒绝 retiring 旧代次迟到请求；保存失败保留
  当前 unit/target 并允许重试，不得降级为 reconcile；
- package gate 关闭撤销全部 facets/entries；
- Registry observed 只投影 `CurrentAttemptSnapshot` 的 observations；operation/candidate/retirement 只作诊断，
  不改写 active package/entry 事实；
- CLI/Spring 只默认装配 Java/Node，产品 runtime 由产品 composition root 显式注册。

### 重启必测场景

- Host A 保存并运行真实 Java、Node、external fixture；
- 使用同一 package/state store 启动 Host B；
- target digest/revision 与 `ContributionId` 业务身份保持，host/runtime/operation/view/Node PID 更新；
  registration 属于新目录，数值允许重复，只有与 view 和 contribution identity 组成完整 tuple 才能准入；
- 核心 Host 门拒绝旧 `viewRevision + ContributionId + registrationIdentity` 完整调用 tuple、错误 registration
  和旧 `RuntimeUnitFence`；产品 session、ack、call result、resource request 由产品 runtime/gateway 单独验证，
  不得由 Fibra fixture 冒充；
- 动态 ClassLoader、Node sidecar 和 contributions 从持久 target 重建；
- 保存后 execution 失败的 current target 在重启时重新收敛；
- package 缺失/损坏时失败可观察且 target 不被改写；
- built-in 同 digest 恢复；provider 缺失、旧 digest 不再提供、metadata 与私有 definitions 不一致时失败可观察
  且 target 不被改写；清理成功时 Engine 保持管理 ready、无 current、投影 `PRESENT + BLOCKED`，
  再通过完整 replacement target 删除坏引用并收敛；发布二进制或声明变化必须改变 built-in
  digest/contract identity；
- save-unconfirmed 由 Engine/store 定向故障注入证明只以重启后的磁盘事实消歧；Host 进程门只验证确定落盘
  target 的恢复，不重复伪造 store 内部窗口。

### 停止条件

- 任一前沿只能靠旧 reader、重载、adapter 或双写编译；
- 重启恢复读取旧进程对象、复用旧 runtimeInstanceId 或手工修复 store；
- Registry 可绕过 package gate 直接管理裸 definition；
- distribution 仍生成 `plugin.properties` 单 artifact 布局。

## Task 12：发行、API、npm、CI 与独立消费者

**依赖：** Task 11 全部门禁通过。

**当前阶段：Integrated。** 28 个 Maven 制品、两个纯契约 npm 包、Engine 新公开状态 API、Java/npm
独立消费者、API baseline、制品内容与可复现门均已通过。本轮只完成本地冻结，不执行正式发布。

### 正式发布集合

Maven 精确发布 28 个 artifacts。各模块 POM 中显式的
`<maven.deploy.skip>false</maven.deploy.skip>` 是发布集合唯一真源，完整集合与维护规则见
`docs/release.md`；本计划不复制第二份模块名称清单。

- 保留 npm：`@sstlfsj/fibra-client-api`、`@sstlfsj/fibra-client-protocol`；
- 不发布：`fibra-runtime-host`、`fibra-runtime-client`、client runner/Web/React；所有测试 fixture 留在既有
  `fibra-parity-tests`、`fibra-distribution/src/test` 或 `client/tests`。

npm 两包版本必须与 Maven release version 完全一致；`-SNAPSHOT` 只允许 build/test/pack，不 publish。稳定版本
发布 `latest` tag，预发布版本使用其 qualifier tag。release workflow 使用 npm trusted publishing/OIDC 与
provenance；凭据或 provenance 不可用时阻断发布，不回退为仓库长期 token。

### 必须修改

- `scripts/verify-distribution.sh`
- `scripts/verify-reproducible-release.sh`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- Java `ApiSignatureBaselineTest`
- `fibra-parity-tests/.../ArchitectureBaselineTest.java`
- `fibra-distribution/src/test/consumers/engine-application/EngineConsumerTest.java`
- `fibra-distribution/src/test/consumers/engine-application/LifecycleConsumerTest.java`
- `fibra-distribution/src/test/consumers/{core,cli,spring-boot}-application` 与生成的 Java plugin consumer
- 已在 Task 11 前置完成：删除 `fibra-artifact/ArtifactPackage.java`、仅服务旧 `ArtifactStore` 的公共面、
  对应正向测试和 `build/plugin-package-assembly.xml`；保留 `PluginPackage` 对旧格式的负向拒绝测试
- Create: `client/api-baseline/client-api.d.ts`、`client/api-baseline/client-protocol.d.ts`
- Create: `fibra-distribution/src/test/consumers/runtime-provider-application/pom.xml` Java external
  RuntimeProvider 空仓消费者
- Create: `client/tests/package-consumer/`，只安装两个 npm tarball 的 TypeScript API/protocol 消费者
- Create: `scripts/verify-client-packages.sh`，在临时目录 pack、安装、编译 package consumer 并检查 tar 内容
- Modify: `scripts/verify-distribution.sh`，复用 POM 发布声明并检查 Maven JAR 内容；npm tarball 内容由
  `verify-client-packages.sh` 检查，不再设置重复门禁
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
- 分别在 distribution/client package 门中解压 Maven JAR/npm tarball，确认不含 fixture、runner、Web loader、
  React、Playwright 或 transport 实现；
- CI 分离 Maven 与 npm；Maven job 复用同一锁定环境执行可复现构建，空仓分发只在 push 与 release workflow
  中执行，PR 事件不重复执行。

```text
mvn -pl fibra-parity-tests,fibra-distribution -am verify
pnpm --dir client install --offline --frozen-lockfile
scripts/verify-client-packages.sh
scripts/verify-reproducible-release.sh
scripts/verify-architecture-boundaries.sh
# 代码与文档冻结、提交并推送后，由 push workflow 自动执行
```

## Task 13：全量验收、文档一致性和独立审查

**依赖：** Task 12 全部门禁通过。

**当前阶段：Integrated。** P0 已在当前工作树本地关闭冻结。role-specific phase、精确 failure subject、
纯投影、可修正 bootstrap、显式资源释放和真实连接纵切已在同一工作树重新取证；正式发布、合并与推送
仍按独立授权执行。

### 全量命令与证据

- 根 reactor `mvn clean verify`；
- Java/Node/external execution 真运行集成；
- Host 重启恢复；
- npm frozen-lockfile build/test/pack；
- Maven/npm 独立消费者；
- `scripts/verify-reproducible-release.sh`；
- `scripts/verify-architecture-boundaries.sh`；
- 代码、测试、文档与审查修订冻结、提交并推送后，由 GitHub Actions 在目标分支执行
  `scripts/verify-distribution.sh`；
- Java/TypeScript API baseline；
- Java Engine API baseline 中不存在 `AttemptRole`、`AttemptPhase`、`AttemptSnapshot`、
  `EngineDiagnostics.phase` 或兼容重载；
- `git diff --check`；
- 旧模型、兼容分支、产品浏览器代码的全仓搜索；
- 文档一致性审查；
- 架构、代码、发行三路独立审查实际 diff、测试输出和制品。

2026-09-20 本次关闭证据：

- Java 21 + Maven 3.9.9 的 `mvn clean verify`：43 个 reactor 模块全部成功；
- `fibra-parity-tests` 全量 139 项通过，含真实 Java/Node/external execution、连接纵切与 Host 重启恢复；
- `EngineAttemptStateModelTest` 覆盖 candidate/current/retirement 并存、精确 owner、bootstrap blocked 与
  fatal gate；Java/Node runtime 定向门通过；
- 插件切换资源门完成复验：Java unit 成功 stop 后立即断开 definition、instance、Scope、contribution、failure
  与 ClassLoader lease 等私有引用，局部替换继续保持无关 unit 的 ClassLoader/运行身份；50 轮真实 Java 替换后
  退役 descriptor/ClassLoader 不累积。Node 成功 stop 后断开 payload/session/process 与私有
  `startupTermination` 异常图，50 轮 start/stop/retire 后 payload、session 和存活 PID 均为零；JVM 类卸载只作
  补充 GC 观测，不替代显式 disposer/lease 完成这一权威释放门；
- Java API baseline 更新与复验通过，Registry、CLI、distribution、archetype 与 plugins 消费方完成硬切；
- `scripts/verify-client-packages.sh`：client API 4 项、protocol 8 项测试、两个 tarball 内容与离线独立消费者
  通过，下载 0 个包；
- `scripts/verify-reproducible-release.sh` 三轮构建及制品逐字节比较通过；
- `scripts/verify-architecture-boundaries.sh`、旧模型/旧 SPI 静态搜索和 `git diff --check` 通过；
- 架构、实现与发行边界复核未留下 P0/P1/P2 阻断项；复核中发现并修正 retirement unit owner 必须使用
  `batchId` 而非 `sourceAttemptId`，并补充回归测试；
- 根门禁暴露并修正 Node supervisor 在首次关闭副作用前尚未取得 shutdown ownership 的可重入竞态；
  `NodeSidecarTest` 全量 76 项与根 reactor 复验通过；
- `scripts/verify-distribution.sh` 按既定策略不在本地或 pull request 运行，只在后续获授权的 push/release
  workflow 执行；这不属于本次本地冻结或正式发布已完成的声明。

2026-09-19 状态模型重构前的历史证据（不构成本次关闭证据）：

- Java 21 + Maven 3.9.9 的 `mvn clean verify`：43 个 reactor 模块全部成功，耗时 2 分 15 秒；
- `scripts/verify-reproducible-release.sh`：三轮构建与 Maven 制品、发行 ZIP、目录树逐字节比对通过；
- `scripts/verify-client-packages.sh`：client API 4 项、protocol 8 项测试通过，两个 tarball 内容与独立消费者通过，
  下载 0 个包；
- `scripts/verify-architecture-boundaries.sh`、7 个脚本的 `bash -n`、workflow YAML 解析与 `git diff --check` 通过；
- 当时的文档一致性、架构与脚本/发行复核无剩余 P0/P1/P2；后续复审发现状态所有权缺口，
  该结论已失效；
- `scripts/verify-distribution.sh` 未在本地执行，按最终约束只由 push workflow 和正式 release workflow 执行。

最终至少执行：

```text
mvn clean verify
pnpm --dir client install --offline --frozen-lockfile
scripts/verify-client-packages.sh
scripts/verify-reproducible-release.sh
git diff --check
scripts/verify-architecture-boundaries.sh
# 上述结果和独立审查收口、提交并推送后，空仓最终门由 push workflow 自动执行
```

最终架构边界不靠历史文档 allowlist 维持。`verify-architecture-boundaries.sh` 只扫描当前生产源码、POM 和发布
入口，拒绝旧 runtime/package 类型、旧模块、旧清单和产品浏览器实现重新进入正式路径；模块依赖方向由
`ArchitectureBaselineTest` 证明，Java/npm 发布内容分别由 distribution/client package 门证明。归档设计和负向
拒绝测试不属于生产依赖，不纳入字符串白名单式门禁。搜索时排除 `target/`、`dist/` 与 flattened POM。

### 文档统一边界

所有 README、vNext、产品架构、Client Foundation 架构、计划、API/release 文档和来源审计必须一致：

- Fibra：SPI、协议、唯一 Engine/RuntimeDriver、Java/Node、发行契约；
- 产品：browser adapter、runner、transport、Web loader、renderer、React/Electron。

同时必须一致使用 `EngineOperation`、`CandidateAttempt`、`CurrentAttempt`、
`RetirementBatch`、`FailureFact`、`TargetConvergence` 的新模型；任一文档出现通用
`AttemptRole/AttemptPhase/AttemptSnapshot`、`EngineDiagnostics.phase` 仍作为现行实现，或把本地冻结写成
正式发布，均视为未完成。历史审计与禁止事项中的名称不表示现行能力。

### 完成条件

- 所有 Task 状态和本地验证 checkpoint 回填；commit、push 与正式发布按独立授权执行；
- 无未归属工作树改动；
- 全部阶段门有本次运行证据；
- role-specific 状态、结构化失败、bootstrap replacement 与 Engine→Java→TypeScript 连接纵切门全部通过；
- 三路独立审查无未关闭 P0/P1/P2；
- 只有此时才能把权威架构改为“已冻结”并把 P0 实现范围标记完成；正式发布、合并与推送按独立授权执行。

任一门禁未实际运行、证据范围小于声明范围、fixture 被生产依赖、发行集合与文档不一致，均不得宣告完成。
