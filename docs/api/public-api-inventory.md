# Fibra 公共 API 审定清单

本清单按受众、能力 owner 和实际用途界定支持合同；`public` 和 `javap` 基线说明可见性，不自动决定 SDK 身份。
审定起点为 `e133814`：19 份 Java 签名基线、423 个类型条目（包含 `$Builder` 等嵌套类型），以及两份 npm
declaration 基线。附录保留该起点的完整类型集合，因此本轮收窄可见性的类型仍有明确审定结论；发行签名以更新后的基线为准。
本轮更新后的19份签名共414个类型：收窄10个顶层类型，新增正式 `RegistryDiagnostics`，其余类型保留原有归属。
整体决策见[公共 API 边界设计](../superpowers/specs/2026-10-15-public-api-boundaries-design.md)。

这是一轮源码、签名、调用路径和既有消费者的静态审定，不代表每个公开方法均已执行测试，也不替代仓外消费者、真实平台和发行验证。

## 支持级别与兼容承诺

| 级别 | 受众与用途 | 兼容承诺 |
|---|---|---|
| 作者合同 | 插件、贡献 kind、配置和标准能力作者 | 签名、类型身份、输入输出含义、资源归属和错误语义均受支持；内部优化须保持这些合同 |
| 宿主合同 | composition root、直接嵌入 core 或 Engine、Registry 管理适配 | 构造与组合方式、命令、CAS、发布、调用和关闭合同受支持；选择低层入口不转移同一运行域的 owner |
| 扩展 SPI | 自定义 runtime、store、审计或宿主能力实现者 | 实现者可依赖必需方法、调用顺序、取消/关闭与失败传播；不能用“高级 API”作为随意改动理由 |
| 诊断合同 | 运维、管理界面、监控与故障工具 | 公开 snapshot、状态、identity、revision、失败分类与只读观察语义受支持；不取得运行资源或写入能力 |
| 适配合同 | Spring、CLI 及标准 runtime 的装配者 | 公开的装配/配置/插件接入方式受支持；第三方框架回调由相应适配 owner 实现，不承诺由用户任意手动调用 |
| 协议合同 | Java/TypeScript execution adapter 和 client runner | wire 字段、判别值、codec 拒绝规则、身份 fence 和版本规则受支持；不包含网络传输或产品 UI 实现 |
| 内部协作，非 SDK | 因 Java 跨包协作而公开的实现值、具体执行器与 descriptor | 不作为外部扩展入口；仍接受字节签名基线和变更审查，不能静默修改后把破坏说成“内部优化” |
| 未接入遗留公开面，非 SDK | 没有进入当前生产调用链的遗留类 | 不推荐新增消费者，也不宣称有运行时错误/装配语义；删除或收窄须显式审定、更新基线并检查既有消费者 |

上述受支持合同的调整须先更新权威设计，说明消费者影响和迁移路径，再同步签名、契约测试及相应仓外验证。
当前 SNAPSHOT 可有经审定的整体迁移，不等于后续任意改动签名。类型的 record 构造器、公开 builder、枚举值、嵌套结果/异常类型，
以及从支持入口可达的参数与返回值，均属于其所属合同。内部协作例外采用下文精确名单，不能扩展成整包豁免。

## 模块与包的默认归属

本表覆盖附录中每一个类型；同一行列明的包均采用该行规则，只有下一节的精确例外可覆盖。
包名统一省略前缀 `com.sstlfsj.fibra`；根包用 `（根）` 表示。嵌套类型继承其顶层类型规则。

