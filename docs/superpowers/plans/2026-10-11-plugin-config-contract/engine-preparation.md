# Task 4 Engine 接缝准备（只读，2026-10-12）

基线：Task1已完成，当前工作树正在实现Task2；本报告不修改仓库、不运行测试。下列源码位置以 `/Users/sunke/dev/ai-project/fibra` 为根；行号为本次读取时位置。`E` = `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`；`P` = 同目录 `DeploymentPlanner.java`；`T` = `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/RuntimeDriverEngineTest.java`。已完成一次定向 Nowledge 查询，仅返回旧部署蓝图，无本合同精确记录；结论均由当前源码与批准设计得出。

权威门：spec §6/7:179–275、CC01–08/10:321–330；implementation-plan Task4:81–97。不发现需重设计的合同矛盾；以下是实现差距和测试迁移点。

## 1. 输入：可直接复用的事实与最小接入点

- **已确认** `P:24–41` 先编译选择/facet 图，再 `DesiredEvaluation.evaluate`，最后同时以 `resolved.effective().enabled()` 和 `selection.enabled()` 过滤。`input.entries()` 已经是执行 entry 的唯一集合，`input.units()` 与之对应；每项真实配置取 `input.desired().require(entryId).resolvedConfig().orElseThrow()`。不要对 raw config 再求值，也不要遍历全部 desired 节点做 schema 校验。
- **已确认** `fibra-config/.../DesiredEvaluation.java:58–86` 的 local/祖先 disabled 跳过 when 与 config；when=false 跳过 config，并向子节点传播 inactive。active config 的 `$ref/$if/$literal` 都由既有 evaluator 求值（75–76），即使 package gate=false 也已经求值。`P:37–41` 的“未选择包”检查仍早于 disabled 过滤；不能把 inactive 延期扩大为允许未选择包。
- **已确认** `deployment/DeploymentTargetCompiler.java:29–56` 会验证所有 selection 的精确身份；enabled package 的 facet 依赖图照旧完整验证。schema 门只消费 active entries，不改变这些既有结构错误、capability 门或 runtime 可用性检查（`P:60–84`、`E:616–617`）。没有 entry 的 install/selection 不强制读合同。
- **建议的最小顺序** 保留 `E:594–607` 的 begin/capture/fingerprint/no-op；`609–613` 捕获 `List<PluginPackageRecord>` 后另行投影 managed packages 给 planner；在 `615–617` 后接一个返回 Mono 的内部预检，完成静态名称、精确合同读取、批量校验与真实关闭，才在 `loop.call(...)` 中进入原 `618–692` candidate 流程。不能先执行 `625` 的 candidate 登记或 `648` 的 `createCandidate`。
- **已确认** 动态 definition 名称目前不由 planner 验证：`P:53–57` 只定位 facet；builtin 已在 `P:48–49` 验 definition。新协作者必须用 runtime 原 `createDefinitionQuery(ManagedFacet)` 读取动态名称，且 query 按所选 facet/definition 核对后才碰 schema。
- **建议** 同一 attempt 内按包复用已读 envelope、按 facet 复用名称纯值；不要全量 compile dormant siblings。`DefinitionConfigDocument.java:22–47` 已做全文件 envelope/中立预算；所选 active sources 按 entry 顺序交 `ConfigSchema.validateAll`（`ConfigSchema.java:51–61`），它负责集合预算与重复实例计费。逐项 `compile().validate()` 会遗漏批量门；向 batch 传全包 sources 会误拒绝 dormant sibling。

## 2. 共享读取 owner：不增加第二屏障

