# Task6 独立规格审查 R1

结论：PASS。Blocker 0 / Important 0 / Minor 0。Ready：是，可进入 Task6 质量独审与后续 Task7；不表示整个插件配置合同目标或 Tela 发行门已关闭。

## 范围与方法

- 工作树：`/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`，未提交。
- 仅审 Task6 相对 Task5 的 `fibra-task6.patch` 三个 Fibra 消费者文件，以及隔离 `/private/tmp/tela-config-consumer.7vs6zn1a/tela` 的 `tela-adaptation.patch` 19 个修改/新增文件。未重开 Tasks1–5 已通过的完整核心审查。
- 依据权威 spec §11 CC01–12、implementation-plan Task6、consumer-acceptance-gap 和派发合同。采用 subagent-driven-development/spec-reviewer 模板，实际读了新增代码、被复用测试、相关产品入口及制品装配，不以实现者 REPORT 自述替代验证。
- 定向 Nowledge `thread_search`：`Fibra plugin config contract Task6 Tela consumer acceptance`，limit 3，结果 0。没有从历史检索引入未经源码证实的结论。
- 审查只写本目录的审查报告与收据；没有修改生产源码、测试、计划、Tela 主树，没有 commit/push，没有启动 Maven 或新的测试矩阵。

## 合同核对

| 合同 | 独立核对结果 |
|---|---|
| 冷查询与 Java validator | `LifecycleConsumerTest.discoversAnUnknownDisabledJavaPackageAndCreatesFromItsCatalog` 核对 ref/package/target/profile、查询和结构拒绝时 load/validator marker 不存在；实际 Java 启动后 marker 恰一次，tool 返回 trim 后 `normalized`。fixture 的真实 `PluginDefinition.validator` 实现已读，未用 ACK 代替执行。Java 静态初始化 marker 同时约束 definition/binder 不可能先执行。Node 查询路径另结合已验证的静态 definition query 实现，不把仅在 start 才记录的 PID 文件夸大为独立系统级 spawn 探针。 |
| 跨入口与输出安全 | `identicalEffectiveValuesReachEngineRegistryAndTheRealCli` 使用相同 schema/effective 合法及未知键/值输入经过真实 Engine、Registry、`FibraCli.run`。归档 CLI 成功 exit 0，失败 exit 3，stderr 为安全 NOT_SAVED 提示；异常 cause/suppressed、Registry audit detail 和日志有 canary 检查。Tela 测试直接调用原 `TelaHostGateway.errorBody` 处理真实 Engine failure，并用 OutputCapture 核对日志。 |
| R1/R2、disabled enable | Java/Node 参数化场景只收紧根 schema，R2 已 publish；拒绝为 NOT_SAVED，旧 selection/current instance/target bytes/save count 不变，Node 原 PID 保留。随后 disable、选择 R2、enable 拒绝，旧 disabled target/selection/save count 保留。最后真实资源关闭断言仍在。 |
| 重叠 CAS | `overlappingApplyCommandsUseTheOriginalCasAndOnlyOneValidator` 用原 FileDeploymentTargetStore 的 save barrier；首条在 durable save 中暂停，第二条已订阅且两 future 均未结束，然后放行。两条同 captured expectedRevision，败者明确 revision conflict，只有一次 save/revision 前进和一次真实 Java validator。并非顺序 stale CAS 冒充并发。 |
| 数值边界 | Node 实际 start/tool/stop 对 9007199254740991/2/3 的回显证明第三值落到 Number 的 9007199254740992，同时 Java target 仍保留原十进制，超界拒绝保留原 PID。client 的真实 Engine assignment 经正式 Java codec 产生 NUMBER 字符串 wire，再经正式 npm protocol 和 renderer actual create 保留 `9007199254740993`。未修改 wire 或给产品规则添加测试字段。 |
| 查询与管理 snapshot 边界 | 活跃实例存在时再次 describeConfiguration，source 与原静态合同相等、不含实例 canary；授权 Registry snapshot 明确仍可读取实例值。没有把授权 snapshot 含值误判为泄漏。 |
| Tela 作者迁移 | 四个 Java 作者规则均唯一位于 `src/plugin/fibra-config.json`，assembly 原字节进入 JAR，distribution 从真实 JAR 提取后 cmp。model-api 的真实 `TelaModelApiEntrypoint` 确有 definition，按空 object 规则处理而非空 API。agent dataRoot、OpenAI model/baseUrl/timeout 与原产品代码相符；默认 60000、blank 检查和 API key 仍归原业务 owner。 |
| Tela 低层与 Host 边界 | 原四作者实际 JAR catalog/prepare/idempotent close、未知 definition、metadata mismatch、原 agent validator 错误仍在执行。新增 Host Engine 测试单独验证静态查询、非法结构 NOT_SAVED、target 保留及 model-api 实际启动；没有用低层 driver 绕过 Engine 门的结果冒充 Host 验证。 |
| 产品 client 生命周期 | renderer 读取真实 build 的 agent-view.js，经实际 Blob URL import 和产品 create，再 activate/stop；断言 root mount/render/unmount 恰一次、unit 移除、scope.closed、URL revoke 和重复 stop 幂等。默认 config 为合法 tagged 空 OBJECT。大数 config 另用 fixture actual create，未声称 agent-view 消费未声明字段。有限 TelaReact/TelaUi stub 仅支持 VM 生命周期证据。 |
| 原状态模型 | SAVED 未 ready、UNCONFIRMED、恢复、owner 五路径/取消/失败关闭按 Tasks4–5 已审证据复用，Task6 未建立第二执行/保存模型。原 consumer lifecycle、client pending/启停/关闭回归保留；不把本轮 53 项 Java 测试标成核心全量状态机重跑。 |