| 模块 / 精确包后缀 | 默认级别与受众 | 唯一 owner、支持用途和成立理由 |
|---|---|---|
| `fibra-api` / `（根）`、`.annotation`、`.event`、`.logging`、`.value` | 作者合同；服务/日志等接口也供宿主实现 | API 定义插件、Context、服务、事件、effect、取消与资源树的共同词汇；core/runtime 和插件共同使用，不能把共享类型身份降为实现细节 |
| `fibra-core` / `.runtime` | 宿主合同；`RuntimeDomainSnapshot` 及全部嵌套值为诊断合同 | core 拥有最小运行域和 lifecycle lane；允许脱离 package/Engine 独立嵌入。Engine 托管的同一域不得再从 core 旁路管理 |
| `fibra-artifact` / `.artifact` | 作者/宿主合同；事务、配置资源供扩展实现者使用 | artifact 拥有 package/facet 格式、标识、不可变内容与安装事务；Engine/runtime 借用受管内容，不接管另一份 package 事实 |
| `fibra-config` / `.config` | 作者/宿主合同；repository/transaction 为扩展 SPI，诊断为诊断合同 | config 拥有 raw desired graph、配置表达/求值、schema 和输入文档事务。文件/内存实现可独立使用；它们不能替代 Engine 的完整 durable target 或管理同一份目标 |
| `fibra-bridge` / `.bridge` | 作者合同与扩展 SPI；snapshot/view 为诊断合同 | bridge 拥有 kind/codec/registration、调用 admission 与 drain。`ContributionAdapter` 是面向宿主命名映射的窄函数接口，虽无生产内置调用方，测试展示了外部适配用途，不能仅据引用数删除 |
| `fibra-engine` / `.engine` | 宿主合同；错误结果为相应管理错误合同 | Engine 拥有完整 command、target CAS、运行代编排与关闭；`HostServiceRegistry` 管绑定，`HostTerminationPort` 管终止通知。下列遗留/协作例外不改变正式入口 |
| `fibra-engine` / `.engine.deployment` | 宿主合同与 runtime/store 扩展 SPI；查询错误为诊断合同 | 完整 target、selection、durable token/store、内建 metadata、facet source/dependency 与 definition/config 查询结果是跨能力共享合同；全局编译实现另列为内部协作 |
| `fibra-engine` / `.engine.execution` | 扩展 SPI | runtime candidate 的 `preparedPlan()` 和 `seal(CompiledRuntimeSlice)` 直接交换 plan/unit/binding/fence；第三方执行器需要完整公开值，不可因名称含 Compiled 而一并收窄 |
| `fibra-engine` / `.engine.runtime` | 扩展 SPI | provider、driver、candidate、query、prepared generation、unit generation、host services 与 target slice 是真正执行器边界；Engine 编排，runtime 实现其私有 payload 与执行生命周期 |
| `fibra-engine` / `.engine.observation` | 诊断合同；`HostTerminationRequest` 也是宿主回调值 | 运维消费者依赖 Engine/runtime/unit/operation/retirement 状态和 failure facts；只读但明确受支持，不能以“无稳定承诺”回避演进责任 |
| `fibra-engine` / `.engine.publication` | 宿主/调用方合同；远端 invoker 为扩展 SPI | 发布视图、revision/registration fence、准入、调用与远端失败转换归 Engine 发布面；读取观察与贡献调用不构成第二管理 owner |
| `fibra-registry` / `.registry` | 宿主管理合同；audit repository 为扩展 SPI；snapshot/audit failure 为诊断合同 | Registry 翻译安装、选择和 desired 编辑并记录审计，最终沿同一 Engine 提交；File/内存审计实现均有明确组合或测试消费者 |
| `fibra-runtime-java` / `.runtime.java` | 标准 runtime 适配合同与 Java 作者合同 | `JavaRuntimeProvider`、`JavaApiContract`、`JavaBuiltInPackage`、`JavaDefinitionEntry` 支持 provider 装配、Host 类型合同和内建/动态 Java 入口；错误/phase 支持诊断。私有 descriptor/具体 driver 另列 |
| `fibra-runtime-node` / `.runtime.node` | 标准 runtime 适配合同；异常/phase 为诊断合同 | `NodeRuntimeProvider` 和 `NodeRuntimeOptions`/builder 支持 Node 路径、sidecar 资源及 deadline 等装配；descriptor/具体 driver 另列，payload 文件协议并不因此成为内部任意格式 |
| `fibra-client-protocol` / `.client.protocol` | 协议合同 | Java envelope、message 的全部嵌套变体、fence、codec 与 codec 错误属于同一版本化 wire 合同；跨 Java/npm fixtures，而非另建 client Registry/Engine |
| `fibra-cli-api` / `.cli.api`、`.cli.api.command`、`.cli.api.input`、`.cli.api.invocation`、`.cli.api.terminal` | 作者/宿主合同 | CLI 作者定义应用、动态命令、输入、输出、profile 和受管终端；不得泄漏 Picocli/JLine/Engine 实现到作者 API |
| `fibra-cli` / `.cli` | `CliSession`/builder 为宿主适配合同；`FibraCli` 为发行入口合同 | session 拥有自己的 lane、交互与终端恢复，借用 PublishedRuntime/stream；launcher 是外部进程入口，缺少 Java 调用方不表示可删除 |
| `fibra-spring` / `.spring` | 适配合同 | `@FibraService`、`FibraServiceBridge`、`FibraServiceExporter` 支持显式 Bean 导出和生命周期接入；Spring 拥有 Bean，Fibra 拥有动态运行域，不可双重销毁 |
| `fibra-spring-boot-starter` / `.spring.boot` | 适配合同 | 自动装配与 `FibraProperties` 支持默认 Bean、替换点、storage-root 配置及启停顺序；AutoConfiguration 是框架反射入口，不要求出现 Java new/import 才算消费者 |
| `fibra-tool-api` / `.plugins.tool` | 作者/宿主能力合同 | 工具 descriptor/request/outcome/content、贡献 kind、服务、spill store 和失败值用于标准工具调用与大结果存储；独立 API 制品不以核心模块引用数衡量用途 |
| `fibra-fs` / `.plugins.fs` | 标准能力作者合同 | FileSystem 服务、读写/edit intent、版本/CAS、结果与错误属于文件能力 owner；实现位于标准插件树，不能只扫描顶层模块后判断无消费者 |
| `fibra-shell` / `.plugins.shell` | 标准能力作者合同 | Shell 服务、request/output/result 与错误归 shell 能力；调用和取消经宿主服务与受管资源完成 |
| `fibra-subprocess` / `.plugins.subprocess` | 标准能力作者合同 | Subprocess/ProcessUnit、spec/output/outcome 与错误归进程能力；资源关闭和范围证明属于此合同，不可由调用方另建进程 owner |
| `fibra-storage` / `.plugins.storage` | 标准能力作者合同 | ConfigStore、document、change/listener/operation 与错误归存储能力；这是标准插件服务，不是 Engine target 的第二事实源 |

