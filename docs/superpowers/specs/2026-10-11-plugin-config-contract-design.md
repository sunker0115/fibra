# 通用插件实例配置合同设计

2026-10-11设计；2026-10-12状态：**已获用户批准，实现、正式资格验收及最终合同/质量双独审均完成。** 原审查绑定的正文摘要与授权记录保留于推进计划；当前交付证据和未验证边界见[最终交付记录](../plans/2026-10-11-plugin-config-contract/closeout.md)。
基线 `0a674176b8b762b4e77044bcba7f051bf06c41d9`；[推进计划](../plans/2026-10-11-plugin-config-contract/task_plan.md)。
本文是已批准并实现的新增专题合同，正文保留公开语义、接纳预算和发行验收要求；实际通过证据见最终交付记录，不重开已交付名称目录 D37。

## 1. 结论、目标与非目标

推荐扩展现有 `fibra-config`，在同一 Engine 部署链中建立“作者静态结构合同 → 实际 effective LiteralValue → 统一纯校验 → runtime 绑定/业务规则”。配置值仍只属于完整 DeploymentTarget；schema 是规则，不是另一份实例值。多实例共享 definition 规则，各自保留 entry/config/生命周期。平台通用性由直接 Engine、CLI/Registry、Java/Node/产品 client/builtin 同门证明，不按第一方/第三方或 Tela 名称特判。

成功标准：不执行插件代码即可查询当前精确 selection 的某 definition 合同；所有真正进入托管执行的输入在任何 runtime candidate prepare 前检查；候选升级使用新 revision；保存、恢复、取消与关闭沿原 owner；作者/宿主从正式制品和使用文档能完成真实消费。

非目标：第二配置中心/Registry/数据库；表单引擎、产品组合、秘密存储、ACL、任意资源/URL浏览；从 Java 类型自动生成 schema；自动迁移、自动补默认/转换；全 JSON Schema 方言；新增 Node/client runner；更改 raw 表达式文法；将低层 core 强行改造成托管 Engine。

2026-10-12用户补充“不用兼容代码”：直接迁移调用点、测试与示例，不新增旧API转发、双格式解析、旧摘要映射或读取回退。复用现有config求值、target/CAS及合法metadata能力属于既有职责，不为旧配置另建执行分支。

## 2. 参照、替代路线与推荐理由

来源、固定提交与行为边界见 [参照证据](../references/2026-10-11-plugin-config-contract-evidence.md)。

| 路线 | 架构与使用成本 | 决定 |
|---|---|---|
| 产品自己读资源/校验 | 对单一编辑页改动少，但其它 Engine 调用者可绕过，各产品复制求值/字段约束，builtin 缺统一来源 | 拒绝作为通用合同；产品规则仍可采用 |
| Fibra 静态合同 + 原部署共同门 | 作者维护字段含义，框架负责一致检查，runtime 保留业务与资源，产品引用；有公共 API、制品及依赖迁移成本 | 推荐；覆盖当前跨 runtime/跨入口需求，未来删除成本集中在 config adapter 与静态描述 |
| Fibra 统一表单/配置供给服务 | 表单、默认、秘密、组合和持久策略混入框架，出现第二状态及生命周期 | 拒绝，本期无必要性 |

OSGi Metatype/Configuration Admin 与 VS Code 提供描述/值/呈现分工参照；不复制其存储。DSH/Cordis 的 inactive 延期与资源登记可借鉴，不复制执行式 schema。Pi 的工具参数与 OpenCode 的 options 不提供本目标的无执行、精确 revision、CAS 前统一保证；不照搬参数转换、全文错误和忽略关闭失败。JSON Schema 标准关键字执行交成熟库，Fibra 特有部分只限语言准入、身份/候选/预算与安全投影。

### 2.1 发现及制品位置

- **采用轻目录 + 按需合同查询**：先列名称，再打开所选类型的合同。现 `describeDefinitions` 结果/SPI不携 schema，坏 schema 不让简单列名失败。
- 不扩目录：没有所有消费者都要全包 schema 的证据；一次返回增加预算和失败耦合。
- **采用包根固定 JSON 文件**：统一格式适用于任何 runtime，schema 只存一处，名称仍由原 descriptor/builtin 声明。相比各 runtime descriptor 嵌入，不需修改 Java/Node/client 三套私有格式与名称 SPI。
- 拒绝任意文件/URL 查询：读取固定语义资源即可；builtin 用等价纯元数据，不暴露路径或工厂。

## 3. Owner 与依赖

| owner | 新增职责 | 保留边界 |
|---|---|---|
| artifact | 绑定 exact record 的固定 `fibra-config.json` 惰性字节读取 owner、文件边界和内容身份 | 不依赖 config/api/Reactor，不理解字段 schema，不执行插件 |
| config | 严格合同文档解码、profile/schema 准入、精确 literal 结构校验、安全诊断 | 继续原文档/表达式求值；无文件/网络读取、包版本选择、runtime 和产品类型依赖 |
| Engine | 当前/候选精确 revision、definition 与合同绑定、共同预检、读取父 owner 和原 CAS/保存/恢复 | 不实现 JSON Schema 算法，不按 runtime 名称分支 |
| runtime/provider | 原名称静态读取；真正 definition 名称核对；类型绑定、业务校验、资源准备/运行；builtin 静态合同 | 不重求值、不自建 schema Registry，不负责产品组合 |
| Registry/CLI/Spring/产品 | 委托原 Engine；产品组合、表单/编辑、显式读值权限和错误呈现 | 不复制字段规则，不以 UI 校验代替提交门 |

