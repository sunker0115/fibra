# Task4 Engine 配置合同实现交审

状态：实现、自审与本阶段验证完成，源码冻结，待主线程安排双独审。本报告不关闭完整配置合同目标，不包含 Task5/6 发行物或 Task7 签名门禁。未 commit、未 push，未改 Tela 主树。

## 范围与冻结身份

- 仓库：`/Users/sunke/dev/ai-project/fibra`
- 分支：`codex/plugin-config-contract`
- HEAD：`0a674176b8b762b4e77044bcba7f051bf06c41d9`，实现仍是工作树修改。
- 本阶段精确 15 文件清单：`task4-files.txt`；逐文件 SHA256：`task4-source.sha256`。
- source manifest 本身的 SHA256：`3809171f9e4f86030cc080b30396f79462fd27cc9c84c988226e2158aabf33c8`。
- 权威 spec 当前 SHA256：`127f4eaf277e13d8899989794281874f7d8c01afc1487261d93c6890e4d3145a`，路径见 `spec.sha256`。
- 工作树含 Task1/2/3 及主线程文档修改。本报告只认领下列 Task4 文件/改动，不将整个 dirty diff 当作本阶段产物。

## 实现与设计对应

`FibraEngine` 原共同部署链新增配置预检，并提供 `describeConfiguration(PluginDefinitionRef,long)`。公共返回 `DefinitionConfigContract`；异常 `ConfigContractException` 使用获批的十个 Code。查询沿原 command loop、revision fence、准入与取消规则；selected disabled 和可管理 BLOCKED 均可查。查询只有静态元数据/资源读取，不执行 definition/binder，不创建实例、不改 target 或历史 operation。

唯一 Engine-held `EngineReadAttempt` 同时承接旧 catalog、配置 query 和部署预检。句柄返回后立即登记；读取串行，关闭逆序，兄弟失败继续清理，成功引用移除、失败引用可达，真实终态使用单个 cached close。runtime/artifact 仍拥有各自实际 I/O。shutdown 首先观察读取 owner 终态；失败时保留首个 operation/failure，不提前关 driver/store。同步 close 抛错也通过已有 loop 排队续关，避免纯 Java 递归溢出。

`EngineConfigurationContracts` 仅做一次 attempt 内静态名称与文档复用。资源来自本次精确 `PluginPackageRecord.configurationResource()`，没有路径/JAR fallback。仅精确 resource.read 的 NoSuchElementException 映射 MISSING；factory/SPI/集合投影等外来异常在窄边界统一映射 READ_FAILED。恶意 List.stream、伪造 ConfigContractException、null 字节/句柄/Mono、重复/非法名称均被安全投影；不读取 foreign message/toString。全局 envelope 和文档上限全局生效，dormant schema 的语义/单 schema 预算按需。配置库承担 profile、预算、默认值与校验语义，Engine 不重写。

部署保留此次 target selections 找到的 exact records，继续原 planner 的 runtime/capability/dependency 规则。仅遍历 planner 的实际执行 entries，effective 值直接取 desired.resolvedConfig。所有 active（包括 retained）静态名称/合同读取及真实关闭完成后，统一 `ConfigSchema.validateAll`，随后才允许注册/创建 candidate 和 prepare。内部 interpreter identity 进入原 inputFingerprint；健康/PENDING reconcile 不重捕输入，不声称检测外部文件变化。

普通预检失败复用既有 deploymentFailed，PLANNING 且无 candidate attemptId。新 Apply、同 target Apply、bootstrap、reconcile 重建、requestRecompile 的 ordinary/cleanup 十组合均有字段断言。cleanup failure.stage=RELEASING，operationStage=PLANNING，部署调用原 failStop 使 operation FAILED，bootstrap 先显式 BLOCKED；query 则 Engine subject、operationStage 空、保留历史成功 operation。catalog/config query 共享 queryReadFailStop，closeOwnedAdmission 的安全关闭规则只有一个实现。read+close 双失败以 CLEANUP_FAILED 为主，仅固定安全消息带前一个枚举，无第三方 cause/suppressed。

## 精确修改文件

生产 5 文件：

