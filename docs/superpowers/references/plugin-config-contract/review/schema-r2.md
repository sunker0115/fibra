# 配置合同设计 schema 复审 R2

2026-10-11。只读审查；未修改 Fibra/Tela 或依赖。审查对象：`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`，SHA-256 **`0792890247fd0f1289351e3ce5c481f5e6e7bc3bc41f928f5ff895ed020b4eb8`**；读取开始/结束均一致。

## 结论

**B0 / I0 / M1。此前 I1/I2/I3/M1 全部关闭。** 在本次 schema/profile/数字/预算/诊断职责审查范围内，候选已足够具体，可以作为向用户申请公共合同/依赖/实现范围批准的材料。剩余M1是现有安全原则在新增字典能力上的直接澄清，不涉及新增架构选择；建议在定稿前补一句。

这不是生产实现或正式发行通过；允许进入获批后的实现与验证，不意味着作者profile已经完成全部资格。正文把U定位为接纳分数而非CPU/内存硬界，并明确正式边界验证失败时修改预算或返回依赖门，因此相关测试尚未实现不再作为本轮设计缺口。artifact owner/字节身份和Engine全生命周期由相应独立审查负责，本报告不替其作超范围通过结论。

## 上轮问题关闭证据

| 上轮问题 | 本版关闭依据 | 本轮判断 |
|---|---|---|
| I1 通用场景缺类型化字典/有限联合 | §4.2新增headers/env、model map与local/remote、false-or-object、number-or-disabled能力矩阵；§5支持schema-valued additionalProperties和专用allOf/anyOf/oneOf组合节点，2–8分支，展开路径组合深度≤4；完整分支独立闭合 | 已关闭；不再把常见结构规则一概退回runtime，也未复制Pi/OpenCode产品配置顶层 |
| I2 全局与单schema预算／dormant隔离 | §5.1分别明确strict parser 1MiB/68层/32768节点/token512、中立source构造界、单schema64KiB/4096节点/32层；明写A active/B dormant超单schema成功与全局失败的两类预期 | 已关闭；parse不编译所有source，单schema资格按需，builtin同中立边界 |
| I3 精确数值与Node实际消费 | §5.1明确精确保证止于effective LiteralValue结构门；Node Number/client tagged decimal/Java作者类型分别说明；保持wire且要求真实Node显示9007199254740993边界，小数不因安全整数区间而精确 | 已关闭；精确结构门与runtime表示区别已成为公开范围，而非测试时临时降低承诺 |
| M1 根ref元数据及标识符文法 | §5根ref允许固定$schema/$defs元数据，禁止断言sibling；ref标识符固定`[A-Za-z_][A-Za-z0-9_-]{0,63}`；组合节点不混type，叶节点显式type | 已关闭；行业probe直接采用根ref+$defs，实际库路径可运行 |

默认分工仍清楚：不注入默认，合法外部缺省/null由schema接受，runtime拥有归一化，不对Java专有返回类型再做JSON校验，也不回写raw target。未来展示默认若需要同源生成，应另立合同；当前取舍不再是未决设计选择。

## M1：明确允许的动态字典键也不进入错误定位

定位：§9首段。“unknown字段”容易被实现者只理解为additionalProperties=false时的未知字段。本版新增additionalProperties schema后，键可被允许，但名称仍是用户输入，可能本身含敏感值。

上轮`FiniteProfileProbe`已经证明：字典数值验证失败时库的instanceLocation包含原始动态键。建议直接增加：**“由additionalProperties schema接受的动态键同样视为输入，诊断定位到最近静态声明的字典父路径，不输出动态键及其后拼出的完整路径。”** 这是现有“不回显输入／按作者结构映射”的落实，不改变ConfigViolation形状，也不需要新的产品权限方案。CC10增加这一canary即可。

## 本轮行业形状 probe

源码：`/private/tmp/fibra-schema-qualification-probe/IndustryShapeProbe.java`。
回执：同目录`industry-compile.json`、`industry-run.json`及各自stdout/stderr。保留实际argv与nativeExit；编译/执行均0，**18条断言通过**。固定networknt3.0.8、Jackson3.1.5、原已核6-JAR classpath、Zulu21、64MiB heap、20秒子进程探针超时。没有重跑或“修好”两个已失败的multipleOf调查探针。

