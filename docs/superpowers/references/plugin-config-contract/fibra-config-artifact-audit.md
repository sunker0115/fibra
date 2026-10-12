# Fibra 通用插件配置合同：制品、公开发现与消费者只读审计

2026-10-11。源码基线 `/Users/sunke/dev/ai-project/fibra`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。只读；未改 Fibra/Tela、未构建、未测试、未 commit。工作树有主线程正在建立的未跟踪 `docs/superpowers/plans/2026-10-11-plugin-config-contract/`，未触碰。以下所有具体格式/API/身份算法均为**批准前推荐**，不是现有接口或已批准契约。源码路径省略上述 Fibra 根目录；Tela 路径明确另列。

## 结论

推荐保留现有名称轻目录，增加精确 definition 的按需配置合同查询；动态合同使用完整 package 根的固定 JSON 资源，builtin 使用等价深不可变公开 metadata。`fibra-config` 解释 profile/schema 并验证真实 effective `LiteralValue`；Engine 只负责精确 revision、共同调用时序、CAS 和读取资源关闭。现有 runtime 名称声明仍是名称真源，不用 schema 资源回填目录，不引入新 Registry、配置存储、任意文件/URL API 或 schema fallback。

必须先关闭的材料问题：builtin schema 如何进入不可绕过的有效 package identity；合同强制覆盖的检查阶段与 inactive 保存边界；作者 JAR 与完整 package 的同源装配；正式消费者究竟跑哪个固定隔离 Tela 输入。仅完成 parser/局部测试不能关闭这些门。

## 1. 当前源码证据

| 证据 | 事实及对设计的约束 |
|---|---|
| `docs/superpowers/specs/2026-10-11-definition-catalog-design.md:11–19,39–61,63–84` | 既有 D37 按 runtime 读动态名称、builtin 读 metadata；结果无 schema。零执行不等于运行就绪；runtime 保留执行校验 owner。不可把本次公共 schema 改写成 D37 已交付内容。 |
| `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java:301–368` | 冷查询进入原 loop，核 durable target revision 和 selection，按精确 digest 找 store；builtin 冲突/旧 digest 拒绝；缺任一 root runtime 拒绝；所有 facet 成功才给名称目录。 |
| 同上 `:389–483`；`engine/runtime/RuntimeDefinitionQuery.java:7–31` | 先登记惰性 query、串行读、逆序关；取消观察不是取消执行；cleanup 失败保留 owner。配置读取不能使用局部 try-with-resources 后吞 close failure 来逃出此合同。 |
| `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaFacetDescriptorReader.java:23–26,57–74,82–139` | Java JAR 内固定 `META-INF/fibra/plugin.yaml`，严格接受 entrypoint/definitionId；空对象是 contract-only；现 owner 持 JAR/stream 并保留失败句柄。 |
| `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeFacetDescriptorReader.java:23–61,75–127` | Node payload 固定 `fibra-plugin.yaml`，四字段严格格式；有真实 stream/walk 资源 owner。嵌 schema 将改变此私有格式和相关解析/关闭路径。 |
| `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackage.java:33–48,108–130,253–305` | 根 manifest 字段严格，format 固定 1；整个目录普通文件进入 package digest，禁止 symlink。额外固定 schema 文件天然参与包内容身份，无需在 manifest 增任意资源路径字段。 |
| `fibra-artifact/src/main/java/com/sstlfsj/fibra/artifact/PluginPackageRecord.java:17–30,44–48`；`PluginPackageStore.java:98–135,158–172` | record 已给 exact package 的 managed root location，安装复制/复核后才发布；无需增加公开“读任意路径”入口。公开查询不得把 location 暴露为结果或让产品自行解析 schema。 |
| `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java:588–655` | 部署使用候选 target selections 查新 package；目前 map 为 managedPackage 后丢掉 record location。共同合同协作者应保留捕获的 record/精确输入，不回头读旧 durable selection。 |
| `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentPlanner.java:24–59,90–117` | 先真实求值，再筛 effective enabled 与 package enabled；变化判断含精确 provenance/raw/effective/resolved。schema-only package 修改通过全包 digest 进入变化链。 |
| `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriver.java:451–488`；`JavaDefinitionEntry.java:24–26` | Java 取得实际 definition 后，把 resolved LiteralValue 交 binder，再 PluginDefinition.prepare；共同 schema 门必须早于此绑定。 |
| `fibra-api/src/main/java/com/sstlfsj/fibra/value/LiteralValue.java:14–40,53–72,75–110` | 已有封闭、递归不可变值和规范 JSON，数字为 BigDecimal。schema 公开值可复用，不暴露可变 Jackson 节点/Map 或插件 Class。 |
| `fibra-config/src/main/java/com/sstlfsj/fibra/config/PluginDefinitionRef.java:3–15` | config 已有不反向依赖 artifact 的完整逻辑引用，可作为按需查询入参。 |

