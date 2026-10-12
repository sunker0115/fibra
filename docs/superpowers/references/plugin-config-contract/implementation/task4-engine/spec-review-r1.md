# Task4 独立合同审查 r1

结论：**PASS，B0 / I0 / M0**。只对冻结的 Task4 Engine 查询、预检、资源 owner、失败事实和直接 fixture 迁移作结论；没有关闭 Task5/6/7 或完整配置合同目标。审查未修改仓库源码/测试、没有 commit/push，没有读取或写入 Tela 主树。

## 输入身份与方法

- 分支 `codex/plugin-config-contract`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- `task4-source.sha256` 自身摘要 `3809171f9e4f86030cc080b30396f79462fd27cc9c84c988226e2158aabf33c8`；15 文件逐项 SHA 在审查开始及末尾均匹配。
- 权威 spec 内容 SHA `127f4eaf277e13d8899989794281874f7d8c01afc1487261d93c6890e4d3145a`，匹配 `spec.sha256`。
- 已读 spec §5–9、CC01–12、implementation-plan Task4、engine-preparation；实际读取五个生产文件、十个测试/fixture 的代码或完整相关 diff，并追入原 Planner、TargetCompiler、CommandLoop、部署失败和 shutdown 路径。
- 使用 subagent-driven-development 的 spec-reviewer 规则及 mvn-env。Nowledge MCP 定向检索 `fibra plugin config contract Task4 EngineReadAttempt`（limit 3，配置身份未改变）只命中旧部署/发行蓝图，未作为当前合同证据；未确认图谱同范围权限，未展开。没有凭实现者报告宣布通过。
- 精确核验收据：`spec-review-r1-input.json`；本次 XML：`spec-review-r1-xml/`，汇总 `spec-review-r1-xml-summary.json`。

## 合同核对

| 合同 | 实际证据及结论 |
|---|---|
| 冷查询、同步参数、准入和固定顺序 | `FibraEngine.java:393–451`：同步 definition/revision 检查，订阅才 submit；engine → revision → selection/exact record/builtin → runtime → names → resource → schema。disabled 不过滤；普通错误不改 operation；读取关闭后才编译并返回 source。测试 `EngineConfigurationContractTest.java:29,55,421`；额外探针证实 PRESENT+BLOCKED 仍可查且整份 PublishedView 不变。 |
| 候选精确 R2 与全部 active | `FibraEngine.java:717–726` 保留本次 store.find records；`455–475` 直接消费 Planner 的全部 entries，包括 retained，直接取 resolvedConfig。`DeploymentPlanner.java:24–57` 保留原 evaluator 与两级 enabled；预检不重新求值。所有名称/资源关闭及 validateAll 完成后，`734` 起才登记 candidate/调用 runtime。`EngineConfigurationContractTest.java:70,92,109,131,295` 证明 R2 严格拒绝不删新包、不变 R1 current/target、无关实例保留和 raw/ref/if/literal。 |
| 按需合同与批量预算 | `EngineConfigurationContracts.java:103–134` 仅解析 envelope，按引用取 source；`FibraEngine.java:448,468` 委托 config 批量 API，不复制预算算法。`EngineConfigurationContractTest.java:309,405` 覆盖批量超预算先于实例错、dormant 单项预算延期、全局 schema 数量预算拒绝。Task1 算法既已独审，本次没有重开。 |
| 唯一读取 owner | `FibraEngine.java:130,501,540,1171` 只有一个 readAttempt/shutdown barrier；原 catalog 与 config/deploy 都登记同一 EngineReadAttempt。`EngineReadAttempt.java:18–54` close cached、逆序续关、成功 remove、失败保留；底层 runtime/artifact owner 不变。`EngineReadAttemptTest.java:21` 的 10000 同步 close 失败在 256 KiB 启动线程独立重跑通过；catch 的 `loop.call` 保留排队续关，没有新增句柄 cap。 |
| 取消与真实关闭 | CommandLoop 原 accepted execution 与观察者分离；`ConfigurationAttempt.finish:504–512` 先真实 closed，成功才清索引和交 source。`EngineConfigurationContractTest.java:245,348,382` 覆盖取消后实际读/关继续、兄弟资源续关、异步关闭结果不得提前交付、cached failure 和父依赖不提前关闭；原 catalog 80 项所在 suite 同时重跑。 |
| 窄边界净化 | `EngineConfigurationContracts.java:76–96,114–130` 分别包围 query factory/read、任意 List 投影、resource factory/read、parse。仅 resource.read 的 NoSuchElementException 归 MISSING；工厂/名称/伪造 public ConfigContractException/非法列表/空字节归 READ_FAILED；fatal 重抛。`EngineConfigurationContractTest.java:148,275,362` 及本次运行证明。真实默认 factory 的 valid/ABSENT/后来变化/消失均有 Engine 证据；非 regular 的具体物理规则由 Task2 artifact 负责。 |
| 失败事实五路径 | `ConfigurationAttempt:483–512` cleanup 使用部署 failStop 或 query Engine subject 两种原投影，BOOTSTRAP 先设 BLOCKED；`deploymentFailed:973–1057` 普通失败保持 PLANNING、无假 candidate attemptId。`EngineConfigurationContractTest.java:197–243,434–451` 逐字段覆盖新 Apply、同 target Apply、BOOTSTRAP、Reconcile、requestRecompile，各 ordinary/cleanup；subject、stage、operationStage、revision、outcome、saveState、attemptId 均有断言。 |
| query 历史 operation 与 shutdown | `FibraEngine.java:494–497,605–608,1170–1178` query cleanup 不改旧成功 operation，shutdown 先观察同一 cached close，失败不建立 SHUTDOWN、不覆盖首个 fact；`EngineConfigurationContractTest.java:167–194` 检查失败句柄仍可达、成功句柄移除、driver/store 未关闭、重复 close 同一错误。closeOwnedAdmission 只有一个固定安全文本实现。 |
| 双失败及安全投影 | `FibraEngine.java:485–487` 仅内部 enum 固定 message，cleanup 主 code，无 secondary getter；窄边界不信任外部同类型异常。测试 `148,167,331,362` 扫描 canary、cause/suppressed 及 engineDiagnostics；额外探针补 INVALID_CONTRACT/MISSING_CONTRACT 两种 prior code。EngineChangeException 管理 view 可含 target，保持 spec 明示边界。 |
| no-op/身份/兼容 | `FibraEngine.java:674–714,1629–1633` 健康/PENDING reconcile 原语义，内部 interpreter identity 进入原 fingerprint；候选动态 packageDigest/builtin 最终 digest 自然参与，无新持久验证状态、永久 schema cache、旧 JAR/旧 API 回退或第二执行入口。 |
| 迁移不掩盖旧场景 | 十个测试/fixture 按其实际 integer/string/null/object 输入给合同。JavaBuiltInRecoveryTest 与 HostRestartRecoveryProcess 额外断言原 PREPARING/Java metadata mismatch，防止被缺合同提前截断；Registry 保留原 save-state 注入，CLI 保留原 runtime failure 断言，没有修改入口生产协议。 |

