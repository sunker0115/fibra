# 通用插件配置合同候选：独立 schema 审查

日期：2026-10-11。审查人未编写本次主规格；此前只提供候选库资格报告。本次只读核主规格、固定参考源码与当前 Fibra 输入/Node/client 数值路径，未修改仓库、未运行正式 Maven 或消费者验收。

审查对象：`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`，SHA-256 **`5e3b1e5eab80bbf5371b9a06165f152763a7d1f7cf961ed991f683dd46fb32ca`**。读取开始和结束摘要一致。结论 **B0 / I3 / M1**：方向可保留，三项重要问题未闭合前不建议关闭设计门或批准实现。B=阻断根本方向，I=实现前必须闭合的重要合同/资格问题，M=较小规格澄清。

## I1：profile 的通用场景资格只覆盖了现有简单配置，类型化字典的拒绝缺少真实场景取舍

定位：规格第111、116、120、237、239行（§5、§10）。

当前 additionalProperties 只能 boolean，properties 又必须预声明键。因此 `headers: Map<String,String>`、`environment: Map<String,String>`、按模型ID索引的配置对象等常见形状无法表达值的统一类型约束：只能 `additionalProperties:true` 接受任意值，或冻结动态键名单。前者把已经明确的结构规则退回runtime，各runtime可能分歧；后者改变配置语义。这个限制不是multipleOf式已实证库错误，也不需要regex或循环分支才能支持。

固定参考源码的真实实例：

* Pi `f1b2e77f5b13b2a199b1052cb79c235451afe7d7`，`packages/coding-agent/src/core/settings-schema.ts:22-25` 的 `modelOverrides` 是字符串键到对象的Type.Record；`:213-216` 的 `modelThinkingLevels` 是动态键枚举值字典。它们是产品配置形状的证据，不冒称Pi已提供Fibra等价的插件实例合同。
* OpenCode `388406238bd5ca15564a762840a2362c3a45bd9c`，`packages/core/src/config/mcp.ts:21,37,47` 分别是环境变量、HTTP头、按名字索引的server；`provider.ts:8,70` 为headers和model字典；`formatter.ts:8` / `lsp.ts:13` 也是环境字典。它们说明扩展/模型/MCP/工具宿主的常见需求，不要求Fibra复制其产品字段或生命周期。

建议先将 `additionalProperties:<受限schema>` 纳入候选资格：它对每个未在properties出现的成员执行同一子schema，不引入anyOf回溯；纳入E子边、N/文本总预算、ref DAG、深度和安全路径规则即可做有限分析。须补正式/仓外资格证明，不把本审查直接视作已获批准的API扩容。若仍拒绝，必须明确呈现“首版无法统一校验常用字典值类型”并让目标范围批准覆盖这个损失，不能只以当前Tela字段尚未需要为通用架构结论。

参考里还存在 tagged union（OpenCode MCP local/remote，`mcp.ts:43`）、boolean-or-object（`:38` OAuth false）、number-or-disabled（Pi `settings-schema.ts:9-10`）。它们未必都必须在首版支持：可以合理保留variant业务不变量给runtime，但应有场景矩阵区分“结构保证降低”与“资源/业务检查本来就应runtime拥有”。不推荐为这些场景直接放开任意anyOf；类型化字典是本项最小且有明确收益的修正。

验收：headers字符串成功/数字值失败；动态model键嵌套对象成功/未知字段失败；大量键、嵌套ref、未知敏感键失败时父路径裁剪；预算按成员次数计费。候选语法和所有入口同规则，而不是每个插件再手写同类检查。

## I2：单schema数据预算与按需解析隔离的责任尚不闭合

定位：第126、147-159、197-198、223行（§5.1、§6、§7、§8）。

第147行规定ConfigSchemaSource构造检查“数据预算”；DefinitionConfigDocument.parse同时返回所有definition的ConfigSchemaSource映射。第126行又列单schema64KiB/4096节点/32层和整文件1MiB等不同预算。第198行明确独立schema语义错误不影响active sibling，仅公共envelope/格式/全文件预算影响全包，但没有规定**某个dormant sibling只超单schema预算**属于哪类失败。

可证伪样例：A有正常小schema且active；B停用，schema有65KiB说明文本（全包仍小于1MiB）或33层数据。若parse逐项构造并按64KiB/32层拒绝B，则A失败；若按需compile才拒绝B，则A成功。目前两种实现都能援引正文，并导致query和发布门不同。