宿主可以直接用 `FibraEngine`，也可以选用 `PluginRegistry` 的管理用例；两种组合都沿同一个 Engine owner。
管理、诊断、调用是用途区分，不要求额外 facade、重复状态机或为每个用途再建模块。

## 精确内部/遗留例外与消费者证据

以下用 FQCN 精确覆盖例外；`$` 表示 Java 签名中的嵌套类型。没有出现在这里的类型均按上表的受支持合同处理。

| 精确类型（统一前缀 `com.sstlfsj.fibra.`） | 级别 | 源码证据与最小处理 |
|---|---|---|
| `engine.DeploymentCandidate` | 内部协作，非 SDK | 只由 Engine 创建和保存，在同一包中聚合 staged generations/units；不是 `runtime.RuntimeCandidate`。本批审定收窄其可见性，保留真正 runtime SPI |
| `engine.deployment.CompiledDeployment`、`engine.deployment.CompiledDeployment$Builder` | 内部协作，非 SDK | 仅 `FibraEngine`、`DeploymentPlanner` 和上项内部聚合使用，保存全局编译/retained/affected 事实；为根包与 deployment 包协作而公开，不是 runtime plan 或对外诊断 DTO |
| `engine.deployment.DeploymentTargetCompiler`、`engine.deployment.DeploymentTargetCompiler$Compilation`、`engine.deployment.DeploymentTargetCompiler$CompiledFacet`、`engine.deployment.DeploymentTargetCompiler$CompiledBuiltInFacet` | 内部协作，非 SDK | `DeploymentPlanner.compile` 调用全局 resolver，结果进入 `CompiledDeployment`；没有第三方 driver 参数/返回值引用。保持跨包所需可见性和签名监控，不为收窄而引入转发层 |
| `engine.PluginCatalog`、`engine.PluginCatalogEntry` | 未接入遗留公开面，非 SDK | 全仓 Java 引用仅限二者自身；没有 Engine builder、provider、Registry 或发行消费者引用。不可误作当前 definition 目录。新消费者使用 `describeDefinitions`/`PackageDefinitionCatalog` 或 Java 的 `JavaBuiltInPackage`/`JavaDefinitionEntry`；本批审定收窄，保留文件与包 |
| `engine.DesiredBindingException`、`engine.UnknownRuntimeException` | 未接入遗留公开面，非 SDK | 全仓 Java 只找到声明，没有 throw/catch 或生产构造调用。不能把它们列作当前管理错误保证；本批审定收窄，本清单不新增抛出路径 |
| `runtime.java.JavaFacetDescriptor` | 内部协作，非 SDK | 仅 Java descriptor reader、class-space 和 driver 消费；`RuntimeArtifactInspection` 刻意不带私有 descriptor。本批审定收窄 record 可见性；Java payload manifest 与 `hostApis` 文件合同继续受支持 |
| `runtime.java.JavaRuntimeDriver` | 内部协作，非 SDK | 构造器包内可见，`JavaRuntimeProvider.create` 返回 `RuntimeDriver`；生产消费者只在 Java runtime 内部。本批审定收窄具体类；通用 `RuntimeDriver` 及其方法合同继续支持 |
| `runtime.node.NodeFacetDescriptor`、`runtime.node.NodeEndpointManifest` | 内部协作，非 SDK | 只在 Node reader/driver/sidecar 协作中交换局部 manifest；Engine 与 provider 装配均不交换此 Java DTO。本批审定收窄，不得因此擅改 `fibra-node.json` payload 协议 |
| `runtime.node.NodeRuntimeDriver` | 内部协作，非 SDK | 构造器包内可见，`NodeRuntimeProvider.create` 返回通用 `RuntimeDriver`；具体类没有 runtime 模块外生产消费者。本批审定收窄，不削减 runtime SPI |

