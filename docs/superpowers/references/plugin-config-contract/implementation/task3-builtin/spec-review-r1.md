# Task 3 builtin 配置合同独立审查 R1

结论：通过本轮合同审查。B=0、I=0、M=0；未发现可复现的 Task 3 合同偏差。该结论只关闭 builtin 元数据和内容身份审查，不表示 Task 4 共同预检、Task 7 公共签名或最终发行门完成。

## 审查输入与方式

- 分支 `codex/plugin-config-contract`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- 独立读取两份生产类实际 diff、全部七个消费/测试迁移文件及新增 bootstrap test；对照 spec §1/§6/§8、implementation-plan Task 3、findings 与 engine-preparation，未依赖实现回执作结论。
- 10 个 Task 3 源文件 SHA-256 与实现者 `source-sha256.json` 全部相同。当前 spec/计划/准备资料和源文件哈希见 `spec-review-r1-input-sha256.json`；实际测试、class 哈希、log 哈希见 `spec-review-r1-receipt.json`。
- Nowledge MCP 定向搜索 `Fibra builtin configSchemas implementationDigest plugin config contract Task 3`，limit=5，实际返回 strict/default；命中旧内置包模型，无本合同精确记录，不作为本轮事实依据。未展开图谱：无 explore_graph 工具，也无已验证的同 identity/space 浏览器会话。Codex 本地 registry 未找到直接相关的本合同条目，未引用旧决策替代当前 spec。

## 合同核对

1. `BuiltInFacet.java:21–28,39,56`：`Map.copyOf` 冻结映射，key 必须属于原 `definitionIds`；值为不可变 `ConfigSchemaSource` 和 sealed LiteralValue 树，source 构造已做中立预算。缺 schema、未知 profile、语义坏 schema 仍可构造；没有 provider 构造期间 compile、definition/binder 执行或额外公共校验 API。
2. `BuiltInPluginPackage.java:19–50`：唯一最终 SHA-256 输入与冻结格式逐字段一致；UTF-8、v2 NUL domain、pluginId/version/implementationDigest、完整 facetId/runtime/target/dependencies/capabilities/definitionIds/configSchemas 均包含。facets、依赖元组、集合及映射排序；profile 与完整 schema 参与 LiteralValue canonical，无另一份 canonical 实现。原非空/version/digest/duplicate/单 runtime 约束保留。
3. `BuiltInPluginPackage.java:54–73`：getter、selection、artifactId 全部引用最终 digest。旧 `Builder.packageDigest(String)` 删除，没有 alias、fallback 或第二持久 revision。全仓 Java 搜索未发现旧带参数 builder 调用；实际 compiled Builder 的 javap 也确认只有 implementationDigest 输入。Actor provenance 已取 metadata.packageDigest；benchmark 与 Java driver fixture 也已迁移。
4. `BuiltInPluginPackageTest` 覆盖固定 canonical 向量、schema/profile 变化、facet 独立改名、facet 名称/内容交换、plugin/version/implementation 变化、集合顺序与等价数字、外部容器修改、不可变返回值、orphan keys、缺/坏合同可构造和原 metadata 约束。所有冻结的 Task 3 反例均有对应证据。enabled/disabled 旧摘要均由原 compiler exact revision 检查拒绝。
5. `RuntimeDriverEngineTest:550–572` 的名称变化不再沿用旧 selection：先确认 PACKAGE_UNAVAILABLE，再显式 expectedRevision=1 选新内容身份，revision=2 返回新名称；duplicate/wrong runtime/wrong digest 分支保留。capability 漂移测试要求新 selection、第二次 save 和 target revision=2；provider contract-only 原场景保持同 durable token/save=1，未改其断言。
6. `JavaBuiltInRecoveryTest:96–115` 显式选精确 metadata，真实 private definition 不一致落到 JavaRuntimeException/LOAD 且核对内部 mismatch 原因；恢复分支断言 PREPARING/cannot prepare Java class space，不能被身份不匹配提前遮蔽。新增 legacy identity 恢复覆盖 PRESENT+BLOCKED、bytes 不改、错误 CAS 拒绝、正确 CAS replacement、再次重启成功。
7. `HostRestartRecoveryProcess:320–325,504–530` 保持 metadata/selection 一致，通过缺失 private entries 制造 mismatch；断言 Java PREPARING 现场。真实独立进程测试继续触达原失败路径。当前该 fixture 未补 schema 是 Task 4 fixture 迁移范围，不以尚未实现的共同 schema 门作为本轮缺陷。

## 实际验证

使用已读取的 mvn-env：Zulu 21、Maven 3.9.9、`-o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2`。执行 reactor `-pl fibra-engine,fibra-runtime-java,fibra-parity-tests -am test`，限定以下用例：

- BuiltInConfigurationBootstrapTest：1。
- BuiltInPluginPackageTest：10。
- RuntimeDriverEngineTest 四项：catalogMissingCorruptAndConflictingRootArePackageUnavailable；catalogBuiltInMetadataIsFreshAndDuplicateWrongRuntimeAndWrongDigestAreRejected；builtInDeclarationDriftRequiresNewSelectionAndChangesFingerprintWithSameProviderContract；planAffectingCallbackRecompilesAllUnitsWithSameDurableToken。
- JavaBuiltInRecoveryTest：3。
- HostRestartRecoveryTest：2，真实独立进程。

合计 20，failures/errors/skipped 均 0，native exit 0、BUILD SUCCESS。完整输出 `spec-review-r1-tests.log`，五份 Surefire XML 已复制为 `spec-review-r1-*.xml`。未重复跑全部 560；原 green-3/consumers-1 日志作为历史范围佐证，非本轮全部复跑结果。`git diff --check` exit 0；`spec-review-r1-javap.txt` 是当前编译 Builder 公共成员核验。

## 边界与交接

- 没有修改仓库源代码、测试、设计、POM 或签名；没有 commit/push/Tela 写入。仅在临时 evidence 目录写审查回执，Maven 正常更新 target 产物。
- 本轮未发现需给最小修复建议的 B/I/M 问题。disabled 旧 target 的完整持久恢复/CAS矩阵、全部 schema 共同门与后续 fixture 合同迁移仍按 Task 4 的计划验收；Task 7 负责公共签名与发行。
- 当前本 agent 启动的 Maven/javac/java 已全部结束；未启动后台进程，可交给下一位质量审查者使用 Maven 写入权。