请明确两层预算及构造时机：整文件严格JSON、envelope形状和安全解析预算全局执行；per-definition作者profile语义及单schema资格预算按需执行，或明确把所有单schema形状/预算都列为全包读取资格。ConfigSchemaSource公开构造器到底检查哪一组数值必须写明；builtin同规则。不要在实现时临时选择。

另需将JSON解析器的字节/嵌套/数字token约束写进读取阶段，而不只写“parse之后预算”：单文件1MiB仍可包含非常深的JSON；遍历必须在可失败深冻结之前拒绝超深，不能先递归形成所有LiteralValue再检查。此点是资源接纳门位置，不要求增加第二配置系统。

验收：A active+B unknown keyword、B错误required、B单schema超预算、全文件超预算分别固定预期；同一个fixture用于按需query、active部署、全包作者发布和builtin构造。明确需要关闭哪一个owner以及安全错误subject。

## I3：宿主结构校验的精确数字边界与真实Node消费还缺显式合同

定位：第130、239行以及CC03/CC09/CC11（§5.1、§10、§11）。

从LiteralValue构建DecimalNode能保证本次结构门精确；它没有自动保证runtime实际收到同一个数学值。现有Node路径是 `fibra-runtime-node/.../NodeRuntimeDriver.java:399` 的 `prepared.config.toJava()` 后通过JSON-RPC发送；正式example `fibra-example/content-sanitizer/node-plugin/payload/index.mjs:57,63` 用JSON.parse后configure。大整数9007199254740993或高精度decimal会在普通Node接收端变成Number近似值。相较之下，client protocol在 `ClientProtocolCodec.java:314-342` 拒绝普通literal number并要求NUMBER字符串tag，已有不同的数值表示边界，不能笼统当成同一路径。

规格没有直接声称Node wire精确，但其“真实消费者/精确数值/同配置跨runtime一致”验收与“wire原则上不变”容易在实施中被不同理解填满。应在设计批准前写清：

1. 本期精确保证止于Engine effective输入结构门，runtime绑定/Node Number表示仍服从既有运行时合同；如果采用此范围，必须在作者和Node文档明确，并在真实消费者测试显示边界，不能用宿主validator结果冒称执行值精确。
2. 若本目标要求跨runtime实际配置值也精确，当前Node路径不足；需单独设计并审批数值wire/运行时限制或编码方案，不得先保持wire再宣告整个目标通过。

Pi固定 `settings-schema.ts:5-6` 显式用Number.MAX_SAFE_INTEGER约束部分计数；这是成熟项目对JS数值域的主动声明，可参考但不能据此把全部Fibra数值静默压到JS安全整数。允许作者按runtime数值域写schema也需要文档，而公共schema本身不应被Engine按runtime名偷偷改写。

验收：真实Node插件回报实际读取的数字表示；对应client consumer保留tagged decimal；Java结构门对相邻数值仍能区分。结果必须按上述批准范围判定，不能用fake ACK或只运行本地10条probe代替。

## M1：根ref与根必填元数据的文法冲突

定位：第108-109、116行。

根必须包含`$schema`，`$defs`又只能在根；ref节点只允许title/description sibling。这意味着常见根 `{$schema:..., $defs:..., $ref:'#/$defs/config'}` 不可能通过。若首版不支持根ref，请直接说明并给有效根内复用例子；若支持，根ref应允许固定`$schema`/`$defs`作为元数据sibling，同时继续禁止断言sibling。也请补ASCII标识符的准确字符集/长度，别让API实现重新发明文法。

## 已成立的设计与相对资格报告的变化