纯 `FibraRuntime`/core 的 typed API 不拥有 package 或 target，仍维持现有 typed 业务合同。需要静态输入保证的低层宿主可显式用 config 纯校验再绑定，或采用 Engine。这里的“全部入口”指托管 Engine 路径，不虚称 core 自动获得制品检查。

## 4. 作者制品格式与唯一来源

推荐新增固定包根 `fibra-config.json`（UTF-8、strict JSON）：

```json
{
  "format": 1,
  "profile": "fibra-config-1",
  "facets": {
    "main": {
      "fs-local": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {
          "root": {"type": "string", "minLength": 1},
          "spillDirectory": {"type": "string", "minLength": 1}
        },
        "required": ["root", "spillDirectory"],
        "additionalProperties": false
      }
    }
  }
}
```

仅允许 envelope 的 format/profile/facets；重复键、尾部输入、未知 envelope 字段、错误值形状、非支持 format/profile 拒绝。facet/definition 键是引用，不创建名称；pluginId/version/runtime/target 不重复到此文件。根 `fibra-package.yaml` format 1 字段不变，新增资源自然参加完整 packageDigest。每个 schema 是 object 文档，描述的配置值可为任意显式 JSON 类型，不强制实例值为 object。

作者源文件推荐 `src/plugin/fibra-config.json`。Java 插件构建将同一源字节放进 JAR 的 `META-INF/fibra/config.json` 供发行装配，并复制/提取到完整 package 根；Engine **只读 package 根**，无 JAR fallback。本仓 distribution 从正式插件 JAR 提取，断言与作者源/根副本一致，不另手写规则。archetype 更新资源/assembly；Node/client 从各自同一包源复制。改变 facet ID/组合多个 JAR 是显式新包装配，必须重新绑定并验证名称，不增加运行时重定位/猜测或通用装配 DSL。

作者发布资格检查整个包的 facet/definition 精确集合：有 definition 就必须有合同；零 definition facet 用空映射。发布检查与运行检查分开：安装/选择但不执行可以暂存未合格包，不能据此标记“作者合同合格”。不做旧 active fallback，不自动补开放 schema。

发布资格由作者构建/QA负责执行，不另建持久资格状态或强制放入每次安装。正式制品的可复制测试流程为：创建临时 store/Engine（注册实际所需 provider），安装并以 disabled selection 选入完整包；逐 facet 用现有 `describeDefinitions` 取得静态名称真源；通过 record 的固定资源 owner 读取并关闭后，用 `DefinitionConfigDocument.parse` 比较全部 facet/definition 集合（含空 facet、missing/orphan）；以 config 的批量资格入口编译全部 source。builtin 使用其声明名称与静态 source 做相同集合和全量编译检查。该流程必须进入 archetype 生成测试、本仓发行门和只依赖正式 JAR 的仓外作者消费者，不能只写成伪代码。inactive 坏 schema 可暂存但发布资格失败，安装成功不代替此检查。该流程不要求启动插件实例；自定义 runtime 作者必须提供实际静态目录 reader，不能用测试假名称代替。

作者QA在外层持有固定资源句柄，真实close成功后才释放引用；常规完成或读失败但close成功时由Engine关闭其已接管的store，不重复独立关闭。资源close失败则保留句柄及其依赖、明确令QA失败并进入宿主终止清理路径，不继续无条件关闭依赖；未移交给Engine的资源由原构造owner关闭。正式作者示例也需覆盖该关闭失败反例。

### 4.1 多实例及 raw/effective 例子

以下沿用已存在的 profile 格式；两实例使用同一规则，各自独立：

```yaml
- id: project-files
  plugin: {id: fibra-fs-local, facet: main, definition: fs-local}
  enabled: true
  config:
    root: {$ref: /fibra/workspaceRoot}
    spillDirectory: .fibra-spill
  realm: {fibra.fs: files}
- id: archive-files
  plugin: {id: fibra-fs-local, facet: main, definition: fs-local}
  enabled: false
  config:
    root: /data/archive
    spillDirectory: .fibra-spill
  realm: {fibra.fs: archive}
```

原 evaluator 先解析 `$ref` 后，schema 看见 `{"root":"/data/project-a","spillDirectory":".fibra-spill"}`。raw `$if/$literal` 同理；schema 自身的 `$ref` 属于不同文档和解释阶段。结构门拒绝数字 root、缺 spillDirectory 或拼错字段；`minLength:1` 不等价非 blank，root/spill 路径及服务可用性仍在业务层检查。

无配置定义显式用 `{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"null"}`；支持空 object 则另声明 object/properties/additionalProperties；允许原有 null 表示 runtime 默认的定义使用 nullable type。不能把缺省 config 的现有 null 自动变成 `{}`。

