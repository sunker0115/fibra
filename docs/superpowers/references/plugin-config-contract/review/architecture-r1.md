# Fibra 通用配置合同候选：独立架构审查

2026-10-11。结论：**NOT READY；B0 / I4 / M2**。不修改仓库、不实现、不运行测试。审查者不是本规格作者；上一阶段仅提供过制品/消费者调查报告，本轮以完整候选与固定源码独立复核，不以原推荐自动通过。

精确输入：

- `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` SHA-256 `5e3b1e5eab80bbf5371b9a06165f152763a7d1f7cf961ed991f683dd46fb32ca`。
- `docs/superpowers/references/2026-10-11-plugin-config-contract-evidence.md` SHA-256 `aa0f05f5f727233b428359bcdd37b17990075af70a898881592789b272e5d461`。
- Fibra生产HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`；完整读取规格、证据索引、资格补充和推进计划。采用 doc-consistency-review 的五类与呈现前交叉核验。
- 固定参考源码再次核HEAD：OpenCode `388406238bd5ca15564a762840a2362c3a45bd9c`，Pi `f1b2e77f5b13b2a199b1052cb79c235451afe7d7`，DSH `c291e7961a515f6d7af9304e7fd1d257929aef26`。没有运行它们的测试。

## A. 前后矛盾

### M1. 根引用 schema 的必需元数据与 sibling 规则不相容

位置：设计 `:108–109`。

> | 基础 | 根 `$schema` 必填且固定上述 URI；每个非 ref schema 显式 type，单一标准类型或非 null 类型与 null 的双类型；title/description 纯文本 |
> | 复用 | 根 `$defs`，仅 `#/$defs/<ASCII标识符>`；引用仅允许 title/description sibling；全部 defs 无环，按引用路径计算展开预算 |

按两行原文，根 `$ref` 既必须拥有 `$schema`（并通常拥有 `$defs`），又不允许这些 sibling；“每个非 ref schema”未明确禁止根 ref。实现者会做出两种不兼容的准入。

最小修改：明确根ref是否支持。推荐允许根元数据 `$schema/$defs` 与 `$ref`，断言 sibling 仍禁止；或明确仅子节点可ref且给作者根包装例子。两种均需一条正例和相邻非法 sibling 反例，不要靠实现自行猜。

## B. 语义不清

### M2. 公共异常审批清单没有闭合 Engine 准入状态与稳定枚举

位置：设计 `:170–172`。

> artifact 的创建无I/O。Engine先登记句柄，再 read；stream 打开后立即属于该句柄，才允许可失败读取。有界读、拒绝非regular/symlink，按 exact managed root 固定文件名定位；read返回防御副本，重复read/close观察同一实际终态。
> `ConfigSchemaException` 区分 unsupported profile/schema invalid/budget exceeded；`ConfigContractException` 区分 stale/not selected/package unavailable/unsupported runtime/unknown definition/missing/invalid/read/cleanup。

这里列出了大部分失败，但未列新查询在Engine未启动/关闭/FAIL_STOP/停止准入时的code；`missing/invalid/read`含义和优先级也还不是可签名的公共枚举。现有名称查询 `FibraEngine.java:301–323,371–379` 明确具有ENGINE_UNAVAILABLE和同步非法参数检查，不能让新API临时抛通用IllegalStateException或把关闭映成READ_FAILED。

最小修改：参照原名称query补一张稳定Code/优先级/同步参数检查表；读失败与schema invalid、cleanup优先、未知facet与definition是否合并明确写出。若是完全继承原目录准入/取消规则，写明确引用与新增差异即可，无需复制全部实现细节。

## C. 缺失

### I1. 通用作者 profile 缺少已证实的普通字典与有限变体场景，不能只用当前内置插件覆盖说明排除

位置：设计 `:108–116,120,132`；证据索引 `:49,58–63`。

> | object | properties、required、additionalProperties（必须显式 boolean）、minProperties/maxProperties；required 无重复且属于本层 properties |
> 所有其它关键词拒绝，包括拼错和 `x-*`。尤其不支持 regex/pattern/format、组合分支 allOf/anyOf/oneOf/not/if、recursive/dynamic refs、外部 refs、uniqueItems、contains、unevaluated、dependent、作者 message/default/examples/readOnly/writeOnly 等。
> 这些是**待正式边界验证的接纳成本预算**，不是毫秒 SLA 或强制CPU取消能力。禁止把 Future timeout/failFast/返回错误数当资源上界。无循环、组合分支、regex、深枚举后才具备有界分析基础。

不是要求完整JSON Schema。问题是本profile同时排除了两种很基础、也不依赖循环/regex/无限schema的实例配置形状：