## 2. 公开发现方案比较

| 方案 | 好处 | 代价/不适配 | 推荐 |
|---|---|---|---|
| 保留 `describeDefinitions`，新增按需合同查询 | 先列名再编辑的使用路径明确；不扩大现有名称 DTO、无需把每个 schema 带给只列名用户；坏 schema 不破坏原名称发现 | 多一次 query；每次结果必须带 target/package revision 并遵守 CAS | 采用 |
| 把 schema 扩进现有 `PackageDefinitionCatalog`/runtime query | 一个往返；天然同一 target 快照 | 名称查询负担所有 schema 的体积、解析、安全预算和坏合同；所有 provider readAsync 返回型与外部消费者需要同步改；与 D37 已冻结的无 schema 返回边界相撞 | 当前消费者无必须一往返的证据，不采用 |
| 任意 package/path/URL 资源 API | 一般性最高 | 本需求不需；失去 schema 语义 owner，builtin 还需另一来源，扩大路径与资源合同 | 拒绝 |

推荐待批准公开形状（名字可由主线程按现有风格最终冻结）：

```java
// FibraEngine，仍只查询当前 selection；无 staged/历史/任意路径入口。
Mono<DefinitionConfigContract> describeConfiguration(
    PluginDefinitionRef definition, long expectedTargetRevision);

// engine.deployment；ConfigSchema 是 fibra-config 的深不可变纯值。
record DefinitionConfigContract(long targetRevision, String packageRevision,
    PluginDefinitionRef definition, ConfigSchema schema) {}

// fibra-config；该类型不能依赖 artifact、Engine、runtime 或网络。
// 内含冻结的 profile 与 LiteralValue.ObjectValue schema，并提供纯检查接缝。
```

返回值无需再带现值、runtime readiness、文件路径、工厂、binder、reader 句柄。查询 schema 与显式读取完整 durable target/config 是两件事。现有 `PublishedView.engine().target()` 已传递 durable target，Engine 发布点见 `FibraEngine.java:1180`；不要通过 schema API 偷带现值。

查询用同 command lane 捕获；expected revision 不符先失败，root 未选次之，精确包不可用/无对应 runtime/未知 facet-definition/缺坏合同/cleanup failure 分层安全分类。建议独立 `ConfigContractException`，而非把配置错误塞入既有 DefinitionCatalogException；公开签名/错误码最终须审批。异常不挂外部 Throwable、输入值、完整 schema。返回结果前必须真实关完读取资源。

内部 `readConfiguration(exact captured source, definition)` 为唯一读取实现；Apply、恢复和 query 共同消费它。内部不可调用并等待公开 `describeDefinitions` 或新公开方法，因为它会重排同 lane，且观察的是旧 durable selection。

## 3. 动态格式与职责推荐

推荐固定包根文件，按主线程已向用户展示的候选统一写作 `fibra-config.json`。文件名、format、profile 名均是待批准候选：

```json
{
  "format": 1,
  "profile": "fibra-config-1",
  "facets": {
    "main": {
      "echo": {
        "type": "object",
        "properties": { "message": { "type": "string", "minLength": 1 } },
        "required": ["message"],
        "additionalProperties": false
      }
    },
    "contract": {}
  }
}
```

这是 schema 内容与已有身份的绑定，不是另一份 definition 目录。pluginId/version 只在根 manifest；facet/runtime/placement 图仍在 manifest；definition 是否存在只由 D37 runtime 声明或 builtin metadata 决定；schema 文件不得凭一条键创建新 definition。包根 JSON 是运行时唯一 schema 真源，Java/Node/client descriptor 不再嵌同一份 schema，也不支持从 descriptor/JAR fallback。