### 4.2 通用场景资格，不能只以文件插件为依据

| 场景 | 静态结构合同 | runtime/产品继续负责 |
|---|---|---|
| 文件插件的 root/spillDirectory | 必填、字符串、未知字段 | 路径语义、可读写、资源生命周期 |
| 同 definition 多实例 | 每项独立检查，共享作者规则 | 实例标识、依赖、realm、各自运行状态 |
| headers/environment、按模型ID索引的配置 | 动态键的值有统一 schema；使用 schema-valued additionalProperties | 凭据供给、协议是否合法、远端是否接受 |
| local/remote、false或OAuth对象、number或disabled | 有界 anyOf/oneOf 分支，各分支自身完整；不把所有字段改成 optional 后退给各 runtime | 实际进程/网络/授权行为、产品交互 |
| 关闭的草稿、升级新规则 | 延期/启用重验、候选精确 revision | 产品迁移提示、操作者确认；平台不自动修值 |

以下是**说明目标能力的假设作者插件**，`example-connectors`/`mcp` 并非当前发行物；沿现有 profile 文法，未发明新的产品级配置顶层：

```yaml
- id: docs-server
  plugin: {id: example-connectors, facet: main, definition: mcp}
  config:
    type: local
    command: [node, /opt/docs/server.mjs]
    environment: {LOG_LEVEL: info}
- id: search-server
  plugin: {id: example-connectors, facet: main, definition: mcp}
  config:
    type: remote
    url: https://search.example.invalid/mcp
    headers: {X-Workspace: project-a}
    oauth: false
```

对应同一个 definition 的 schema 使用两个封闭 object 的 `oneOf`：local 分支要求 type=local、command:string[]，可选 environment:string-map；remote 分支要求 type=remote、url:string，可选 headers:string-map 和 oauth=false或OAuth对象。每个分支显式 `additionalProperties:false`，字典本身用 `additionalProperties:{"type":"string"}`。local 缺 command、remote 混入 command、headers 值为数字必须在 prepare 前拒绝。URL/命令执行安全和连接可用性不由字符串 schema 保证。此形状直接由固定 OpenCode MCP 配置和 Pi 设置的真实需求归纳，不把它们的产品字段纳入 Fibra 核心。

## 5. `fibra-config-1` profile 推荐

采用 JSON Schema 2020-12 明示子集，不能宣传为完整标准实现。此限制本身是需批准的公开作者合同；真实需求超出时重开 profile/依赖资格，不在实现中默默删需求或放行。

| 类别 | 首版支持 |
|---|---|
| 基础 | 根 `$schema` 必填且固定上述 URI；普通断言节点显式 type，单一标准类型或非 null 类型与 null 的双类型；title/description 纯文本 |
| 复用 | 根 `$defs`，仅 `#/$defs/<标识符>`，标识符为 `[A-Za-z_][A-Za-z0-9_-]{0,63}`；引用仅允许 title/description sibling，根额外允许 `$schema/$defs` 元数据；不允许断言 sibling；全部 defs 无环，按引用路径计算展开预算 |
| 有限组合 | allOf/anyOf/oneOf，每节点只选一个，2–8个分支，展开路径组合嵌套≤4；组合节点只有组合关键词与元数据，不混 type/其它断言；每个叶分支仍须符合本 profile |
| 值 | 标量 const/enum（enum 1–64项、数学等值去重）；不支持结构化 const/enum |
| object | properties、required、additionalProperties（必须显式 boolean 或 schema）、minProperties/maxProperties；required 无重复且属于本层 properties |
| array | items（必须显式单 schema 或 boolean）、minItems/maxItems；禁止 tuple 旧语法 |
| string | minLength/maxLength，按标准 code point；预算另外限定字节/字符成本 |
| number/integer | minimum/maximum/exclusiveMinimum/exclusiveMaximum，精确数值；计数约束为非负整值且受预算上限 |

所有其它关键词拒绝，包括拼错和 `x-*`。尤其不支持 regex/pattern/format、not/if、recursive/dynamic refs、外部 refs、uniqueItems、contains、unevaluated、dependent、作者 message/default/examples/readOnly/writeOnly 等。boolean 子 schema 仅在 properties/items/additionalProperties 位置允许；根不用 boolean，开放 object 必须显式 `additionalProperties:true`，`{}` 不能充当默许开放合同。组合按标准语义执行，不做分支转换或自创 discriminator 算法；allOf 不会合并各分支的 properties，封闭对象不能靠 allOf 偷渡额外字段。有限组合及类型化字典已通过本轮行为与预算反例验收，证据见最终验证账本CC09/CC12；该资格不构成性能或总内存保证。

**默认分工**：首版 schema 不声明或注入 default。现有 runtime 默认/规范化仍属于业务输入到运行配置的转换，作者 schema 必须接受其合法外部形状（如缺可选字段/null）；不能把 runtime 默认复制成第二权威值。未来确需 UI 显示可执行默认时，另立同源生成/验证合同，不只加一个无约束 annotation。schema 不验证业务 validator 的返回值，因为它可能是 Java 专有对象；runtime 保证返回值满足自身业务不变量，不回写 raw target。

