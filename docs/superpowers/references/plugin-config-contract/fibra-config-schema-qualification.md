# Fibra 配置 schema 候选资格补充（只读／仓外）

日期：2026-10-11。范围：networknt 3.0.8 固定源码与 Fibra 当前 LiteralValue 路径；未修改 Fibra/Tela、生产 POM 或 ~/.m2。

结论：保留 networknt 3.0.8 为有限实例配置 profile 的候选，但**不能批准任意 JSON Schema，也不能将精确解析等同全关键字精确性**。新查明 `multipleOf` 的大整数与小指数反例；建议首版显式不支持该关键字。若业务要求任意精度步进规则，则此候选的该项资格未通过，不能为了留下库而删掉需求。

## 1. 输入身份和新证据

已逐个重新 SHA-256 核对原 `/private/tmp/tela-networknt-3.0.8/sha256.json` 与 `compat-classpath.json`，合计 1,334 项，无缺失或不匹配。包含固定源码 commit `12596e6c94c3f41804356c9b7664c374e85abd07`、Central 3.0.8 JAR `d3f4c61ba35c3543bdf1ab42462bf89721542f4adea5b092ee7b430dfd467181`、原 probe/输出及 Jackson 3.1.5 兼容 classpath。没有将历史绿色推断为本次正式构建绿色。

新 probe 位于 `/private/tmp/fibra-schema-qualification-probe/`，保留 Java 源码、每次 stdout/stderr、实际 argv、nativeExit 与 `sha256.json`：

* `QualificationProbe` 第一次退出 1：探针把 divisor 写成 1，不能触发预想的大整数反例；这是探针假设错误，不是库反例。
* 第二次把 divisor 改成 3 后，前两条反例成立：`9007199254740993` 对 `multipleOf:3` 被错误拒绝；`1.1e-400` 对 `multipleOf:1e-400` 被错误接受。第三条 `multipleOf:1e400` 在 Jackson 3.1.5 抛 `DecimalNode.doubleValue()` 超出 double 范围异常，nativeExit=1。停止继续修补此探针，没有称全绿。
* 独立 `ProfileProbe` 不包含 multipleOf，编译/运行退出 0，10 个断言通过：相邻高精度 decimal minimum/enum、相邻大整数 maximum、exclusive 边界、DecimalNode integer/非integer、无组合 schema 的 failFast 单错误、boolean 安全返回、普通 schema 编译会忽略错误形状的 `required:7`。
* 所有新 Java 执行固定 Zulu 21.0.2、64 MiB heap、15 秒子进程超时；这个超时仅控制仓外探针，不是拟实现的运行期中止机制。SLF4J 因 probe 故意不装 provider 报 NOP；不能视作产品日志验证。

固定源码关键位置：`keyword/MultipleOfValidator.java:63-94` 对 BigIntegerNode dividend 经 doubleValue，divisor 先用 double 判零/有限；`MinimumValidator` / `MaximumValidator` 的普通分支使用 BigDecimal；`EnumValidator.java:134-143` 对标量数字规范化 DecimalNode；`utils/JsonNodeTypes.java:54-70` 支持精确整值 decimal 满足 integer。

## 2. 真实需求与参考项目处理

只读核对 Fibra `ShellLocalConfig`（可执行路径、正 int 输出预算、正 long grace）、`SubprocessLocalConfig`、`LocalFileSystemConfig`、`JsonStorageConfig`、`FileToolConfig`、`SearchPluginConfig` 及 limits/timing；Tela `OpenAIModelEntrypoint` 为 model/baseUrl/正 timeout，`TelaAgentEntrypoint` 为 dataRoot。对 Fibra plugins 与 Tela 源码的 `multipleOf` 检索无命中。此范围没有已知必须采用步进约束的实例配置，但这不证明第三方作者永远不需要；非 blank/文件路径可用性仍由业务 validator/runtime 处理，`minLength:1` 不等价非 blank。