profile 固定其 schema 方言、允许关键词、引用边界和资源预算。示例不能先行承诺全部 JSON Schema 2020-12；具体 allowlist/数字/预算由依赖调查门关闭。建议只允许单文件内、固定 profile 允许的本地引用；禁绝外部 URL、文件、classpath schema resolution。查询消费者拿到的是 author schema，不是 raw `$ref/$if/$literal` 语法；schema default 只作注释或直接不支持，绝不自动写值/转换。

`fibra-config` 拥有 profile、严格 JSON 值解析、schema 合法性和纯 LiteralValue 检查，bundle以String facet/definition键表达，不反向依赖artifact身份类型。`artifact` 拥有不可变 root、内容身份和固定文件字节读取，不应为了验证配置依赖 config、runtime或Reactor。Engine对齐这两种值，并把读取资源登记在当前 query/deployment attempt；解析移交config。

主线程提出的 artifact 固定字节资源 `AutoCloseable` 句柄，较 Engine 私有直接文件 I/O 更符合现 owner 拆分，推荐优先。可审批一个 record 绑定的窄入口，例如 `PluginPackageRecord.configurationResource()` 返回惰性 `PluginConfigurationResource`，仅支持固定 `fibra-config.json` 的有界字节读取和close；不接收任意Path/URL/String文件名，不解析schema。具体签名未批准。创建必须无I/O，Engine先登记，再调用read；read打开stream后立即记在此唯一owner字段中，才执行readNBytes/解析输入准备；close失败保留stream与句柄引用且后续观察不重试。Engine包装异步观察与取消语义，不要求artifact引入Reactor。字节getter返回防御副本或明确单次immutable值容器，不能泄露可变共享buffer。丢失句柄的普通try-with-resources不满足本任务关闭失败合同。

公开reader并非因技术上无法取得路径而必需（record已有location），而是因为Engine跨模块复用artifact资源owner需要窄合同；这是有职责的公开API，仍须材料审批。若最终选择Engine私有reader也可完成任务，但须在设计解释为何把固定包资源I/O放Engine，并确保不复制第二owner。不要为省一个公开类而把schema parser搬进artifact或开放通用资源浏览器。

**不需更改 `RuntimeDefinitionQuery.readAsync(): Mono<List<String>>` 或 `RuntimeDriver.createDefinitionQuery`。** 使用既有 runtime query 确认所请求名称后，通用 reader 取得对应 schema。Java/Node/client 作者只需打包通用文件；各 runtime 原有名称声明/执行核对/业务绑定逻辑继续各自负责。Builtin metadata 增加同等静态合同是单独材料性公共 API 变化。

相比私有 descriptor 嵌入：根文件让每种 runtime 共享同一个 schema parser/profile、错误清洗和预算，且产品 runtime 无需实现 JSON Schema parser。嵌入方案虽可复用 JAR/stream reader，但会扩大每种 descriptor/public value/SPI，三个来源形成同一通用合同的多套格式与迁移点，容易把 runtime 业务含义和通用结构混在一起。这里采用公共资源只用于配置，不追溯性重开 D37 的名称 owner。

## 4. 完整集合与 inactive：必须显式选择检查阶段

有两个不同的不变量，不能把它们混成“校验配置”：

1. 作者制品的名称/schema 绑定覆盖与 schema 文档是否合法；这是静态事实，无需求值任何实例。
2. 当前 entry 的输入是否满足 schema；只有真实 resolvedConfig 存在时才有可检查对象。

**建议：作者发布/构建门做全包 facet/definition 精确覆盖；运行时按真正请求/参与检查的 definition 惰性强制。** 新规范要求可执行 definition 提供 schema，显式开放输入也须显式 schema；missing 不能走旧 active fallback。查询某 definition 时，核其 runtime 声明存在并且对应 schema 存在、合法。部署时按决定的真实 resolvedConfig/active 范围调用同一 reader；没有 resolvedConfig 的 local/ancestor disabled、when=false 不假开开关，也不为其首次加载 schema。启用时首次缺/坏合同必须在 save 前拒绝。根文件一旦读取，仍须严格预算、JSON和envelope检查，不允许从坏 JSON 局部捞出一项。

因此，在推荐惰性运行门下，只有 inactive 且无 resolvedConfig 的旧缺合同 entry 可以保存为延期，后续 enable 拒绝；这不是旧制品可运行兼容分支。全包精确集合检查是作者制品资格，不应通过 runtime 遍历所有 selected 包强制执行，除非单独批准更强保存合同。Orphan schema 不能成为新名称，运行 API 不通过它返回不存在 definition；完整 orphan/missing 审计在发布门发现。

