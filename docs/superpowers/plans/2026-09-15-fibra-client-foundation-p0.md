# Fibra Client Foundation P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Fibra 重构为拥有框架无关 client foundation 的跨执行域插件内核，并以真实浏览器中的 DOM/React 双适配、CLI/UI 同一控制面和完整生命周期围栏完成 P0 可行性验证。

**Architecture:** Fibra 保持唯一 Engine/Registry/desired target，把逻辑插件编译成 Java、Node、command 与 client facets。Maven 发布 Host 协议与协调器，npm 独立发布 framework-neutral client runtime 和可选 renderer adapters；control wire 只使用版本化协议与内容 descriptor，资源 bytes 由按 digest 寻址的独立数据面获取，不共享进程对象、交付 URL 或本地路径。

**Tech Stack:** Java 21、Reactor、Jackson 3、Maven 3.9.9、TypeScript、pnpm、Node test runner、esbuild（仅构建自包含 Web facet）、Playwright、React（仅参考 adapter）。

---

## 文件结构

新增或重构后的职责固定如下：

- `fibra-artifact/.../PluginPackage.java`：唯一逻辑安装包及严格 manifest 解析。
- `fibra-artifact/.../PluginFacet.java`：物理 facet 身份、runtime、target、payload 与 digest。
- `fibra-engine/.../ArtifactRuntime.java`：长期拥有 facet 探测、准备、检查和不可变制品资源。
- `fibra-engine/.../PreparedArtifactUpdate.java`：先登记后 I/O 的受影响制品闭包更新句柄。
- `fibra-engine/.../ExecutionRuntime.java`：按 desired facet target 编译、协调执行实例并发布 observed。
- `fibra-engine/.../ExecutionUpdate.java`：先登记后执行的 lifecycle 更新句柄。
- `fibra-runtime-host/`：统一承接内建 definition 与动态 Java prepared artifact 的
  `PluginInstance/RuntimeDomain` 执行细节。
- `fibra-client-protocol/`：Java wire 值对象与严格无状态 codec。
- `fibra-runtime-client/`：client facet 静态准备，以及 Host 侧 execution 注册、资源 broker、reconcile、调用桥和 observed。
- `client/packages/client-api/`：无 DOM/框架依赖的 TypeScript API。
- `client/packages/client-runtime/`：Scope/effect、session/instance lifecycle actor、资源 provider 与只提交已校验值的 single-flight cache。
- `client/packages/client-runtime-web/`：浏览器 ESM、size/digest 校验、verified cache loader 与 object URL 生命周期。
- `client/packages/client-react/`：React renderer adapter；不得被 core 反向依赖。
- `verification/client/`：DOM、React、跨语言和真实浏览器 P0 夹具。

## 阶段与 checkpoint 纪律

- Task 2–5 是 P0-A client 技术栈可行性门，只新增隔离模块和 verification；不得修改生产 target 格式或
  伪造第二 Registry。它不证明协议与真实 Engine 状态机已经契合。
- Task 6–9 建立最终模型、SPI 与 runtime 实现的隔离契约；每个受影响 Maven/npm 模块必须 RED/GREEN，但这些
  尚未接线的类型不是可发布结果。
- Task 10 是 P0-B1 Engine 核心风险门：先硬切持久 target、state store 与 Engine，再把真实 client runtime、
  浏览器和同一 `PublishedRuntime` 接到生产路径。该门失败时停止，不继续外围迁移。
- Task 11 是 P0-B2 管理面与调用方迁移，按模块依赖前沿形成可编译、可测试 checkpoint；Task 12 是 P0-B3
  真实 fs contract/provider 家族、跨包依赖与最终浏览器/CLI 证据；Task 13 才形成可发布 checkpoint。
- 分支 checkpoint 允许尚未迁移的 reactor 模块暂时不编译，也允许最终类型暂未接线；不允许旧新互转
  adapter、双格式读取/双写、第二 Registry/desired state 或运行时选择分支。失败时保留证据并停在最后一个
  通过的 checkpoint；不向用户现有运行目录写新格式。Git 承担回退，最终合并和发布必须全仓全绿且只剩新模型。

### 实施前耦合侦察

基于 `da48362` 的全仓调用点检查已经验证 Task 11 的迁移前沿，不再把“可以分批编译”当作纸面假设：

| 前沿 | 直接生产耦合 | 下游耦合与结论 |
|---|---|---|
| Registry/config | `fibra-registry` 集中在 `PluginRegistry`、`RegistrySnapshot`、`RegistryPluginState` 3 个类；`fibra-config` 没有直接引用待删 runtime/artifact 类型，但 `DesiredInputEntry` 契约需要同步硬切 | Registry 只依赖已经在 B1 硬切完成的 Engine；可用 `-pl fibra-config,fibra-registry -am` 独立变绿，不要求 CLI/Spring 同时编译 |
| CLI/Spring | `fibra-cli` 集中在 `CliHost`、`ProfileArtifactSource`、`FibraCli` 3 个类；`fibra-spring-boot-starter` 的旧模型装配集中在 `FibraAutoConfiguration`，`FibraEngineLifecycle` 只消费保留的 Engine 生命周期；`fibra-spring` 无生产直接引用 | 二者位于 Registry 下游，可在 Registry 变绿后用自身 `-pl ... -am` 验证，不需要提前迁移 examples/plugins |
| examples/benchmarks | 两个 example host 共 3 个生产调用类；`fibra-benchmarks` 的 `EngineTransactionBenchmark` 直接构造旧 `PluginCatalog` | 都是下游消费者，必须纳入 B2，不能留到 Task 13 全仓门禁才发现编译失败 |
| archetype/plugins/parity/distribution verification | archetype、正式插件 acceptance、parity 与 distribution consumer 的旧调用主要位于测试/验收 harness；正式插件实现没有直接依赖待删 Engine 类型 | 可在生产 composition roots 之后迁移；distribution 的仓外发布验证仍由 Task 13 最终关闭 |

每个前沿只用 Maven `-pl ... -am` 构建自身及上游，不使用 `-amd` 把尚未迁移的下游混入当前 checkpoint。
若任一前沿无法在不增加兼容 API、旧新转换或双格式 reader 的情况下独立变绿，立即停在上一个 checkpoint，
回到本计划修正依赖切分，不把整仓长期不可构建当作正常迁移状态。

## Task 1：冻结权威架构并删除冲突设计

**Files:**

- Create: `docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md`
- Modify: `docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md`
- Modify: `docs/superpowers/specs/2026-09-13-fibra-based-agent-product-architecture.md`
- Modify: `docs/superpowers/references/2026-09-13-architecture-source-audit.md`
- Modify: `README.md`

- [x] **Step 1: 将新规格设为 client foundation 唯一真源**

  新规格必须明确仓库/发布物/进程/协议/控制面五类边界、最终模块图、逻辑包模型、execution observed、
  P0 验收和停止条件。

- [x] **Step 2: 删除旧文档冲突内容并整段重写**

  删除“adapter/runner/传输归产品仓库”“第二个非 Agent 消费者后才下沉”“底座侧不再新增能力”和
  `client-web = Electron renderer` 等旧结论。保留 Electron + React 作为产品首个消费者，不再作为 Fibra
  契约。

- [x] **Step 3: 运行文档一致性检查**

  Run: `rg -n '产品仓库实现 host 侧 client|第二个非 Agent|底座侧不再新增能力|client-web \| Electron' README.md docs`

  Expected: 没有仍作为 client foundation 当前结论存在的匹配；只允许本任务的删除清单/检查命令、历史审计
  明确标注“已废弃”，以及产品 Session 章节保留的“通用 streaming contribution 仍等待第二个非 Agent
  消费者”独立边界。

