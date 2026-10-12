# 通用插件配置合同调查

## 2026-10-12 预期超时的阶段证据

- `NodeProcessUnitTest` 原测试允许“写任务尚未执行实际 I/O，关闭已结束，随后写入失败被吞掉”的时序。测试内临时 gate 强制该时序，原测试仍通过（`old-delayed-writer.log`）；补强后相同时序在进入证据处明确失败（`new-delayed-writer-red.log`）。
- 本轮 owner 不变：测试直接观察自己的线程，artifact 验证目标 resource monitor、首次 read owner 及 close/read 栈；CLI 验证具体 session monitor 的 readLine 交接等待；Node 验证指定 writer 的真实管道 writeBytes，以及由本 fixture Process 捕获的 close 线程在 finishClose 直接调用 join。后者排除在 NodeProcessUnit 内等待另一关闭者的假匹配。
- 保留 Node 用例的 100ms 负向观察，但先取得真实阶段证据；artifact/CLI 的负向超时改为具体锁等待证据。所有新正向等待有上限，不把一次 isDone 或仅启动 latch 当进入证明。使用既有 JDK 21/平台线程观测范式，不增加生产 hook 或依赖。
- 反例临时取消 resource close 同步、CLI 消息等待、Node 输出尾部 join，4 个用例全部 assertion failure；加上延后 writer 共 5 个预期失败、0 error。所有临时修改按保存 SHA 恢复，完整 clean verify 对恢复后的源码重建，避免残留 mutant class。原始日志位于 `/private/tmp/fibra-timeout-proof`。

## 2026-10-12 合并后 CI 观察竞态

- main CI `38246646807` 的 npm 通过，Maven 唯一错误为 `CrossRuntimeSelfDisableTest` 等待 `candidate=FAILED` 10 秒超时；同 SHA 开发分支 CI `38243119872` 已通过。
- `PublishedRuntime.views()` 的合同允许合并中间事实；实际链使用 `onBackpressureLatest` 与异步交付。`deploymentFailed` 发布 FAILED 后清空 candidate，失败诊断及操作结果仍保留，测试不应把通知流当事件日志。
- 同版 Reactor 3.8.6 临时实验稳定重现 FAILED 被最终视图替代；本轮将受控零需求观察纳入真实 Java/Node 场景。CI 日志无逐帧视图，不把推断说成 CI 现场完整重放。原日志与诊断位于 `/private/tmp/fibra-ci-38246646807`。
- 有界同类审查发现 `NodePublishedCancellationOwnershipTest` 的 DRAINING 有远端 release 文件屏障，但 STOPPING 后立即 ACK，也可能被合并；应断言停用回执、真实生命周期文件顺序及进程退出。
- `PluginRegistryTest.watchProjectsEngineFactsWithoutOwningASecondStateMachine` 还有同源异步交付竞态：安装回执与 watch 通知独立完成。现改为在统一 10 秒期限内消费通知直到回执对应的精确 viewRevision，并核对 Engine 投影；不引入生产缓存或新状态机。
- 真实 self-disable 场景同时覆盖普通需求与延后需求；测试只在候选清理结束后释放慢订阅者请求，使中间失败视图必然被合并。原谓词反例 `old-predicate-red.log` 为 2 项、1 failure、0 error，只有慢模式失败；定向修复回归 `focused-green.log` 为 17 项全部通过。完整回归及最终独审另记。

## 已核事实