若改选“每次 Apply 对全部 selected 包全量 schema/名称精确覆盖”，那缺/坏 schema **会阻止仅 inactive 的保存或 disabled 安装选入 target**，即使实例值验证延期。这可以是一致设计，但显著改变既有 install/disabled 的资格；必须在设计/迁移文档直说，不能声称完全沿原 inactive 合同。还要决定仅选包无 entry 是否也受此门。若采用“只要某包一项活动就全包检查”，则该包 dormant sibling 的坏 schema 也会阻塞活动项，亦应列明。

package enabled=false 与 resolvedConfig 缺失不等价：现 `DeploymentPlanner.java:30–41` 在求值后才看 package gate，可能已经拥有 resolvedConfig。应由总设计明确这一格是否也结构检查；本审计不代主线程冻结。

## 5. builtin 深不可变与不可绕过 identity

当前 `BuiltInFacet.java:13–23,28–46` 只冻结 List/Set；新增 schema 应使用 `LiteralValue` 和不可变映射冻结全部层级，不能存可变 JsonNode/Map、Supplier、definition() 或 schema 回调。`JavaBuiltInPackage.java:17–36,39–65` 复制私有 definition 集合，并把真实集合校验延期到 candidate 使用，保留启动失败管理面；不要为了公共 schema 静态读取反过来执行 Java definition。

现漏洞窗口（新增配置能力若处理不当才形成）：`BuiltInPluginPackage.java:12–17,27–34,38–44` 的 packageDigest 是作者给定 64-hex，selection/artifactId 直接使用；`FibraEngine.java:1498–1518` 的 fingerprint 只包含现有 metadata。只在 fingerprint 加 schema 可以避免进程内错误复用，却不能保证跨 Host/重启的同 packageRevision 仍指同合同。不能用 provider.contractIdentity 代替逐 builtin 包的 schema identity。

推荐最小封闭算法：把 builder 原作者摘要输入显式改名为 `implementationDigest(...)`（破坏性源码 API，须批准），`BuiltInPluginPackage` 构建时统一派生最终 `packageDigest`：

```text
SHA-256(UTF-8("fibra-built-in-package-v2\0") || canonicalJson({
  pluginId, version, implementationDigest,
  facets: sorted(facetId, runtimeId, executionTarget,
                 sorted(dependencies), sorted(capabilities),
                 sorted(definitionIds), configContracts)
}))
```

`configContracts` 包含固定 profile 和完整 canonical schema 内容。digest 由同一个对象内部算，不接受“我已算好最终 digest”的旁路；输入对象后改 Map 不能改变结果。getter `packageDigest()`、`selection()`、`artifactId()`、原 compiler、原 target 格式不增加第二 revision。代码二进制内容身份仍由 provider 给 implementationDigest；本算法不假装对 factory 字节码自动签名，但作者即使复用 implementationDigest 也不能绕过 schema 变化的最终 identity。

fingerprint 继续基于有效 packageDigest 与 schema metadata/profile（显式包含后者便于审计；只有一个权威 canonical 实现，勿复制两套序列化）。更改 schema 或profile必须改变最终 digest；Map顺序、数字等价形式应有稳定规范策略。不是必须新增独立 contractHash/Registry。

身份变化后旧 durable selection 失配是正常：`DeploymentTargetCompiler.java:29–41` 在 enabled 过滤之前就核 builtin revision；`BuiltInPluginPackageTest.java:46–53` 明确 disabled 也必须匹配。因此即便旧 builtin entry disabled，更新 schema 后旧 selection 也需原 CAS replacement 修复；不把旧 digest偷偷映射到新schema。恢复时应沿 `JavaBuiltInRecoveryTest.java:52–86` 的 RUNNING 管理面、PRESENT + BLOCKED、原target字节不动、replacement可修复模式，不能在 provider构造或Engine构造阶段因新schema编译错误永久关掉管理面。

建议分开 metadata 的深冻结/identity构建与 profile/schema可用性检查：前者不能执行插件；后者在 query/deploy中失败并留下安全阶段事实。既有基础结构值非法仍可按constructor合同拒绝，不能借此把原已保存target不满足新schema算成构造失败。