- [x] **Step 4: 提交架构决策**

  ```bash
  git add README.md docs/superpowers
  git commit -m "docs: define Fibra client foundation architecture"
  ```

## Task 2：建立独立 Maven/npm 发布边界

**Files:**

- Modify: `pom.xml`
- Modify: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ArchitectureBaselineTest.java`
- Create: `fibra-client-protocol/pom.xml`
- Create: `fibra-runtime-client/pom.xml`
- Create: `client/package.json`
- Create: `client/pnpm-workspace.yaml`
- Create: `client/pnpm-lock.yaml`
- Create: `client/tsconfig.json`
- Create: `client/packages/client-api/package.json`
- Create: `client/packages/client-runtime/package.json`
- Create: `client/packages/client-runtime-web/package.json`
- Create: `client/packages/client-react/package.json`

- [x] **Step 1: 写 Maven 模块边界测试**

  在 `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ArchitectureBaselineTest.java` 增加断言：Java
  protocol/runtime 模块存在，现有纯 Java 模块不依赖 npm、React 或 Electron。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `mvn -pl fibra-parity-tests -am test -Dtest=ArchitectureBaselineTest -Dsurefire.failIfNoSpecifiedTests=false`

  Expected: FAIL，缺少新模块。

- [x] **Step 3: 新建最小模块与 workspace**

  Maven 聚合只包含两个 Java 模块；`client/` 使用独立 workspace，根 Maven 生命周期不调用 pnpm。

- [x] **Step 4: 首次解析依赖并提交锁文件**

  Run: `pnpm --dir client install`

  Expected: 生成 `client/pnpm-lock.yaml`；人工核对只含计划内依赖后纳入同一提交。

- [x] **Step 5: 运行 Maven 与 npm 边界测试**

  Run: `mvn -pl fibra-client-protocol,fibra-runtime-client,fibra-parity-tests -am test`

  Run: `pnpm --dir client install --frozen-lockfile && pnpm --dir client test`

  Expected: 两条命令独立成功。

## Task 3：实现严格且传输中立的 client protocol

**Files:**

- Modify: `fibra-client-protocol/pom.xml`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/ClientEnvelope.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/HelloIdentity.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/SessionFence.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/LifecycleFence.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/CallFence.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/ClientMessage.java`
- Create: `fibra-client-protocol/src/main/java/com/sstlfsj/fibra/client/protocol/ClientProtocolCodec.java`
- Create: `fibra-client-protocol/src/main/resources/com/sstlfsj/fibra/client/protocol/v1-fixtures.json`
- Test: `fibra-client-protocol/src/test/java/com/sstlfsj/fibra/client/protocol/ClientProtocolCodecTest.java`
- Test: `fibra-client-protocol/src/test/java/com/sstlfsj/fibra/client/protocol/ClientProtocolFenceTest.java`

- [x] **Step 1: 写严格 codec 失败测试**

  覆盖未知字段、重复字段、尾随 token、缺失/错误阶段 identity、未知 message type、协议版本不等于 `1`。断言错误码
  分别稳定为 `MALFORMED_MESSAGE`、`INVALID_IDENTITY`、`UNSUPPORTED_PROTOCOL`。另以合法 envelope 覆盖
  UTF-8 恰好 1 MiB/超 1 字节、64 层 JSON 容器嵌套的读写对称；不得用本身非法的 JSON 冒充
  大小门禁，也不得将标量叶子多算为一层。`LiteralValue.NumberValue` 覆盖任意精度、超过
  `Long.MAX_VALUE` 和极端指数的 NUMBER tag 规范字符串精确往返，并拒绝原生 JSON number、非规范
  十进制字符串、错误 tag 和多余字段；覆盖规范 decimal 恰好 1000 字符可往返、1001 字符编解码
  均拒绝，并证明十万字符非规范输入在 `BigDecimal` 构造前命中长度门禁；业务 ObjectValue 含
  `kind/value/values` 键时仍必须无歧义往返。
  `targetRevision`/`registrationIdentity` 必须覆盖 `Long.MAX_VALUE` 的 wire 字符串往返，并拒绝数字
  token、负数、前导零和越界字符串。

- [x] **Step 2: 写资源 descriptor 与控制/数据面 RED 测试**

  snapshot resource 只接受 `path/digest/byteLength`，`entryModule` 必须引用同一 assignment 的资源
  path；拒绝 raw URL、inline bytes、Host path、负数/非规范/越界 byteLength。codec 不创建可变 pending tracker，
  也不解析 URL、origin、HTTP 或 IPC。protocol v1 的 Web facet 是自包含单文件 ESM，但该代码闭包由 Task 5
  的标准构建器和产物检查证明，不让 Java codec 自写 JavaScript parser。资源实际读取和授权由 Task 5/9 的
  独立数据面验证。

- [x] **Step 3: 写分阶段身份 RED 测试**

  `client.hello` 只接受 `HelloIdentity(clientNonce)`；`host.welcome` 分配
  `SessionFence(hostInstanceId, clientExecutionId)`；生命周期消息必须使用
  `LifecycleFence(SessionFence, targetRevision, runtimeInstanceId, lifecycleOperationId)`；调用消息必须使用
  `CallFence(SessionFence, expectedViewRevision, registrationIdentity)`。跨阶段夹带或缺失字段都严格拒绝。

- [x] **Step 4: 实现不可变协议模型与 codec**

  envelope 只承载 `protocolVersion/messageId/type` 与对应 sealed message；不创建全字段可空的万能 identity。
  `targetDigest` 放在 snapshot/target 内容中，不能代替 lifecycle fence。按架构 §8 的完整 v1 schema 实现
  snapshot assignments/resource descriptors/contributions、调用 input/outcome、结构化 lifecycle failure 与 per-execution
  observed；这些是 P0 真实装载与调用所需的最小正式字段，不得留给 carrier 私设，也不得暴露可变 Jackson
  tree。`expectedViewRevision/registrationIdentity` 必须直接映射现有 `PublishedRuntime.invoke(String, long, ...)`。
  删除 codec 内的 `LifecycleFenceTracker`；A→B→A ack 所需的可变 pending 状态归 Task 9 的 Host execution owner。

- [x] **Step 5: 运行模块测试**

  Run: `mvn -pl fibra-client-protocol -am test`

  Expected: PASS。

## Task 4：实现 framework-neutral TypeScript client core

**Files:**

- Create: `client/packages/client-api/src/index.ts`
- Create: `client/packages/client-runtime/src/scope.ts`
- Create: `client/packages/client-runtime/src/runtime.ts`
- Create: `client/packages/client-runtime/src/protocol.ts`
- Create: `client/packages/client-runtime/src/resource-cache.ts`
- Modify: `client/packages/client-api/package.json`
- Modify: `client/packages/client-runtime/package.json`
- Create: `client/packages/client-api/tsconfig.json`
- Create: `client/packages/client-runtime/tsconfig.json`
- Create: `client/tsconfig.core.json`
- Create: `client/scripts/check-core-boundaries.mjs`
- Test: `client/packages/client-runtime/tests/scope.spec.ts`
- Test: `client/packages/client-runtime/tests/runtime.spec.ts`
- Test: `client/packages/client-runtime/tests/protocol-fixtures.spec.ts`
- Test: `client/packages/client-runtime/tests/resource-cache.spec.ts`

