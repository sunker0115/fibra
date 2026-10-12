# Jackson 3.1.5 告警适用性有界核查

核查日期：2026-10-12。范围：Fibra 当前生产源码、新增配置 Schema 路径、本地固定 Jackson 3.1.5 / networknt 3.0.8 sources JAR；输入为本目录 Task 5 发行 ZIP 的 OSV 收据及七条 GHSA JSON。只读检查，未改源码/依赖，未运行 Maven、PoC、攻击或利用性测试。

## 结论与证据层级

发行物中的 Jackson core/databind 3.1.5 命中七条公告是真实的依赖版本匹配，不能记为 SCA 通过或零漏洞。当前 POM 与 HEAD POM 的 `jackson.version` 都是 3.1.5；七条不是本次新增坐标或版本造成的。新增 networknt 3.0.8 / itu 1.14.0 在本次 OSV 查询中无命中，只能解释为该次查询无记录。

**已见生产路径**：Java 插件配置确实进入默认 `YAMLMapper.convertValue(value, definition.configType())`。但当前仓库内置配置模型没有找到这些公告需要的特定注解、XML 类型或 Path 绑定；内置数值模型以 Integer/Long/int/long 为主。七条均未在本次核查中证明完整的当前内置利用链。

**新增 Schema 路径**：未见这七条公告的直接触发入口。其输入解码、树节点转换、类型检查和预算详见下文。该判断不是全库无漏洞证明，也不覆盖任意外部插件代码。

**外部插件边界**：既有 binder 接受插件定义的配置类，插件可引入公告所需的字段类型和注解；因此不能将“仓库内置模型未使用”扩大为“平台不受影响”。还须核对真实插件模型、其 schema 是否允许对应输入、输入控制者、mapper 存续时间及运行时 provider/ClassLoader 条件。

## 逐条适用性

修复版本来自本目录 OSV 完整记录的 tools.jackson.core 坐标；只列 Jackson 3 支线，不表示已完成升级兼容验证。

| 公告 | 必要触发前提 | 当前路径与分层判断 | Jackson 3 修复版本 | 本次新增？ |
|---|---|---|---|---|
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) | 不可信畸形 token 经 `createParser(DataInput)`，错误 token 累积缺少长度限制 | **未见当前内置使用**：生产 Java 搜索无 DataInput；新增 reader 是 String parser，固定 meta-schema 是 InputStream。外部插件自行调用 DataInput parser 属于额外插件代码，当前 Fibra binder 不提供该输入入口。 | core 3.1.7 / 3.2.3 | 否，既有 core 3.1.5 |
| [GHSA-p6pp-m3f8-5c89](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) | 攻击者字符串到 `NumberInput.looksLikeValidNumber`，触发正则二次回溯 | **已见通用 binder；未见当前内置完整触发；外部插件可条件触达**。固定 3.1.5 源码中 Float/Double/Number/BigDecimal 反序列化及 StringNode 数值转换存在该调用。不能单凭 Integer/Long 字段或 `convertValue` 判定命中。内置 schema 约束数值为 integer/null；新增树路径不做字符串数值 coercion。外部插件若声明相关数值类型却允许字符串到 binder，需单独核实。 | core 3.1.7 / 3.2.2 | 否，既有 core；新增 schema 未见该 coercion |
| [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) | `@JsonIdentityInfo` 的集合/Map 未解析对象 ID，随后逆序解析，导致 pending-reference 反复扫描 | **未见当前内置使用；外部插件可条件触达**：生产源码未见 JsonIdentityInfo。插件配置模型可带注解，经既有 binder 绑定；需要真实模型、对象 ID 输入和到达条件，尚未证明。树节点自身不构成对象 ID 反序列化。 | databind 3.1.7 / 3.2.3 | 否 |
| [GHSA-gx83-3vf8-gh7j](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j) | `Comparable` 为多态基类，`@JsonTypeInfo` 使用默认 validator，缺少额外限制 | **未见当前内置使用；外部插件可条件触达**：生产源码未见 JsonTypeInfo / default typing 启用。ExecutionUnitKey 实现 Comparable 本身不满足前提。外部模型若使用相应注解/基类，需另行核验；可控对象构造也不自动等于代码执行。 | databind 3.1.6 / 3.2.2 | 否 |
| [GHSA-q4xh-88c3-wmh7](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7) | JSON 字符串绑定到 `javax.xml.datatype.Duration` / `XMLGregorianCalendar`，内部超长数值解析 | **未见当前内置使用；外部插件可条件触达**：生产源码未见这些 XML 类型。现有 `java.time.Duration` 使用不是该 XML 类型。外部配置类若声明 XML 类型且 schema 接纳字符串，则默认 binder 的类型扩展边界仍需评估。 | databind 3.1.6 / 3.2.2 | 否 |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) | 字符串绑定到 `java.nio.file.Path`，URI scheme 驱动 provider 选择；实际副作用还需要具副作用的第三方 provider | **未见当前内置 Path 绑定；外部插件可条件触达**：内置 LocalFileSystemConfig / JsonStorageConfig 的 root 是 String，后续 `Path.of(String)` 不等于 Jackson 的 Path URI 反序列化。外部模型若声明 Path 可触达相应 binder；运行时 provider 副作用未核实，不能声称已形成网络 I/O、挂载或文件访问链。 | databind 3.1.6 / 3.2.2 | 否 |
| [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) | `JsonTypeInfo.Id.NAME` 加 defaultImpl/等效 fallback，攻击者持续提供不同未知 ID，复用 mapper/type deserializer | **未见当前内置使用；外部插件可条件触达**：未见 JsonTypeInfo/defaultImpl。既有 mapper 被 binder lambda 捕获，不能假设每次绑定都清缓存，但也未证明任意宿主生命周期下的无限复用。需真实模型、未知 ID 输入与生命周期证据。 | databind 3.1.7 / 3.2.3 | 否 |