JSON Schema 2020-12 validation §4.2 允许任意精度数字，§6.2.1 的 multipleOf 是精确整除。Fibra 限定数字域属于公开 profile 限制，不能宣传为完整标准实现。[官方规范](https://json-schema.org/draft/2020-12/json-schema-validation)

上游固定 README 的缓解项为 schema 信任、loader allow/block、regex predicate、failFast；固定 `AllowRegularExpressionFactory.java` 只是调用者提供 Predicate 的门，不证明 regex 有界。2026-10-11 当前上游 README 已另有 Recursive applicators 段，承认返回格式/失败快停无法约束递归分支的内部工作。公开 #1276 给出递归 anyOf/oneOf 重复路径证据，没有已核实的3.0.8修复。应采用“限制作者语言 + 有界预算”的最小适配，不能用 Future timeout/Boolean output 当修复。[当前官方 README](https://github.com/networknt/json-schema-validator#recursive-applicators)、[上游问题 #1276](https://github.com/networknt/json-schema-validator/issues/1276)、[正则讨论 #1113](https://github.com/networknt/json-schema-validator/discussions/1113)

重新搜索官方 multipleOf 问题/源码，未找到可直接套用且覆盖本次大整数/指数反例的已验证3.0.8修复。选项：A 首版公开有限 profile 排除该能力（当前调查配置可满足）；B 若步进约束成为需求，重开候选资格，证明全数字 DecimalNode + 限定指数的适配或采用已修复版本；C 自定义替换关键字/维护 fork 会增加标准语义 owner 和退出成本，不推荐本期未经单独批准采用。不把 runtime 业务 validator 当跨 runtime 结构步进一致性的替代证明。

## 3. 推荐首版 profile（待设计批准，不是已批准 API）

采用 JSON Schema 2020-12 的明示子集，单 JSON 文档、根 object schema，根 `type:object`（插件无需配置时可描述空对象；null/void 兼容语义由主设计决定，不能在此擅自改变）。每个非 `$ref` schema 节点有显式 type：单一标准类型，或一种非 null 类型与 null 的二元素去重数组。禁止无界多类型组合。boolean 子 schema true/false 可用于 items 或 properties 的明确语义，根不用 boolean。

允许的完整关键词集合建议：

* 根元数据：`$schema` 仅固定 `https://json-schema.org/draft/2020-12/schema`；`$defs` 仅根；`title`、`description` 为有界纯文本。
* 复用：`$ref` 仅形如 `#/$defs/<name>`；name 建议 ASCII 标识符 `[A-Za-z_][A-Za-z0-9_-]{0,63}`（此固定语法由普通字符检查实现，不执行作者 regex）；不支持 URI 百分号编码、JSON Pointer 转义、anchor、嵌套 defs 或任意片段。ref 节点只能有 `$ref` 与 title/description，不允许断言 sibling；整个 ref 图必须 DAG。
* 通用断言：`type`、`const`、`enum`；const/enum 仅 JSON 标量，enum 1–64 个互不相等值（数学相等数视为重复），拒绝 object/array 枚举。
* 对象：`properties`、`required`、`additionalProperties`、`minProperties`、`maxProperties`。additionalProperties 首版仅 boolean；要求作者显式声明，每层都如此，不默默注入 false。required 必须无重复字符串且都在本层 properties；属性名上限128字符。想要字典值 schema 的真实需求出现时重开 additionalProperties schema 支持，不先悄悄允许。
* 列表：`items` 单 schema/boolean、`minItems`、`maxItems`；要求显式 items，禁止旧 draft 的数组形式。
* 字符串：`minLength`、`maxLength`（标准 Unicode code point 语义，预算长度另按 UTF-8/UTF-16安全计算）。
* 数字：`minimum`、`maximum`、`exclusiveMinimum`、`exclusiveMaximum`，仅精确值比较。非负计数约束必须整值且落在profile预算范围；不能让 long/intValue 截断改变校验。

所有其他关键字拒绝，不容许 `x-*` 默默放过。明确拒绝：`multipleOf`、`pattern`、`patternProperties`、`format`（包括看似注解的 format，避免使用者以为校验生效）、`propertyNames`、`allOf`、`anyOf`、`oneOf`、`not`、`if/then/else`、`contains/minContains/maxContains`、`prefixItems`、`uniqueItems`、`dependentSchemas/dependentRequired`、`unevaluated*`、`$id/$anchor/$dynamicRef/$dynamicAnchor/$recursiveRef/$recursiveAnchor/$vocabulary`、`content*`、OpenAPI nullable/discriminator、作者 message/default/examples/readOnly/writeOnly/deprecated。首版只让 title/description 做展示，不悄悄引入表单平台、secret政策或默认写值。**default 是否需要纯注解暴露是主设计需确认的功能取舍**；若需要，必须限定值规模且明确绝不注值，不以本报告建议排除为已获批准。

schema准入按顺序：严格JSON解析（重复键、trailing token、不合法number拒绝）→ schema位置感知的keyword/形状扫描（properties的用户属性名不当关键词）→ 数字/文本/计数预算 → ref解析与全图cycle/展开预算 → 固定元schema验证 → 编译。所有 $defs（包括未使用者）也必须过准入，不能埋未检查分支。

普通 `getSchema` 不是元校验；`required:7` 实跑可通过任意对象，unknown keyword 默认仅annotation。官方元schema也不会替 Fibra 拒绝未知扩展词汇，所以 profile扫描与元校验两者都需要。参考固定 `QuickStartTest.readmeMetaSchema()` 使用内建 2020-12元schema验证作者schema。拟实现必须从宿主/库固定ClassLoader按白名单读取并预装标准元schema（含其固定依赖），禁止用插件TCCL寻找；作者编译器则阻断全部外部资源。只给元schema独立内部 reader/registry，不是第二业务Registry。不开放任意URL/file/classpath读入口。本次尚未跑这套白名单元schema正式实现，不能宣称闭合。

## 4. 明确的预算算法与建议值

以下数值为首版待批准的保守候选，必须用真实作者/消费者fixtures与边界测试验证，不是测出的SLA：单schema UTF-8≤64 KiB；原始JSON节点≤4,096；原始深度≤32；单schema对象成员≤128；单标题≤256字符、description≤4,096字符；ref链≤16；按复用路径计数的展开节点E≤8,192。单次结构校验effective配置JSON≤1 MiB、节点N≤10,000、深度≤64、单字符串≤64 KiB；只有参与已声明合同校验的值受该profile预算，不能顺便修改全平台 ConfigLimits 的4 MiB/100层默认。

展开算法不实际复制整棵schema：对根与每个defs节点做三色DFS。局部权重w(v)包括本节点的所有非子schema JSON节点、属性名和标量字符块；schema子边是properties的每个值、items以及解析后的ref target，不包括defs容器自动执行边。`E(v)=w(v)+ΣE(child)`；**按边相加，重复ref命中不得因visited去重而只计一次**；用memo仅缓存子树的E，回边立即拒绝；加法在limit+1饱和。总资格预算 `E(root)+Σ E(each defs)`（重复计数保守但安全），expandedDepth同样memo取max，≤64。即使无环，菱形DAG的路径展开仍可指数增长，E会在展开/编译前拒绝，而不是展开后再计数。

验证工作预检：给每份schema计算展开后的weighted E（required/enum成员和总标量字符亦计权，长字符串按每256 UTF-16 code units至少加一单位）。配置有N节点、总文本B、数字位预算P；在调用库前计算保守 `U=E*(N+ceil(B/256))`，单entry U≤1,000,000，整个一次DesiredEvaluation所有实际待校验entry的ΣU≤4,000,000（上限加法/乘法均饱和）。配置预检应使用迭代遍历，超限即停；同一对象在多路径出现须按出现次数计费，不能identity去重成小输入。schema编译/元校验也单独受E/bytes/count约束，不能只计运行期。

为什么这个profile能分析：没有组合分支、循环ref、regex、深结构enum/const或unique比较；每个结构child按其匹配位置下降，required/enum/属性匹配长度均计在E。库中单个计数/字符串遍历与精确数字比较仍有固定实现常数和有界字符/位成本，因此U是保守接纳成本量，不是假称精确CPU指令数。此处给出的是设计上的工作形状和前置拒绝条件；仍需正式实现的证明/压力fixture确认，并不能承诺线程可强制取消或毫秒硬deadline。仅Future超时、limit返回错误条数、failFast绝不能取代前置预算。

不引入跨revision永久缓存；编译schema及其registry归既有精确revision reader/owner，卸载/替换释放引用。多definition包还必须有包总schema字节/编译预算和一次命令总预算；推荐包schema总字节≤1 MiB，单次打开最多128份，最终必须与现有artifact总读取预算整合，不能另建无限目录服务。

## 5. LiteralValue精确数字与安全错误

Fibra当前 `LiteralValue.NumberValue` 使用BigDecimal并stripTrailingZeros，`toJava`仍BigDecimal；ConfigDocumentReader与DeploymentTargetCodec都已开启USE_BIG_DECIMAL_FOR_FLOATS/USE_BIG_INTEGER_FOR_INTS。结构adapter应遍历已形成的effective LiteralValue直接构建宿主JsonNode，数字保持DecimalNode；不经Double或JavaScript Number转桥，不把binder的转换当结构输入。schema严格reader同样精确解析；integer token保留BigInteger也可用于本profile range，但统一DecimalNode简化证明。注：调用者最初交给LiteralValue.of(Double)前已丢的精度无法恢复，文档不得承诺恢复。

建议数字词法≤512字符，转换后precision≤256且abs(scale)≤256、adjusted exponent=precision-scale-1也在[-256,256]；zero按规范化zero处理。使用long检查scale和表达式避免溢出，在任何BigDecimal运算、canonical输出或库编译前拒绝越界。不要只限token长度：`1e999999999`非常短。数字域限制必须写进profile并测边界，不能用Double finite作为有效性依据。当前NumberValue构造和公开接口可拿到更大值，新合同adapter需要自己有界检查，不能修改所有既有LiteralValue语义。

默认校验不转换、不注入默认、不改配置；runtime业务validator保留已有归一化/生命周期责任。schema通过只证明结构。

安全输出：建议 `failFast=true` 且annotationCollection关闭；在这个无组合profile中单错误有实证。只返回Fibra不可变受控诊断（稳定code、受控keyword、经过schema声明映射/裁剪的路径），或返回boolean后创建固定错误。`OutputFormat.BOOLEAN`只是避免返回Error，不阻止库临时创建Error。

绝不能把原Error、异常cause、toString/message/arguments/details/instanceNode/schemaNode存入result、日志、Mono缓存、审计或长期对象。库在校验栈里临时持有输入不可避免；目标是adapter返回后不额外保留。unknown属性名也是输入秘密：additionalProperties错误仅指向最近已声明父object，不返回未知名字；列表只返有界数字下标；required可返回已声明属性名；schema路径也不返任意作者文本。编译/解析错误转固定code，内部日志仅可记录异常类/受控阶段，不能attach throwable，因为cause message可含数字、schema或输入。失败泄漏验证必须扫描响应、日志、保存结果与cause链，不能只测最终错误字符串。

## 6. 依赖/维护边界与未关闭资格

本次实时核官方release显示3.0.8于2026-09-30发布；固定POM为Apache-2.0、Java17，可用于当前Java21。安全页当前没有公开advisory或SECURITY.md，不等于无漏洞。[release](https://github.com/networknt/json-schema-validator/releases/tag/3.0.8)、[security](https://github.com/networknt/json-schema-validator/security)

保留当前Jackson3.1.5/SLF4J2.0.18，显式排除validator传递YAML，Joni/Graal不引入；itu1.14.0暂保留，新增validator+itu字节642,803沿原核验。原POM默认Jackson3.2.1不构成升级授权。CVE-2026-59889公告修复版包括3.1.5和3.2.1，不能拿该条强制全平台升级，也不能证明全依赖安全。[官方公告](https://github.com/advisories/GHSA-5gvw-p9qm-jgwh)

未通过/尚未证明：任意multipleOf精度；全schema支持；本profile完整shape grammar/元schema白名单/预算实现与边界；正式Maven依赖收敛与licenses/SBOM/SCA；真实dynamic Java/Node/client/builtin发现与启用；真实消费者反序列化/数值跨wire；精确revision升级、inactive延期、CAS与保存后失败语义；ClassLoader/读取关闭失败与owner保留；打包发行及卸载后缓存生命周期。10条probe不能关闭这些门。

正式制品下一证据：隔离Maven仓库install/verify、dependency:tree与有效POM锁Jackson3.1.5/YAML排除；打包JAR和发行zip内容/NOTICE/SBOM；每个allowed关键词成功/最近非法/上限边界fixtures、unknown/坏shape/拒绝关键词/循环与菱形ref预算；secret canary全链；使用正式制品的真实Java/Node/client/builtin消费者和作者文档示例（不能用假ACK替代）；schema读取/编译与target生命周期并发和失败owner证据。

## 7. 检索说明

使用Nowledge search-memory流程做一次目标检索，MCP query=`Fibra 通用插件配置 schema networknt 3.0.8`，未指定额外filters，返回strict/default范围。五条结果均为旧Fibra/Tela分层或存储背景，没有本次3.0.8/profile资格证据，因此本报告结论不引用这些结果。未展开图，因为当前没有证据证明图入口能保持相同strict/default Space授权；本地MEMORY关键词检索无相关命中。没有写长期memory。
