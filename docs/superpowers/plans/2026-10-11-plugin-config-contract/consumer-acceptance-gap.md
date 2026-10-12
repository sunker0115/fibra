# Task6 消费者验收差距（只读有界分析）

2026-10-12；仓库 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`。这不是正式终审，也不是新的 GREEN 收据。本次没有运行 Maven/Node、修改源码或文档、访问 Tela 主树、派发实现 agent。只新增此临时交接报告。Task5 正在修改，相关作者/装配项以下一律标为待验证，不作为已确认缺陷。

依据：权威 spec `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` §11；plan 的 consumer-preparation、consumer-impact、implementation-plan；Task4 归档的 result、spec-review-r1、quality-review-r1；实际测试方法与消费者入口。下列源码路径均相对 Fibra 根；Tela 路径均相对只读隔离副本 `/private/tmp/tela-config-consumer.7vs6zn1a/tela`。

结论：核心单元/Engine 证据已覆盖大部分 owner、规则与失败事实，Task6 不应复制整套状态机。最小增量是沿现有正式仓外 LifecycleConsumerTest、Java 作者 fixture、Tela renderer harness 和真实错误投影补集成断言；然后用本轮正式 Maven/npm 字节执行并保留收据。明确缺口主要为并发 CAS、Engine→Java 的 validator 一次、跨入口安全投影、真实 client 产品执行与数值消费边界。

## 1. 三层证据界限

| 层 | 当前可复用证据 | 能证明 / 不能证明 |
|---|---|---|
| 纯值、资源与 Engine 单元 | config 资格/精度/safety；artifact 固定资源状态机；EngineConfigurationContractTest 的 probe provider 与注入句柄 | 能证明共同结构门、精确候选选择、失败字段及父 owner；不能把 probe prepares=0 当真实 Java binder/Node spawn/client import 计数 |
| 实际 runtime | JavaRuntimeDriverTest 的生成 JAR；NodeRuntimeDriverTest/NodeProcessUnitTest；JavaBuiltInRecoveryTest；Tela 现有 renderer-load VM | 能证明各 runtime 当前路径/真实资源行为；低层直接调用 driver 不经过 Engine schema 门；VM fixture import 也不等于产品 agent-view 或 Desktop 实机 |
| 正式制品/仓外消费 | scripts/verify-distribution.sh；distribution consumers；scripts/verify-client-packages.sh；隔离 Tela | 只有本轮发布/消费仓库与源/制品 SHA、命令 nativeExit、XML 一起绑定，才能证明当前最终组合。旧 GREEN、源码 reactor 和旧 SNAPSHOT 不替代此层 |

Task4 最终核心 reactor、独审和之前入口回归的准确边界见归档；本报告未重跑，不改写其结论。Task4 最后一行 owner 排队修复之后核心完整回归通过；更早入口运行不得标作同次最终全入口运行。

## 2. CC01–12 双向覆盖索引

| CC | 实际已找到的断言/入口 | Task6 或后续尚需证据 |
|---|---|---|
| CC01 | EngineConfigurationContractTest.queryIsColdFencedDisabledAndPureAndUsesExactFacet:29（同名多 facet、冷查询、不变 view、probe create/prepare=0）；RuntimeDriverEngineTest.configurationQueryApiExistsWithoutExecutingPluginCode:80 与 builtin query；JavaRuntimeDriverTest 生成 ObservedEntrypoint 查询断言 effects=0，真实 prepare 后 effects>=3；NodeRuntimeDriverTest.definitionQueryReadsDescriptorWithoutStartingOrPreparingNode:66 | 将配置 query 接到正式 Java 作者和真实 Node fixture，核静态初始化/definition/binder 和 spawn 不发生。TelaClientCatalogIntegrationTest 现有恶意 import 脚本只能证目录/Host，不证实际模块执行 |
| CC02 | PluginConfigurationResourceTest 的 changed/truncated/replace、ABSENT 后新增、metadata-only/toBuilder/restart；FixedPluginConfigurationResourceTest；BuiltInPluginPackageTest 的 schema/profile/facet association、顺序、外部 Map；JavaBuiltInRecoveryTest.legacyIdentityRecoveryPreservesBytesAndRequiresExplicitCasReplacement:117（PRESENT+BLOCKED、旧字节不变、错 CAS 拒绝、显式 replacement） | 不重造资源/摘要算法测试。正式作者源→JAR→包根字节、正式包 revision 与查询 revision 需 Task5/6 收据；若只补 schema-only 升级，复用现有 lifecycle fixture |
| CC03 | EngineConfigurationContractTest 的未知/坏/缺合同与 RuntimeDriverEngineTest.configurationPreflightRejectsUnknownMissingAndInvalidBeforeCandidateCreation:125；Engine 输入直接来自 resolvedConfig | 当前 CLI/Registry 测试多数只补了合法 schema fixture，并非相同 schema/effective 的跨入口失败/成功矩阵。沿真实入口补共同输入断言；Java/Node/client 各至少一条实际消费，不必全词汇笛卡尔积 |
| CC04 | EngineConfigurationContractTest.inactiveEntriesDeferRulesButPackageGatePreservesEvaluation:109、expressionsValidateResolvedValuesAndEveryRepeatedInstance:131、ifAndLiteralReachOnlyResolvedSchemaAndDoNotSupplyDefaults:295 | 延期与求值算法无需消费者重造；选择性补 enable 失败时旧 disabled target 字节/selection 仍在的公开路径断言（现 inactive test 末尾只 assertThrows） |
| CC05 | candidateRevisionMustSupplyRulesAndRejectedRevisionRemainsPublished:70：R1 integer=8 成功、R2 maximum=2 拒绝；NOT_SAVED、旧 current/target、create/prepare/save 不增、R2 包仍存在、读到 R2；schemaOnlyUpgradePreflightsRetainedEntriesButPreparesOnlyAffectedUnits:92：retained 仍预检，无关 unit 保留 | 正式 Registry upgrade 接同一反例。明确缺口：LifecycleConsumerTest:119 只有先成功后顺序 stale CAS，未见两个并发提交共享 expectedRevision 的测试；需两个调用同时接纳、最多一次 save/一次 revision 前进，失败者不 prepare |
| CC06 | ConfigSchemaTest.validatesEveryTypeWithoutCoercionOrDefaultMutation:38；Engine 的 if/literal/no-default；core PluginInstanceLifecycleContractTest.preparedMountValidatesOnceWithoutCreatingThePluginUntilMount:91，validator 返回 normalized，prepare=1/mount 仍1/update 才2 | core 直接 definition.prepare 不证明新增 Engine schema 门没有重复调用真实 runtime validator。加一个 Engine→真实 Java driver→现有作者 fixture 的可见计数和归一化返回断言；query/结构拒绝=0、一次合法启动=1。null/省略/开放/关闭对象复用 config 已有断言，不扩产品字段 |
| CC07 | EngineConfigurationContractTest:167 query 双失败保留 owner/driver/store/原 operation；:197 五发起路径×ordinary/cleanup 字段矩阵；:245 取消后实际读/关；:348 兄弟续关；:382 异步 close 与缓存终态；EngineReadAttemptTest:21 10000 同步失败有界栈；artifact close-before-read/read-close 同步 | 核心 owner 证据充分，无需在消费者创建第二 I/O 注入框架。正式消费者保留正常关闭和真实 Node PID/session 回收；对注入失败与物理 I/O 证据分别引用，不宣称完成不可制造的真实 OS 故障 |
| CC08 | 上述 Engine 五路径精确 saveState；EngineAttemptStateModelTest.explicitSaveFailureRemovesCandidateAndPreservesCurrentAndGates:298、saveUnconfirmedEntersFailStopWithoutPromotingCandidate:323；Registry 原测试的 save-state 注入；R2 published 而 target NOT_SAVED 已明确断言 | 真实 Registry/CLI/Tela 投影至少保留 NOT_SAVED 及现有 SAVED 未 ready、UNCONFIRMED/恢复路径回归。不要求为 schema 重写原保存模型；正式升级失败必须检查包发布与 target 保存分离 |
| CC09 | ConfigSchemaTest:60 精确数字（含9007199254740993）、:85 长度、:106 typed map/有限分支、:131 拒绝词/引用、:228 boolean/nullable；ConfigSchemaBudgetTest 全量预算/组合/菱形/深度/精度边界；DefinitionConfigDocumentTest 延期与全局预算分离 | 不重跑独立 probe 冒充正式消费。真实 Node JSON.parse 的 Number 表示边界尚需观测；client 必须经 actual create 验 tagged NUMBER 保持 decimal 字符串。Node 与 client 保证不同，不能强行改 wire 或 Number 化 |
| CC10 | ConfigSchemaTest.projectsSafeStaticPathsWithoutSecretKeysValuesOrLibraryCauses:164 含动态键+数组；EngineConfigurationContractTest:131、148、167、331、362 覆盖未知键/值/恶意异常、安全 message/cause/suppressed/diagnostics；归档 Task4 日志 canary 扫描 | 尚缺本轮实际 CLI 输出、Registry audit、Tela errorBody/HTTP 投影和捕获日志的 canary 集成断言。需查询 source 不混值，且授权 snapshot 仍可显式读取值。不可把整个 EngineChangeException.view 序列化后扫描，作为禁止 view 含配置的假要求 |
| CC11 | LifecycleConsumerTest.provesThePublicRegistryLifecycle:147 已实际 Java/Node 配置回显、install/upsert/update/disable/enable/upgrade/remove/reinstall、Node PID/session 和 Java loader；EngineConsumerTest 正式12插件实际功能；TelaJavaDefinitionConsumptionTest 四个真实作者 JAR 目录与 prepare；renderer-load 的 SourceTextModule/Blob 实际 fixture import | Task5 后迁移这些包根合同再跑正式制品；实际产品 agent-view import/create/activate/stop 和 scope/URL/root 清理未有本轮证据。手工 ACK 绝对不能替代。OpenAI prepare 不等于供应商联网调用，也不应为本任务强行联网 |
| CC12 | verify-distribution 的空消费仓、archetype、正式 ZIP/CLI/PTY；verify-client-packages 的声明/boundary/pack/仓外消费；consumer-preparation 记录本轮 npm 门结果 | Task5作者QA、模板同源字节待完成；Task7 README示例实际执行、公共签名/架构/许可证/依赖树/发行可复现门及最终双独审仍是阶段门，不在本次只读分析宣布通过 |

## 3. 给 Task6 的最小有界补充清单

### A. 复用正式仓外 Java/Node 生命周期夹具，不新建 runner

位置：`fibra-distribution/src/test/consumers/engine-application/.../LifecycleConsumerTest.java`、同消费者 `java-plugin/.../LifecycleEntrypoint.java`。

- 为 JavaFixture/NodeFixture 的实际 packageSource 提供与真实输入一致的固定合同。现有 Java rewrite descriptor 会改变 definition/facet，提取合同后必须与该 fixture 真名称一致，不能盲拷原作者 schema 导致假失败。
- 在原静态目录测试旁调用 describeConfiguration，断言精确 revision、规则来源、target bytes/history 不变；现 loadMarker 可以复用。按需增加 definition/binder/validator marker，不改变产品 SPI。
- 用原 Java/Node config 回显与进程清理断言作为成功链；新增一次 R2 只收紧现有配置字段的 upgrade 拒绝，断言 NOT_SAVED、旧 selection/target bytes/current instance/PID、无额外 runtime 创建、R2 包仍已发布。
- 共用 CountingTargets（已有 file-store delegate），新增两个异步 Apply 共用捕获的 expectedRevision 的受控并发测试。两者均为合法且不同目标；用 latch/barrier 证明重叠而非第二个读了新 revision。等待两终态；恰好一个成功、save 增1、durable revision 增1、loser 无 candidate/prepare。不能用线程数或顺序旧 CAS 代替。
- 在 Java 实际 definition 增一个测试专用可观测 validator（计数+原值归一化），断言静态 query/结构拒绝不触达，合法 Engine deploy 只调用一次，运行时读到 normalized 值；core 已有规则无需重写。

### B. 数值只补真实边界，各保留原合同

- 沿 NodeFixture 的 `fibra.start`→`tool.lifecycle` 回显，在独立边界场景中传 exact decimal `9007199254740993`，记录 schema 接纳与 Node 实际 `JSON.parse` 后表示（预期 Number 舍入，须实测断言实际结果，不能声称精确保留）。加 safe-integer 相邻值作为对照即可。此测试 fixture 可以有数字字段，真实 sanitizer 没有相关输入就不为测试增加产品能力。
- client 原 Java codec 与 npm protocol 已有 decimal/长度测试（npm `protocol-fixtures.spec.ts:61` 的 `1E+400` tagged NUMBER）。补 renderer create 观察 `context.config` 的 `{kind:'NUMBER',value:'9007199254740993'}`，与 Java Engine/codec 输出连接或明确绑定同一已验证 wire fixture；不改 module-loader 的 `config: assignment.config`（现:66）与公开协议。

### C. 隔离 Tela 保留现有 harness，分开产品与边界 fixture

- 四个 Java 作者和 agent-view 合同作者源/JAR/包根装配按 consumer-impact；agent/openai 正式 classifier 必须正确。所有运行基于本轮 Fibra 正式 Maven/npm 制品。
- `TelaJavaDefinitionConsumptionTest` 当前直接 driver.prepare；保留原 mismatch/invalid-agent 反例的 PREPARING/真实 validator 现场，另补 Host Engine query/preflight，不将原测试改成缺 schema 后“通过”。
- `TelaClientCatalogIntegrationTest:103` 的脚本故意 throw，:87/89 手动 ACK 只证明 Host 协议。可扩配置发现，但不能列作实际执行证据。
- `tela-desktop/test/renderer-load.test.mjs` 已 bundle 真实 client-runtime，Blob bytes 经 SourceTextModule 执行。沿同 harness 加实际构建 agent-view，提供有限 TelaUi/TelaReact 能力，核 import/create/activate/stop、root 卸载、scope cleanup 和 URL revoke。数值用独立 boundary fixture，别向不读 config 的 agent-view 硬加产品字段。
- 结果明确标“VM 中产品模块实际执行”，不称 Electron Desktop 实机。

### D. 安全投影一组 canary，覆盖真实出口

- 复用未知键、敏感值、动态字典键嵌套数组及 foreign error 三类 canary；先确保合法 source 查询不含实例 canary。
- Registry 真实 mutation 的 `.doOnError` 将 `failure.toString()` 写 audit（PluginRegistry:188–194），须断言最终历史/持久 audit detail，而非仅 Engine diagnostics。
- CLI 走真正执行入口，捕获 stdout/stderr 及该执行的日志；Tela 走 `TelaHostGateway.errorBody`（:544起）或现 HTTP 入口，核 message/saveState/viewRevision。不要另造“理想 DTO mapper”。
- 断言 canary 不在这些响应/诊断/message/cause/suppressed/日志/audit；另断言授权管理 snapshot 可显式读取合法配置值。当前 EngineChangeException 管理 view 合同保留。

### E. 正式收据及不重复项

- Task5 稳定后先锁最终输入 SHA。使用原 verify-distribution 路线、独立发布仓与空 consumer-m2；其 initial deploy -DskipTests 不是全测试成功。不要手改消费者依赖回源码 target/classes，也不使用旧 SNAPSHOT。
- 脚本 EXIT 删除 temporary_root。正式执行前安排保存命令、nativeExit、XML、发布/消费 POM/JAR/ZIP SHA；若必须做 wrapper，只转发参数并保留日志，不偷换 consumer repo。正式日志没有路径/字节即不能事后补称独立消费者通过。
- 现 npm 收据可用于未变化输入；Tela vendor 必须用对应 tarball SHA。适配后锁定 Tela 完整输入并保留实际测试命令/输出。
- 不新增 schema validator facade、第二资源 owner、产品 wire、永久缓存或通用验收框架。不复制 Task1 白名单全集、Task2 OS 句柄状态机、Task4 五路径矩阵。只补上述跨层缺口及最终既有门运行。

## 4. 检索与范围说明

按要求执行一次 Nowledge MCP memory_search：query=`Fibra plugin config contract CC01 CC12 consumer acceptance`，limit=5，默认身份未改，服务返回 strict/default scope。只命中旧发行/部署规划（首条 03475969-09d3-4174-b9ff-bf34b2673811，score 0.99），未作为当前验收依据；本地 MEMORY.md 定向搜索无相关命中。未确认图谱同 owner/space 权限，不展开图谱。结论只引用当前文件与已归档报告。

本报告中的“未见”限上述有界搜索和文件检查；Task5、Task6 后续实现可能关闭缺口，主线程应在最终源码冻结后更新验收账本。本报告不宣称整个 config/artifact 内部实现已重新审查。