`multipleOf` 暂不支持是已实证的库资格限制，不是用 Double 近似满足：3.0.8 对大整数/极小指数存在误判。当前核过的 Fibra/Tela 实例配置未发现此需求；任意精度步进成为真实要求时必须重开，不能用业务 callback 冒充跨 runtime 同规则。

准入顺序：strict JSON → schema位置感知的词汇/形状检查 → 数字/文本/深度预算 → 全 defs/ref DAG 与展开预算 → 固定内建 meta-schema 校验 → 编译。`getSchema` 本身不是 meta 校验；meta-schema 也不能代替拒绝扩展关键词。标准 meta-schema 及依赖只允许宿主固定类加载器内建白名单，禁止插件 TCCL 或作者指定资源检索。作者编译 loader 阻断所有外部 URI（含 file/classpath/https），文档内引用由已批准 DAG 处理。

### 5.1 工作与数值预算

预算分两层，避免按需语义被构造阶段误改为全包资格。全文件 strict parser 在创建递归对象前限制 UTF-8≤1 MiB、嵌套≤68、节点≤32768、数字token≤512；envelope最多128份 schema，schema值必须为object。`ConfigSchemaSource` 构造只在冻结/canonical前检查中立数据的≤1 MiB/32768节点/64深度及下述数字界，未知 profile/关键词和单schema资格延期。动态文档和builtin同源采用这些中立上限；超全局安全预算属于整个文档失败。

被查询、执行或发布审查的单 schema 另需≤64 KiB UTF-8/4096 JSON节点/32原始深度；title≤256、description≤4096字符；property名≤128字符、单对象成员≤128；ref链≤16；展开深度≤64/加权展开节点 E≤8192。单 effective 值≤1 MiB/10000节点/64深度/单字符串64 KiB。A active、B dormant 且仅B超过单schema资格上限时，A query/Apply可成功，B query和全包发布失败；B超全局解析/中立构造预算则全包失败。未知关键词/坏required属于B的单schema语义，不能在parse所有source时提前编译。

计数关键词的接纳上界明确为：minLength/maxLength≤65536，minItems/maxItems及minProperties/maxProperties≤10000；均须为数学上的非负整数。负数或非整数属于INVALID_SCHEMA，超过上界属于BUDGET_EXCEEDED。长度断言仍按Unicode code point执行，effective单字符串64 KiB按UTF-8字节独立限制；节点预算包含容器，因此关键词上界不承诺同样数量的元素一定能被接纳。此项为2026-10-12实现中对“计数约束受预算上限”的明确化。

schema 遍历采用饱和计数：`E(v)=自身权重+ΣE(child)`，properties/items/additionalProperties/ref和全部组合分支都是执行子边；memo 可缓存子树权重，但每条 ref 边都加一次，禁止 visited 去重低估菱形展开。根与所有 defs 的 E 加总（保守重复计费）。标量文本每256 UTF-16单位加权，required/enum成员也计费。对有效值迭代统计 N 与总文本 B，修订候选成本 `U=E×(N+ceil(B/256))×(C+1)`，C为展开路径最大组合深度，计入组合错误向祖先传播；每 entry U≤1,000,000，同一命令总U≤4,000,000；乘/加都在上限+1饱和。**此式是接纳模型，不能单凭DAG宣称已证明总内存或耗时界**；错误集合构建、复制、路径长度也须核对，若不充分则改预算或回依赖门，不能删除字典/变体需求来换取通过。

实例按出现次数计费；原始值预检先于 JsonNode 构造/库调用，schema预算先于展开/编译。命令最多128份唯一精确schema，总schema输入≤1 MiB；config-owned batch先核整个集合预算，再编译/执行任何schema，Engine不复制E/U算法。独立单项compile/validate只承诺单项界；命令必须用批量入口，全包发布用批量编译入口。去重按profile和同一canonical source内容，实例不能因共享source而免计验证成本。

“先核整个集合”指所有输入接纳完成前不得执行库；预算扫描已发现确定超限时可立即拒绝，不要求继续收集其余预算错误，也不要求后项集合超限覆盖前项单项超限。接纳前不得复制整批未知规模的输入；执行只消费接纳阶段有界捕获的不可变记录。预算通过后，普通schema语义失败与实例失败共同按原输入顺序返回。此项为2026-10-12独立实现审查中对前置接纳与首错顺序的明确化。

数字词法≤512字符、规范 precision≤256、abs(scale)≤256、adjusted exponent在[-256,256]；运算使用long防溢出，zero按规范zero。先检查再 canonical 编码/库调用，拒绝短词法巨指数。直接从 LiteralValue 构建 DecimalNode，不经过 Double/JavaScript Number；schema 同样精确解析。不会恢复调用者传入 Double 前已经丢失的精度。

这些是**接纳成本预算**，不是毫秒 SLA 或强制CPU取消能力。禁止把 Future timeout/failFast/返回错误数当资源上界。循环/regex禁止、组合/深度/展开工作受限只是资格前提。上限超过需要显式失败，不能自动放宽或删约束。原 ConfigLimits 的4 MiB/100层不变；新限制只作用参与本合同的 schema/effective 输入。