成熟参照：本仓 `docs/superpowers/references/2026-10-11-definition-catalog-evidence.md:67–71` 引用的 OSGi Core 8 wiring，本轮重新读取官方 §7.1.1/§7.3，revision包含资源和metadata、每次update建立新revision，仍被使用的旧wiring保留：[OSGi Core 8 Bundle Wiring](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html)。本建议借鉴资源/metadata绑定精确revision；SHA内容寻址算法来自Fibra现有 `PluginPackage.java:270–305`，不是OSGi要求。拒绝引入OSGi resolver、生命周期或配置数据库，也不把Metatype补查倒写成原D37来源。

## 6. 作者源码、正式 Java JAR 与完整 package 只维护一份 schema

现安装单位是完整目录，不是单 JAR（`README.md:84–120`）。archetype已有 `src/plugin/fibra-package.yaml` 与 assembly复制清单（`fibra-plugin-archetype/.../src/assembly/plugin.xml:8–18`）；模板 `PluginConfig.java:3–7` 目前 message 必须非空，可作实际schema例子。当前 distribution 的目录布局从 `src/main/distribution` 拷贝，manifest单独过滤（`fibra-distribution/pom.xml:71–99`），最终ZIP来自stage目录（`src/assembly/bin.xml:9–18`）。

推荐作者源文件放 `src/plugin/fibra-config.json`，且**只手写这一份**：

- archetype assembly从该源文件复制到安装目录根；普通Java JAR可通过现Maven resources将相同字节放在约定的 `META-INF/fibra/config.json` 作为供装配使用的作者资源。该JAR资源不是runtime fallback，也不被Engine读取。
- 发布JAR被其他发行工程消费时，使用现Maven dependency/resource装配步骤从该JAR提取并复制为package根文件，门禁断言字节一致；不要在distribution另手写一份schema。若同repo assemble直接用作者源文件，也应断言与所发布JAR作者资源一致，避免拿源码证据冒充消费已发布字节。
- 根envelope包含facet绑定，作者默认完整package facet与JAR作者资源一致。若发行方主动改变facet ID或合并多JAR到多facet包，属于生成新package：装配步骤显式映射已有schema到新facet键并验证名称，schema约束正文仍只来自作者源；不要给运行时新增隐式重定位或猜名字规则。最小期无需通用装配DSL，先文档明确自定义装配责任与测试。
- Node/client的作者源码同样只有一个包根合同源，正常复制进入目录。不要给它们创建“运行时 descriptor schema + 根 schema”双可编辑来源。
- 多一份构建输出字节不等于第二事实源：唯一编辑源 + 确定性复制/已发布制品提取 + 一致性门；运行时只认受管包根副本。若主线程选择完全不把资源放JAR，则必须同时发布完整package供外部消费，不能声称单个已发布Java JAR仍足够重建作者合同。

这一打包选择涉及模板资源、archetype描述符include、正式各插件module、distribution、示例与外部consumer fixture的同步迁移；应给出实际文件清单，不只在README口头说“复制schema”。

## 7. 真实消费者、生命周期与交付验收接缝