- [x] **Step 1: 写 Scope/effect RED 测试**

  测试父子 Scope 反向关闭、失败聚合、重复关闭共享同一终态、关闭后拒绝新 effect、listener/timer 必须通过
  effect 所有权撤销。child/effect 只有成功清理后才能脱离 owner；显式关闭失败后，父关闭仍必须聚合同一失败。
  插件可见的 root Scope 必须是运行时也没有 `close/dispose` 的登记视图，只有 instance actor 能关闭
  root；`child()` 返回插件可独立关闭的 owned child Scope。

- [x] **Step 2: 写 lifecycle RED 测试**

  以 snapshot 先建立 session/assignment 授权，再测试一次性 runtime instance 的
  `NEW -> PREPARING -> PREPARED -> ACTIVATING -> ACTIVE -> DRAINING -> DRAINED -> STOPPING -> STOPPED`。
  命令只在串行 mailbox 内提交状态；同 operation 重放共享终态，前序失败后已排队 phase 不执行，失败实例
  不得原地 prepare/activate 重试，stop 只负责尽力清理。命令不能凭 fence 创建未授权 instance；assignment
  撤销和 detach 必须退休实例及幂等账本，循环 100 次后状态数量不随历史增长。Host ack 的 A→B→A 围栏留给
  Task 9，不在浏览器 executor 复制 `begin/accept`。

- [x] **Step 3: 实现最小 core**

  `client-api` 导出 `ClientContext`、`ClientScope`、`ClientDisposable`、`ClientModule`、`HostCaller`、
  `ClientResourceProvider` 和结构化错误；`client-runtime` 负责 session/instance actor、状态机与按 digest
  合并并发读取、只接收“读取并校验完成”loader 回调的 cache，不出现 DOM、React、Vue、Electron、URL、fetch
  或产品类型；cache 不提供写入未验证 bytes 的 `put`。删除现有
  `scheduled/applied` 回滚和 client 侧 `begin/accept`，失败实例只保留真实失败终态。

- [x] **Step 4: 双语言读取同一 fixture**

  Java 与 TypeScript 必须读取 Task 3 的相同 wire fixtures；字段和值完全一致。共同 fixture 只冻结 wire
  语义，不要求不同语言共享解析器或运行机制；URL 已不属于该 fixture。

- [x] **Step 5: 运行依赖禁入与测试**

  Run: `rg -n "from ['\"](react|react-dom|electron)|\b(document|window|HTMLElement)\b" client/packages/client-api client/packages/client-runtime`

  Expected: 无匹配。

  Run: `pnpm --dir client typecheck:core && pnpm --dir client lint:boundaries`

  Expected: core 使用不含 DOM lib 的独立 TypeScript 配置编译；边界脚本同时检查源码 import、package
  dependencies/peerDependencies 和 workspace 依赖图，不能只靠文本搜索假装框架无关。

  Run: `pnpm --dir client test`

  Expected: PASS；包先生成 JavaScript 与 declaration，测试至少一次通过 package `exports` 导入，不得让
  Node/独立消费者直接执行 `src/*.ts` 或依赖 workspace 私有输出目录。

## Task 5：完成 P0-A client 技术栈可行性门

**Files:**

- Create: `client/packages/client-runtime-web/src/index.ts`
- Create: `client/packages/client-runtime-web/src/module-loader.ts`
- Create: `client/packages/client-react/src/index.tsx`
- Test: `client/packages/client-runtime-web/tests/module-loader.spec.ts`
- Test: `client/packages/client-react/tests/adapter.spec.tsx`
- Create: `verification/client/risk-gate/package.json`
- Create: `verification/client/risk-gate/pnpm-lock.yaml`
- Create: `verification/client/risk-gate/playwright.config.ts`
- Create: `verification/client/risk-gate/src/protocol-harness.ts`
- Create: `verification/client/risk-gate/src/index.html`
- Create: `verification/client/risk-gate/build.mjs`
- Create: `verification/client/risk-gate/fixtures/dom-plugin/index.ts`
- Create: `verification/client/risk-gate/fixtures/react-plugin/index.tsx`
- Create: `verification/client/risk-gate/tests/client-risk-gate.spec.ts`

- [x] **Step 1: 写 ESM/resource provider/cache/释放 RED 测试**

  loader 只接收 `ResourceDescriptor + ClientResourceProvider`。size/digest 不匹配不得 evaluate 或写 cache；同一
  digest 的串行和并发请求只调用 provider 一次，重复 prepare 与 A→B→A 复用 verified bytes。相同 operation
  重放幂等；替换必须等待旧 Scope 关闭并撤销 object URL、listeners、timers 和 contributions，bytes cache 不随
  instance stop 被误删。异步 activate 的 disposer 必须在 handler 调用前被 root 拥有，并发 root 关闭要等待
  handler settle 后恰好清理一次；同时插件只能关闭自己创建的 child Scope，不能在类型或 JavaScript
  运行时获得 root `close/dispose`，以免自等待。cache 的 single-flight loader 必须包含“provider 读取 +
  byteLength/digest 校验”，失败
  移除 pending 且不能提交未验证 bytes。入口 fixture 必须是自包含单文件 ESM；增加含相对 import、bare import
  和绝对 URL import 的反例，证明不会把 blob URL 的解析行为当成模块依赖系统。

- [x] **Step 2: 实现 framework-neutral Web loader 与两个 renderer probes**

  使用 esbuild 的 `bundle=true`、`format=esm`、`splitting=false` 生成 facet；构建后依据 metafile 与 TypeScript
  AST 断言产物不存在运行时静态、动态或 bare import，不自行实现模块解析器。loader 通过 provider 获取 bytes、
  校验 byteLength/digest，以 `text/javascript` 创建 Blob、dynamic import、调用 `activate(ClientContext)` 并持有
  disposer；它不解析 snapshot URL，不创建 React root。verification HTTP provider 才使用浏览器原生
  `URL/fetch` 并限制 scheme、origin、redirect 和 credentials。DOM probe 注册 mount factory；React probe 只通过
  `client-react` 注册 adapter，并把 React/adapter 依赖闭包编入该 probe 的单文件 bundle，不依赖运行时 bare
  specifier 解析或共享 framework instance。

- [x] **Step 3: 用无持久状态协议 harness 跑真实浏览器**

  Playwright 启动真实 Chromium，验证 Java/TypeScript 共用 fixtures、prepare/activate/drain/stop、刷新、
  A→B→A 迟到 ack、同 digest 下载合并和 DOM/React 共用同一 core。资源从真实 HTTP 数据面读取，但 endpoint
  只存在于 verification provider，不进入 snapshot。harness 不保存 desired、不提供 Registry、不实现版本选择，
  其结果不得替代 Task 10 的生产 Engine 证据。测试页必须发送显式 CSP：`script-src 'self' blob:` 且不允许
  `unsafe-eval`，并声明 capability `client.web.module.blob.v1`；去掉 `blob:` 后 capability 不成立、loader
  必须拒绝启动，不能静默放宽 CSP。

- [x] **Step 4: 运行 P0-A client 技术栈门禁并提交 checkpoint**

  Run: `pnpm --dir verification/client/risk-gate install`

  Expected: 生成并核对 `verification/client/risk-gate/pnpm-lock.yaml`；本地 client packages 只作为 P0-A
  `file:` 测试依赖，不作为 Task 13 独立消费者证据。

  Run: `mvn -pl fibra-client-protocol -am test`

  Run: `pnpm --dir client test && pnpm --dir client lint:boundaries`

  Run: `pnpm --dir verification/client/risk-gate install --frozen-lockfile && pnpm --dir verification/client/risk-gate test`

  Expected: 全部 PASS；`client-api/client-runtime/client-runtime-web` 不依赖 React，且生产 target 文件没有改动。