精确数值保证止于 Engine effective LiteralValue 的结构检查，不把它表述为各runtime执行时都保留任意精度。现有Node JSON.parse采用Number，client有tagged decimal，Java绑定取决于作者类型。本期不改wire、不按runtime名偷偷截断通用数值；Node作者对整数计数可显式声明安全整数范围，必须精确的金额/外部标识用作者定义的字符串合同或另行升级wire。正式真实消费者需显示9007199254740993在Node现有路径的表示边界，不能以宿主校验通过冒充执行值精确；任意小数也不因落在安全整数范围而自动精确。

## 6. 公共合同候选及内部组织

以下签名为审批范围，最终实现按此写入签名基线，变化需重新核对影响，不在示例虚构已可调用接口：

```java
// Engine：当前 selection、原 target fence；不隐式选择历史/最新包。
Mono<DefinitionConfigContract> describeConfiguration(
    PluginDefinitionRef definition, long expectedTargetRevision);

// engine.deployment：纯值，source不含库对象、路径、现值或运行状态。
record DefinitionConfigContract(long targetRevision, String packageRevision,
    PluginDefinitionRef definition, ConfigSchemaSource source) {}

// config：构造source只深冻结/检查数据预算，不编译业务schema。
record ConfigSchemaSource(String profile, LiteralValue.ObjectValue schema) {}
final class ConfigSchema {
    static ConfigSchema compile(ConfigSchemaSource source);
    Optional<ConfigViolation> validate(LiteralValue effectiveValue);
    static Optional<ConfigBatchFailure> checkSources(List<ConfigSchemaSource> sources);
    static Optional<ConfigBatchFailure> validateAll(List<ConfigValidationInput> inputs);
}
record ConfigViolation(String code, String keyword, String instancePointer) {}
record ConfigValidationInput(ConfigSchemaSource source, LiteralValue effectiveValue) {}
record ConfigBatchFailure(int inputIndex, ConfigViolation violation) {}

// config：无I/O的严格固定文档解码；键沿已有PluginDefinitionRef用string。
final class DefinitionConfigDocument {
    static DefinitionConfigDocument parse(byte[] bytes);
    String profile();
    Map<String, Map<String, ConfigSchemaSource>> facets();
}

// artifact：record绑定exact revision，不接收任意路径/URL。
PluginConfigurationResource PluginPackageRecord.configurationResource();
interface PluginConfigurationResource extends AutoCloseable {
    byte[] read();
    void close();
}
```

batch输入不可变且按调用顺序定位；inputIndex≥0指向失败项，-1仅表示整个集合超预算。checkSources执行全部source资格/编译而无实例值；validateAll先核全量接纳预算，再检查source并校验值；只返回首个安全失败，不能携带库对象。ConfigViolation.code固定为UNSUPPORTED_PROFILE/INVALID_SCHEMA/BUDGET_EXCEEDED/VALUE_MISMATCH；前三类没有实例pointer/keyword时用空串。单项compile失败抛安全ConfigSchemaException，batch对应归入ConfigBatchFailure；非法API参数仍同步拒绝，不能把null集合等编程错误作为实例值错误。

artifact 的创建无I/O。Engine先登记句柄，再 read；stream 打开后立即属于该句柄，才允许可失败读取。有界读、拒绝非regular/symlink，按 exact managed root 固定文件名定位；read返回防御副本，重复read/close观察同一实际终态。close先等实际read结束；close前read未开始则终止惰性句柄并拒绝后续read。close失败保留原stream，不再次物理关闭；成功清除资源/原异常引用。artifact不因新增接缝依赖Reactor。

精确字节绑定采用原包snapshot的派生证据：在原根digest遍历中记录固定路径的缺失、非regular或regular文件事实；对regular文件从同一读取chunk计算raw SHA-256及长度，原两次snapshot同时核对。缺失与非regular分开，非regular在固定资源读取时以READ_FAILED拒绝，不当作缺失或后来新增。不另开文件，不改变 `fibra-content-v1` 算法。Store创建record时私有携带此事实及root/pluginId/version/packageRevision绑定，重启find重建，不写新持久字段。resource对本次实际读取字节计算并匹配该事实才返回；find后改写/截断/替换为新规则必须拒绝，R1缺失后新增不能成为R1规则。正常store管理不修改已安装内容；此机制只绑定该固定资源，不声称对整个目录提供原子快照或抵御同进程恶意ABA。

固定资源的缺失信号沿既有签名使用JDK异常：仅原摘要已验证ABSENT的read抛出固定安全文本、无cause的NoSuchElementException，Engine仅在该资源read边界将其映射为MISSING_CONTRACT。原REGULAR后来消失、NONREGULAR、身份不符及实际I/O失败沿既有ArtifactException报告，使用固定安全消息、null path及null cause；不能凭异常文字或当前文件是否存在重分类。原ABSENT后新增仍是该revision没有合同。close成功清除stream和原始失败引用，保留防御性字节或安全异常终态供重复调用观察。此项为2026-10-12实现前对缺失/读取失败接缝的明确化，不增加公共类型或修改方法签名。