证据入口：

- [Engine 管理、candidate 与 snapshot 发布](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java)、[DeploymentPlanner](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/DeploymentPlanner.java)、[CompiledDeployment](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/deployment/CompiledDeployment.java)、[DeploymentTargetCompiler](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/deployment/DeploymentTargetCompiler.java)。
- [RuntimeDriver](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/runtime/RuntimeDriver.java)、[RuntimeCandidate](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/runtime/RuntimeCandidate.java)、[PreparedRuntimeGeneration](../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/runtime/PreparedRuntimeGeneration.java) 的真实签名决定 SPI 可达值；内部全局 compilation 不在其中。
- [Java provider](../../fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeProvider.java)、[Java built-in 装配](../../fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaBuiltInPackage.java)、[Node provider](../../fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeProvider.java) 说明 provider 配置公开而具体 driver 受 owner 管理。
- [Spring exporter](../../fibra-spring/src/main/java/com/sstlfsj/fibra/spring/FibraServiceExporter.java)、[Boot 自动装配](../../fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/boot/FibraAutoConfiguration.java) 说明框架入口与资源关闭归属。
- [Java 制品外消费者源码](../../fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/EngineConsumerTest.java)、[生命周期消费者源码](../../fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/LifecycleConsumerTest.java)、[npm 打包消费者源码](../../client/tests/package-consumer/src/index.ts) 证明已存在相应组合用途；本次只读源码，不把其历史通过状态算作本次验证结果。

消费者核对包含全仓 Java 文本引用、主要生产调用链与消费者 fixture。未发现引用只能证明本仓未见直接源码消费者，不能证明仓外无人使用；
可见性收窄或移除仍需对应的签名变更审定。反过来，测试-only、框架反射、launcher 和独立标准能力都有成立的外部用途，不能仅按引用计数移除。
本批审定收窄共 10 个顶层类型，不删除文件、不重排包；上述 6 个全局编译类型因跨包协作保留字节 `public`。
这里记录审定决定，源码改动、基线再生成与验证结果由本批交付证据确认，不把本清单中的决定当成已验证实现。

## 管理与诊断的用途边界

管理入口为 core 的独立运行域入口，或 Engine 的完整命令；Registry 在同一 Engine 上提供编辑用例。
完整 replacement 必须携带调用方观察到的 expected revision、全部 selections、raw graph 和 config context。
`PluginDeploymentRequest` 的本轮修正属于收口 CAS 合同；Registry 不应在收到完整外部 replacement 后自行换成最新 revision。
局部编辑仍沿 Registry 的原编排和 Engine CAS，不能变成持久目标的第二 writer。

`EngineSnapshot`/`EngineDiagnostics`/`RuntimeDriverSnapshot`、operation/attempt/retirement/failure facts 与 Registry snapshot/audit
是受支持的只读诊断。诊断消费者可以依赖字段含义和身份，但不能依靠快照中的路径/状态获取 execution handle、关闭 unit 或重新驱动生命周期。
`PublishedRuntime` 同时有发布观察和贡献调用用途，其 view revision 与 registration identity 校验继续归原发布 owner。
本轮将普通管理投影保留在 `RegistrySnapshot`，将显式深度读取放入 `RegistryDiagnostics`；两者以及其公开 builder 都是正式支持合同，
owner 同为 Registry，diagnostics 只汇集原 Engine 事实，不持有第二运行态。新增诊断类型沿 `.registry` 默认规则归属，纳入本轮更新后的签名基线。

## npm 与 Java client 协议的完整归属

两份 npm baseline 不以包内是否有运行时实现来决定支持级别；interface/type 本身即为消费者合同。