## Task 6：实现隔离的新逻辑 PluginPackage 模型

**Files:**

- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginId.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/FacetId.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/FacetRole.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ExecutionTarget.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginFacet.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackage.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/FacetDependency.java`
- Test: `fibra-artifact/src/test/java/com/sstlfsj/fibra/artifact/PluginPackageTest.java`

- [x] **Step 1: 写 canonical 三 facet 包测试**

  fixture 根只接受 `fibra-package.yaml`，字段为 `format/id/version/facets`。每个 facet 只接受
  `id/role/runtime/target/payload/dependencies/capabilities`，拒绝未知、重复、空、重复 facet id、越界路径、
  符号链接和 digest 不一致。每个 dependency 严格为 `pluginId/facetId`，不接受物理 `ArtifactId`、
  `packageRevision`、`versionConstraint` 或其它未实现字段。

- [x] **Step 2: 写旧格式拒绝测试**

  新 `PluginPackage.read` 对只有 `plugin.properties` 的旧单 facet 包必须失败；不得在新 API 内存在 fallback、
  迁移读取或双写。旧生产入口暂不连接新模型，并在 Task 11 删除；这不是可发布 checkpoint。

- [x] **Step 3: 实现 PluginPackage 与内容身份**

  `PluginPackage` 是用户安装单位；package/facet digest 由内容计算，不接受 manifest 自报覆盖。

- [x] **Step 4: 冻结 manifest 依赖引用边界**

  `fibra-artifact` 只解析和保存稳定的逻辑依赖引用，不读取 target、选择 package revision 或执行跨包解析；
  同包与跨包统一使用 `FacetDependency(pluginId, facetId)`。精确选择解析、缺失目标和环检测属于 Engine
  target compiler，在 Task 7 实现。P0 不加入版本范围解析、自动选版、多版本冲突仲裁或占位字段。

- [x] **Step 5: 运行隔离模型测试**

  Run: `mvn -pl fibra-artifact -am test -Dtest=PluginPackageTest -Dsurefire.failIfNoSpecifiedTests=false`

  Expected: PASS；新模型不调用旧 `ArtifactPackage`，也不包含兼容读取器。旧生产路径只在未发布的硬切工作
  包内暂时存在，Task 11 必须删除。

**检查点：** 已提交 `bd1747c`（`feat: add logical plugin package model`）。`fibra-artifact` 84 项测试通过，
规格复核与质量复核均通过；候选目录双快照、`fibra-content-v1` 摘要和旧格式拒绝已落地。

## Task 7：冻结生产硬切契约与 Engine 全量处置

**Files:**

- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ArtifactRuntime.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PreparedArtifact.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PreparedArtifactUpdate.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionRuntime.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionUpdate.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionHandle.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionTargetPlan.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ExecutionObservation.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PluginSelection.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTarget.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentTargetCompiler.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ResolvedFacetDependency.java`
- Create: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/BuiltInPluginPackage.java`
- Create: `fibra-config/src/main/java/com/sstlfsj/fibra/config/PluginDefinitionRef.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/DeploymentTargetContractTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/DeploymentTargetCompilerTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/RuntimeOwnershipContractTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/BuiltInPluginPackageTest.java`

- [x] **Step 1: 写逻辑 target 与部署代次纯契约测试**

  `DeploymentTarget` 的 canonical digest 只由 package selections 与 desired 决定；`targetRevision` 是独立的
  持久代次。纯值对象测试证明连续相同 digest 可判定 no-op，A→B→A 的两个 A digest 相同但由 revision
  分配器获得不同代次。真实保存前后失败矩阵移至 Task 10。

- [x] **Step 2: 写精确依赖编译 RED 测试**

  给定 canonical 动态包和一个已选中的最终格式合成 provider fixture，目标编译器必须把同包、跨包
  `FacetDependency(pluginId, facetId)` 解析为携带精确 `packageRevision/facetId/artifactId` 的
  `ResolvedFacetDependency`。缺失、未启用、重复边和依赖环必须在 target 保存前失败；编译器不解析版本
  范围、不下载或另选 package，也不维护第二份版本选择状态。

- [x] **Step 3: 写 execution observation 纯聚合测试**

  没有匹配 execution 时 client facet 聚合为 PENDING；不同 capabilities 的 execution 明细独立。Host
  start/deploy 不受离线影响的生产行为测试移至 Task 10。

- [x] **Step 4: 写 package gate 与 definition 归属值对象测试**

  `PluginDefinitionRef` 构造时必须同时具备 plugin/definition；内建 definitions 必须来自带稳定
  plugin/version/digest 的 `BuiltInPluginPackage`，且不能与动态包冲突。`DesiredInputEntry` 改造、package
  gate 撤销全部 executions/instances 及匿名 catalog 删除的生产行为测试移至 Task 10。

- [x] **Step 5: 冻结先登记后 I/O 的双更新契约**

  `PreparedArtifactUpdate` 必须在 prepare I/O 前创建并进入 ChangeSet；prepare 完成后，纯
  `ExecutionRuntime.compile` 产出 plan，`ExecutionUpdate` 再在执行 I/O、启动进程或发送远端命令前创建并
  登记。两个 `createUpdate` 自身均不得执行 I/O。测试取消、prepare 部分失败、adopt 前后关闭、不变资源借用、
  清理失败保留及 execution 清完前禁止 retire artifact。

- [x] **Step 6: 冻结 Engine 43 个生产类的最终处置**

  表中“保留”表示责任与公开语义保留，仍需回归；Engine 核心项必须在 Task 10 收口，其余“修改/替换”项
  必须在 Task 11 完成前全部收口，不能留下新旧模型转换层。当前 43 个文件均位于
  `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/`；Task 7 只冻结契约与新类型，实际迁移由 Task 8–11
  完成。

| # | 当前类 | 处置 | 最终责任 |
|---:|---|---|---|
| 1 | `ApplyDeployment` | 修改 | 完整 package selections + desired 的一次 CAS/ChangeSet |
| 2 | `ChangePhase` | 保留 | 准备、保存、收敛、退役等阶段 |
| 3 | `DeploymentArtifact` | 改名 `DeploymentPackage` | 逻辑包安装源 |
| 4 | `DeploymentManifest` | 修改 | targetRevision、targetDigest、package selections、desired |
| 5 | `DeploymentManifestCodec` | 修改 | 新格式严格读写，旧 format=3 拒绝 |
| 6 | `DesiredBindingException` | 修改 | 增加 plugin/facet/definition 定位 |
| 7 | `DesiredSourceMonitor` | 保留 | 只产生源变脏信号 |
| 8 | `EngineChangeException` | 保留 | 保存与发布失败事实 |
| 9 | `EngineCommand` | 修改 | 删除单 artifact 命令，加入逻辑包命令 |
| 10 | `EngineCommandLoop` | 保留 | 串行、取消隔离、关闭排空、observed 回序列 |
| 11 | `EngineCommandResult` | 保留 | 返回权威 view 与 warnings |
| 12 | `EngineDiagnostics` | 修改 | Host ready 与各 execution convergence 分离 |
| 13 | `EngineSnapshot` | 修改 | package/facet/execution 分层，资源投影单列 |
| 14 | `EngineState` | 保留 | client 离线不等于 Engine FAILED |
| 15 | `EngineStateStore` | 修改 | 唯一目标并持久推进 targetRevision；保留 save-unconfirmed 语义 |
| 16 | `EngineStateStoreException` | 保留 | 持久化错误责任 |
| 17 | `FibraEngine` | 修改 | 只保留统一协调、目标保存、发布、准入与阶段顺序 |
| 18 | `FileEngineStateStore` | 修改 | 新 envelope/revision；保留锁、force、原子 replace |
| 19 | `HostServiceRegistry` | 保留 | Host service 装配，不跨 wire 暴露对象 |
| 20 | `InitialArtifactSource` | 改名 `InitialPackageSource` | bootstrap 完整 package selection |
| 21 | `InstallArtifact` | 删除，以 `InstallPackage` 替代 | 删除物理 artifact 管理入口 |
| 22 | `MutationGateClosedException` | 保留 | 未确认保存/清理失败关闭变更门 |
| 23 | `PluginArtifactProbe` | 改名 `PluginPackageProbe` | 外层清单后逐 facet 静态探测 |
| 24 | `PluginCatalog` | 修改 | 仅 Host 本地 definition catalog |
| 25 | `PluginCatalogEntry` | 修改 | 仅 Host execution 内部 binder |
| 26 | `PluginInstanceSnapshot` | 以 `ExecutionObservation` 替代 | 通用 execution 身份与状态 |
| 27 | `PluginRuntimeAdapter` | 删除 | 由 artifact/execution 两契约替代 |
| 28 | `PublishedRevisionConflictException` | 保留 | viewRevision 准入冲突 |
| 29 | `PublishedRuntime` | 保留 | CLI/UI 共用的唯一 current/views/invoke 入口 |
| 30 | `PublishedView` | 修改 | 发布逻辑与 execution 诊断，routes 原子同步 |
| 31 | `RefreshDesired` | 保留 | 刷新唯一 logical desired |
| 32 | `ReplaceConfigContext` | 保留 | context 独立 CAS |
| 33 | `ReplaceDesiredGraph` | 修改 | 逻辑 definition ref；不能绕过 package gate |
| 34 | `RuntimeArtifactInspection` | 修改 | 静态 facet inspection，不承载执行句柄 |
| 35 | `RuntimeCatalog` | 删除 | 拆为 PreparedArtifact 描述与 Host PluginCatalog |
| 36 | `RuntimeDiagnostics` | 修改 | Host domain 明细与通用 execution observations 分离 |
| 37 | `RuntimeResourceOwner` | 删除 | 长期所有权并入 ArtifactRuntime |
| 38 | `RuntimeResourceSnapshot` | 修改 | 制品资源事实及 package/facet provenance |
| 39 | `RuntimeResourceUpdate` | 改名 `PreparedArtifactUpdate` | 一次受影响制品闭包更新 |
| 40 | `RuntimeResources` | 改名 `ArtifactResources` | 制品准备、采用、保留、退役 |
| 41 | `TargetSaveState` | 保留 | 保存事实 |
| 42 | `UninstallArtifact` | 删除，以 `UninstallPackage` 替代 | 撤销逻辑插件所有 facets |
| 43 | `UnknownRuntimeException` | 修改 | 区分 artifact runtime 与 execution target；离线不是未知 runtime |

- [x] **Step 7: 运行契约与分类门禁**

  Run: `mvn -pl fibra-engine -am test -Dtest=DeploymentTargetContractTest,DeploymentTargetCompilerTest,RuntimeOwnershipContractTest,BuiltInPluginPackageTest -Dsurefire.failIfNoSpecifiedTests=false`

  Expected: 新契约测试 PASS；43 类均有对应实施项，不存在未归类的生产类。旧生产路径尚未引用新类型，
  也不存在旧新互转 adapter；Task 13 前不可发布。

**检查点：** 已提交 `22fc84a`（`feat: freeze deployment runtime contracts`）。Task 7 契约测试 23 项、
`fibra-engine` 201 项和全 reactor 486 项测试通过，两路独立复审均无剩余 P0/P1/P2；生产硬切仍按
Task 8–11 的阶段门继续，不能把本检查点视为可发布状态。

## Task 8：迁移制品面与 Java/Node/client 静态准备

**当前状态：进行中。** `PluginPackageStore` 原子事务子阶段已完成，store 定向测试 15 项、
`fibra-artifact` 全模块 99 项测试通过；Step 1 仍待补 Engine 侧“任一 facet inspect 失败不发布 package”的
协调测试，Step 2–4 尚未完成。只有通过对应门禁并形成独立检查点后才能勾选。

**Files:**

- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackageStore.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackageInstallTransaction.java`
- Create: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackageRecord.java`
- Modify: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ManagedPluginPackage.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaArtifactRuntime.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaFacetDescriptor.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaFacetDescriptorReader.java`
- Create: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaFacetGraph.java`
- Modify: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaClassIndex.java`
- Modify: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaClassSpace.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeArtifactRuntime.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeFacetDescriptor.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeFacetDescriptorReader.java`
- Modify: `fibra-runtime-client/pom.xml`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientArtifactRuntime.java`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientPreparedArtifact.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/ArtifactResources.java`
- Test: `fibra-artifact/src/test/java/com/sstlfsj/fibra/artifact/PluginPackageStoreTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/ArtifactResourcesTest.java`
- Test: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaArtifactRuntimeTest.java`
- Test: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaFacetGraphTest.java`
- Test: `fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodeArtifactRuntimeTest.java`
- Test: `fibra-runtime-client/src/test/java/com/sstlfsj/fibra/runtime/client/ClientArtifactRuntimeTest.java`

- [ ] **Step 1: 写 package 原子事务与受影响闭包 RED 测试**

  `PluginPackageStore` 一次提交一个 package record 并导出受管 facet records；任一 facet stage/digest/inspect 失败不发布
  package。覆盖候选取消、partial prepare、adopt、retained failure、无关资源身份保持。

- [ ] **Step 2: 实现 ArtifactRuntime 与 ArtifactResources**

  在不连接旧 Engine 的隔离实现中迁入现有 `PluginRuntimeAdapter.probe/inspect`、owner/update 的资源责任。
  保留创建即登记、prepare 失败可关闭、反向释放、失败依赖保留和 cached terminal；新路径不产生
  `RuntimeCatalog`。旧实现只供尚未切换的生产路径编译，Task 11 在最后一个调用点迁移后删除，不写互转
  adapter。

- [ ] **Step 3: 迁移 Java/Node 并实现 client 静态描述**

  Java 的 class space/index/loader 算法保留，Node 的 payload 准备保留。二者从可信外层 `PluginFacet` 和
  Engine 已解析的 `ResolvedFacetDependency` 获得 plugin/facet/package revision/dependency wiring；
  runtime-local descriptor 只保存 entrypoint、contribution 声明等本地信息，不再声明逻辑 id、version 或
  requires。`JavaFacetGraph` 只消费精确解析结果，不解析版本；Node sidecar 启动、initialize 与 contribution
  注册不留在 artifact 面。旧 reader/manifest/graph 暂时只供尚未切换的生产路径编译，并在 Task 11 与旧
  adapter 一次删除。`ClientArtifactRuntime` 从受管 client facet 读取并校验 entry module、资源
  path/digest/byteLength 及 payload digest，建立 Host 内容存储并产出携带 execution target 和
  capability 条件的 `ClientPreparedArtifact`；descriptor 不含 URL/inline bytes，prepare 不连接
  transport、不要求 execution 在线，也不启动浏览器，失败必须在 target 保存前暴露。

- [ ] **Step 4: 运行制品面门禁**

  Run: `mvn -pl fibra-artifact,fibra-engine,fibra-runtime-java,fibra-runtime-node,fibra-runtime-client -am test`

  Expected: PASS；新制品路径拒绝旧单包格式，且不启动 Node sidecar、不连接 client transport、不等待
  client execution。旧生产路径的全仓删除由 Task 11 验证。

## Task 9：抽离执行面并实现 Host 侧 client execution runtime

**Files:**

- Modify: `pom.xml`
- Create: `fibra-api/src/main/java/com/sstlfsj/fibra/ScopeView.java`
- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/Scope.java`
- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/Context.java`
- Modify: `fibra-api/src/main/java/com/sstlfsj/fibra/InvocationContext.java`
- Modify: `fibra-core/src/main/java/com/sstlfsj/fibra/internal/DefaultScope.java`
- Modify: `fibra-core/src/main/java/com/sstlfsj/fibra/internal/DefaultContext.java`
- Test: `fibra-core/src/test/java/com/sstlfsj/fibra/runtime/ScopeOwnershipBoundaryTest.java`
- Create: `fibra-runtime-host/pom.xml`
- Modify: `fibra-parity-tests/pom.xml`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientExecutionRegistry.java`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientExecutionSession.java`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientExecutionRuntime.java`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientTransport.java`
- Create: `fibra-runtime-client/src/main/java/com/sstlfsj/fibra/runtime/client/ClientResourceProvider.java`
- Test: `fibra-runtime-client/src/test/java/com/sstlfsj/fibra/runtime/client/InMemoryClientTransport.java`
- Test: `fibra-runtime-client/src/test/java/com/sstlfsj/fibra/runtime/client/InMemoryClientResourceProvider.java`
- Create: `fibra-runtime-host/src/main/java/com/sstlfsj/fibra/runtime/host/HostExecutionRuntime.java`
- Create: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeExecutionRuntime.java`
- Modify: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ArchitectureBaselineTest.java`
- Test: `fibra-runtime-client/src/test/java/com/sstlfsj/fibra/runtime/client/ClientExecutionRuntimeTest.java`
- Test: `fibra-runtime-client/src/test/java/com/sstlfsj/fibra/runtime/client/ClientInvocationFenceTest.java`
- Test: `fibra-runtime-host/src/test/java/com/sstlfsj/fibra/runtime/host/HostExecutionRuntimeTest.java`
- Test: `fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodeExecutionRuntimeTest.java`
- Test: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/HostJavaRuntimeCompositionTest.java`