1. `headers/env/命名后端` 等动态键、已知值类型的map。`additionalProperties:true`只能放行任意值，false只能禁键，均无法静态表达“所有header值为string”。让Java/Node/client各自业务校验此结构，会丢掉此次统一结构合同的主要价值。
2. 有限tagged分支或跨类型的有限变体，如 `local{command}` / `remote{url}`，以及 `oauth:false | OAuthObject`。把所有字段变optional再交runtime判断并非等价结构合同；增加一堆独立definition也改变作者的实例模型。

真实一手证据（固定源码，均是配置，不拿工具参数充数）：

- OpenCode `packages/core/src/config/mcp.ts:15–24`：Local配置包含 `environment: Schema.Record(Schema.String, Schema.String)`；`:34–47`：Remote包含同类headers，OAuth为对象或false，Server是Local/Remote tagged union，servers是动态键map。
- OpenCode `packages/core/src/config/provider.ts:7–10,65–70`：request.headers是typed dictionary，models是string→Model。
- Pi `packages/coding-agent/src/core/settings-schema.ts:9–10,18–25`：timeout是number或"disabled"，modelOverrides是string→结构对象；`:213–216`还包含按模型名的枚举字典。这是产品设置形状，不能直接当Fibra插件合同，但足以证实通用输入的正常使用场景，不能仅以当前内置definition没有使用而删除。
- DSH `packages/session/session-telemetry-otel/src/index.ts:95–119` 的真实插件配置传递SDK exporter/processor options；至少说明插件作者需要嵌套SDK配置，不宜仅按现Fibra内置简单字段裁剪平台。

