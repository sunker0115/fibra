# 通用插件配置合同调查

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