1. `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`：查询、共同预检、原失败/关闭事实集成、测试构造器接缝、内部 fingerprint identity。
2. `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/EngineReadAttempt.java`：唯一父读取 owner。
3. `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/EngineConfigurationContracts.java`：本次静态名称/合同读取协作。
4. `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/deployment/DefinitionConfigContract.java`：新公共 DTO。
5. `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/deployment/ConfigContractException.java`：新公共安全异常。

测试 10 文件：

6. `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineConfigurationContractTest.java`：20 个测试方法，含多场景和五路径矩阵。
7. `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineReadAttemptTest.java`：10000 同步关闭失败的有界栈、逆序续关和 cached terminal。
8. `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/RuntimeDriverEngineTest.java`：新 API/预检最小 RED→GREEN、candidate 计数、builtin 查询与活跃校验、迁移旧 catalog owner 反射断言，既有整数 schema 补 dialect。
9. `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineAttemptStateModelTest.java`：真实 integer 配置合同 fixture。
10. `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaBuiltInRecoveryTest.java`：已有 null schema 补 dialect，原 prepare 恢复现场保持。
11. `fibra-registry/src/test/java/com/sstlfsj/fibra/registry/PluginRegistryTest.java`：发布前根合同；普通 main/aux 为 null，真实 `{value:1}` 场景为专门 object 合同。
12. `fibra-cli/src/test/java/com/sstlfsj/fibra/cli/CliHostTest.java`：context string 合同。
13. `fibra-cli/src/test/java/com/sstlfsj/fibra/cli/CliHostOwnershipTest.java`：ownership string 合同。
14. `fibra-cli/src/test/java/com/sstlfsj/fibra/cli/FibraCliTest.java`：failer/command null 合同。
15. `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/verification/host/HostRestartRecoveryProcess.java`：builtin/Java/Node/external 按真实配置键生成 string object 合同。

未改这些入口模块生产协议/POM/签名。JavaRuntimeDriverTest 的本轮回归是低层直调边界验证；其已有工作树变化属于前序阶段。Spring 空 target 无须添加合同。

## 命令与 native exit

所有 Maven 均在仓库根执行，JDK21。命令公共环境：

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

最后一组准确命令（各命令 stdout/stderr 重定向到同编号 `.log`，原 `$?` 保存同名 `.exit`；不是外层 shell tail 的 exit）：

```sh
# 15 nativeExit=1；Node 沙箱进程探针受限，下面有明确恢复证据
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine,fibra-runtime-java,fibra-registry,fibra-cli,fibra-spring-boot-starter -am test
# 16 nativeExit=0；require_escalated，原测试与断言保持
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-runtime-node -am test -Dtest=NodeProcessUnitTest -Dsurefire.failIfNoSpecifiedTests=false
# 17 nativeExit=0；入口与真实独立进程恢复
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-parity-tests -am test -Dtest='EngineConfigurationContractTest,RuntimeDriverEngineTest,EngineAttemptStateModelTest,JavaBuiltInRecoveryTest,JavaRuntimeDriverTest,PluginRegistryTest,CliHostTest,CliHostOwnershipTest,FibraCliTest,FibraAutoConfigurationTest,HostRestartRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false
# 18 nativeExit=0 未复现；19 nativeExit=1 真实 bounded-stack RED，命令相同，测试增加受控 256KiB 栈
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine -am test -Dtest=EngineReadAttemptTest -Dsurefire.failIfNoSpecifiedTests=false
# 20 nativeExit=0；同步失败续关的一行修复后最后完整 Engine reactor
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine -am test
```

15 中 Engine175、Java68 通过，Node108 有16失败，后续入口被跳过。源码定位 GroupProbeFixture 生成的 payload 调 `/bin/ps -o pgid= -p pid`；沙箱内该命令实际 exit127/operation not permitted，payload 在写 identity 前抛错。16 在自动批准的非沙箱环境原样49通过，没有改 Node 生产或测试。首次权限审查超时未启动命令，随后一次重试获准；没有遗留审批 blocker。不能将15写成全绿。

17 为220项通过（Engine111、Java55、Registry14、CLI37、Spring1、HostRestart2）。20 为516项通过（API2、Core123、Config94、Artifact88、Bridge32、Engine177）。17 发生在最后 owner 同步 catch 续关的一行修复之前；20 完整验证最终 Engine 源码，不将17假称为最终源码后的全入口重跑。主线程确认该有界修复后无需无新证据重复扩大测试。