官方标准也明确additionalProperties可以是约束值的schema，而非只能boolean：[JSON Schema object](https://json-schema.org/understanding-json-schema/reference/object#additionalproperties)。有限组合可组合现有schema，oneOf需验证全部候选，官方也提醒成本：[JSON Schema combining](https://json-schema.org/understanding-json-schema/reference/combining)。这支持做预算资格，不支持假定所有无环有限分支都无法有界。

最小修改：给profile补 `additionalProperties: schema|boolean`；把schema边纳入原E预算，遇动态键的诊断仍只到安全父object。增加受限、有分支数量上限的无环 `anyOf/oneOf`（可先限制为明确互斥类型/标签的作者形状），所有实际分支按边计入E及U；原成熟库执行标准语义，Fibra不手写验证器。先用上述typed-map、local/remote、false-or-object/number-or-disabled真实场景做资格，失败再回依赖/设计门。recursive refs、regex、multipleOf的现有明确风险可分别保持拒绝，不必为支持普通map顺带扩大到全方言。

若用户确实只需要现内置简单配置，另一合法修法是明确把首版能力降为“受限简单配置”，列出这些已知不支持场景及后续目标；但这是对本次“通用插件配置合同”目标的材料缩减，需要用户明确选择，不能用一条“将来出现真实需求再重开”把已找到的成熟场景划走。

### I2. 命令级总预算没有 config-owned 公开执行接缝，批准现API后实现会被迫越界或新增API

位置：设计 `:128,147–159,174`。

> 对有效值迭代统计 N 与总文本 B，每 entry `U=E×(N+ceil(B/256))≤1,000,000`，同一命令总 U≤4,000,000；乘/加都在上限+1饱和。
> final class ConfigSchema {
>     static ConfigSchema compile(ConfigSchemaSource source);
>     Optional<ConfigViolation> validate(LiteralValue effectiveValue);

单次compile/validate只能封闭单schema/单值。Engine没有获批API读取E/U或提交一个batch，却被要求在任何candidate前统一管整命令U、128份唯一schema、总schema输入。把算法再写一遍Engine违背config唯一owner；私有方法不能跨Maven模块调用；实现时临时加public预算计数器又超出本次冻结签名。

最小修改：新增一个config-owned批量纯检查接缝，接受中立schema/value项并返回安全失败项索引/诊断，同时统一累计编译与验证预算；或者明确一个短寿命ConfigValidationSession/预算上下文的public方法，由config内部计费、Engine仅创建并传递。优先最小批量方法，不把工作成本数字变成可绕过的用户传入参数。为公共独立 `ConfigSchema.validate` 明确只保证单项预算；命令路径必须使用batch。无需新Registry、线程池或生命周期服务。补上同schema多实例按实例计费与跨schema总额的反例。

### I3. 唯一 builtin 摘要算法遗漏 facetId，重命名可复用旧 package identity

位置：设计 `:212–221`。

> SHA256("fibra-built-in-package-v2\0" + canonical({
>   pluginId, version, implementationDigest,
>   sorted facets(runtime,target,dependencies,capabilities,definitionIds,configSchemas)
> }))

现文字列出的facet内容没有facetId。“sorted facets”只排序不会自动把排序key序列化。对于单facet builtin，仅将facet从main改成alternate，其余字段/schema/implementationDigest相同，则按当前明确算法最终digest相同。旧selection仍会匹配，且同packageRevision的名称目录可突然换facet，破坏精确包身份。`BuiltInPluginPackage.java:30–34`表明facetId是ArtifactId和所请求绑定的一部分，这不是可忽略展示字段。

最小修改：canonical schema显式包含每项facetId及所有依赖的pluginId/facetId，明确排序key；不要仅写“现有规范排序”。加入单facet改名、两facet相互换名称/内容、相同schema不同profile的identity反例。无需另加digest字段或更复杂架构。此项是拟设计算法缺口，不声称当前未实现代码已有新bug。

### I4. 作者全包发布资格是必交付规范，但没有可执行 owner/入口与真实消费者闭环

位置：设计 `:73–75,155–159,196–198,241,260`。

> 作者源文件推荐 `src/plugin/fibra-config.json`。Java 插件构建将同一源字节放进 JAR 的 `META-INF/fibra/config.json` 供发行装配，并复制/提取到完整 package 根；Engine **只读 package 根**，无 JAR fallback。
> 作者发布资格检查整个包的 facet/definition 精确集合：有 definition 就必须有合同；零 definition facet 用空映射。发布检查与运行检查分开：安装/选择但不执行可以暂存未合格包，不能据此标记“作者合同合格”。

懒运行检查已经有明确行为，但全量资格目前只是一句要求：谁在何时枚举真实runtime声明、检查orphan/missing/每份schema、失败如何阻断作者构建，以及archetype/只消费正式JAR的外部作者如何调用，都未确定。单独 `DefinitionConfigDocument.parse` 没有名称真源；单个 `describeConfiguration`也不能发现多出的schema键。仅在Fibra内部fixture临时断言，不能兑现“作者从正式制品和文档完成真实消费”。

最小修改：冻结一个**可执行作者QA流程**，不一定新增公共Engine方法。可用现有正式API构建一次临时Host，安装/disabled选入package，通过原describeDefinitions枚举名称，读取公开DefinitionConfigDocument的绑定集合比较，并compile每份source，最后正常close；针对external runtime作者明确由其provider参与、builtin使用相同纯值集合检查。将该流程做成可复制的测试/构建fixture，archetype与仓外发布消费者实际执行；输出不增加持久“已合格”状态。若该流程过重，另行设计窄的资格检查API并纳入审批，而不是留下无owner的标准。用orphan、缺schema、零definition、inactive坏schema仍可保存但作者资格失败四格明确区分。

## D. 可调整

无额外可选事项计数。对default/examples暂不支持不单列问题：规格已经明确不给schema default注值，并诚实留出展示限制；这与是否需要未来显示默认值是另一需求。multipleOf有固定反例，拒绝近似执行成立。此处没有要求为“完整标准”引入新库或升级Jackson。

## E. 内容定位错位

未发现必须迁移章节的错误。规格保留profile预算与公开API是审批所需；最终细节另落使用文档/发行验收清单的安排合理。证据索引没有把Metatype倒写成D37旧参照。

## 已核对可保留的设计

- 轻名称目录与按需合同查询分开，不重开D37真源；固定根资源没有任意路径API，不增加第二配置事实。
- artifact固定字节owner、config解析/纯检查、Engine精确身份/时序，模块依赖方向清楚；公开artifact reader是有职责的窄API。其完整并发/read-close协议仍要实现反例验证，本轮不冒称已经证明。
- 原始/effective检查、package gate、inactive延期、候选升级与保存后未ready分开。当前规格没有“inactive必须全包先验”矛盾；I4是发布资格执行闭环缺失，不是要求把发布门塞回每次Apply。
- Java作者源→JAR供装配资源→package根是一个编辑源的派生产物，不天然形成第二真源。根是唯一运行来源、无fallback、发行从正式JAR提取并核字节已写清；跨JAR合包显式重新绑定也合理。
- builtin从实现摘要与metadata派生有效digest、旧selection不自动迁移方向正确；I3只要求补全唯一算法输入。
- 公开schema查询不读现值；管理异常snapshot与错误DTO安全分开，未以null view偷改旧合同。
- 文档/模板/正式制品/真实Java与Node/产品client import-create-close的验收面齐全；旧51项与Desktop实机证据明确分开，Tela主树未获修改授权。

## 复核方法与边界

呈现前完成三项交叉检查：再次读取被引规格原文；搜索同文档是否已有batch/全包资格执行入口/facetId/root-ref例外；重新读取固定OpenCode/Pi/DSH行段。排除“inactive与全包发布资格直接矛盾”“根资源就是第二真源”两种假阳性。所有新事实仅为源代码/规范证据，没有执行参考项目或新Fibra测试。

Nowledge MCP thread_search，query=`Fibra 通用 插件 配置 合同 profile`，mode normal，limit3；返回早期发行/CLI架构会话，没有本次profile证据，未据其形成finding。没有新增长期记忆。

最小关闭条件：修I1–I4与M1–M2后，提供新的完整规格hash及明确profile/公开API差异，重新整体审查。即使设计通过，也只关闭设计门，不能关闭总目标或启动未批准的公共API/依赖/跨仓实现。