- [ ] **Step 1: 写 per-execution observed RED 测试**

  两个 capabilities 不同的 execution 连接同一 Host；只接收匹配 facets，状态与失败独立，任一断开不修改
  desired target。

- [ ] **Step 2: 写调用围栏 RED 测试**

  `client.call` 携带错误 host/view/registration identity 时拒绝；正确调用必须进入同一
  `PublishedRuntime.invoke` 并持有原 route lease。

- [ ] **Step 3: 写资源数据面授权 RED 测试**

  resource provider 只允许当前 `SessionFence + targetRevision + runtimeInstanceId` 已获 snapshot assignment 授权
  的 descriptor；错误 session、已撤销 assignment、path/digest/byteLength 不一致均拒绝。endpoint 或临时签名
  轮换不修改 prepared artifact、targetDigest 或 targetRevision；相同 digest 可以由不同 execution-local adapter
  交付相同 bytes。

- [ ] **Step 4: 写 drain/stop RED 测试**

  stop 前必须封闭 action、等待在途调用和 matching lifecycle ack；断开不能冒充 stop 成功。

- [ ] **Step 5: 实现 runtime 与测试内存 transport/provider**

  transport 只负责 message carrier；resource provider 只按已授权 descriptor 读取 bytes；session state
  machine、身份、能力匹配、deadline 和 observed 全由 `ClientExecutionRuntime` 拥有。A→B→A 的 pending
  lifecycle ack tracker 在此实现并随 session/runtime retire，不放回 codec。两个 InMemory 实现都只放在 test
  sources，不能被生产 composition root 依赖或作为正式 adapter 发布。