- **已确认** `E:127` 是唯一 Engine 读取 attempt 索引；`CatalogAttempt:390–484` 先登记全部 runtime query，再串行 read、逆序 close；成功句柄移除，失败句柄保留；`closed.cache()` 固定真实终态。`readAndClose:424–429` 在成功关闭后才清索引/交付纯值。新父 owner 应只抽出这些生命周期事实，原名称 query 的内容投影和异常类型保留。
- **建议** 父 owner 只需要有界的登记项与逆序关闭观察器，覆盖 runtime query 和 artifact resource；artifact `configurationResource()` 创建后立即登记，然后才 `read()`。底层句柄仍自己拥有 read/close 终态，父 owner 不重开、不重试物理 close。配置文档/返回 source 留在局部协作者，不把它们扩展成 Registry/cache/framework。
- **已确认** `EngineCommandLoop.java:14–36,62–66` 已将 accepted execution 与结果订阅者分离；`E:194–200` shutdown 先 quiesce。公开 configuration query 使用同样一次 `loop.submit`；内部部署直接使用此次已捕获 records/builtin，不能嵌套 submit 等自己。
- **已确认** shutdown `E:1057–1059` 先观察旧 attempt.closed，然后才 `1063–1068` 建 SHUTDOWN；`beginOperation:1222–1226` 会清 `failure`。抽父 owner 后保留此顺序，失败 close 直接阻止依赖释放（`1114–1120`），且不能清 owner 索引。兄弟清理必须已经在父 owner 内继续执行完。
- **回归不能削弱** `T:117–147` 登记/串读/逆关顺序；`T:285–341` cleanup 优先、旧 operation 不变、close 一次、driver/store 保留和失败句柄可达；`T:346…` 同步抛错/null/异步 close；`T:525…` quiesce 前已接纳 query 仍执行。反射 `catalogAttempt` 字段测试可迁移到共享父 owner 的结构，但不能删除“失败子句柄仍可达、成功子句柄已释放”的断言。

## 3. 失败投影：尤其 BOOTSTRAP cleanup 的早返窗口

**已确认普通部署预检失败路径**：仍没有 candidate；安全异常进入 `deploymentFailed` → `E:935–945`，无 current 时设置 ABSENT/BLOCKED，BOOTSTRAP 用 `durableTargetFailure`，其余用 `operationFailure`；原 operation 保持 PLANNING，outcome FAILED。已有 current 时不关闭旧准入。query 普通失败只返回安全异常，不触碰 operation。

**必须补的 cleanup 顺序（从现实现推出，直接满足 spec:265–273）**：

1. 读取父 owner 逆序观察全部子句柄，缓存 close 失败终态并保留失败子句柄；构造只含固定安全 code 的结果，read+close 双失败以 cleanup 为主，不能挂原 cause/suppressed。
2. 部署上下文在此时仍是原 operation 的 PLANNING，`candidate==null`、`operation.attemptId==null`。BOOTSTRAP 必须**在 failStop 前**显式 `targetConvergence=BLOCKED`，`durableState` 保持 PRESENT。理由：bootstrap 在 `E:529–531` 已置 PRESENT+CONVERGING；`failStop:977–1012` 不处理 DurableTarget convergence；随后 `deploymentFailed:866–868` 与 `bootstrap:534–535` 均会早返，绝不会再进入 `935–945`/`536–537` 补 BLOCKED。
3. 部署 cleanup 用既有 `failStop(safeFact)`，不要只 `enterFailStop`。subject 为 BOOTSTRAP 的 `DurableTarget(revision,digest)` 或其余的 `Operation(operationId)`；failure.stage=RELEASING，operationStage=PLANNING，targetRevision=本次 operation。`failStop:979` 负责 outcome=FAILED；其余准入/终止通知复用原机制。不能套 query 的 Engine subject，也不能造 candidate attemptId。
4. query cleanup 沿 `catalogFailStop:487–500` 的模式：Engine(hostInstanceId)、RELEASING、operationStage 空、捕获 durable revision；不能调用会把历史 Apply 标 FAILED 的通用 `failStop`。共享的是读/关 owner，发起上下文的投影不同。
5. 后续 close 必须先复读同一 cached close failure 再考虑 begin SHUTDOWN；断言 failure、operationId/kind/stage/outcome/saveState、targetRevision 均不被覆盖，driver/store close=0，兄弟句柄已尝试，物理 close 不增加。

字段级用例至少覆盖：新 Apply=NOT_SAVED/候选 revision；同 target 重建 Apply=NOT_APPLICABLE/原 revision；BOOTSTRAP=PRESENT+BLOCKED/NOT_APPLICABLE/DurableTarget；Reconcile 重建和 requestRecompile=NOT_APPLICABLE/原 revision/Operation；query 保留之前成功 Apply。`E:1428–1438` 的通用 failureFact 会读当前 operation 并 `error.toString()`，query 不能直接用它；部署也只能传已净化的安全错误。新合同清理路径还须避免 `failStop:1014–1018` 的第三方 admission 错误文本泄漏（原 catalog 路径使用固定文本，`495–497`）。

