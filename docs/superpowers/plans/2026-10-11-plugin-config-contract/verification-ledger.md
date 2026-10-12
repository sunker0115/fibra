# 插件配置合同最终验证账本

2026-10-12，Task7 技术门及最终合同/质量双独审均完成，B0/I0/M0。此账本把规格 CC01–12 对到真实源码、原断言和制品证据；独立结论见[最终交付记录](closeout.md)。仓外完整证据根为 `/private/tmp/fibra-config-contract-evidence/`，最终门为其 `final/`；可移交收据归档于 `../../references/plugin-config-contract/implementation/task7-final/`。

技术门已完成：完整 clean verify 原生0（171份本轮XML、1228项、失败/错误/跳过均0），本轮Java→TS、原可复现发行、最终ZIP正式坐标解析及原CLI黑盒门、架构与diff-check均原生0。19个签名只变批准的3个；28主JAR/30POM匹配Task6正式输入，npm28源输入与2tarball匹配原合格回执。最终报告与逐项SHA见[Task7交接](../../references/plugin-config-contract/implementation/task7-final/REPORT.md)。最终双独审通过，用户已决定本次保留Jackson3.1.5并记录告警、升级另行处理；配置合同目标已完成，既有告警仍保留，不表示授权发布。

## 合同与断言

下表的测试类均为当前仓库真实测试；Engine 新门的主要断言集中于 `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineConfigurationContractTest.java`。正式消费者为 `fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/`；隔离 Tela 使用 Task6 R2 的 482 输入锁，绝不借用主树或把 ACK 当执行。

| ID | 实现与实际断言落点 | 证据及边界 |
| --- | --- | --- |
| CC01 | EngineConfigurationContracts/EngineReadAttempt 共用静态读取；`queryIsColdFencedDisabledAndPureAndUsesExactFacet` 验 disabled、cold、facet 隔离与 creates/prepares=0；正式 `LifecycleConsumerTest.discoversAnUnknownDisabledJavaPackageAndCreatesFromItsCatalog` 验 Java 初始化/validator marker 为零；builtin 静态目录测试 | 本轮全量 XML + Task6 external-frozen。Node/client 静态查询不执行由共同 Engine 门与 provider 计数证明；实际 Node/client 执行另见 CC11。 |
| CC02 | PluginPackageStore 捕获摘要证据、FixedPluginConfigurationResource 校验；PluginConfigurationResourceTest 的变化/原缺失/非regular/metadata builder/toBuilder/reopenedStore；BuiltInPluginPackageTest 的 schema/profile/facet/顺序/不可变及 disabled identity；JavaBuiltInRecoveryTest | 本轮全量 XML；规则进入原 packageRevision/最终 builtin packageDigest，无第二身份源。测试中根文件只补在安装前，未放宽摘要检查。 |
| CC03 | FibraEngine.preflightConfiguration；`unknownNamesWinOverBadDocumentAndDormantSemanticErrorsAreDeferred`、`missingSelectedSchemaAndGlobalEnvelopeBudgetRejectWhileDormantSchemaBudgetDefers`；正式 `identicalEffectiveValuesReachEngineRegistryAndTheRealCli` | 本轮全量 + Task6 同一合法/非法有效值经 Engine/Registry/实际 CLI。运行时错误/结构错误各自保留原边界，直接低层 runtime 消费不会自动获得 Engine 门。 |
| CC04 | 原 config 求值后执行结构门；`inactiveEntriesDeferRulesButPackageGatePreservesEvaluation`、`expressionsValidateResolvedValuesAndEveryRepeatedInstance`、`ifAndLiteralReachOnlyResolvedSchemaAndDoNotSupplyDefaults`；正式 Java/Node enable 拒绝场景 | 本轮全量 + Task6 external。覆盖 local/ancestor/when 延期，package gate 不改变 raw/context 求值；disabled target 在启用拒绝后保持原值。 |
| CC05 | `candidateRevisionMustSupplyRulesAndRejectedRevisionRemainsPublished`、`schemaOnlyUpgradePreflightsRetainedEntriesButPreparesOnlyAffectedUnits`；正式 `rejectsSchemaOnlyUpgradeAndEnableWithoutReplacingTheCurrentRuntime`、`overlappingApplyCommandsUseTheOriginalCasAndOnlyOneValidator` | 本轮全量 + Task6 barrier 实际并发 Apply。R2 包历史保留、target/current/PID 不被拒绝更新替换；原 CAS 只有一次保存，无新事务入口。 |
| CC06 | ConfigSchema 纯规则，ConfigSchemaTest 类型/null/nullable/exact decimal；Engine 的 if/literal 默认断言；正式 Java 实例 marker 与 trim 结果/原 raw target | 本轮全量 + Task6 external：有效启动 validator 一次、schema不补默认/不归一化；空 API facet 与有 null definition 由正式作者 QA 分开。 |
| CC07 | FixedPluginConfigurationResourceTest 读关串行、close-before-read、双失败/重放；EngineReadAttemptTest 多同步关闭失败不漏兄弟；Engine 的五类发起、取消观察、异步关闭、owner 保留测试 | 本轮全量 XML。`allFiveInitiatorsKeepPlanningProvenanceForReadAndCleanupFailure` 覆盖 new/same/bootstrap/reconcile/request；`queryDoubleFailureRetainsOnlyFailedOwnersAndDoesNotRewriteSuccessfulOperation` 保留父资源并缓存终态；非实际关闭完成不提前释放。 |
| CC08 | Engine 上述五路径 provenance；RuntimeDriverEngineTest 原 SAVED 阻塞运行、NOT_APPLICABLE 恢复及 save uncertain；EngineAttemptStateModelTest；HostRestartRecoveryTest | 本轮全量 XML。NOT_SAVED 仅目标未保存，已发布包可保留；SAVED 未ready/UNCONFIRMED 未被新结构合同改写。真实独立 JVM 重启属于已有测试，不声称进程崩溃的所有时序已穷尽。 |
| CC09 | SchemaProfile/SchemaBudget/SchemaLibrary；ConfigSchemaTest、ConfigSchemaBudgetTest、ConfigSchemaBatchOrderTest、ConfigSchemaBatchAllocationTest、DefinitionConfigDocumentTest | 本轮全量：allowlist、最近非法、typed map/组合/ref/循环/菱形/预算、单schema延期与全局预算、精确数字和库前拒绝；正式 Node `nodeReceivesJsonNumbersAfterExactSchemaValidation` 明确 Number 精度边界，Task6 renderer 真 Java wire保留 tagged decimal。没有 CPU/堆/耗时 SLA 或性能 benchmark。 |
| CC10 | SchemaDiagnostics 安全映射；`projectsSafeStaticPathsWithoutSecretKeysValuesOrLibraryCauses`、`foreignErrorsIncludingForgedContractErrorsAreSanitizedAtSpiBoundary`、`unknownAndDynamicInputKeysNeverReachSafeFailureProjections`；正式 CLI/Registry 与 Tela Gateway | 本轮全量 + Task6 external actual-output、audit/cause/suppressed、Tela OutputCapture。查询 source 不混 current 值；既有管理 snapshot 可含完整 target，文档明确不得整体序列化为错误 DTO。 |
| CC11 | 正式文件仓主 JAR 到仓外 Engine/provider/CLI/Spring/Java/Node；Task6 隔离 Tela Java/Host/实际 agent-view module import/create/activate/stop | 最终 artifact-qualification 已证明28主JAR/30POM字节相同，可复用Task6；external19/8XML，Tela34/5XML、干净Host1、npm29、renderer28分别记账。VM 产品模块执行已验证，Electron实机、OpenAI联网、完整Tela发行IT未验证。 |
| CC12 | docs/api/plugin-configuration.md 明确接入片段并链接可执行 Lifecycle/Author消费者；原 archetype/正式作者 source→JAR→root→ZIP；ApiSignatureBaselineTest、架构/npm/Java→TS/repro/最终ZIP门 | 最终门原生回执与 SHA 见 Task7 REPORT。19签名自动生成只变3个，16个不变；不手改baseline。最终合同/质量双独审均B0/I0/M0，批准配置范围已闭合。 |