原public builder仍可构造metadata record，但没有内部核验事实的record调用新增configurationResource立即无I/O拒绝；新方法的Javadoc明确先从store.find取得已核record，不做path-only fallback。toBuilder只在root/内容身份保持一致时保留核验事实，state/timestamp变化可保留，location/revision/managedPackage身份变更则丢弃；equals/hashCode仍按原公开metadata，不把派生证据当第二身份。此新能力前置条件纳入公共API审批。正式测试覆盖手造record、toBuilder两类变化、同字节mtime变化、重启重建及原包digest不变。

`ConfigSchemaException.Code` 为 UNSUPPORTED_PROFILE/INVALID_SCHEMA/BUDGET_EXCEEDED。`ConfigContractException.Code` 为 ENGINE_UNAVAILABLE/STALE_TARGET/NOT_SELECTED/PACKAGE_UNAVAILABLE/UNSUPPORTED_RUNTIME/UNKNOWN_DEFINITION/MISSING_CONTRACT/INVALID_CONTRACT/READ_FAILED/CLEANUP_FAILED。沿现目录query的同步参数检查（definition非null，revision非负）、lane接纳及取消规则；依次核engine准入、target fence、selection/package、runtime、静态facet/definition存在、资源/envelope、所选schema。未知facet/definition合并UNKNOWN_DEFINITION；整个文件或被查询键缺失为MISSING_CONTRACT，解码/profile/schema不合格为INVALID_CONTRACT，实际I/O/字节身份核验失败为READ_FAILED。发生清理失败时CLEANUP_FAILED优先，保留两个安全code但不留原cause；公开错误只保留稳定code及受控定位，不暴露第三方异常对象。

Engine根包内新增一个有界合同编排协作者，复用原名称 query。原 CatalogAttempt 抽出共享读取父 owner 后，名称和配置读沿**一个 Engine 持有的 attempt/shutdown 屏障**；每个底层句柄只由 runtime/artifact 自己关闭，父attempt只按逆序观察。config compiler是局部纯对象，无全局Registry/cache、线程池或跨revision永久缓存。公开query返回前必须真实close完成，随后才交付不可变source。

实现可测接缝限定在包内：生产构造默认使用`PluginPackageRecord::configurationResource`，包级Engine构造器允许测试向上述协作者注入资源创建函数；不新增public Builder选项或runtime SPI，不改变唯一读取owner。双失败沿`code()`主分类及固定安全message组合两枚举保留，cleanup为主；secondary仅用于诊断，不要求外部解析文本，不新增secondary getter或第三方cause。此为2026-10-12实现细化，公开异常形状沿既有目录异常模式。

## 7. 生效范围、时序、升级和恢复

```text
Apply/启动恢复/需要重编译的reconcile/runtime request
 → 原target revision/CAS及精确selection捕获
 → 原planner.inputs：真实raw/context求值
 → 对真正执行entries取得精确名称及schema（同owner读/关）
 → 统一结构检查全部这些effective输入
 → 原candidate登记、prepare/validate/seal
 → 原targetStore.save（仅新命令）
 → 原promote/retire/activate/reconcile
```

完整预检成功之前不得执行任何runtime candidate create/prepare（更不能先装Java definition再结构检查）。公开 query 自己进入 lane；内部预检直接消费本次 exact records/builtin快照，不再次loop.submit，不读取旧 durable selection。保存后 activation 仍可失败。

| 状态 | 行为 |
|---|---|
| effective enabled 且 package enabled | 核声明存在、对应schema存在/合法，验真实resolved值；多个entry逐个检查 |
| local/祖先 disabled 或 when=false | 无resolvedConfig，不假求值、不读对应合同；保存为延期，首次启用必经完整门 |
| package gate=false | 仍保留原求值行为（可能因raw缺引用失败）；不进入执行/schema门，不能与上一格混称免求值 |
| 仅install/selection，没有执行entry | 不强制查询所有schema；包存储成功不等于配置资格通过 |
| 某definition的按需query | 即便包停用也要求该名称存在/该schema合法；文档envelope坏则失败，不从坏JSON局部取值 |
| 同包 dormant sibling | 其独立schema语义错误不阻塞其它active项；共有envelope/格式/全文件预算错误影响该包所有读取；发布门仍全量发现缺/孤儿合同 |
| 升级 | 使用候选新 revision/schema复验active项；不自动迁移/删字段/补默认/回退旧schema |
| 恢复 | 使用保存target的精确revision；schema失败为PRESENT+BLOCKED/NOT_APPLICABLE，允许管理修复，不伪称新命令NOT_SAVED |

健康/PENDING reconcile只协调已经验证且未换输入的既有代，原路径并不重捕输入或比较fingerprint，也不承诺发现外部文件变更。Apply/显式requestRecompile/需要重建的reconcile进入deploy后重捕身份，原no-op再按compiledFingerprint判断复用，不新增“已验证”持久位；profile解释器版本参与原inputs identity，schema变化动态通过包digest、builtin通过派生digest失效。所有进入新candidate的active inputs重新验证，runtime仍只准备原affected闭包，不为了schema重启无关实例。