- [ ] **Step 6: 隔离实现 Host 与 Node execution runtime**

  先硬切 Host Java root Scope 所有权：`Context.scope()` 返回不实现 `Scope/AutoCloseable`、无
  `close/closeAsync` 的稳定 `ScopeView` facade，`openChild()` 才返回插件可关闭的 owned `Scope`。
  RED 测试必须证明 root view 无法在类型或运行时下转为可关闭 Scope，child 可独立关闭，
  并覆盖原 `Plugin.start(context -> context.scope().closeAsync())` 自等待反例；不保留旧的可关闭
  `Context.scope()` 公开签名或运行时代理。

  `HostExecutionRuntimeTest` 使用 Engine 契约 fixture 同时覆盖内建 definition 和动态 Java prepared artifact，
  证明二者只由同一个 Host runtime 执行 `PluginDefinition.Prepared/PluginInstance/Scope/RuntimeDomain` 生命周期；
  该测试不得给 `fibra-runtime-host` 增加对 `fibra-runtime-java` 的依赖。组合测试放在
  `fibra-parity-tests`，以真实 `JavaArtifactRuntime` 输出驱动 `HostExecutionRuntime`。
  `fibra-runtime-java` 不再创建第二个 execution runtime。Node sidecar 启动和 contribution 注册只由
  `NodeExecutionRuntime` 拥有。此任务不接线 `FibraEngine`；Engine 内旧逻辑的删除、新 runtime 接线与统一封
  准入/排空/stop/retire 顺序全部在 Task 10 的核心路径完成。

- [ ] **Step 7: 锁定 runtime 模块依赖方向**

  扩展现有 `ArchitectureBaselineTest`：`fibra-engine` 不得依赖 `fibra-runtime-host`、`fibra-runtime-java`、
  `fibra-runtime-node` 或 `fibra-runtime-client`；四个 runtime 模块单向依赖 Engine SPI，且不得互相依赖。
  `fibra-runtime-java` 只实现 artifact SPI，`fibra-runtime-host`、`fibra-runtime-node` 分别实现 Host、
  sidecar execution SPI，`fibra-runtime-client` 同时实现 client artifact 与外部 client execution SPI；
  `fibra-runtime-host` 必须直接依赖
  `fibra-engine`。具体实现由 Task 10 的 verification Host 和 Task 11 的 CLI、Spring starter 等
  composition root 装配；Maven reactor 不得出现循环依赖。

- [ ] **Step 8: 运行执行面与模块边界测试**

  Run: `mvn -pl fibra-runtime-host,fibra-runtime-java,fibra-runtime-node,fibra-runtime-client,fibra-parity-tests -am test`

  Expected: PASS；Engine 对 runtime 实现零依赖，runtime 实现单向依赖 Engine SPI，reactor 无环。

## Task 10：完成 P0-B1 Engine 核心风险门

**Files:**