## 4. R2 与 no-op 身份

- **已确认** `E:609–613` 当前 find 的确取“本次 target”的精确 selection，却立即丢掉 record。最小修复是保留这些 store.find 返回的核验 record；资源必须从它们取得，绝不根据 durableTarget（当前 R1）再查、根据目录手造 record、或调用公开 query。`describeSelectedDefinitions:334–348` 现有双向 record/managed 身份检查可复用其原则。
- **必写反例** 先成功部署 R1（宽松规则），安装同 id 的 R2（仅 schema 更严格、同 raw/effective 配置）；Apply R2 必须在 create/prepare 前拒绝，target/current 仍 R1，R2 保留为已发布包历史。`targetStore.save` 仍只在 `E:676` 发生；不能为 NOT_SAVED 自动删 R2。另测试只改 schema 的成功 R2 影响原 affected 闭包、无关实例不重启。
- **已确认** 动态 revision 已进 target canonical（`deployment/DeploymentTarget.java:105–110`）；builtin digest 已进 `E:1505–1518` inputs identity。`P:106–111,157–162` 用包 revision/artifact/static facet inputs/effective config 算 affected。Task3 的唯一最终 builtin digest 应直接复用，不再序列化 schema 到第二身份。
- **差距** `E:1498–1518` 尚无 profile 解释器版本。应在现有 inputs identity canonical map 中加入固定内部解释器 identity（不仅是可变/外部传入 profile 字符串），无新持久字段。Task1 `SchemaProfile:14–16` 是 package-private，批准公共 API 没有新增 identity getter；当前最小边界是在 Engine 内部协作者声明固定 identity 并纳入此 map，不扩公共 API。
- **已确认语义** 同 target Apply 和 requestRecompile 都重捕 inputs identity 后经过 `E:601–607` no-op，后者 forceAll 也不会越过这个 no-op。健康/PENDING `retryCurrent:573–582` 根本不重捕，不应宣传能侦测外部文件变化。进入新 candidate 才必须预检全 active entries，runtime 仍只 prepare 原 affected 部分。

## 5. 现有 fixture 的最小迁移清单

以下为源码确定的影响点；尚未执行测试，不能称为已失败/已通过。

| 测试与位置 | 真正需要的迁移及不可削弱的语义 |
|---|---|
| `T:2836–2841` ProbeProvider.metadata；`2691–2697` graph/entry | builtin main/definition 要给匹配 integer 配置的显式 schema（具体非整数用例先读输入再调整对应 fixture）；不得用缺合同失败代替 prepare/seal/save/activate/retire 失败。继续禁止 builtin 使用动态 query SPI（2850）。 |
| `T:551–568` catalogBuiltInMetadataIsFresh… | 原测试允许同 digest 改名称，Task3 后应拒旧 revision、明确 CAS 选入新 metadata.selection 后查到新名称；不能继续冒用固定 digest。 |
| `T:1534–1544` builtInDeclarationDriftChangesFingerprintEvenWhenContractAndDigestStayTheSame | 新最终 digest 包含 requiredCapabilities，此测试名和“store.saves==1”旧语义不再成立；保留 metadata-only 变更重编译的目标，按真实新 selection/revision 断言，不能只放宽计数。`1520–1529` 单改 provider contract 的同 durable token 场景则仍成立。 |
| `T:610–637` catalogPackage/catalogEngine | 这些动态目录测试多为无执行 entry 或预期前置图失败，不应全局自动补开放合同；只给实际需成功 active deploy 的新/旧用例写真实根资源。保持名称 query 无 schema 也能工作。 |
| `fibra-runtime-java/.../JavaBuiltInRecoveryTest.java:115–134` | metadata 声明真正 null 配置 schema，并让 deployment 使用该 metadata 的最终 selection；当前硬编码 DIGEST 会提前身份拒绝。`52–86` 必须继续验证私有 definitions 缺失后的可管理恢复，`90–103` 必须继续到 runtime 完整声明集核对，不能被 MISSING_CONTRACT 提前拦截。建议增加 prepare/绑定现场证据防止假绿。 |
| `fibra-runtime-java/.../JavaRuntimeDriverTest.java:1847–1861,1897–1907,2082…` | 主要直接构造 RuntimeDriver/candidate/slice，不通过 Engine；不要强加 package 根读取。仅随 Task3 元数据 API 迁移 builtin helper。原类型绑定、静态空间、失败 prepare/close 等直接 API 语义继续有效。 |
| `fibra-registry/.../PluginRegistryTest.java:349–373` source | 两个 facet main/aux 都声明 run（query 422–437），desired 默认 null（376–377），在发布前写根 `fibra-config.json` 的对应 null 合同。保留 397–407 的 NOT_SAVED/UNCONFIRMED save 注入以及 activationFailure；不允许这些测试被缺合同提前截断。 |
| `fibra-cli/.../CliHostTest.java:149–179` javaPackage | main/context 的配置是 String（182–189），写显式 string 合同。92–126 的包 publish 与 target 不变断言继续保留。 |
| `fibra-cli/.../CliHostOwnershipTest.java:192–210` javaPackage | main/ownership 为 String（213–235），写 string 合同才能继续到真实 start/drain/release 失败、资源持有现场；不能改预期为 preflight 拒绝。 |
| `fibra-cli/.../FibraCliTest.java:620–665` failingToolProfile/dynamicCommandProfile | main/failer 与 main/command 未给 config（623/647），补匹配 null 的静态合同，保留真实运行/命令失败测试。 |
| `fibra-spring-boot-starter/.../FibraAutoConfigurationTest.java:23–35` | 仅空 target 装配 Java/Node/Registry，无 active fixture 缺口；跑入口回归即可，不为本门臆造既有失败或添加无意义 schema。 |

