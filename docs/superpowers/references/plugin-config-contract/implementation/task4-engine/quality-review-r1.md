# Task4 独立代码质量审查 r1

结论：**PASS；B0 / I0 / M0；Ready: Yes（仅 Task4 阶段继续交付）**。没有发现需修复的正确性、owner、资源生命周期、安全投影或可维护性问题。此结论不表示完整配置合同已完成，也不表示可以跳过 Task5/6/7 合并或发行。

## 输入与审查方法

- 仓库 `/Users/sunke/dev/ai-project/fibra`；分支 `codex/plugin-config-contract`；HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。审查对象为该 HEAD 上冻结的工作树修改，不把其他阶段的 dirty 文件纳入 Task4。
- 15 文件清单与逐文件哈希均实际复核成功；manifest SHA256 为 `3809171f9e4f86030cc080b30396f79462fd27cc9c84c988226e2158aabf33c8`。
- 权威 spec SHA256 为 `127f4eaf277e13d8899989794281874f7d8c01afc1487261d93c6890e4d3145a`。输入收据 `quality-review-r1-input.json` 包含每个文件实际哈希、归档 XML 哈希与重新计算的数量、native exit 和 diff check。
- 使用 requesting-code-review 与 subagent-driven-development 质量审查规则；已读用户规范、spec §6/7、Task4 计划与 engine-preparation、5 个生产文件、10 个测试/fixture 的相关实际 diff，并追读 CommandLoop、Planner、FacetDefinitionNames、ConfigSchema、DefinitionConfigDocument 与 Engine 原部署/关闭路径。
- Nowledge MCP 查询 `Fibra plugin config contract EngineReadAttempt Task4`，limit=5、默认配置身份未改，返回 strict/default 检索范围；结果均为旧部署、发行或 external Assignment 背景，没有本轮精确记录，未作为实现证据。无已验证同身份/空间权限的图谱通道，未展开。Codex 本地 registry 快查没有本轮 Engine 合同记录，未将旧记录当当前事实。
- 本次为只读质量审查；未运行 Maven、未编译或启动额外探针。主线程持有唯一 Maven 写入权。既有报告只用于定位，结论依据实际源码、diff、XML 与原日志。

## 实际质量证据

