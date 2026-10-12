# Task 3 builtin 独立代码质量审查 R1

结论：PASS / Ready Yes。B=0、I=0、M=0。没有发现可复现且属于 Task 3 范围的质量缺陷。

## 输入和范围

- 分支 `codex/plugin-config-contract`，基线/HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- 审查两份生产类 BuiltInFacet/BuiltInPluginPackage、七个消费/测试迁移文件及新增 bootstrap test 的真实内容和 diff；并读取 LiteralValue、ConfigSchemaSource/SchemaBudget、DeploymentTargetCompiler、FibraEngine 的 fingerprint/bootstrap/failure 路径以及 JavaBuiltInPackage/JavaRuntimeDriver 消费。
- 合同采用当前 spec §8；已读取最新 §6 的 Task 4 接缝细化，不把后续共同预检/公开签名/发行门算成本 Task 3 已完成或缺陷。
- 当前源与合同 SHA-256 见 `quality-review-r1-input-sha256.json`。10 个 Task 3 文件与合同审查时哈希全部相同。最新 spec 单独重新绑定。

## 独立判断

1. 元数据 ownership 清楚。BuiltInFacet 对集合及 configSchemas 使用不可变复制，schema key 只引用原 definitionIds；没有名称合成、运行时 callback 或构造期 schema 编译。ConfigSchemaSource 在提供给摘要前已有迭代式中立预算检查；sealed LiteralValue 树及 BigDecimal/String 无可变内部状态。缺项和语义坏 schema 的延迟处理符合管理修复边界。
2. 身份编码保持一个 owner。BuiltInPluginPackage 在基础形状校验后一次性计算最终 digest，v2 NUL 前缀与 canonical JSON 明确分域；plugin/version/implementation、facet 名称与内容、runtime/target、依赖元组、capabilities、definitions、schema/profile 均编码。各无序集合排序，schema 使用已有 LiteralValue canonical（包括等值数字和字符串转义），没有第二套序列化、旧摘要 fallback 或新状态字段。selection/artifactId 沿用该最终值。方法规模与职责适当，无必要再抽象。
3. 没有引入 I/O、线程、ClassLoader、全局缓存或新资源生命周期。构造失败发生在对象发布前；SHA-256 不可用按固定宿主异常处理。neutral source 的预算由 config owner 维护；身份类没有复制 schema 资格算法。
4. 原 consumer 直接迁移。全仓 Java 搜索未发现有参数的旧 packageDigest builder 调用；fake runtime provenance 取最终 digest。DeploymentTargetCompiler 在 enabled 判断前核精确 revision，因此旧 enabled/disabled selection 均被拒绝。FibraEngine fingerprint 使用最终 digest，不复制 schema；既有 metadata 编码保留不改变本轮 owner。
5. 恢复失败具有可修复边界。bootstrap 先保存已验证 durableTarget/token/PRESENT，然后以不保存模式 deploy；身份不匹配进入原 BLOCKED 路径。新增真实 Java 恢复测试核原字节不变、错误 CAS 拒绝、正确 replacement、再次启动。disabled 的精确 revision 拒绝由 compiler 两态测试和同一恢复控制流支撑；其完整持久恢复矩阵仍按 Task 4 执行，不冒充已实跑。
6. 私有 Java definition 失败没有被新身份提前遮蔽。JavaBuiltInRecoveryTest 提交当前 metadata.selection；HostRestartRecoveryProcess 让 metadata 不变而移除 private entries。JavaRuntimeDriver 核对 metadata 最终摘要后才进入 owner.definition/validateDefinitions；测试进一步断言 LOAD 及原 mismatch 原因，恢复和真实进程断言 PREPARING/cannot prepare Java class space。不是仅断言某处抛错的假阳性。
7. 新测试有独立预期。固定 canonical 向量手写完整预哈希文本，未调用生产摘要方法计算 expected；其余覆盖独立 schema/profile/plugin/version/implementation 变化、facet 改名和内容交换、集合顺序/数字等价、深冻结和 orphan 拒绝。调用点迁移保留原失败语义；capability 漂移明确要求新 selection 和第二次 save，旧目标不会静默追随新声明。

## 验证证据与限制

- 本轮为独立源码质量审查，没有启动 Maven、javac、Java 或重复已有 560/20 用例；没有需要新增探针才能判定的疑点。
- 独立解析已有 spec-review 的 5 份 XML：共 20 tests（含 HostRestartRecoveryTest 两个真实进程测试），failures/errors/skipped 全部为 0。核对日志 SHA-256 及四份已编译生产 class SHA-256，均匹配原测试回执；这证明所引用测试证据对应当前 Task 3 内容，不表述为本轮重跑。
- `git diff --check` 本轮通过。证据核验明细见 `quality-review-r1-receipt.json`。
- 本轮不重新评价其它 config/artifact/POM 变化，不宣称 Task 4 schema 共同门、Task 7 signature 或最终发行已通过。
- Nowledge MCP 定向检索 query=`fibra plugin config contract builtin packageDigest implementationDigest`，limit=5，服务器返回 strict/default；仅命中旧模型/发行背景，没有本 Task 精确历史，因此不以记忆作当前结论。无可核实同 identity/space 的图入口，未展开图。Codex 本地 registry 只有历史 definition-catalog 项，未作为本轮事实依据。

## 交接

没有源码/测试/仓库文档修改，没有 commit、push 或 Tela 写入。仅创建本审查的临时报告、输入 SHA 和证据回执。未启动任何验证后台进程，无本 agent 遗留 Maven/Java 进程；Maven 写入权已释放。