## 独立收据核验

`spec-review-r1-receipt.json` 是本审查独立复算结果，未运行实现者 `collect.py` 或重写其收据。

- 三个 Fibra 最终输入、24 个仓外输入、482 个隔离 Tela 输入全部匹配锁 SHA；两份 target 外源码 tar 的每个锁定文件内容均匹配。
- 54 条实际 JAR 消费路径对应 27 个独立发布 SHA，逐一与 `author/distribution/run/remote` 当前 JAR 字节比较一致。13 份 XML 的 classpath 无 Fibra 工作树/target/classes 旁路；所列 Fibra JAR 都在已核集合内。
- 两个正式 Node22.14 npm tgz 与 Tela vendor 一致；进一步解包逐文件比较 Tela 实际 node_modules 中安装包，全部一致。Node22.14 命令身份沿本轮固定 COMMANDS 及正式包收据承接，未伪称此审查重跑 Node。
- 四 Java 作者 source→JAR entry→基础 ZIP 根、JAR→ZIP payload→实际测试 JAR 字节一致。client source→包根→基础 ZIP 规则以及实际产品 JS 均一致；另独立检查 Desktop ZIP 中五份规则全部匹配作者源。
- external-frozen：19 tests / 8 XML；tela-focused-final：34 tests / 5 XML。逐 XML 核 tests/实际 testcase 数/零失败零错误零跳过、所属 suite 在最终日志出现；13 份归档 XML 均与当前对应 target XML 逐字节相等。未混入旧 RED。
- external-frozen、tela-package-r3、tela-focused-final、tela-renderer-final 原生 exit 文件均为 0；renderer 最终 TAP 为 28/28，无 fail/cancelled/skipped。最终三份运行日志均无 CANARY。
- Java→client wire SHA 为 `cae3a06ce7b73867652ede6b03cb5c09c878c5e9b6d786eda523e9489dc6689f`，内容确为 NUMBER 字符串 `9007199254740993`。

## 边界与交接

- Tela `package -DskipTests` 仅是装配通过，日志明确 Java tests skipped；不能称完整 Tela Java/发行 IT 通过。
- 产品 client 证据是 VM 中真实 module 的加载和生命周期，不是 React 实际页面渲染、Electron 实机、Desktop 签名公证、干净机器或 OpenAI 联网证据。
- 首次 Java validator RED 只有原日志/exit，没有独立历史 XML；如实保留该历史边界，不补造、不重跑冒充历史。
- Task7 full gates、签名与最终全局独审尚待主线程执行；既有 Jackson 资格问题由主线程处理，本审查未变更依赖或扩大范围。
- 收尾复核：Fibra 三输入、外部 24 输入与 Tela 482 输入 SHA 均未变。REPORT SHA `351a1acc23ad7f443071c4fa62422f9f286fac30ac4a9af1d3a57b48fb11a653`、Tela 锁文件 SHA `5963ae08a4590ef84a8ef6c58ae250ceca919307b85b3b0930bc0d562e2fb071` 与派发一致。
- 本审查执行的命令均已终态，没有本审查启动的 Maven/后台进程；Maven 写入权释放回主线程。系统级 `ps` 受沙箱限制，未声称全系统进程清单已验证；主线程已明确不需扩大此检查。