## 新增 Schema 路径的具体边界

- `DefinitionConfigDocument.java:22-24` → `SchemaDocumentReader.java:21-36`：文档 byte[] 先受 1 MiB 限制，严格 UTF-8 解码，再用 String overload 创建 parser，不使用 DataInput。
- `SchemaDocumentReader.java:49-73`：只构造 LiteralValue；数值 token 单独检查长度后 `BigDecimal`，VALUE_STRING 保持字符串，不送入 Jackson 数值反序列化。parser 约束深度 68、数值 token 512、字符串 1 MiB，另外计数最多 32768 节点。
- `SchemaLibrary.java:41-50,72-89`：LiteralValue 手工创建 StringNode/DecimalNode/容器节点，再传 `getSchema(JsonNode)` / `validate(JsonNode)`，没有 POJO configType、对象 ID、多态或 XML/Path 类型绑定。
- `SchemaLibrary.java:28-37,100-108`：meta-schema 只取固定清单中的 networknt 自带 classpath InputStream；非 meta registry 拒绝资源读取。networknt 3.0.8 `SchemaRegistry.java:822-824`、`DefaultNodeReader.java:44-48` 表明此处以 InputStream 读取为树，不是 DataInput。
- `SchemaProfile.java:18-29,34-53` 限制接纳语言；`ConfigSchema.java:28-35,72-95` 在库验证前计量；`SchemaBudget.java:17-36` 将单 schema 限为 64 KiB/4096 节点/深度32，effective 值为 1 MiB/10000 节点/深度64/单字符串65536，并限制数值精度和指数。
- networknt 3.0.8 `SchemaRegistryConfig.java:359` 的 `typeLoose=false`，Fibra 未开启；`JsonNodeTypes.java:179-190`、`MinimumValidator.java:120-128`、`MaximumValidator.java:113-121` 在数值阈值处理前检查数字类型。不能把第三方内部存在 `asLong()` 与 Fibra 对不可信 StringNode 实际调用该方法混为一谈。
- 预算是输入/工作量接纳约束，不是 CPU/堆硬上界，也不是七条公告的通用修复。尤其字符串预算不能替代禁止危险绑定或修复 Jackson。