* local + command:string[] + environment:string-map通过；缺command拒绝。
* remote + url + headers:string-map + oauth:false通过；oauth对象通过；true/对象未知字段拒绝。
* remote混入local的command字段拒绝；headers数值拒绝。
* anyOf(number minimum0, const disabled string)：精确decimal与disabled通过，数字字符串及负数拒绝。
* oneOf(integer, number)：整值因两匹配拒绝，字符串因零匹配拒绝，小数因唯一匹配通过。
* allOf字符串minLength2/maxLength4交集成功与邻近失败；两个独立封闭对象分支不会合并properties，`{a,b}`被拒绝。

这组probe验证库对候选的真实常用形状具有可用语义，弥补只看路径/超时字段的偏窄调查。没有实现或测试Fibra自己的profile grammar、meta-schema白名单、source构造、config batch、预算饱和计算或安全投影；这些有明确合同，归正式实现验收，不以probe冒充。

## 成本与批次 owner 复核

§5.1现在把additionalProperties与所有组合分支计为执行子边，ref memo仅缓存子树权重、每次出现仍加，实例不因schema去重免计。C沿展开路径计算；通过ref/数组/字典下降不能绕过深度限制。组合专用节点至多一个applicator，减少并列分支遗漏计费的风险；仍需正式grammar执行，而非仅靠调用者自律。

`U=E*(N+ceil(B/256))*(C+1)`在正文中是待资格的接纳模型。源码已知anyOf有逐层错误list复制，oneOf快停只缩小返回聚合而非停止所有候选，故实现不得用最终错误数估计U；正式测试应保留上轮有限DAG8×4反例、mixed applicator、required/字典多错误、最长路径及边界±1。E权重的精确实现需覆盖keyword/required/enum/property名称和文本；NodePath为共享父链而非每Error全量复制，安全投影避免多次getElement导致不必要的二次扫描。

新增checkSources/validateAll将总预算和算法留在config owner：先全量接纳，再编译/检查；Engine不复制E/U公式，单项compile不冒称批次界，inputIndex=-1明确集合预算失败。接口已提供足够实施方向；编译/metaschema与值校验的阶段预算需要按正文测试，不再是职责未定。

元schema为可信固定内建图、作者loader阻断外部资源，待实现时验证其固定ClassLoader和白名单依赖；当前没有新增动态加载策略待选。依赖版本、YAML exclusion、Joni/Graal排除和不升级整平台均明确。支持不了既有版本、预算或安全门时回设计/依赖门而非放行，此停止条件适当。

## 批准边界

建议：补M1措辞后，可在本审查范围向用户申请具体实现范围批准。用户仍须批准材料性API/制品/依赖及破坏性builtin变更；本审查不是该授权。获批后以正式TDD/制品/消费者/文档证据关闭实现与发行门。若正式测试需要扩大profile语法、改变wire、改数值保证、升级依赖或更换owner，则回材料性设计门；只校准明确接纳模型的实施常数并补边界证据，不应被误当整个方向必须重做。

## 最终定稿有界核对

最终规格SHA-256：**`3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`**。仅复核主线程指定的三处定稿内容，未编辑spec、未重跑探针：

* §9新增允许的动态字典键仍视为输入，错误裁到静态字典父路径，并要求secret canary、嵌套对象/数组测试。完全覆盖本报告R2 M1，**M1关闭**。
* 作者QA关闭分工区分Engine已接管的store与尚未移交资源；资源close失败保留句柄和依赖并令QA失败，不继续无条件关闭父依赖。与原“底层owner关闭、父owner观察、失败现场保留”的合同一致；没有通过QA示例引入第二关闭权。
* snapshot明确区分缺失、非regular、regular；非regular由固定资源读取报READ_FAILED，不能降格为MISSING_CONTRACT，也不能让后来新增文件继承旧缺失事实。与原固定资源必须regular、精确字节身份的边界一致。

本审查范围最终结论：**B0 / I0 / M0**，可作为具体实现范围批准材料。正式实现/发行验收和用户材料性授权的边界仍按上文保留；此结论不提升为整个项目或运行时资格已经完成。