预检普通拒绝在PLANNING阶段，subject归原operation（恢复归durable target），无candidate假现场；旧current/准入保留。读取关闭失败归真实读取父owner，fail-stop并保留其driver/store依赖，不能套用query专用subject伪装部署失败。取消返回结果只取消观察，accepted command继续真实读/关。关闭沿原lane排空；失败read与失败close都记录安全code，cleanup优先，兄弟清理继续，owner不能从索引消失。

读取attempt仅是私有资源owner，不新增FailureSubject变体。公开失败投影按发起上下文冻结：

| 发起路径 | FailureFact.subject | operation/outcome/saveState |
|---|---|---|
| 公开query | cleanup用Engine(hostInstanceId)；普通失败只返回query异常 | 不新建operation、不改历史operation；operationStage为空；有目标时用捕获durableRevision |
| 新target Apply | Operation(operationId) | APPLY/PLANNING/FAILED，candidate targetRevision，NOT_SAVED |
| 同target Apply但需要重建 | Operation(operationId) | APPLY/PLANNING/FAILED，原revision，NOT_APPLICABLE |
| BOOTSTRAP已有目标 | DurableTarget(revision,digest) | BOOTSTRAP/PLANNING/FAILED，durableRevision，NOT_APPLICABLE；PRESENT+BLOCKED |
| Reconcile重建/runtime requestRecompile | Operation(operationId) | RECONCILE/PLANNING/FAILED，durableRevision，NOT_APPLICABLE |

部署普通拒绝的failure stage为PLANNING，cleanup失败为RELEASING；operationStage始终PLANNING且targetRevision与本次operation一致，不虚构candidate attemptId。部署cleanup使用原failStop(fact)先把operation置FAILED并关闭准入；query沿Engine失败投影，不把之前成功Apply改成FAILED。BOOTSTRAP显式BLOCKED。失败attempt保留，shutdown在新建SHUTDOWN operation前观察同一缓存close终态，失败时不覆盖首个失败事实/operation，不释放其driver/store依赖；兄弟清理继续。read+close双失败仅组合固定安全code，close优先。上述五条路径均需字段级反例，不能只断言抛异常。

Registry install/upgrade在Apply前可能已发布不可变制品；NOT_SAVED只表示target未保存，不表示包事务没发布，禁止自动删除未选择revision回滚。

## 8. builtin 身份与迁移

`BuiltInFacet.Builder.configSchemas(Map<String,ConfigSchemaSource>)` 新增纯元数据映射；保留原 definitionIds 为名称真源。映射键只引用声明名称，缺项在query/active预检拒绝（保留管理可修复与inactive延期），source深不可变。不调用 definition()/binder 从Java类型反推schema。

原 `BuiltInPluginPackage.Builder.packageDigest(String)` 改为 `implementationDigest(String)`；不保留兼容重载。框架内部唯一算法派生最终packageDigest：

```text
SHA256("fibra-built-in-package-v2\0" + canonical({
  pluginId, version, implementationDigest,
  facets sorted by facetId: [{facetId, runtime, target,
    dependencies: [{pluginId, facetId}], capabilities, definitionIds, configSchemas}]
}))
```

facet按facetId、依赖按pluginId/facetId元组、capability与definitionIds按字符串排序；映射按键排序，configSchemas包含每项profile与完整schema。schema采用同一LiteralValue数值/对象canonical；shape/中立预算检查先于编码。selection()/artifactId()/target字段继续用此唯一最终digest，不加第二revision字段。implementationDigest仍由provider对实际实现负责；框架只保证schema变更不会因作者忘记改实现摘要而复用旧合同身份。fingerprint引用最终digest及固定profile解释器identity，不复制第二套schema序列化。验收必须含facet单独改名、facet名称/内容交换、profile单独变化引起身份变化，以及输入集合顺序无关。

这是破坏性API与builtin内容身份变化：旧selection（即使disabled）不会被偷偷映射到新schema；旧目标恢复PRESENT+BLOCKED，宿主通过原CAS提交明确replacement。现存版本无兼容分支，不自动重写target。作者必须同步迁移全部builder调用及消费者fixture。未知profile/非法schema的语义错误在query/部署失败，不在provider构造时编译schema堵死管理入口；基础metadata形状/有界数据仍可按构造器合同拒绝。

## 9. 安全错误与查询值的区别

结构错误不复制value、完整schema、raw模板、第三方Error/message/arguments/details/cause/suppressed。unknown字段错误定位到最近已声明父object，不输出未知字段名；列表仅有界下标，required只引用schema已声明的字段。诊断path须经schema结构映射且长度受限，不能直接输出库JSON Pointer。编译/解析失败固定code，日志不attach原throwable。

由additionalProperties schema接受的动态键同样视为输入，诊断定位到最近静态声明的字典父路径，不输出动态键及其后拼出的完整路径；CC10须覆盖动态键本身含secret canary、嵌套对象和数组的情况。

配置合同查询只返回规则；现值仍走原宿主显式管理读取。产品应把title/description当纯文本，不执行HTML。schema不提供访问控制、secret扫描或“可公开实际值”的推论。

