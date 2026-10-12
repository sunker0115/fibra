# Task3 builtin 配置元数据与唯一内容身份

状态：实现完成、自审通过，等待独立审查。已停止编辑与 Maven；无 commit/push，未改 Tela。

## 范围

- 生产仅 BuiltInFacet / BuiltInPluginPackage 两类。configSchemas 默认空、不可变、键限 definitionIds 子集；不语义 compile，不新增 runtime/direct API 校验。
- 删除 Builder.packageDigest(String)，仅 implementationDigest(String) 输入。getter packageDigest / artifactId / selection 始终引用唯一最终身份。
- 最终身份按批准 spec §8 的 v2 NUL domain、完整 metadata + profile/schema、facet/依赖/集合排序，经现有 LiteralValue.canonicalJson 与 UTF-8 SHA256 派生；无另一套 canonical 器/持久 revision 字段。
- 旧 version/digest/duplicate/单 runtime 约束保留。DeploymentTargetCompiler 现有 exact revision 校验足够，未修改。
- 实际 builder 调用点迁移：EngineTransactionBenchmark、RuntimeDriverEngineTest、EngineAttemptStateModelTest、HostRestartRecoveryProcess、JavaRuntimeDriverTest、JavaBuiltInRecoveryTest、BuiltInPluginPackageTest。全仓 rg 未发现旧 .packageDigest(参数) 调用；读取未机械改名。
- RuntimeDriverEngineTest 的 catalog 元数据变更改为旧 selection 拒绝、明确 CAS 选择新最终 identity 后查新名称；保留 duplicate/wrong runtime/wrong digest。capability 漂移改为旧身份拒绝、显式新 selection、target revision 2 和 saves 2；provider contract-only 原测试未改变。
- EngineAttemptStateModelTest 原 provenance 硬编码旧 digest 改为 metadata 最终 digest。
- JavaBuiltInRecoveryTest deployment 接受精确 metadata，补真实 null schema；新增旧 identity 持久 target 恢复 PRESENT/BLOCKED、bytes 不改、旧 CAS 拒绝、正确 CAS replacement、重启恢复。完整 metadata/private definitions 不一致继续通过真实 JavaRuntimeException LOAD 的 cause 验证；恢复断言 Java prepare failure 与 PREPARING，防止规划身份失败假绿。
- HostRestartRecoveryProcess selections 从最终 metadata 派生；mismatch 保持 metadata 不变而缺私有 definition，防止变成身份失配；明确断言 Java prepare 现场。
- 预备 Task4 的 fixture 仅 RuntimeDriverEngineTest integer config / JavaBuiltInRecoveryTest null config。其他动态资源与其他 builtin schema fixture 留后续任务，不用开放 schema 掩盖逻辑。

## TDD 与验证

- bootstrap-red：nativeExit 1，1 test / 1 failure / 0 errors，真实 NoSuchMethodException BuiltInFacet.Builder.configSchemas(Map)。
- identity-red：nativeExit 1，1 test / 1 failure / 0 errors，facet 单独改名仍等于旧 a*64 摘要。
- green-1：153 Engine tests 中 9 个旧 fixture 失败，原因 runtime plan provenance 仍用实现 digest；修复该单一来源。
- green-2：Engine 已全部通过；Java 新断言 3 个失败，源码核实 JavaRuntimeException 安全外层包装，CAS conflict 原本抛 IllegalStateException；调整新断言到真实协议，保留精确 cause/phase 和 failureStage。
- green-3：nativeExit 0。API 2、Core 123、Config 94、Artifact 88、Bridge 32、Engine 153、Java Runtime 68，合计560，failures/errors/skipped 全0。
- consumers-1：nativeExit 0。HostRestartRecoveryTest 2项真实独立进程恢复通过；fibra-benchmarks compile 成功；其余 reactor 为所选测试依赖编译，无匹配测试，不声称全套测试通过。
- git diff --check 通过。source-sha256.json 固定全部10个变更/新增文件哈希；选定 Surefire XML 已复制；原始日志与 nativeExit/commands.txt 在本目录。

## 自审与边界

- 检查固定 canonical hash 向量、schema/profile/单 facet 改名/双 facet 名称内容交换、version/plugin/implementation、顺序/数值等价、深不可变、orphan keys、缺 schema/未知 profile/语义坏 schema 可构造、原 metadata 约束。
- selection 旧 identity 包括 enabled/disabled 明确拒绝。未提供旧 API、旧摘要 fallback、自动 target 修写。
- 未运行公共签名 parity 全套：批准的新 API 签名更新属 Task7；本次仅运行 HostRestartRecoveryTest，并不代表发行门已通过。
- 未修改 config/artifact 生产类、POM、签名基线；没有新依赖。Nowledge 定向查询仅返回旧内置模型，当前结论以权威 spec 和源码为准。