低层 `DeploymentPlannerTest`、`DeploymentTargetCompilerTest` 只测计划/元数据，不应被强加 artifact 读取；随 Task3 builder/digest 迁移即可。正式 Java/Node/client 消费者与发行作者包属于后续任务，本报告没有读取 Tela。

## 6. 建议交给 Task4 实现者的第一批 RED

1. 新 target unknown definition / missing contract / invalid effective value：create=prepare=save=0，PLANNING、Operation、NOT_SAVED、无 attemptId；query 不执行插件。
2. R1宽松/R2严格拒绝且 R2仍留包历史；同 schema 多 entry 的 resolved 值逐项验证、批量预算；local/祖先/when 不读合同，package gate=false 仍可 raw 求值失败。
3. 共享父 owner 的 synchronous/asynchronous read/close、取消结果观察、逆序兄弟清理、failed owner/dependencies 保留、shutdown 先观察缓存失败。
4. 五类发起路径字段矩阵，特别 BOOTSTRAP cleanup 的 PRESENT+BLOCKED 和旧成功 query operation 不变；扫描安全 cause/suppressed/diagnostic/log canary。
5. no-op 不新建 candidate；健康/PENDING reconcile 不查新文件；需要重建的路径读所有 active 合同、只 prepare affected closure；回归 fixture 证明仍触达原失败现场。

## 7. 补充：Engine 资源失败注入与双安全 code 的最小形状（待主线程决定）

本节于同日补核；期间 Task3 正在改测试，旧节行号可能已移动。没有改仓库或运行测试。

**现有可用接缝核对**：`E:142` 构造器 private，`Builder.build:1796` 直接 new；没有现成 resource factory 或包级构造注入。Engine 现有测试通常使用 RuntimeProvider probe 的可控 queryFactory（当前 `T:2814,2862–2865`）、自有 DeploymentTargetStore fake；`T:2608–2620` 为命令线程关闭故障使用 Reactor 官方 decorator。`T:333–340` 反射只检查自有 owner 的可达性，并非适合资源失败注入的惯例。artifact 的 `PluginPackageRecord:65–69` 固定创建 `FixedPluginConfigurationResource`，其 `ResourceIo` 注入构造器位于 artifact 包内（`FixedPluginConfigurationResource:38–42`），Engine 不应扩大该权限或反射 JDK stream。

**建议采用的最小方案**：是，可以沿计划中的包内配置协作者传入 `Function<PluginPackageRecord, PluginConfigurationResource>`，再仅增加一个 FibraEngine 包级构造接缝。形状示意（非新增公共 API 冻结）：