既有 EngineChangeException 携管理PublishedView，view内可能有durable target。本次保留该受权管理快照合同，**不承诺Throwable整个对象图无配置**，禁止直接序列化异常/view作为公开错误DTO。安全承诺覆盖schema诊断、message/toString、cause/suppressed中的诊断内容、日志、审计及官方错误传输投影；schema API本身不带现值。实际CLI/Registry/Tela的输出路径只取message/saveState/viewRevision等；TelaHost还依赖异常view与current快照比较，不能为schema单独丢弃view或改异常种类。测试须覆盖这些真实投影并单独验证受权显式snapshot仍可读取值。若需要整个管理异常图无配置，须另行统一receipt API迁移，不能用schema旁路或null view掩盖契约变化。

## 10. 依赖、发行和文档影响

候选新增 `com.networknt:json-schema-validator:3.0.8` 到 fibra-config；保留Jackson3.1.5/SLF4J2.0.18，不升级平台。显式排除validator传递YAML（config原有YAML读取依赖保持），不引入Joni/Graal/日志provider；保留itu1.14.0。Apache-2.0，新增validator+itu约642803字节为JAR测量，不冒充ZIP增量；NOTICE/完整依赖/SCA/启动与ClassLoader仍须正式资格。库内部SchemaRegistry只作短寿命编译器对象，不是业务Registry。

固定库源码/probe仅证明有限路径可行，包含保留的失败反例；[本次资格补充](../references/plugin-config-contract/fibra-config-schema-qualification.md)与[归档摘要](../references/plugin-config-contract/evidence-sha256.json)绑定。正式准入须核有效POM/dependency tree、JAR/ZIP许可、精确数字/所有支持词汇/边界预算；如既有Jackson不兼容，返回依赖门，不自动升级或降库。

影响模块：config、artifact、Engine及其公开签名；Java builtin API调用；所有正式动态插件的合同源和JAR装配；archetype、distribution、example、parity/公开provider消费者。名称查询SPI、raw/target编码、client wire协议和npm公开类型原则上不变；若实现证明必须改变，回设计门，不捎带修改。

使用文档必交付：README能力说明+作者/宿主最小可运行示例；`docs/api/plugin-configuration.md` 完整接入；同步docs/api/README、权威vNext/Client Foundation相关节、release、模板/插件/example/Node/client说明及实际签名。详细文档包含schema存放/版本/支持限制、显式读值区别、默认/unknown/raw/effective、创建编辑启用升级恢复、保存状态、生命周期和可执行QA负例。候选不能提前写成“已经可用”。

Tela主树只读。隔离真实消费申请范围：固定D39适配输入、4个Java包合同源/装配、builtin声明（若实际使用）、client包根合同、Host公开query/部署及renderer真实import/create/关闭测试。不上新产品组合UI，不覆盖Tela主树；批准前只准备精确文件清单和基线，不做适配。此前51项不替代本次证明。

## 11. 可证伪验收与阶段门

| ID | 必需证据 |
|---|---|
| CC01 | dynamic Java静态初始化/definition/binder计数0，Node无spawn、client无import；builtin等价发现；多facet同名不串 |
| CC02 | root资源字节改变→包revision改变；find后资源变更拒绝、原缺失不接受后来新增、手造record/toBuilder/重启；builtin schema-only/facet改名/profile变化/顺序不变性/外部Map修改/旧target恢复修复 |
| CC03 | 同schema同effective跨Engine/Registry/CLI及Java/Node/client一致；unknown/missing/非法schema在prepare前拒绝 |
| CC04 | raw ref/if/literal、local/祖先/when延期、package gate独立；enable失败旧disabled target不变 |
| CC05 | R1当前宽松/R2候选严格升级拒绝；只改schema影响闭包；两个并发原CAS至多一个保存 |
| CC06 | schema pure、runtime validator仅一次、原默认/归一化不变；null/无配置/开放与关闭对象 |
| CC07 | 读/关成功、同步/异步失败、取消观察、close先于read、失败owner/兄弟清理/父依赖保留；五类发起路径的subject/stage/outcome/saveState字段；不自动重试未知终态 |
| CC08 | NOT_SAVED / SAVED未ready / UNCONFIRMED原行为 / 恢复NOT_APPLICABLE明确；包publish与target save区分 |
| CC09 | 每个allowlist成功/最近非法/边界；typed map、local/remote、false或对象、number或disabled、oneOf零/多匹配；未知词、坏required、外部引用、循环/菱形、总预算/组合/深度拒绝；单schema延期与全局预算分开；库数字精度和Node消费边界 |
| CC10 | secret canary扫描响应诊断、日志、审计、cause/suppressed、schema查询不混现值；公开管理snapshot边界明确 |
| CC11 | 正式Maven制品独立消费者与Java/Node真实执行、产品client真实import/create/配置/关闭，拒绝假ACK；fixture不冒充Desktop实机 |
| CC12 | README示例实际运行、模板生成/装配、作者规则同源字节、发行签名/架构/npm/原可复现门及最终独审 |

设计门需独立审查无未关闭重要问题，主线程关闭具体公共API/制品/依赖与安全回执歧义，再取得用户材料性批准。实现门按失败先行测试及原owner交付；最终正式制品/消费者/文档/独审完成才关闭目标。所有历史/probe/局部绿色不替代这些门。