| 包与精确导出组 | 受众、owner 与级别 |
|---|---|
| `client-api`：`ClientDisposable`、`ClientScope`、`OwnedClientScope`、`HostCaller`、`ClientInstanceContext`、`ClientModule`、`ClientModuleDefinition`、`ClientEntryModule` | client 插件作者与 runner；作者合同。runner 拥有 root lifetime，每个 assignment 创建独立 instance；插件只能持借入的 scope/host capability |
| `client-api`：`ClientError`、`SessionFence`、`LifecycleFence`、`CallFence`、`ContributionId`、`LiteralValue`、`LiteralNumber`、`LiteralObject`、`ClientCall`、`ResourceDescriptor`、`Assignment`、`Contribution` | execution adapter/runner；协议值合同。长整型用规范十进制字符串，assignment 绑定 resolved config 和 unit 创建 revision，调用 fence 不由插件伪造 |
| `client-protocol` 自有导出：`LifecycleOutcome`、`CallOutcome`、`ExecutionObservation`、`Envelope`、`ProtocolEnvelope`、`PROTOCOL_VERSION`、`MAX_ENVELOPE_BYTES`、`MAX_NESTING_DEPTH`、`MAX_DECIMAL_CHARACTERS`、`ClientProtocolError`、`decodeEnvelope`、`decodeFixtures`、`encodeEnvelope` | Java/TypeScript execution 协议实现者；协议合同。codec/限额/判别值、版本和失败拒绝规则归协议 owner，不包含 transport、下载和产品 UI |
| `client-protocol` 转导出：`Assignment`、`CallFence`、`ClientCall`、`Contribution`、`ContributionId`、`LifecycleFence`、`LiteralValue`、`SessionFence` | 同一 `client-api` 类型合同的公开路径；转导出路径本身受兼容保护，不复制定义或另设 owner |

基线：[client-api.d.ts](../../client/api-baseline/client-api.d.ts)、[client-protocol.d.ts](../../client/api-baseline/client-protocol.d.ts)。
`client-protocol.d.ts` 内的非导出 `ErrorCode` 是 `ClientProtocolError` 签名可达的封闭错误词汇，随该错误合同演进；
不承诺用户可以直接按名字 import。Java `ClientMessage` 的所有嵌套消息与 outcome、codec nested exception/code 均在下方附录覆盖。

## 完整 Java 类型覆盖索引

下表从审定起点的 `## FQCN` 条目机械提取，不根据文件名猜测类型。每一项按“模块/包默认规则 → 精确例外”唯一归属；
`$Builder`、`$Prepared`、sealed 子类型和嵌套诊断值均显式保留。数量为审定起点的条目数，不是当前发行类数量。