| 路径 | 现有接缝与需增加证明 |
|---|---|
| 动态 Java | `fibra-distribution/.../LifecycleConsumerTest.java:55–128` 已用正式仓外unknown disabled包做名称query→CAS创建，断言静态初始化marker没写、原target/audit不变，实际调用读回config。扩展为读schema/坏config拒绝且binder未调用/好config真实factory启动/升级R2拒绝R1输入；不能只证明编译。 |
| Node | `LifecycleConsumerTest.java:144–155` 既有Java/Node参数化真实生命周期；`NodeRuntimeDriver.java:128–153` 静态query不spawn。新增真实Node记录收到的effective config，坏schema/config必须在进程启动之前失败；schema通过但启动拒绝证明saved-not-ready；旧关闭范围合同继续验证。 |
| 外部 provider | `fibra-distribution/src/test/consumers/runtime-provider-application/.../RuntimeProviderConsumerTest.java:56–61,133` 是公开SPI消费者。根资源方案不改变该SPI返回型，但fixture的package/builtin合同需要迁移并从正式Maven制品消费。 |
| builtin | `JavaBuiltInRecoveryTest.java:52–104` 已有真实Java builtin成功、已保存目标不匹配仍管理可用、prepare失败不保存。新增schema深冻结、schema-only finaldigest变化、fingerprint变化、不执行definition发现、重启旧revision失配/原CAS修复、业务validator拒绝；还应有零入口facet空合同。Node provider目前 `builtInPackages()` 空（NodeRuntimeProvider.java:32–35），不必为对称而捏造Node builtin产品能力。 |
| Tela host/client | 当前主树 `/Users/sunke/dev/ai-project/tela/tela-host/.../TelaClientRuntimeDriver.java:90–112,168–172` 读取descriptor所选definition并把resolvedconfig放Assignment；renderer `tela-desktop/src/renderer/runtime/module-loader.ts:30–70` 校验资源、真实import、选择definition并create(config)。schema验证应发生在host共同门，client wire无需携schema或引入新schema registry。 |
| Tela基线边界 | 主树reader和driver不是上期隔离适配版，主树无createDefinitionQuery；上期已完成输入在 `/private/tmp/tela-definition-consumer.f6x_d0l_/tela`，其driver `:59–105` 有惰性名称query及file-owner。权威记录 `docs/superpowers/plans/2026-10-11-definition-catalog/delivery.md:21–32` 说明使用隔离树、Desktop E2E未执行。本期不得把主树或旧51项结果误称已通过新配置合同。取得隔离验证授权、核固定字节后，需真实renderer import/create/配置读取/关闭，不用假client ACK代替。 |

共同反例至少：无插件代码执行的精确静态schema；Java/Node/client同schema输入成功/unknown/missing一致；所有schema与数字限制；raw模板经真实求值后的effective；inactive→enabled；R1当前/R2候选升级不读旧schema；schema-only dynamic digest及builtin有效digest变更；query观察取消后清理继续；读取失败且close失败保留owner和store/driver依赖；保存前not-saved/保存后not-ready/恢复PRESENT+BLOCKED；并发CAS；错误无敏感值和完整schema。

## 8. 文档、签名与迁移落点

- 根README：模块职责 `:66–82`、package `:84–120`、runtime `:122–143` 和深入阅读 `:237–244`。增加作者源文件+实际schema最小例子、宿主读合同→提交原完整target的可运行示例，明确schema成功不保证业务ready。不能只链接设计稿。
- `docs/api/README.md` 当前是公开入口说明（`:1–4`），应加入配置合同与原轻目录的区别、错误与版本/CAS；详细稳定使用文档建议同层新增 `docs/api/plugin-configuration.md`，内容包括格式/profile/作者打包、builtin、值读取、raw/effective、inactive、升级恢复、错误、资源与QA。
- `fibra-plugin-archetype/.../README.md:9–26` 的目录树、schema源和打包说明同步；模板JavaConfig、schema和主机消费例应真实一致。`fibra-example/README.md`、插件README及Node/client公开接入说明同步。
- 更新受影响 config/engine/artifact（如实际改公开面）与runtime相关public-signatures；不为了“同步”修改没有变化的client wire声明。既有 `CONTRIBUTING.md:29–31,38–53` 要求权威设计、签名、契约测试、独立消费者与文档同批；当前本地空仓distribution门有明确限制，正式Maven字节消费应由主线程据本次授权与门禁制定，不能本审计自行执行。
- strict迁移无旧active fallback；缺schema先提供作者修订包，再用原完整selection/CAS换revision；不覆盖不可变历史、不自动改target。Builtin builder API与旧durable selection要单列迁移。

## 检索与限制

Nowledge MCP memory_search，query=`Fibra definition catalog 静态 名称 目录 配置 合同`，mode=`normal`，limit=5，server scope=`strict/default`。返回中相关为 rank1 `f2faf64a-fcdd-4afe-a491-ccb7878c4bbc`（Engine严格失败与启动失败保留，score0.9107585）及rank4 `63f16dff-7434-408a-b6bf-ec74cbd42057`（Assignment显式resolvedconfig，score0.7463703）；其余是用户服务fixture/Tela历史关闭记录，未用于此方案。结论已按当前代码复核，不以记忆代替证据。当前无explore_graph工具且未取得浏览器同身份/space的图访问证明，按技能跳过自动图展示。

本报告仅源码/设计/官方规范审计，没有执行新验证，也没有批准API、profile、依赖、builtin算法或跨仓修改。