18 文件名带 red 但真实 exit0，普通 Surefire 栈下1万句柄未复现。19 将探针放在显式256KiB platform thread，实际 StackOverflowError，exit1。只将 catch 的直接递归改为与正常分支相同的 loop.call 排队后，20 包含该测试通过，确认全部兄弟继续访问、只生成一次 cleanup callback、重复 close 返回同一失败。

早期流水线保留原日志，以下 native exit 是执行时返回值（01–14 未单独写 `.exit` 文件，不事后伪造原始旁证）：

| 日志 | native exit | 事实与解决 |
|---|---:|---|
| 01-red | 1 | 缺查询 API、预检未拦在 candidate/prepare 前的真实 RED |
| 02-green | 1 | 文件名误导；已有 Task3 整数 fixture 缺 dialect，非 GREEN |
| 03-reactor | 1 | EngineAttemptStateModel 缺真实整数合同；补 fixture |
| 04-matrix-red | 1 | 测试错用 accessor 的编译失败，不计行为 RED |
| 05-matrix-red | 1 | forged ConfigContractException 泄漏真实反例；同时修正测试 pointer/bootstrap 返回预期错误 |
| 06-reactor | 0 | 第一轮 Engine 完整通过 |
| 07-entry-regression | 1 | JavaBuiltin null schema 缺 dialect；补 fixture |
| 08-hostile-list-red | 1 | SPI 任意 List.stream 伪造异常绕过安全投影真实 RED |
| 09-entry-regression | 1 | hostile List 已通过，Registry 缺根合同；补发布 fixture |
| 10-entry-regression | 1 | Registry group 实际 object 配置与 null fixture 不符；按真实键补专门合同 |
| 11-expanded | 0 | 扩展配置、Java/Registry/CLI/Spring 通过 |
| 12-restart | 0 | HostRestartRecoveryTest2，真实独立进程恢复 |
| 13-null-red | 1 | resource null bytes 被归 INVALID 而非 READ 的真实 RED |
| 14-final-reactor | 0 | 窄 read 边界修复后完整 Engine175及依赖通过 |

## XML 与安全证据

`xml-summary.json` 列出逐 suite 原 XML 计数与来源 run；`xml-summary.md` 为模块汇总；原 XML 副本在 `xml/<run>/<module>/`。仅复制能由相应本轮日志归属的 suites；不复制 parity 目录其他历史 XML 来充本轮证据。20 XML 合计516。Java classloader/index 与 Node sidecar/driver 的补充 XML 来自15，NodeProcess49 来自16；这是明确多次运行的证据集合，不是虚构一次全仓 GREEN。

测试覆盖 raw ref/if/literal 实际 effective 值、同名不同 facet、每类 inactive/package gate、R1→R2拒绝及保留、schema-only升级 affected 闭包、文档/单schema/实例/批量预算顺序、关闭与取消/重复观察/异步完成、五发起路径普通与 cleanup 事实、query 历史 operation 保留、query 与 shutdown 共用屏障。未知键/配置值/恶意 foreign 异常 canary 断言仅检查官方安全投影；最终20日志未发现两枚测试秘密字符串。

既有 EngineChangeException.view 是管理 target 视图，可能含配置；不宣称整异常对象图无配置值。原生命周期测试仍故意输出其自身故障日志，不能把这些预期日志等同为秘密泄漏或测试失败。

## 未验证边界与交接

包级 resource factory 接缝只注入本次 Engine 合同协作者，返回的真实 artifact 包装句柄立即进入同一 owner。这证明 Engine 的读取/关闭失败事实与安全映射；真实 OS stream 状态机由 Task2 artifact tests 证明，本阶段不把注入等同成 OS 故障。默认生产 factory 的真实 bytes、ABSENT、身份变化、后来删除另有 Engine 测试，真实 Java/Node/独立进程恢复也有入口证据。

本阶段未跑 distribution 仓外 consumer、作者12包/archetype/Node示例迁移、Tela UI 或公共签名发布门；这些仍属 Task5/6/7。双独审尚未进行，不声明审查通过。

最终 `git diff --check` exit0。最后 Maven session 已完成；随后只读升级权限 ps 对 Maven/Surefire/HostRestartRecoveryProcess/Node Fibra 探针无匹配，见 `process-check.txt`。交接后停止文件编辑和 Maven，释放唯一写入权，由主线程安排独审。