| 基线模块 | 精确包 | 条目数 | 全部类型（包内名） |
|---|---|---:|---|
| `fibra-api` | `com.sstlfsj.fibra` | 33 | `Associated`, `CancellationSource`, `CancellationToken`, `ConfigValidator`, `Context`, `Disposable`, `Disposables`, `DrainingDisposable`, `EffectHandle`, `EffectMetadata`, `Effects`, `Events`, `FibraException`, `InvocationContext`, `ManagedPluginControl`, `Plugin`, `PluginDefinition`, `PluginDefinition$Builder`, `PluginDefinition$Prepared`, `PluginEntrypoint`, `PluginFactory`, `PluginInstance`, `PluginInstanceState`, `Plugins`, `Properties`, `PropertyAccessor`, `PropertyKey`, `Scope`, `ScopeView`, `ServiceKey`, `ServiceRef`, `ServiceRegistration`, `Services` |
| `fibra-api` | `com.sstlfsj.fibra.annotation` | 2 | `InjectService`, `InjectServices` |
| `fibra-api` | `com.sstlfsj.fibra.event` | 7 | `AggregateEventException`, `EventKey`, `EventMode`, `EventOptions`, `EventTarget`, `EventTarget$BoundContext`, `Next` |
| `fibra-api` | `com.sstlfsj.fibra.logging` | 6 | `FibraLogger`, `LogExporter`, `LogLevel`, `LogMessage`, `LoggerIntercept`, `LoggerService` |
| `fibra-api` | `com.sstlfsj.fibra.value` | 7 | `LiteralValue`, `LiteralValue$BooleanValue`, `LiteralValue$ListValue`, `LiteralValue$NullValue`, `LiteralValue$NumberValue`, `LiteralValue$ObjectValue`, `LiteralValue$StringValue` |
| `fibra-artifact` | `com.sstlfsj.fibra.artifact` | 20 | `ArtifactException`, `ArtifactId`, `ArtifactPhase`, `ArtifactState`, `ExecutionTarget`, `FacetDependency`, `FacetId`, `FacetRole`, `ManagedFacet`, `ManagedPluginPackage`, `ManagedPluginPackage$Builder`, `PluginConfigurationResource`, `PluginFacet`, `PluginId`, `PluginPackage`, `PluginPackageInstallTransaction`, `PluginPackageRecord`, `PluginPackageRecord$Builder`, `PluginPackageStore`, `RuntimeId` |
| `fibra-bridge` | `com.sstlfsj.fibra.bridge` | 20 | `ContributionAdapter`, `ContributionAdmission`, `ContributionBinding`, `ContributionCall`, `ContributionCodec`, `ContributionDirectory`, `ContributionDirectoryView`, `ContributionDrainException`, `ContributionHandler`, `ContributionId`, `ContributionKind`, `ContributionKindRegistry`, `ContributionRegistrar`, `ContributionRegistration`, `ContributionRoutes`, `ContributionServices`, `ContributionSnapshot`, `ContributionSnapshotEntry`, `ContributionUnavailableException`, `RemoteContributionFailure` |
| `fibra-cli-api` | `com.sstlfsj.fibra.cli.api` | 2 | `CliApplication`, `CliApplication$Builder` |
| `fibra-cli-api` | `com.sstlfsj.fibra.cli.api.command` | 7 | `CliBootstrapCommand`, `CliCommandContributions`, `CliCommandDescriptor`, `CliCommandHandler`, `CliCommandOption`, `CliCommandRequest`, `CliCommandResult` |
| `fibra-cli-api` | `com.sstlfsj.fibra.cli.api.input` | 3 | `CliInputHandler`, `CliInputRequest`, `CliInputResult` |
| `fibra-cli-api` | `com.sstlfsj.fibra.cli.api.invocation` | 4 | `CliExitStatus`, `CliInvocation`, `CliOutput`, `CliProfile` |
| `fibra-cli-api` | `com.sstlfsj.fibra.cli.api.terminal` | 13 | `CliTerminal`, `CliTerminalCapabilities`, `CliTerminalControl`, `CliTerminalCursor`, `CliTerminalFrame`, `CliTerminalInput`, `CliTerminalKey`, `CliTerminalLease`, `CliTerminalModifier`, `CliTerminalRenderer`, `CliTerminalSize`, `CliTerminalUnavailableException`, `CliTerminalUnavailableReason` |
| `fibra-cli` | `com.sstlfsj.fibra.cli` | 3 | `CliSession`, `CliSession$Builder`, `FibraCli` |
| `fibra-client-protocol` | `com.sstlfsj.fibra.client.protocol` | 34 | `CallFence`, `ClientEnvelope`, `ClientMessage`, `ClientMessage$Activate`, `ClientMessage$Assignment`, `ClientMessage$Call`, `ClientMessage$CallOutcome`, `ClientMessage$CallOutcome$Failed`, `ClientMessage$CallOutcome$Success`, `ClientMessage$CallResult`, `ClientMessage$Contribution`, `ClientMessage$ContributionId`, `ClientMessage$Detach`, `ClientMessage$Drain`, `ClientMessage$ExecutionObservation`, `ClientMessage$Failure`, `ClientMessage$Hello`, `ClientMessage$LifecycleOutcome`, `ClientMessage$LifecycleOutcome$Applied`, `ClientMessage$LifecycleOutcome$Failed`, `ClientMessage$LifecycleResult`, `ClientMessage$Observed`, `ClientMessage$ObservedState`, `ClientMessage$Prepare`, `ClientMessage$ResourceDescriptor`, `ClientMessage$Snapshot`, `ClientMessage$Stop`, `ClientMessage$Welcome`, `ClientProtocolCodec`, `ClientProtocolCodec$ErrorCode`, `ClientProtocolCodec$ProtocolException`, `HelloIdentity`, `LifecycleFence`, `SessionFence` |
| `fibra-config` | `com.sstlfsj.fibra.config` | 41 | `ConfigBatchFailure`, `ConfigContextSnapshot`, `ConfigDiagnostic`, `ConfigDocumentSnapshot`, `ConfigDocumentStore`, `ConfigException`, `ConfigLimits`, `ConfigSchema`, `ConfigSchemaException`, `ConfigSchemaException$Code`, `ConfigSchemaSource`, `ConfigStage`, `ConfigValidationInput`, `ConfigViolation`, `ConfigWriteTransaction`, `DefinitionConfigDocument`, `DesiredCompilation`, `DesiredCompilation$Builder`, `DesiredConfigCompiler`, `DesiredEvaluation`, `DesiredIncludeContent`, `DesiredIncludeContent$Collected`, `DesiredIncludeContent$Uncollected`, `DesiredInputEntry`, `DesiredInputEntry$Builder`, `DesiredInputGraph`, `DesiredInputGraph$EffectiveDesiredEntry`, `DesiredInputGraph$EffectivePolicyValue`, `DesiredInputGroup`, `DesiredInputGroup$Builder`, `DesiredInputInclude`, `DesiredInputInclude$Builder`, `DesiredInputNode`, `DesiredSourceSnapshot`, `DesiredStateRepository`, `DesiredStateWriteTransaction`, `FileDesiredStateRepository`, `InMemoryDesiredStateRepository`, `PluginDefinitionRef`, `PublicationRequirement`, `ResolvedDesiredEntry` |
| `fibra-core` | `com.sstlfsj.fibra.runtime` | 13 | `FibraRuntime`, `PluginUpdate`, `RuntimeDomain`, `RuntimeDomainSnapshot`, `RuntimeDomainSnapshot$Builder`, `RuntimeDomainSnapshot$CleanupFailure`, `RuntimeDomainSnapshot$Dependency`, `RuntimeDomainSnapshot$Event`, `RuntimeDomainSnapshot$Listener`, `RuntimeDomainSnapshot$OwnerIdentity`, `RuntimeDomainSnapshot$Plugin`, `RuntimeDomainSnapshot$Service`, `RuntimeDomainSnapshot$ServiceIdentity` |
| `fibra-engine` | `com.sstlfsj.fibra.engine` | 16 | `ApplyDeployment`, `ApplyDeployment$Builder`, `DeploymentCandidate`, `DesiredBindingException`, `EngineChangeException`, `EngineCommand`, `EngineCommandResult`, `FibraEngine`, `FibraEngine$Builder`, `HostServiceRegistry`, `HostTerminationPort`, `MutationGateClosedException`, `PluginCatalog`, `PluginCatalogEntry`, `ReconcileCurrent`, `UnknownRuntimeException` |
| `fibra-engine` | `com.sstlfsj.fibra.engine.deployment` | 29 | `BuiltInFacet`, `BuiltInFacet$Builder`, `BuiltInPluginPackage`, `BuiltInPluginPackage$Builder`, `CompiledDeployment`, `CompiledDeployment$Builder`, `ConfigContractException`, `ConfigContractException$Code`, `DefinitionCatalogException`, `DefinitionCatalogException$Code`, `DefinitionConfigContract`, `DeploymentTarget`, `DeploymentTargetCodec`, `DeploymentTargetCompiler`, `DeploymentTargetCompiler$Compilation`, `DeploymentTargetCompiler$CompiledBuiltInFacet`, `DeploymentTargetCompiler$CompiledFacet`, `DeploymentTargetStore`, `DeploymentTargetStore$SaveUnconfirmedException`, `DeploymentTargetStore$StoredTarget`, `DeploymentTargetStoreException`, `DurableTargetToken`, `FacetDefinitionNames`, `FileDeploymentTargetStore`, `HostCapabilitySnapshot`, `PackageDefinitionCatalog`, `PluginFacetSource`, `PluginSelection`, `ResolvedFacetDependency` |
| `fibra-engine` | `com.sstlfsj.fibra.engine.execution` | 9 | `CompiledRuntimeSlice`, `DefinitionBindingPlan`, `DefinitionBindingPlan$Builder`, `ExecutionUnitKey`, `ExecutionUnitPlan`, `ExecutionUnitPlan$Builder`, `RuntimePlan`, `RuntimeUnitFence`, `RuntimeUnitFence$Builder` |
| `fibra-engine` | `com.sstlfsj.fibra.engine.observation` | 37 | `CandidateAttemptSnapshot`, `CandidatePhase`, `CurrentAttemptSnapshot`, `CurrentPhase`, `DurableTargetState`, `EngineDiagnostics`, `EngineOperationKind`, `EngineOperationOutcome`, `EngineOperationSnapshot`, `EngineOperationStage`, `EngineSnapshot`, `EngineState`, `ExecutionObservation`, `ExecutionObservation$Detail`, `ExecutionObservation$Detail$Builder`, `ExecutionObservation$Failure`, `ExecutionObservation$State`, `FailureFact`, `FailureStage`, `FailureSubject`, `FailureSubject$Candidate`, `FailureSubject$Current`, `FailureSubject$DurableTarget`, `FailureSubject$Engine`, `FailureSubject$Operation`, `FailureSubject$Retirement`, `FailureSubject$Unit`, `HostTerminationRequest`, `PluginInstanceSnapshot`, `PluginInstanceSnapshot$Builder`, `RetirementBatchSnapshot`, `RetirementPhase`, `RuntimeDiagnostics`, `RuntimeDiagnostics$Builder`, `RuntimeDriverSnapshot`, `TargetConvergence`, `TargetSaveState` |
| `fibra-engine` | `com.sstlfsj.fibra.engine.publication` | 7 | `PublishedRevisionConflictException`, `PublishedRuntime`, `PublishedView`, `PublishedView$Builder`, `RemoteContributionInvocationException`, `RemoteContributionInvocationException$Code`, `RemoteContributionInvoker` |
| `fibra-engine` | `com.sstlfsj.fibra.engine.runtime` | 12 | `PreparedRuntimeGeneration`, `RuntimeArtifactInspection`, `RuntimeCandidate`, `RuntimeDefinitionQuery`, `RuntimeDriver`, `RuntimeHostServices`, `RuntimeProvider`, `RuntimeRecompileReason`, `RuntimeTargetSlice`, `RuntimeTargetSlice$Builder`, `RuntimeUnitDisableRequest`, `RuntimeUnitGeneration` |
| `fibra-fs` | `com.sstlfsj.fibra.plugins.fs` | 19 | `FileSystem`, `FileSystemServices`, `FsEdit`, `FsEditIntent`, `FsEditIntent$ReplaceIfVersion`, `FsEditIntent$Unconditional`, `FsEditResult`, `FsErrorCode`, `FsException`, `FsFileType`, `FsInfo`, `FsTarget`, `FsVersion`, `FsWriteIntent`, `FsWriteIntent$CreateIfAbsent`, `FsWriteIntent$ReplaceIfVersion`, `FsWriteIntent$Unconditional`, `FsWriteOperation`, `FsWriteResult` |
| `fibra-registry` | `com.sstlfsj.fibra.registry` | 13 | `FilePluginAuditRepository`, `InMemoryPluginAuditRepository`, `PluginAuditDeliveryFailure`, `PluginAuditDeliveryFailure$Builder`, `PluginAuditEntry`, `PluginAuditEntry$Builder`, `PluginAuditRepository`, `PluginDeploymentRequest`, `PluginInstallRequest`, `PluginRegistry`, `RegistryPluginState`, `RegistrySnapshot`, `RegistrySnapshot$Builder` |
| `fibra-runtime-java` | `com.sstlfsj.fibra.runtime.java` | 8 | `JavaApiContract`, `JavaBuiltInPackage`, `JavaDefinitionEntry`, `JavaFacetDescriptor`, `JavaRuntimeDriver`, `JavaRuntimeException`, `JavaRuntimePhase`, `JavaRuntimeProvider` |
| `fibra-runtime-node` | `com.sstlfsj.fibra.runtime.node` | 9 | `NodeEndpointManifest`, `NodeFacetDescriptor`, `NodeRpcException`, `NodeRpcPhase`, `NodeRuntimeDriver`, `NodeRuntimeException`, `NodeRuntimeOptions`, `NodeRuntimeOptions$Builder`, `NodeRuntimeProvider` |
| `fibra-shell` | `com.sstlfsj.fibra.plugins.shell` | 10 | `Shell`, `ShellErrorCode`, `ShellException`, `ShellOutput`, `ShellOutput$Builder`, `ShellRequest`, `ShellRequest$Builder`, `ShellResult`, `ShellResult$Builder`, `ShellServices` |
| `fibra-spring-boot-starter` | `com.sstlfsj.fibra.spring.boot` | 2 | `FibraAutoConfiguration`, `FibraProperties` |
| `fibra-spring` | `com.sstlfsj.fibra.spring` | 3 | `FibraService`, `FibraServiceBridge`, `FibraServiceExporter` |
| `fibra-storage` | `com.sstlfsj.fibra.plugins.storage` | 8 | `ConfigChange`, `ConfigChangeListener`, `ConfigChangeOperation`, `ConfigDocument`, `ConfigStore`, `StorageErrorCode`, `StorageException`, `StorageServices` |
| `fibra-subprocess` | `com.sstlfsj.fibra.plugins.subprocess` | 11 | `ProcessUnit`, `Subprocess`, `SubprocessErrorCode`, `SubprocessException`, `SubprocessOutcome`, `SubprocessOutcome$Builder`, `SubprocessOutput`, `SubprocessOutput$Builder`, `SubprocessServices`, `SubprocessSpec`, `SubprocessSpec$Builder` |
| `fibra-tool-api` | `com.sstlfsj.fibra.plugins.tool` | 15 | `ResultSpillStore`, `ToolContent`, `ToolContent$Text`, `ToolContributions`, `ToolDescriptor`, `ToolException`, `ToolFailure`, `ToolFailureCode`, `ToolOutcome`, `ToolOutcome$Failure`, `ToolOutcome$Success`, `ToolOutcomes`, `ToolRequest`, `ToolResult`, `ToolServices` |