以下 `E` 为 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`；同目录简称 `Owner` 为 `EngineReadAttempt.java`，`Contracts` 为 `EngineConfigurationContracts.java`；`T` 为 `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineConfigurationContractTest.java`。

| 审查点 | 证据与判断 |
|---|---|
| 能力分层与唯一 owner | `E:130,501,540,1171` 仅一个 Engine-held `readAttempt`，旧 catalog 与配置 query/preflight 没有第二 shutdown 屏障。`Owner:18–54` 只负责子句柄登记、逆序观察关闭、保留失败与缓存终态，实际 I/O 仍归 runtime/artifact。`Contracts:48–57` 的 names/documents 只属于本次 Read；不跨命令、revision 缓存，无新线程池、注册中心或业务事实源。 |
| 关闭和失败保留 | `Owner:37–54` 同步 throw/null 与异步 error 均继续兄弟关闭，成功项移除，失败项保留；`closed.cache()` 保持同一终态与一次 cleanup callback。同步 catch 已经使用原 `loop.call` 续关，未引入新线程或递归堆栈风险。`E:504–512` 真实关闭成功才清索引并交付纯值。`T:167,348,382` 验证失败句柄可达、成功句柄释放、重复观察及异步等待。 |
| 原 lane、准入、CAS 与取消 | `E:393–451` 保留同步参数检查和冷 submit；CommandLoop:29–36 的 accepted command 独立于结果订阅者，内部预检未嵌套 submit。`E:665–715` 保留原 CAS、no-op、健康/PENDING reconcile。`T:245` 取消观察后仍等真实 read/close，driver 不提前关闭。 |
| 候选 R2 与 candidate 时序 | `E:717–726` 使用本次 target 的精确 records，传 planner 后继续用于资源读取。`E:461–475` 对 planner 全部 active entries（含 retained）先核静态名称、读取、关闭，再统一 `ConfigSchema.validateAll`；随后 `E:727–784` 才计算 affected、登记 candidate/create/prepare。没有旧 durable R1 查规则、raw 二次求值或校验后补默认。`T:70,92,109,131,295` 验证 R2 拒绝保留 R1、包历史不删除、无关实例 retained 和实际 effective 输入。 |
| 局部 compiler 与单一语义实现 | Engine 只调用 config 的批量入口，不复制 profile/预算/默认值/校验算法。`Contracts:111–136` 只复用 envelope 并按需取 source；ConfigSchema 的 Batch/compiled 对象为每次调用私有状态。`T:309,405` 覆盖集合预算先于普通实例错误、单 schema dormant 延期及全文件 envelope 约束。 |
| 异常安全边界 | `Contracts:76–98,114–132` 分别净化 runtime factory/read、任意名称 List 投影、resource factory/read 和 parser 异常。即使外来异常类型是公开 ConfigContractException，也先在该 SPI 窄边界替换；外层 safeReadFailure 只保留此前内部受控分类。仅 resource.read 的 NoSuchElementException 归 MISSING；null 字节/句柄/Mono、伪造异常及非法名称归 READ_FAILED。`T:148,331,362` 对 hostile message/toString、动态输入键、cause/suppressed 做实际断言。新 catch 对 VirtualMachineError/ThreadDeath 均重抛。 |
| 五类发起路径及 query 历史事实 | `E:483–499` 将读关闭事实留在 Engine 私有 owner：部署使用原 failStop，query 使用 Engine subject 且不把旧成功 operation 改为 FAILED；BOOTSTRAP cleanup 在 failStop 前显式 BLOCKED。普通错误继续原 deploymentFailed。`T:197–243,434–451` 逐项覆盖新 Apply、同 target Apply、BOOTSTRAP、reconcile、requestRecompile 的普通/cleanup 十组合，检查 stage、subject、revision、outcome、saveState 与空 attemptId。 |
| shutdown 与安全关闭规则 | `E:1170–1178` 在建立 SHUTDOWN operation 前观察同一 cached close，失败时不覆盖首错/历史 operation、不关闭 driver/store；`E:605–608,1128–1135` 收敛 query fail-stop 和 admission 关闭规则，未扩散新的 failure policy。`T:167–194` 与原 RuntimeDriverEngineTest:385–418 检查保存失败引用和旧 operation。 |
| 公开合同最小化 | 新 DTO 与异常只提供获批四字段和十个 code；资源工厂重载为包级测试接缝，默认生产入口仍为 record.configurationResource，注入 null 在获取资源前拒绝。未添加 public Builder 开关、兼容 API、path/JAR fallback、第二执行入口或 secondary getter。`E:1629–1633` 仅把内部解释器身份纳入既有 fingerprint。 |
| 测试真实性与范围 | 测试使用真实 package store、Engine lane、部署链和 effective 值；可控资源只承担失败/等待注入。默认 factory 的真实 bytes、ABSENT、后改/后删有 `T:275`；包内 factory 注入没有冒充 OS stream 故障。入口 fixture 迁移按原 integer/string/null/object 值设置合同，JavaBuiltInRecoveryTest:75–80,111–113 与 HostRestartRecoveryProcess:321–326 追加 PREPARING/Java mismatch 断言，避免缺合同导致原恢复场景假绿。 |

代码边界与当前规模相称：共享 owner 抽出的是同一真实生命周期规则；合同协作者仅承担一次读取的名称/文档组合；失败投影保留在 Engine，未把资源类变成策略层。没有为形式继续拆分或添加抽象的需要。

## 验证与边界

- 本轮独立重算 `xml/20` 的 69 个 XML：516 tests，0 failures/errors/skipped；原 `20-owner-final-reactor.exit=0`，日志 BUILD SUCCESS，包含最终 Engine 源码。
- 本轮独立重算 `spec-review-r1-xml` 的 4 个 XML：112 tests，0 failures/errors/skipped（配置20、owner1、Engine80、状态11）；原 `spec-review-r1-tests.exit=0`，日志 BUILD SUCCESS。不是本质量 reviewer 重新执行，明确复核已有独立执行证据。
- `19-owner-bounded-stack-red.exit=1` 的原日志有真实 StackOverflowError；20 与独立112中的 `EngineReadAttemptTest` 验证 10000 同步失败逆序访问全部兄弟、单 cleanup callback、同一终态。18 实际 exit0，不把带 red 文件名当失败证据。
- 17 的入口220（含 HostRestart2）发生在最后 owner 一行修复前；没有声称最终所有入口重跑。15 实际 exit1；16 原样升级权限 NodeProcess49、exit0，不能将15改称全绿。
- spec reviewer 补充 probe 的断言 PASS 与进程正常结束不同：其 fail-stop 保留非 daemon 生命周期线程后由宿主终止，exit143；不能算正常 close 成功。原终止与进程确认记录存在，本 reviewer 没有复启它。
- 本次 `git diff --check` exit0；扫描最终20原日志，两枚配置安全 canary 均不出现。安全结论只覆盖受控异常/diagnostics；既有 EngineChangeException 的管理 view 可含 target/config，不声称整个对象图无配置值。
- 没有新疑点需要追加宽测试，因此没有占用 Maven 或启动探针。Task5 作者包/模板/发行装配、Task6 仓外与 Tela 消费、Task7 签名/文档/正式发行仍待完成，不作为 Task4 已完成项，也不据此阻塞本阶段。

## 交接

无源码或测试改动；无 commit/push；未读取或修改 Tela 主树。仅新增 `/private/tmp/fibra-config-contract-evidence/engine/quality-review-r1*` 审查产物。本 reviewer 未启动 Maven/JVM/Node，无本审查遗留进程；无需清理其他任务的进程。审查结束并释放写入权（本轮从未申请或占用 Maven 唯一写入权）。