* 根schema描述值放宽为任意显式JSON类型，并明确无配置null/nullable，不把null改成空对象。这是对现有runtime输入更合适的收敛，未发现安全问题。
* 默认值由runtime单一事实源拥有、结构门不验证Java专有返回对象、不回写target，方向成立。Pi其实从`settings-defaults.ts`导入同一常量到schema的default注解，说明“展示默认”不必然要求第二值源；本期不做同源生成可以作为显式范围决定，不能把“不注入”误等同“标准不允许描述”。目前正文已保留未来同源生成门，未据此另列重要问题。
* 拒绝regex、循环引用和任意组合分支，加每路径DAG展开预算，确实比只限schema输入字节更可靠。数值/文本/节点上限和U是待资格的接纳成本模型，正文没有冒称CPU硬取消。尚缺全allowlist元schema/预算实现与真实规模边界，10条probe只能支撑局部可行性。
* 固定标准meta-schema白名单、作者loader禁止外部资源与不使用插件TCCL方向正确；正式实现须证明每个固定依赖的加载都被白名单覆盖、未使用默认classpath fallback，否则“阻断网络”仍不等于静态纯校验。
* Error/cause/suppressed不外传，未知属性只报已声明父路径，保留受权PublishedView的例外并测官方投影，安全承诺比粗略“任何Throwable图无配置”准确。实现须证明config/compiler对象也不长期持有库Error；不是只替换message。
* YAML exclusion与config已有YAML能力区分、Jackson3.1.5/SLF4J2.0.18不偷升、Joni/Graal不引入、正式SCA/NOTICE/发行门仍打开，正确。没有将存在release等同全面安全。

## 审查依据与范围限制

主规格与本次归档资格报告已读；复核了Pi/OpenCode上述固定源码和Fibra当前Node/client代码。沿前次已核的networknt3.0.8固定源码及探针，不重新跑全库调查。没有认定networknt任意JSON Schema资格通过，没有认定产品表单可由此完整生成。建议保留“扩展现有config、统一Engine门、runtime资源owner”总体路线，但先补I1场景资格与I2/I3边界，再交材料性批准。

按Nowledge路由做一次MCP memory_search：query=`Fibra 配置合同 profile multipleOf Pi OpenCode`，limit3，strict/default；结果为旧CLI范围/配置owner/权威文档背景，不包含本profile的直接资格证据，未据其作本审查结论。没有进行图展开（缺少确认同Space授权的图入口证据），没有写长期记忆。

## 补充：类型化字典和有限联合的有界支持资格

主线程提出允许additionalProperties schema与有限allOf/anyOf/oneOf的修订方向后，本轮增加仓外 `FiniteProfileProbe.java`，没有修改被审规格或撤销上述I项。仍应以主规格后续精确摘要重新审查，不能把本附录当自动批准。

实际执行：旧已核classpath上的networknt3.0.8+Jackson3.1.5，Zulu21，`-Xmx64m`，子进程20秒超时。`finite-compile.json` / `finite-run.json` 的nativeExit均0；28个断言通过。探针构造有限DAG，所有引用同文档且无环：宽2或8、组合深2或4，分别allOf/anyOf/oneOf，每次failure输入0，叶子要求string。每个组合节点通过同层重复ref制造真实路径放大，不使用无限递归。

| 组合 | 宽8/深4，failFast=false最终错误数 | failFast=true最终错误数 | 固定源码含义 |
|---|---:|---:|---|
| allOf | 4096 | 1 | 顺序逐分支执行，可在首个失败断言抛快停 |
| anyOf | 4096 | 4096 | 临时禁failFast，收集各失败分支errors并逐层addAll |
| oneOf | 4681 | 1 | 临时禁failFast逐分支；原配置failFast=true时不聚合child列表，但仍执行失败分支并创建错误 |

最终错误条数不是峰值/分配总量。探针同时记录 `summedHeapPoolPeakUsed`（各heap pool在本次重置peak后的最大已使用值之和）：宽8深4anyOf两模式约8.7/10.8MiB、oneOf约13.9/16.0MiB；数字包含同进程既有对象、JIT和GC影响，各pool峰值也可能不在同一时刻，**不是本次校验净分配或可移植峰值上界**。仅用于避免把“返回1个错误”当“低内部成本”，不将其作为容量/SLA。固定源码 `AnyOfValidator.java:64-184` 与 `OneOfValidator.java:75-239` 明确了临时列表、复制与快停差异，正式预算须针对这些分配机制。

字典4断言：两个动态键的精确boundary/大整数通过；相邻小decimal失败；字符串数字不转换；库 `getInstanceLocation` 含原始动态键。即使additionalProperties schema接受该键，这仍是输入秘密，安全投影不能把“已允许动态键”误认为“作者声明的字段”。应定位字典的最近静态父属性；字典值再嵌套也不能通过完整指针泄漏该键。

推荐修订的有限语言：