- Modify: `pom.xml`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentManifest.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentManifestCodec.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/EngineStateStore.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FileEngineStateStore.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/EngineSnapshot.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/EngineDiagnostics.java`
- Modify: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PublishedView.java`
- Replace: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeResources.java` with `ArtifactResources.java`
- Modify: `fibra-config/src/main/java/com/sstlfsj/fibra/config/DesiredInputEntry.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/DeploymentTargetPersistenceTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineExecutionLifecycleTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/ClientOfflineConvergenceTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/PackageGateReconciliationTest.java`
- Create: `verification/client/package.json`
- Create: `verification/client/pnpm-lock.yaml`
- Create: `verification/client/playwright.config.ts`
- Create: `verification/client/host/pom.xml`
- Create: `verification/client/host/src/main/java/com/sstlfsj/fibra/verification/client/EngineGateHost.java`
- Create: `verification/client/src/web/index.html`
- Create: `verification/client/tests/engine-gate.spec.ts`

- [ ] **Step 1: 先硬切持久 target 与 revision 语义**

  `DeploymentManifest`/codec/state store/File store 只接受最终 target 格式，持久化严格分离 `targetDigest` 与
  `targetRevision`。复用并迁移现有锁、force、原子 replace、崩溃恢复、部分采用和 save-unconfirmed 测试；
  新测试覆盖连续相同内容 no-op、A→B→A 分配新代次、旧 `format=3` 拒绝、保存确认不明时关闭变更门。

- [ ] **Step 2: 把最终双更新契约接入真实 FibraEngine**

  使用最终格式的 synthetic canonical 包和 provider fixture，不迁真实 fs provider，也不引入 shell。Engine
  必须在 prepare I/O 前登记 `PreparedArtifactUpdate`；prepare 完成后执行纯 target compile，再在任何
  execution I/O 前登记 `ExecutionUpdate`；静态编译成功后才保存 target，再收敛 Host/Node/client。
  client facet 必须由正式 `ClientArtifactRuntime` 产生 `ClientPreparedArtifact`，不得由 verification Host
  或 Web carrier 临时拼装静态描述。
  测试覆盖非法 client entry module/resource descriptor/digest 在 execution 离线时仍导致 prepare 失败且不保存 target、
  其它 prepare/compile 失败不保存、保存后 execution 失败保留新 desired、client 离线为 PENDING 且 Host
  ready、资源 endpoint/签名变化不改变 targetDigest/targetRevision、package gate
  close→drain→stop→retire、执行和在途调用清完前禁止 retire。旧 Engine 协调路径从此
  不再可达；不得用临时 Engine、第二 Registry、协议 harness 或旧新 adapter 代替。

- [ ] **Step 3: 用真实 browser execution 撞击生产 Engine**

  `verification/client/host` 是只依赖最终 Maven 模块的 Java fixture Host；Playwright 连接实际
  `FibraEngine`、`ClientArtifactRuntime`、`ClientExecutionRuntime` 和 `PublishedRuntime`。用 synthetic provider 的确定性 contribution
  验证保存 target、离线 PENDING、连接 ACTIVE、descriptor 经真实资源数据面取得 verified bytes、相同 digest
  重连不重复下载、DOM/React 共用 core、CLI-like caller 与 UI 调用同一 route、
  A→B→A 迟到 ack、drain/stop 和重连收敛。根 `pom.xml` 在现有 `fibra-benchmarks` 之后直接增加
  `<module>verification/client/host</module>`；Host POM 以 `<relativePath>../../../pom.xml</relativePath>` 继承根
  parent，并继承 `maven.deploy.skip=true`，不创建第二个 Maven aggregator，也不作为发布物。此处不冒充
  真实 provider 或独立发布物证据。

- [ ] **Step 4: 运行 P0-B1 核心门禁并提交 checkpoint**

  Run: `mvn -pl fibra-engine,fibra-runtime-host,fibra-runtime-java,fibra-runtime-node,fibra-runtime-client,verification/client/host -am test`

  Run: `pnpm --dir verification/client install`

  Expected: 生成并人工核对 `verification/client/pnpm-lock.yaml`，只包含计划内依赖。

  Run: `pnpm --dir verification/client install --frozen-lockfile && pnpm --dir verification/client exec playwright test tests/engine-gate.spec.ts`

  Expected: 核心受影响模块和真实浏览器 PASS；生产 Engine 只有最终 target/双 runtime 路径。尚未迁移的 Registry、
  CLI、Spring、examples、plugins 和 distribution 可以暂时不编译，不能用兼容层把它们伪装成已迁移。

## Task 11：完成 P0-B2 管理面与调用方硬切

**Files:**

- Delete: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ArtifactPackage.java`
- Replace: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ArtifactStore.java` with `PluginPackageStore.java`
- Replace: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ArtifactInstallTransaction.java` with `PluginPackageInstallTransaction.java`
- Rename: `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/ArtifactRecord.java` to `FacetArtifactRecord.java`
- Delete: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PluginRuntimeAdapter.java`
- Delete: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeResourceOwner.java`
- Delete: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeCatalog.java`
- Replace: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/RuntimeResourceUpdate.java` with `PreparedArtifactUpdate.java`
- Delete: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/InstallArtifact.java`
- Delete: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/UninstallArtifact.java`
- Rename: `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentArtifact.java` to `DeploymentPackage.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapter.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaArtifactGraph.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaArtifactRequirement.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaManifestReader.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaPluginManifest.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/Version.java`
- Delete: `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/VersionConstraint.java`
- Delete: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaArtifactGraphTest.java`
- Delete: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaArtifactPackageTest.java`
- Delete: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapterTest.java`
- Delete: `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/VersionTest.java`
- Delete: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodePluginRuntimeAdapter.java`
- Delete: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeManifestReader.java`
- Delete: `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodePluginManifest.java`
- Delete: `fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodePluginRuntimeAdapterTest.java`
- Delete: `fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeResourceOwnerTest.java`
- Modify: `fibra-registry/src/main/java/com/sstlfsj/fibra/registry/`
- Modify: `fibra-cli/src/main/java/com/sstlfsj/fibra/cli/`
- Modify: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/boot/`
- Modify: `fibra-parity-tests/`、`fibra-example/`、`fibra-plugin-archetype/`、`fibra-plugins/`
- Modify: `fibra-benchmarks/`
- Modify: `fibra-distribution/src/main/distribution/plugins/`，其中 `fibra-fs`/`fibra-fs-local` 的外层逻辑包
  留给 Task 12 作为真实 provider 迁移证据
- Modify: `verification/distribution/`
- Test: `fibra-registry/src/test/java/com/sstlfsj/fibra/registry/LogicalPackageManagementTest.java`
- Test: `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/BuiltInPackageMigrationTest.java`

- [ ] **Step 1: 逻辑化 Registry、config 与内建包**

  install/enable/disable/upgrade/uninstall 全部面向 `PluginId` 和 package gate；实例配置只使用
  `PluginDefinitionRef`。删除 `FibraEngine.Builder.catalog` 与匿名 definition 入口，composition root 改为
  `BuiltInPluginPackage`；内建包不可安装/升级/卸载但可统一启停。package gate 关闭在同一 ChangeSet 撤销
  全部 facet executions 与 Host instances。

- [ ] **Step 2: 按依赖前沿迁移调用方**

  顺序固定为：Registry/config → CLI/Spring composition roots → examples/benchmarks → parity/archetype/plugins
  与 distribution。除 Task 12 保留的 `fibra-fs`/`fibra-fs-local` 外层包外，本阶段完成所有调用方和包资产的
  机械迁移。每一前沿先写或迁移测试，再让该前沿全部模块通过；未进入前沿的模块可以暂时红，但不能增加兼容 API、
  旧新互转、双格式 reader 或旁路 Registry。每个支持 client facet 的生产 composition root 必须同时显式
  装配 `ClientArtifactRuntime` 与 `ClientExecutionRuntime`；不得把 verification Host、Web carrier 或测试内存
  transport 带入生产依赖。

  Run: `mvn -pl fibra-config,fibra-registry -am test`

  Run: `mvn -pl fibra-cli,fibra-spring-boot-starter -am test`

  Run: `mvn -pl fibra-example,fibra-benchmarks,fibra-plugin-archetype,fibra-plugins,fibra-parity-tests -am test`

  Expected: 每条命令在对应前沿迁移后独立 PASS；不得靠同时修改尚未进入当前前沿的下游源码取得绿色。