```java
private FibraEngine(Builder builder) {
    this(builder, PluginPackageRecord::configurationResource);
}
FibraEngine(Builder builder,
    Function<PluginPackageRecord, PluginConfigurationResource> resourceFactory) {
    // 原初始化唯一实现；把非 null factory 传给计划中的包内配置协作者。
}
```

- 公共 `builder(...).build()` 原签名/行为不变；包内 EngineConfigurationContractTest 用公开 builder 配置普通依赖，再 `new FibraEngine(builder, factory)`，不加 public Builder 测试开关、全局 static hook、runtime SPI 或第二 factory Registry。包级重载是这里最少的线；不必另加 buildForTest 与第三个构造层。
- factory 只改变**惰性句柄的创建来源**。调用位置只能在选定本次 exact record、静态名称存在确认之后，父读取 attempt 立即登记 `factory.apply(record)` 的非 null 返回值，再 read；公开配置 query 和内部 preflight 都从同一协作者进入。factory 不 read/close、不缓存 owner；父 owner 仍是 Engine 唯一索引，底层句柄仍自行实现 close 终态，故未复制 owner。
- 注入 null factory 必须在任何有副作用的 Engine 资源获取前拒绝；factory.apply 抛错/返回 null 由原读失败净化边界收敛，之前已登记兄弟仍清理。生产唯一默认值仍是 `PluginPackageRecord::configurationResource`，不允许根据测试标志走另一条成功部署路径。
- 此风格已有邻模块先例：`fibra-runtime-java/.../JavaRuntimeDriver.java:69–89` 的默认构造逐层委托包级 overload，测试注入 LoaderCloser/descriptor ResourceAccess；artifact 自身也是相同的默认实现委托 ResourceIo（28–42）。这比扩 public builder 或跨包 artifact test helper 更小。
- 测试用真实 store.find records、真实 Engine lane/Apply/bootstrap/reconcile/query/shutdown，factory 校验收到的 plugin/revision，按指定第 N 次创建返回可控 handle 或默认委托。可控 handle 负责 latch、read/close canary、计数和固定终态；新 Apply、同 target 重建、bootstrap、runtime requestRecompile、query 的失败投影都走同一实际 Engine 链。
- **证据边界**：这证明真实 Engine 的资源 SPI 调用与 owner/投影，不单凭 fake handle 声称操作系统 stream 物理关闭失败已在跨模块集成重现。物理 read/close 故障与原 stream 保留由 artifact 包内 `FixedPluginConfigurationResourceTest:224–227` 的真实实现+ResourceIo 注入证明；Engine 另留不注入 factory 的真实资源成功、缺失/身份破坏测试，防止默认生产委托断线。无需为每条 Engine 发起路径重复物理 I/O 故障装置。

**ConfigContractException 意见，不擅自新增 getter**：当前批准设计（本次行号 spec:231,263,275）要求 cleanup 优先、保留两安全 code、无原 cause，并未要求 secondary code 供程序分支读取。`DefinitionCatalogException.java:17–24` 现形状就是 `Code + String message + code()`。因此最小可行方案是沿此形状：`code()==CLEANUP_FAILED`，只在固定安全 message 中保留 `CLEANUP_FAILED` 和先前的 `READ_FAILED`/`MISSING_CONTRACT`/`INVALID_CONTRACT` 等 enum name，例如固定模板 `configuration contract failed: CLEANUP_FAILED; prior=READ_FAILED`；无双失败则不生成 prior 段。模板只能消费内部 enum，不能消费 foreign.getMessage/toString/className、schema/value/path，cause 为 null，suppressed 为空。

这满足“诊断保留两个安全 code”的文字合同，且不要求外部解析该文本；测试可校验内部生成消息中的固定枚举组合与安全性。父 attempt 可私有持有 firstReadCode 用于净化后投影，不需新增公开 getter/record。若主线程将“保留两 code”解释为两者均须供调用方结构化读取，则 `code()+message` 不足，须另行明确公共形状；当前没有这样的明确要求，不建议自行增加 `readCode()`/`secondaryCode()`。此处仅给可行解释与推荐，最终取舍由主线程记录到权威设计。