## 既有 binder 与内置模型证据

- `fibra-runtime-java/.../JavaClassSpace.java:56-63`：创建默认 YAMLMapper，并绑定至 `definition.configType()`；该文件相对 HEAD 无 diff。
- `fibra-runtime-java/.../JavaDefinitionEntry.java:24-26`：LiteralValue.toJava 后调用 binder；`JavaRuntimeDriver.java:475-476,953-956` 使用该入口。
- `fibra-engine/.../FibraEngine.java:458-475`：本次配置合同阶段对 resolvedConfig 调用 `ConfigSchema.validateAll`；这是新增的前置约束，不能推导为所有宿主自定义调用都受保护。
- 仓库内置配置包括 LocalFileSystemConfig(String root, String spillDirectory)、JsonStorageConfig(String root)、SubprocessLocalConfig(String nodeExecutable)、ShellLocalConfig(String, int, long)、FileToolConfig(Integer/Long) 和 SearchPluginConfig(String, String, SearchLimits, SearchTiming)，后两嵌套类也是 Integer/Long。`fibra-plugins/.../fibra-tool-fs/src/plugin/fibra-config.json` 对数值字段要求 integer/null。
- 全仓生产 Java 搜索（排除 test/target）未见 `DataInput`、`looksLikeValidNumber`、`JsonIdentityInfo`、`JsonTypeInfo`、`defaultImpl`、`activateDefaultTyping`、`enableDefaultTyping`、`XMLGregorianCalendar`、`javax.xml.datatype`。`ClientProtocolCodec.java:537` 在节点取 int 前要求 integral number，不把任意字符串直接 coercion。
- 固定 Jackson 3.1.5 sources：`NumberDeserializers.java:710,815,926,1126`，`StdDeserializer.java:1087`，`StringNode.java:341,353,364,375,386,397` 是本次核对到的 looksLikeValidNumber 关键调用点。只找到 API 通用能力不证明具体生产输入可利用。

## 交付与后续决策边界

1. 本次依赖检查应记录为“发现既有 Jackson 3.1.5 七条公告；已完成有界适用性核查；未证明当前内置完整触发；外部插件条件风险待具体环境评估”，保留 OSV 命中，不做全局忽略或自动清零。
2. 用户已限定本次保持 Jackson 3.1.5，故本核查不改变平台基线。是否允许带已知告警交付、是否另开平台补丁升级，需要由主线程纳入明确决策，不能由 Schema 测试通过替代。
3. 若单独批准升级，按这七条记录，3.1 支线 core/databind 的共同修复下界是 3.1.7；3.2 支线共同下界为 3.2.3。这不是升级推荐或完成证明，仍需 Jackson 家族版本一致性、networknt、YAML、binder、ClassLoader 与发行物消费者回归，并重新扫描准确发行物。
4. 若维持基线，优先针对实际外部插件配置类型、schema、输入权限、mapper 生命周期及 FileSystemProvider 清单做定向核验；不通过构造攻击负载来补充本报告，也不声称外部插件全部安全。

## 来源与复核

- 本目录 `task5-osv-receipt.json`：2026-10-12T09:47:56Z，26 个坐标/版本，core 2 条、databind 5 条；七份 `GHSA-*.json` 保存完整公告与范围。
- 本次用 web 工具实时复核 [FasterXML NumberInput 公告](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89) 的 coercion 前提和 3.1.7/3.2.2 修复版本，以及 [FasterXML Path 公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf) 的第三方 provider 副作用前提和 3.1.6/3.2.2 修复版本。
- 其余五条按本地完整 OSV 公告逐条核对，没有额外下载公告或运行上游 PoC。
- 本地固定源码包：`/Users/sunke/.m2/repository/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5-sources.jar`；`/Users/sunke/.m2/repository/com/networknt/json-schema-validator/3.0.8/json-schema-validator-3.0.8-sources.jar`。源码核查没有执行其中代码。
- 执行过一次定向 Nowledge 搜索；结果为历史通用架构背景，未用于替代当前依赖、实现或告警证据。