- [ ] **Step 3: 删除旧模型、旧 reader 与旧测试**

  最后一个调用点迁移后立即执行本任务 Files 中的删除/替换/改名。runtime-local descriptor 删除逻辑
  `id/version/requires`，外层只认 `fibra-package.yaml`；旧 Java version solver、旧 Node/Java manifest reader、
  单 artifact command 和 `plugin.properties` reader 不保留。已有测试能迁移到最终语义的必须迁移，只有纯旧
  契约测试才删除。

- [ ] **Step 4: 运行 P0-B2 调用方门禁并提交 checkpoint**

  Run: `mvn -pl fibra-engine,fibra-registry,fibra-runtime-host,fibra-runtime-java,fibra-runtime-node,fibra-runtime-client,fibra-cli,fibra-spring-boot-starter,fibra-parity-tests,fibra-example,fibra-plugin-archetype,fibra-plugins,fibra-benchmarks -am test`

  Run: `rg -n 'ArtifactPackage|PluginRuntimeAdapter|RuntimeResourceOwner|RuntimeResources|RuntimeCatalog|InstallArtifact|UninstallArtifact|DeploymentArtifact|JavaArtifactGraph|JavaArtifactRequirement|JavaPluginManifest|VersionConstraint|NodePluginManifest' fibra-* verification --glob '*.java'`

  Run: `rg -n 'definitionName' fibra-config/src/main/java/com/sstlfsj/fibra/config/DesiredInputEntry.java fibra-registry/src/main/java`

  Expected: 已进入迁移前沿的测试 PASS；两次搜索均无生产匹配，不存在旧新模型并行分支。真实 fs 行为和完整
  distribution 门禁由 Task 12–13 关闭。

## Task 12：完成 P0-B3 真实 fs、浏览器与 CLI/UI 验收

**Files:**

- Modify: `verification/client/host/pom.xml`
- Create: `verification/client/host/src/main/java/com/sstlfsj/fibra/verification/client/ClientP0Host.java`
- Create: `verification/client/tests/client-p0.spec.ts`
- Modify: `fibra-plugins/fibra-plugins-fs/`
- Modify: `fibra-distribution/src/main/distribution/plugins/fibra-fs/`
- Modify: `fibra-distribution/src/main/distribution/plugins/fibra-fs-local/`
- Modify: `fibra-parity-tests/pom.xml`
- Create: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/scenario/ClientFacetLifecycleTest.java`

- [ ] **Step 1: 构造依赖真实 fs provider 的 full-stack probe package**

  canonical 动态逻辑包包含 host-java、command、client facets，并显式依赖 target 已选中的真实
  `fibra-fs-local` provider facet；target 同时选择 `fibra-fs` contract 逻辑包，provider 对 contract facet 的
  依赖也必须解析到精确 package revision。Host contribution 调用真实 provider，CLI 与页面都只通过
  `PublishedRuntime` 准入。缺失、未启用、重复边和依赖环在 target 保存前被拒绝。shell/subprocess 不进入本
  验收，除非真实 fs 实现源码证明存在不可移除的运行依赖。

- [ ] **Step 2: 运行真实浏览器最终场景**

  使用 Task 10 已核对的锁文件执行 `pnpm --dir verification/client install --frozen-lockfile`。Playwright 启动
  真实 Chromium，连接真实 Java Host，验证 PENDING→ACTIVE、DOM/React 渲染、CLI/UI 调用同一真实 fs
  contribution、刷新重连和当前 target 收敛；真实 HTTP 资源 provider 不把 endpoint 写入 snapshot，同一 digest
  在重复 prepare、重连和 A→B→A 中只下载一次，size/digest 错误时不 evaluate。

- [ ] **Step 3: 验证停用、升级、围栏与进程清理**

  覆盖旧 action `stale/revoked`、已接受调用排空、A→B→A 迟到 ack、无关 Java/Node/client execution 身份
  保持；浏览器关闭、client detach、Host SIGTERM 后不得残留 Host、Node、浏览器或临时资源进程。

- [ ] **Step 4: 运行 P0-B3 门禁**

  Run: `mvn -pl fibra-parity-tests,verification/client/host -am test`

  Run: `pnpm --dir verification/client install --frozen-lockfile && pnpm --dir verification/client test`

  Expected: 真实 provider、真实 Engine、真实浏览器和 CLI/UI 共用 `PublishedRuntime` 的最终场景 PASS；synthetic
  fixture 不计作本阶段通过证据。

## Task 13：发行、API、文档和独立审查

**Files:**

- Modify: `fibra-distribution/pom.xml`
- Modify: `verification/distribution/`
- Modify: `scripts/verify-distribution.sh`
- Modify: `docs/api/README.md`
- Create: `docs/api/fibra-client-protocol-public-signatures.txt`
- Create: `docs/api/fibra-runtime-client-public-signatures.txt`
- Create: `docs/api/fibra-runtime-host-public-signatures.txt`
- Modify: `README.md`
- Modify: `docs/release.md`
- Modify: `docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md`

- [ ] **Step 1: 独立消费者验证**

  npm packages 先生成并仅发布 JavaScript、declaration、license 与必要 metadata，`exports/types/files` 全部指向
  包内产物，不指向 `src/*.ts` 或 workspace target。从安装后的 Maven/npm 发布物构建，不读取 reactor
  classpath、workspace link、源码路径或全局包；Node 20 和真实浏览器都至少一次通过正式 package entry 导入。

- [ ] **Step 2: 执行全仓门禁**

  Run: `mvn clean verify`

  Run: `pnpm --dir client install --frozen-lockfile && pnpm --dir client test`

  Run: `pnpm --dir verification/client test`

  Expected: 全部 PASS。

- [ ] **Step 3: 检查变更纯度**

  Run: `git diff --check`

  Run: `rg -n 'ArtifactPackage|plugin\.properties|第二个非 Agent|产品仓库实现 host 侧 client' README.md docs fibra-* verification client`

  Expected: 生产代码零匹配；文档只允许明确标注用途的 pre-P0 基线、旧格式拒绝规则、删除清单和独立的
  streaming 下沉边界匹配。不存在兼容代码、双格式、旁路 Registry 或第二 desired state。

- [ ] **Step 4: 回填 P0 产物分层与后续能力账本**

  以实际制品、公开 API 签名和测试证据回填架构第 14.1 节；明确哪些 verification fixture 只留在测试范围。
  第 14.2 节继续保持未实施 backlog，不得把正式 transport、协议演进、多 execution、安全、版本 solver、
  marketplace 或额外 UI adapter 冒充为 P0 已完成，也不得在本任务顺手实现。

- [ ] **Step 5: 独立架构与代码审查**

  审查必须检查模块依赖、协议围栏、资源所有权、失败事实、浏览器真实证据和发行隔离；发现 P0/P1 必须修复
  并重跑相关门禁后才能完成目标。审查还必须核对架构第 14 节：生产 composition root 未引用验证夹具，
  P0 完成声明未吞并后续 Fibra backlog，上层产品职责也未被误列为 Fibra 欠账。
