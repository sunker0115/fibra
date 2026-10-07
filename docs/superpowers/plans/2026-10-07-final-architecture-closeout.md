# Fibra 最终架构缺口关闭与 main 合并计划

**目标：** 关闭 `d462dc7` 审查确认的实现、契约和验收缺口，通过最终工作树的测试与独立审查后合并 main；未经另行授权不 push、不发布。

**架构：** 以 2026-09-15 Client Foundation 权威规格为准；Engine 仍独占目标保存、状态分类和生命周期编排，driver 私有定义不进入 Engine。修复现有 owner 的职责，不引入新状态源、公共 API 或依赖。

**技术栈：** Java 21、Maven 3.9.9、Reactor、现有 Java/Node driver、TypeScript API/protocol、项目锁定的 Node/pnpm；执行环境偏差单独记录。

**授权：** 用户已要求开始修复并在通过验收后合并 main，随后明确“按最终架构来设计”，允许参考源项目。合并不等于发布或推送。

## 基线与模式分析

- 初始分支 `codex/fibra-ui-foundation`，HEAD `d462dc7`，工作树干净；本地 main 已包含 P0，尚缺 BOM、发行 ZIP 与关闭修复三个提交。
- 权威规格定义状态 owner、完整 target 和第 12 节冻结门；vNext 为既有内核记录，冲突处同步更正。
- 故障分类沿既有 `FailureFact`、`EngineChangeException`、`TargetSaveState`，不新增异常协议；关闭、准入和 termination 沿现有 owner。
- 测试使用真实文件 store、可控 provider 和现有公开 SPI seam；先确认 RED，再提交最小实现与 GREEN。单元成功不替代重启/连通/发行证据。
- 源项目只作有界参照：固定 Cordis/DSH 的失败、effect 和 Scope 所有权；不引入其补偿回滚或第二状态源。
- 已读取本地 DeepSeek Harness `c291e7961a515f6d7af9304e7fd1d257929aef26:vendor/cordis/src/fiber.ts`；tree 与既有证据 `745dea6282ba11f41fa0eb87171a4a56afe9730f` 一致。`_reload` 保留 `_error`，`await()` 等待 inertia 后重抛启动错误，`_unload` 等待已登记 disposer。采用错误与完成边界，Fibra 特有的 durable target/operation/unit 映射仍由 Engine 持有；不复制上游卸载日志隔离来掩盖 Fibra 的 lease 失败。

## 已确认设计

1. 不可可信读取/校验 target 时 `FAIL_STOP`，错误、准入与一次 termination 同步形成事实；后续 start 不重跑。
2. 普通确定性 activate 失败保留已保存 target、Engine RUNNING；current/convergence BLOCKED，operation FAILED、精确 unit failure。合法 PENDING 与此区分。
3. raw entry 引用的 selection 必须存在，包括 disabled entry；package gate=false 的合法 dormant 引用仍不 prepare。
4. retirement 的 drain/stop/retire 全部成功后才 reconcile 新 units；共享 retained generation 继续由当前 units 持有，不提前 retire。
5. built-in metadata/私有 definitions 不匹配在相关 target 的 prepare 阶段拒绝；可信持久目标恢复为管理 ready、PRESENT/BLOCKED，提交移除坏引用的完整 replacement 后恢复。静态结构非法仍拒绝。
6. 连通门拆成真实 Engine/Java codec 生成与 TS 正式 codec/factory 消费，二者在 CI/release 强制串接。Maven 不启动 pnpm，普通 npm 包门不依赖 Maven 输出。
7. Central 上传 ZIP 在同一次发布生命周期中、上传前与已验证 ZIP 比较；运行件版本一致，正式发布集合继续以各 POM 的 `maven.deploy.skip` 为唯一真源。

## 执行步骤与文件归属

### A. Engine 状态、完整目标与回收顺序

负责文件：`fibra-engine/.../FibraEngine.java`、`DeploymentPlanner.java` 及 engine 测试。

- [x] RED：真实损坏 target、load/token 错误、activate FAILED、合法 PENDING、disabled 缺 selection、retire 失败零新启动。
- [x] 最小实现：分开可信 load 与可修正 prepare；在 operation 终结处分类已冻结 observation；selection 校验先于 enabled 过滤；调整 retire 顺序。
- [x] GREEN：重复 start、显式 reconcile 新 identity、原 target 不回滚、邻近合法 gate、retained generation 与资源关闭。
- [x] 命令：Java21 Maven `-o -pl fibra-engine -am test`，保留定向 RED/GREEN 日志。

### B. built-in 可修正恢复

负责文件：`JavaBuiltInPackage.java`、`JavaBuiltInRecoveryTest.java`、`JavaRuntimeDriverTest.java`、`HostRestartRecoveryTest.java`、`HostRestartRecoveryProcess.java`。

- [x] RED：构造合法 metadata 与不匹配私有 definitions，prepare 拒绝且零启动；真实 Host 恢复进入 BLOCKED。
- [x] 最小实现：将 definitions 集合相等校验移入 driver 私有 prepare 使用路径，保持结构校验与可重复关闭。
- [x] GREEN：错误 target 内容/版本保持，管理门开放，完整 replacement 删除坏引用后收敛；合法 package 不受影响。

### C. Engine→Java codec→TypeScript 连通门