1. additionalProperties可boolean或本profile子schema，作为E的执行子边；每个实际成员按出现次数计N和校验工作。循环ref、patternProperties、propertyNames和regex仍拒绝。
2. allOf/anyOf/oneOf每个数组1–8个分支；沿**展开后执行路径**组合嵌套≤4（穿过ref/properties/items/additionalProperties也不重置），原ref DAG/展开深度/E预算继续有效。不同组合keyword并列须累计全部边，不只取max；组合分支深度与原始JSON深度是两种限制。
3. 联合专用节点可不写type，但必须含获准组合断言；否则原“非ref节点必填type”使boolean-or-object、number-or-disabled无法精确表达。含普通type的节点仍保留其独立约束，不能把oneOf自动改成anyOf或OpenAPI nullable。主规格需决定根组合/根ref元数据文法。
4. allOf不是对象合并指令；`additionalProperties:false`只看该schema本层properties。禁止unevaluatedProperties时，作者不能把两个闭合对象allOf当合并字段。建议作者闭合完整变体后用oneOf，或把同一对象properties放在唯一owner节点，不为支持union引入自动合并。

不能仅把旧U=E×N称为新增能力的完整预算。建议config-owned准入模型分开计算：访问工作V（含每路径E、N、文本权重、数值位上限），最坏断言错误数K（required每字段、additionalProperties每成员、每组合失败的错误均计费），错误聚合/路径保留成本A。对于批准的有限语言，可保守采用 `K≤weighted(E)*N`，再令 `A=K*(2*C+P+3)`，其中C为展开后组合深度、P为最大实际执行路径长度；系数显式覆盖subSchema/allErrors/existingErrors复制与每个Error的evaluation/instance路径。这个式子是待实现/边界证明的保守候选，不是本次已证明的精确内存上界；应另设K/A总阈值，并在入库前饱和拒绝，而不是validate后截断列表。enum/required长度、属性名/字符串字符、数字精度必须进入权重和固定上限；元schema校验/编译是另外的固定可信图成本，不能被V含糊替代。

共同批次预算应由config模块拥有：Engine提交本次待校验的精确schema/effective值集合，config统一做编译输入总量、去重身份、V/K/A批次总量与全体校验，Engine不复制E/U/A计算。具体batch API由主线程冻结；这维持单一算法owner，也保证同一批里后面的巨大schema不能在前面已执行runtime prepare后才失败。跨entry/cached schema不可将每次instance工作因schema去重而免计。

新增资格还欠：有限联合success、零匹配、oneOf多匹配、null/type差异、分支中嵌套字典与array、allOf闭合对象语义、边界组合/ref混合、K/A阈值相邻值、恶意动态键和全部失败路径、安全聚合、正式源码行级算法证明及真实行业形状fixture。28条局部probe不关闭设计或发行资格。

对主线程随后提出的简化 `W=E*(N+ceil(B/256))*(C+1)` 的精确复核：可以继续作为接纳工作分数候选，不据当前证据断言它在有界profile下必然遗漏指数项；也未证明它足以代表内存字节或CPU硬上界。E必须精确定义自身权重，至少覆盖每个keyword、required/enum成员、properties成员及文本，否则“一个schema节点”可发出多条错误。C因子与AnyOf逐层错误引用复制相符，但包含ArrayList容量增长等常数，不能将W=1说成一个字节或一次操作。

进一步核固定 `path/NodePath.java:30-82,148-154` 与 `ExecutionContext.java:50-63`：append使用共享父链、新建一个节点，**并非每个Error复制整条路径**；此前附录的P因子只是一种保守保留/投影成本模型，不能反述成库已经这样复制。getNameCount递归、toString回溯并缓存字符串；若安全投影逐index调用getElement，会产生额外二次路径扫描。应线性回溯且只对选定的一条诊断做有界投影，不格式化所有库错误。

W仍不覆盖单次实例校验之外的工作：固定meta-schema校验、schema编译/preload、enum规范化/集合构造及strict reader深冻结都需config batch自己的输入总量/深度边界；Error.build保存惰性messageSupplier，调用message/toString会触发额外格式化并泄漏参数，因此禁止原错误格式化是安全与成本共同要求。当前规格可写明这些独立前置预算与正式资格停止条件，而不能以一个W公式宣布所有阶段已经有证明。