## 本轮全量发现的夹具遗漏

首次 `clean verify` 原生退出 1，parity 的 156 项中 10 errors。六个测试包构建处没有 `fibra-config.json`，结构门因此拒绝启动；JavaPublishedViewRetentionTest 的五项随后无法取得 descriptor/observation。已保存 `final/clean-verify-red.*` 与独立 RED XML；全量计数排除 `src/test/consumers` 下未参加 reactor 的旧报告。

窄修只为 CrossRuntimeConditionalConfigTest、CrossRuntimeSelfDisableTest、NodePublishedCancellationOwnershipTest、JavaPublishedViewRetentionTest、ClientProtocolVerticalVerificationTest、RuntimeHostVerticalVerificationTest 的作者夹具增加固定规则：真实 String/Void 对应 string/null，Node 路径字段和外部 mode 对应闭合 object。原生命周期、取消、GC、CAS、Java codec 和六阶段断言全部保留。已全仓核对 parity 包生成点，先前 HostRestartRecoveryProcess 已有合同。定向 10 项/6 XML 原生 0 后再执行全量原门，不将定向通过代替完整通过。

第二次全量遇到独立的既有 Node 夹具竞态：`NodeProcessUnitTest.GroupProbeFixture.launch` 等待 `identity.json` 存在即读取，而 payload 的 `writeFileSync` 先创建空文件再写，读取窗口可得到 `MissingNode`。该轮原生 1，实际执行708项/78 XML中1 error；其余未执行模块的旧 XML 明确排除，报告为 `final/clean-verify-node-flake.*`。主线程核对后授权仅将测试 payload 改为同目录 `identity.tmp` 写完后 rename；生产 supervisor 字节校验、PGID/entrypoint/execArgv断言、关闭预算均不变。原49项定向门与后续全量结果分别记录，不用无修改盲重跑掩盖失败。

## 依赖与工具限定

保留已批准的 networknt 3.0.8 / itu 1.14.0、Jackson 3.1.5 与 SLF4J 2.0.18。完整依赖树、effective POM及最终 ZIP/JAR绑定见 Task7收据。新增两 JAR 共642803字节，旧 ZIP 两条目压缩合计578110字节；这不是全发行净增量，也不是启动性能。功能启动/ClassLoader/关闭通过不能推成性能已量化。

既有26坐标 OSV 核查发现 Jackson 七条公告；新增两库未命中仅代表该次查询范围与时间。独立适用性核查未证明新 schema 路径直接触发，但外部插件 binder 条件可达不能排除。用户已决定本次保留3.1.5并明确记录告警，升级另行处理，详情见[依赖资格](dependency-qualification.md)；不写零漏洞或完整SCA通过。

最终工具 PATH使用官方 microsoft/ripgrep-prebuilt v15.0.1 的 darwin-arm64 asset，其归档与官方 asset digest相符，实际二进制自报 `ripgrep 15.0.0 (rev 3a612f88b8)`；回执如实保留两种身份，不改二进制。Task5 使用的 rg15.2.0 与旧 ZIP为历史证据；新ZIP的实际字节、CLI和正式坐标解析已另行验证通过。