表中 Java 路径均相对仓库；主文件前缀 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/`，主测试前缀 `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/`。

## 独立执行证据

JDK21，根 pom release=21，离线复用指定缓存。原生命令（stdout/stderr 在 `spec-review-r1-tests.log`；原 `$?` 在 `spec-review-r1-tests.exit`）：

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine -am test -Dtest=EngineConfigurationContractTest,EngineReadAttemptTest,RuntimeDriverEngineTest,EngineAttemptStateModelTest -Dsurefire.failIfNoSpecifiedTests=false
```

**native exit 0**，112 tests、0 failure/error/skipped：配置矩阵20，读取 owner1，原 Engine80，状态模型11。每份 XML 仅复制本次这四个 suite，没有把 target 旧 XML 混入独立结果。

补充 `/tmp/SpecReviewProbe.java` 同包 main，以本次 XML 的 `java.class.path` 编译/执行（classpath 原值在 `spec-review-r1-classpath.txt`）。`javac -cp <classpath> -d spec-review-r1-probe-classes SpecReviewProbe.java` 编译 exit0；`java -cp spec-review-r1-probe-classes:<classpath> com.sstlfsj.fibra.engine.SpecReviewProbe` 全部断言完成，日志首行 PASS。三个独立场景为 BLOCKED query 保持 PublishedView、INVALID+cleanup、MISSING+cleanup，后两者验证 driver/store 保留。

**该 probe 不记为 native exit0**：main 返回后两个预期 fail-stop retained runtime 的 `fibra-lifecycle-2/3` 非 daemon 线程仍存活（SIGQUIT 转储保存在同一 `spec-review-r1-probe.log`，DestroyJavaVM 已出现）。这与本合同的宿主终止清理边界一致；只对已确认 PID83194 发 SIGTERM，Python 记录 returncode 143 在 `spec-review-r1-probe-1.exit`，外层命令 exit143。未改断言，未把宿主终止说成正常 close。jcmd attach 因 socket 不可用失败，随后只用 SIGQUIT 得到实际线程证据，没有继续重复 attach。

实现者原证据亦独立核了 native exit：15=1，16=0，17=0，18=0，19=1，20=0。归档20的69个 XML 实际合计516，0失败；17归档只保留8个非Engine suites合计109，Engine111见17原日志，共220。15不是全绿；NodeProcess49的16是原样升级权限恢复。17在最后一行 owner 修复前，不能冒称最终源码的全入口重跑；本次112确实在冻结最终源码上运行。此边界不需要无疑点扩大测试。

## 范围与结束状态

未证明 Task5正式12作者包/archetype/distribution、Task6仓外/Tela消费、Task7公共签名/文档/full release；不把这些后续门当 Task4 缺陷，也不声称全合同完成。资源注入证明 Engine owner/净化/投影，不替代真实 OS 故障；实际 artifact 物理关闭由前序 Task2 负责。

本审查没有发现需修复的合同缺失、额外兼容实现或需求误解。全部验证已停止，审查 JVM 已终止，释放 Maven 唯一写入权。最终进程确认记录见 `spec-review-r1-process-check.txt`。