负责文件：新增 parity `ClientProtocolVerticalVerificationTest` 和 `client/tests/engine-connection/consume-snapshots.mjs`，必要 verification-only helper。

- [x] RED：缺真实 Engine fixture 时消费器明确失败；正式 dist 静态导入及身份/config 断言为硬门。
- [x] 真实 Engine 两个 entry 独立配置，覆盖 candidate 不泄漏、retirement 在途、retained identity、旧 route/fence 拒绝与新 session。
- [x] Java codec 输出至 `fibra-parity-tests/target/client-protocol-conformance/`。
- [x] GREEN：`node client/tests/engine-connection/consume-snapshots.mjs fibra-parity-tests/target/client-protocol-conformance`。
- [x] CI/release 在 Java 和 npm 构建完成后强制运行，不允许条件 skip。

### D. 发行字节与发布集合

负责文件：`.github/workflows/release.yml`、根 POM、distribution POM、`ArchitectureBaselineTest.java`。

- [x] 基于本地固定 Central 插件 0.9.0 核对最后上传时序和 skipPublishing；不能假定 deployAtEnd 能控制该插件。
- [x] RED：发布工具链、验证 ZIP artifact 传递、上传前 cmp 和正式集合映射门。
- [x] GREEN：配置锁定 Node/rg；同次 central-release verify 阶段 cmp；缺参考物、不同字节均在上传前失败；不执行真实上传。

### E. 文档、最终验证、独审与合并

- [x] 更新现有权威文档与发行文档，旧顺序直接更正；保留历史计划事实并指向本计划，撤回无证据的当前完成表述。
- [x] 按项目规则运行根 clean verify、client package/连通门、架构/API、三轮可复现；每轮 Maven 串行，避免 target 互相覆盖。
- [ ] 核对该最终提交 CI/空仓消费者证据。项目默认空仓门不在本地执行，若无法取得必须明确阻断，不假装通过。
- [x] 最强可用模型完成生命周期、持久状态、发行/连接和文档的交叉独立审查；已报告的 4 处文档矛盾全部修正并经复核关闭。测试运行证据仍由主线程最终集成确认。
- [ ] 保护用户变化；不自动 push、不触发发布；成功后记录 main 提交与实际验证范围。

## 停止条件与当前进度

- 同一问题两次失败而没有新证据，停止硬试并记录现场。
- 新公共契约、依赖、超出本任务的状态源或无法解释的资源 owner 分歧先停止该部分，继续独立工作。
- 目标只在全部验收及合并完成后关闭；局部测试通过不关闭总目标。
- 当前：A–D 与文档修正、交叉独审已完成；第二轮根 clean verify、npm 与真实连接消费门通过。三轮可复现构建与逐字节比较通过，可进入最终提交的 Linux CI/空仓门验收；尚未推送、合并或发布。

## 本轮验证证据

完整日志保存在 `/private/tmp/fibra-final-architecture-audit-20261007/`，测试代码随本计划一并交付。

- Engine：`engine-red.log` → `engine-green-targeted.log`；engine reactor 112 项通过；最后规划拒绝的 convergence 保持补丁已由本轮全仓运行覆盖。
- built-in：`builtin-red.log` 证明构造阶段异常阻断恢复；`builtin-release-green-2.log` 通过 Java runtime、真实独立 Host 重启/replacement、发行基线与新连通测试。
- 连接：`connection-red.log` → `connection-java-green.log` / `connection-client-green.log`；最后正向远端调用与精确拒绝断言已由 `builtin-release-green-2.log` 覆盖。
- 集成：`final-clean-verify.log` 首轮在 Registry 旧测试期待“启动失败但 upsert 成功”处失败；`retention-contract-red.log` 单独复现启动失败应为 BLOCKED 的旧断言。不能把此轮写为成功。
- npm：`final-client-packages.log` 通过 12 项测试、声明基线、严格 tarball 内容及离线独立消费者；架构边界脚本已通过。
- 本机 Java 21/Maven 3.9.9/pnpm 11.19.0；Node 22.22.2、ripgrep 15.2.0 与 CI 锁定 22.14.0/15.0.1 有偏差，最终 CI 环境仍须验证。
- 独立复核：三个 `gpt-6-astra / ultra` agent 交叉审查不属于各自实现范围的改动；Engine/builtin 与发行/连接未发现新 blocker，文档 4 项问题已关闭。未将审查结论冒充测试结果。
- 全仓最终 GREEN：`final-clean-verify-2.log`，44 模块 reactor 全部成功，包含真实 Java/Node runtime、Registry 失败审计、loader 释放、独立 Host 重启、API/架构基线和 archetype 独立构建。
- 最终连接消费：`final-connection-green.log` 通过，输入来自上述根 clean verify 本轮生成的六份正式 envelope。
- 可复现 GREEN：`final-reproducible-release.log` 三次构建全部成功，28 组主 JAR/sources/Javadoc、30 个 Maven POM、发行 ZIP 和目录树逐字节一致。
- 当前唯一未关闭合并门：最终提交的 GitHub Actions Linux CI 与空仓消费者。`docs/release.md` 将空仓门限定于 push/release workflow；用户级规范禁止自动 push，因此推送工作分支需要单独授权，不能在本地绕过该门。