- `main` 初始干净，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`；`fc57549` 为名称目录交付，后者仅 Node 测试等待修订。已创建独立 `codex/plugin-config-contract`。
- 祖先至仓根未发现 AGENTS.md；用户提供规范有效。当前目录另一个名称目录会话 idle，Tela 设计会话 active。
- 权威文档为 vNext、Client Foundation 及专题规格；阶段事实位于 docs/superpowers/plans；公开使用边界 docs/api/README.md 和 README；公共签名与发行门分别见 docs/api/*-public-signatures.txt、docs/release.md。
- 目录原计划明确配置 schema 不在已批准范围。旧交付不得传递证明新能力。
- 已读取三个仓外候选报告：owner review、public seams、networknt evidence；内容仍须逐项以当前源码/固定制品复核，报告不是规范。
- Nowledge 定向搜索返回数值 wire/历史 profile/产品边界记忆，未直接命中本次 D44 合同。旧记忆曾包含早期 Registry owner 表述，不作为当前实现事实。

## 待核

- 完整配置链与内部 reader/关闭复用形状；统一 preflight 顺序。
- 静态合同格式与精确身份、作者 profile 与预算、公开按需查询、全部 provider/builtin 迁移影响。
- networknt 固定源码/JAR/probe 字节与依赖资格；正式发行并未验证。

## 新增核验

- 用户补充：有问题先查参考项目，明确要求 Pi、OpenCode。已核两本机固定 HEAD 和具体实例配置/工具参数/产品设置路径，详见 references/2026-10-11-plugin-config-contract-evidence.md；未运行其测试。
- 用户询问 config 融合：已明确推荐复用原读取/求值链，在 effective 与绑定之间加纯结构门；schema 是规则，不是第二份值/存储。停用延期仍保留。
- artifact 当前不依赖 fibra-api/config/Reactor；尚无可直接复用的固定schema资源失败owner。设计不能假称现成，需评估最小固定文件句柄接缝，避免倒置模块依赖。
- `LiteralValue` 真正路径是 fibra-api/.../value/LiteralValue.java；之前两次猜测路径失败后改由 rg --files 定位，后续不猜路径。OpenCode V2 effect 为目录，已用文件清单找到 plugin.ts/context.ts。
# 2026-10-12 builtin迁移前复核

- `RuntimeDriverEngineTest.catalogBuiltInMetadataIsFreshAndDuplicateWrongRuntimeAndWrongDigestAreRejected`当前允许同一人工digest下更换definitionIds后直接读到新名称。新effective digest明确覆盖definitionIds，后续Task3必须把此断言迁移为旧selection拒绝、显式CAS选择新revision后读取成功；不能只改builder方法名后保留旧语义。
- `JavaBuiltInRecoveryTest.deployment`直接用旧常量DIGEST构造selection，后续必须接收对应metadata的最终packageDigest；metadata包含不同definitionIds时最终revision不同，测试仍须真实命中原provider definition一致性失败，不能让revision不符提前掩盖它。
- `DeploymentTargetCompiler`已有disabled selection也检查builtin exact revision的逻辑，可直接复用。`FibraEngine.inputFingerprint`已有最终packageDigest，Task4补解释器身份时不需要额外全局schema缓存。
- 后续Engine只读接缝复核见[engine-preparation.md](engine-preparation.md)。另一个builtin测试`builtInDeclarationDriftChangesFingerprintEvenWhenContractAndDigestStayTheSame`也必须迁移：requiredCapabilities纳入最终revision后不能继续假定metadata变更保持digest；provider contractIdentity独立改变的原场景仍保留。
- Task4 cleanup重点：BOOTSTRAP先显式BLOCKED再failStop，否则已有早返保留CONVERGING；shutdown必须先观察读owner的缓存关闭失败，再创建SHUTDOWN operation。既有failStop会把closeAdmission异常toString放入cleanupFailures，新安全合同路径须消除这条原异常文本泄漏。优先在现有唯一failStop关闭准入边界使用固定安全文案并保留致命错误传播，不增加第二套状态转换入口或额外策略开关；测试继续验证真实关闭尝试和原首错。
- Task4测试接缝已决：沿JavaRuntimeDriver已有包级构造注入惯例，FibraEngine私有默认构造委托包级`(Builder, Function<PluginPackageRecord,PluginConfigurationResource>)`，生产仍用record方法引用；函数只负责惰性创建，句柄仍立即登记唯一父owner。无public Builder测试选项。双失败使用主code()+固定安全message组合枚举，不新增secondary getter/原cause；已同步权威规格§6。


## 2026-10-12 用户追问检查必要性后的架构复核

用户问“参考项目也是这样检查的么”“那我们需要这样检查么，从架构和行业场景来考虑”。重新读取本地固定Pi/OpenCode源：Pi工具参数存在转换与回显，OpenCode插件options开放传递；二者不证明Fibra整套检查已有等价实现。当前共同effective结构门、精确候选revision与12正式包构建名称/schema/bytes QA均对应已有部署、恢复、多入口及打包风险，应保留；不把它们扩为运行时全包资格服务。

重新核官方Terraform framework validation与resource configuration validation文档：基本类型/必填检查跨validate/plan/apply入口，配置验证无需已配置API client；只采纳结构门与后续业务资源检查分工，不复制RPC/状态体系，也不声称Terraform无插件执行。来源：https://developer.hashicorp.com/terraform/plugin/framework/validation 和 https://developer.hashicorp.com/terraform/plugin/framework/resources/validate-configuration 。OSGi Metatype和VSCode configuration只支持描述/统一访问与呈现分工，不能推出Fibra保存/关闭保证。

独立有界复核确认：FixedPluginConfigurationResource直接持有Path/evidence/InputStream，不直接引用runtime/targetStore。任何schema文件close失败冻结整个Engine并保留全部依赖，是当前批准合同采用的保守隔离政策，并非物理依赖必然。保留packageStore独占锁可解释为阻止失败句柄存活时再次接管，仍属于政策取舍。已向用户纠正此前过强解释；当前未改已批准合同或实现，也不在Task5顺手改失败模型。若收窄，需按真实依赖明确终止/保留/继续清理边界并独审，不能通过吞错或无条件关闭变相绕过。Task5继续既有门，暂停扩展额外防御矩阵。
