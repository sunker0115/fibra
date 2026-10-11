# 已选择包的 definition 目录与 Java 取得协议

2026-10-11。状态：**本期声明级目录、无兼容迁移、Java 取得修复及隔离 Tela 消费验证已完成，完整构建、发行与最终独审通过**。本稿据[最终架构评估](2026-10-11-plugin-model-target-evaluation.md)收敛推荐。原执行 Java metadata 的目录路线被本稿替代，不同时交付两种目录来源。既有 vNext / Client Foundation 仍定义当前实现。阶段状态见[计划](../plans/2026-10-11-definition-catalog/task_plan.md)，实际交付与未验证边界见[交付记录](../plans/2026-10-11-definition-catalog/delivery.md)，取证与参照见[证据](../references/2026-10-11-definition-catalog-evidence.md)。不恢复 Tela P3，不宣告全栈装配已实现。

## 1. 目标、owner 与边界

用户明确补充：Fibra不专属于Tela，不能为了Tela需求修改Fibra；只有证据确认Fibra缺少通用能力或存在通用缺陷才允许上游变更。Tela作为真实消费者验证缺口，不定义内核语义。本期两项上游资格分别是任意已选包/任意runtime缺少统一definition发现，以及所有Java插件共用取得路径的资源owner缺陷。实现及审查不得包含Tela、Workspace、模型、React或产品默认组合特判；这些需求留在产品层。

真实消费者是：一个此前不知道 definition 名称的本地包安装并选入当前 target 后，用户列出该包全部 facet 的 definition，选择其一，使用原完整 `ApplyDeployment` 创建实例。disabled 包也能查询，提交创建继续使用原 target CAS。目录成功不证明配置、服务、realm、capability、Node 进程或 client 模块可运行。

| Owner | 本次职责 | 边界 |
|---|---|---|
| artifact / PackageStore | 校验与提供精确不可变包、facet、digest | 不解释私有 definition，不认识发行来源政策 |
| Engine / deployment | 捕获同 target 的精确包 selection、统一查询入口、聚合与资源关闭协调 | 不解析依赖闭包，不持有 Class/factory/binder，不解析 runtime descriptor |
| 各 runtime | 解释动态制品的名称声明；持有并释放读取所需资源；装载时核对实际入口 | 不保存第二 target；client driver 的代码仓库不改变其 SPI owner 身份 |
| 插件 | definition 与 config 契约 | 不获得目录专用 Context 或启动能力 |
| Tela | 默认组合、升级/卸载政策、Workspace、模型设置、UI、Session/Work/Approval/业务持久化 | 不另建 ClassLoader/resolver，不解析 runtime 私有元数据 |

没有首方/第三方 Fibra 插件类型。动态内容与 provider built-in 是装载来源。不给 Registry 增加无独立职责的转发层，不增加 schema 表单、远端市场、版本范围求解、查询缓存或持久查询 ledger。只查询 selected 包，不查询未选历史包或 staged revision。

DSH 的 `name → import → unwrapExports → registry.plugin` 以模块 specifier 直接定位插件入口。Fibra 扩为 `pluginId/facetId/definitionId` 后，只交付了已知 definition 的创建与生命周期，遗漏未知包的公开发现。这个缺口属于 Fibra 适配与端到端验收遗漏，不能用 Java 或多 runtime 更复杂作为不闭合的理由；目录应补在既有 owner，而不重做管理框架。

对当前产品的单实例创建路径候选：安装并选入 target→公开目录→选取明确 definition→配置→原 CAS 创建→观察实际状态。UI 可对唯一可用项省略选择，对多项展示选择；这是 Tela 的交互建议，尚不修改其合同。已知默认组合继续直接使用完整引用，与未知包最终进入同一 target 提交流程。该单实例路径不能代替最终全栈插件的组合装配验收；组合意图见最终架构评估 §4.2。Fibra 本目录候选不增加默认 definition 推断、自动创建或第二写入口，也不要求用户手填三个 ID。

### 1.1 模型能力与当前必要性

本稿保留最终架构所需的模型，不以目录需求重新论证所有模型维度必不可少，也不增加它们的使用要求：

| 维度 | 既有职责与证据 | 本次边界 |
|---|---|---|
| 多 runtime | Java、Node、client 的 payload 解释及资源生命周期不同；一个 facet 指定一个 runtime，executionTarget 单独表达执行位置 | Engine 保留通用入口，原 runtime 负责名称读取与资源；不把某个产品的 descriptor 解析搬入 Engine |
| 多 facet | 同一 package 可包含多个组成部分，共用 package revision/selection；现有 artifact 测试覆盖三 facet 包 | 保留既有包形状；不要求后端/client 合包，不承诺跨 facet 自动运行事务 |
| facet 内多 definition | built-in 私有集合和 client module 合同允许多种可创建类型；动态 Java 是零个或一个，Node P0 是一个 | 按实际集合返回，不要求所有 runtime 扩成多定义；同一 definition 的多个实例不构成多 definition 的必要性证据 |

有界发行扫描：当前 Fibra distribution 的 12 个 manifest、Tela distribution 的 5 个 manifest 均是单 facet；后者含 Java 与 client 两种 runtime。多 facet 与多 definition 已有契约/测试消费者，但本次未找到这些发行 manifest 对多 facet 的产品依赖。此结果不能外推为所有外部消费者不存在，也不能据此删除已公开身份。Tela 最终 overview §6/§12.1 明确要求全栈逻辑插件及三类 facet 的统一管理，不能用当前发行形状代替最终架构判断；完整推导见最终架构评估。详细范围和测试源码见证据文档。

因此当前候选只补齐已有完整引用的公开发现。目录含零项时没有可创建入口；单项可由产品省略选择；多项才需要选取。该交互建议不改变 Fibra 的显式引用，不增加默认入口协议。若用户决定合并或删除 facet/definition 身份，需另行核对持久 target、公开 API、制品、provider 和消费者迁移，不能在本次目录实现中暗改。

## 2. 来源选择与阶段保证

推荐目录回答“这个精确包声明提供哪些类型”。目录不执行目标插件代码，不取得 ClassLoader、静态依赖 lease，不绑定配置、不启动 Node、不 attach/import client。仍需要对应 runtime provider 解释动态包的私有格式；缺 provider 返回 unsupported，而不是由产品猜名称。

| 路线 | 适用前提 | 选择 |
|---|---|---|
| runtime 读取不可变制品声明 | 管理时展示入口，插件可能尚不可运行；provider 已安装 | 采用。Node/client 复用已有 descriptor，Java 增加名称声明；内建包读取既有公开 metadata |
| 执行原 Java metadata 列名 | 接受管理查询依赖齐备、执行类初始化/构造/definition()，且等待插件返回 | 保留为比较依据，不交付此目录路径；不因旧制品少改就牺牲阶段分离 |
| PackageStore 直接读取统一身份段 | 缺 runtime provider 仍必须发现入口 | 本期没有该消费者，不新增统一索引/公共 manifest 字段 |
| 产品自解析、猜名称、枚举实例 | 无法覆盖未知包或破坏 owner | 拒绝 |

声明是该精确制品对外承诺的身份，实际实现必须满足所用声明，不能在装载后回填或覆盖目录。静态声明不是运行成功的第二状态；query 每次沿精确 digest 读取，不引入注册表或缓存。构建期可以校验声明，但不要求为打包自动执行 Java 插件 metadata。

不能把所有 runtime 的执行校验都称作 prepare 保证：

| 来源 | 查询阶段 | 准备阶段 | 实际运行阶段及未闭合窗口 |
|---|---|---|---|
| 动态 Java | 私有 YAML 名称，不加载类；contract-only 返回空 | 所有可执行制品必须声明名称，metadata 的真实单个 definition.name() 必须相等，失败不 bind/发布 wiring | factory/create 及业务启动仍可失败；缺名称是非法制品，无旧格式部署分支 |
| Java built-in | Engine 捕获的 BuiltInFacet.definitionIds，不执行 provider 私有工厂 | 该包的 definition 被 candidate prepare 使用时，原 JavaBuiltInPackage 精确校验 metadata 与实际私有集合；仅 selection、disabled 或零 entry 不保证已校验 | 所选 definition 的绑定与启动按原合同，目录成功不免除实际集合校验 |
| Node | 原 descriptor.definitionId | descriptor/入口文件及所选 ID 校验，不启动进程验证 JS | 原进程协议启动时验证协议与运行能力；当前单入口协议不枚举真实工厂集合 |
| Tela client | 原 descriptor.definitions | Host 核描述符、资源 digest 和所选 ID；不 import ESM | 实际客户端加载时验证所选导出存在，再 create。当前 find 不拒绝重复、不核全量集合；本期不声称全量一致已保证 |

“静态目录 + 装载校验”应解释为声明保证和执行保证分阶段，不是承诺所有 runtime 在保存 target 前完成同一验证。Node/client 本期沿原运行合同，不新增全量工厂枚举协议或 client wire 字段；若要宣称 client 所选导出唯一，须在隔离消费者验证中补齐相应检查并获跨仓修改授权。未声明的导出不能凭执行结果加入公共目录。最终架构需要更强全量一致性时，另定 runtime 协议和实际校验位置，不能把现有 find 冒充集合校验。

## 3. 公共入口与结果候选

下列是已批准的交付合同，实施状态见推进计划，不能据此断言全部API已实现。入口留engine.FibraEngine；只读值放engine.deployment。

```java
Mono<PackageDefinitionCatalog> describeDefinitions(
    PluginId pluginId, long expectedTargetRevision);

record PackageDefinitionCatalog(long targetRevision, PluginId pluginId,
    String packageRevision, List<FacetDefinitionNames> facets) {}

record FacetDefinitionNames(FacetId facetId, RuntimeId runtimeId,
    ExecutionTarget executionTarget, List<String> definitionIds) {}
```

结果深度不可变；facet 按 facetId、definition 按原始名称字典序排序。名称在所属 facet 内唯一、非空白，允许跨 facet 同名。空 contract-only facet 不省略。所有 facet 完整成功才返回，不提供部分成功。没有 readiness、配置 schema、payload 路径、Class、工厂或句柄。targetRevision 和 packageRevision 使消费者继续通过原完整 ApplyDeployment/CAS 创建。

expectedTargetRevision >= 0；0 沿原 Apply 语义表示没有 durable target。revision 不符先 STALE_TARGET；匹配但 root 未选（包括无 target）报 NOT_SELECTED。无订阅不入队；重复订阅独立查询。

拟新增 DefinitionCatalogException（engine.deployment），public 构造器(Code,String)、code()；Code 为 ENGINE_UNAVAILABLE, STALE_TARGET, NOT_SELECTED, PACKAGE_UNAVAILABLE, UNSUPPORTED_RUNTIME, READ_FAILED, CLEANUP_FAILED。宿主异常仅带安全诊断，不挂插件 Throwable 图。不包装 EngineChangeException，不虚构 target 已保存。

顺序：Engine 准入→revision→root selection→精确包→root runtime provider 支持→声明读取/结构校验→cleanup→结果。缺 root 精确版本/身份冲突/损坏包为 PACKAGE_UNAVAILABLE；未知 driver 为 UNSUPPORTED_RUNTIME；缺名称等畸形声明/null/重复/缺结果/读取失败为 READ_FAILED；cleanup 失败优先 CLEANUP_FAILED。所有 runtime 读取错误按 READ_FAILED 清洗，不提供旧格式专用错误或回退信号。

## 4. 精确包读取，部署解析保持唯一 owner

Engine 在原 command lane 捕获完整 target，以 root selection 的(pluginId,packageRevision)调用同一个 PluginPackageStore.find，使用 record.managedPackage。内建包沿原 captureBuiltIns 在本次命令内捕获 immutable metadata，核重复、动态/内建同 pluginId 冲突及选中 digest 相等；不新增长期 metadata 缓存，也不把先前命令的捕获结果当本次事实。

仅 root 包的全部 facets 参与；root disabled 可读；其依赖缺选、缺包、disabled、cycle 或不可装载不阻塞声明目录。无关 selection 不读。这些故障继续由唯一 DeploymentTargetCompiler 在 Apply/prepare 处理，目录成功不消除部署错误。

本路线不增加 resolveDefinitions compiler 入口，不构造 Compilation、假 enabled target、依赖图或 RuntimeTargetSlice。既有 compiler 的全部 selection 身份核验、enabled gate、精确依赖及 cycle 合同不变。此前 metadata 路线的公共 compiler 方法和依赖 query input 已撤回。

root 每个 runtime 都须有 driver。对 built-in facet，Engine 直接复制已有公开 definitionIds；不为该声明取得 provider 私有定义，也不调用动态目录 SPI。动态 SPI 是所有 RuntimeDriver 的必需合同；纯 built-in 目录不调用该方法，不取其私有实现。内建实际集合一致性仍由原 prepare owner 核验，不把 query 回显等同实际实现验证。

## 5. 动态 Runtime SPI 与 Java 私有声明

```java
// RuntimeDriver 新增必需方法，无兼容 default。
RuntimeDefinitionQuery createDefinitionQuery(ManagedFacet facet);

interface RuntimeDefinitionQuery {
    Mono<List<String>> readAsync();
    Mono<Void> closeAsync();
}
```

每个动态 root facet 一个 inert 句柄，输入只复用原 ManagedFacet；不新增 batch input、requestedArtifacts、dependencies 或 builtIns 副本。Engine 按传入 facet 聚合结果，runtime 不返回身份或旁支。create 仅 bookkeeping、不 I/O/取得资源；Engine 先登记再 read。create/read 的任何异常均 READ_FAILED；只有 Engine 缺 driver 才是 UNSUPPORTED_RUNTIME。null handle/publisher、Mono.empty、null/空白/重复名称均拒绝；合法空 List 是零入口。

read 不执行目标插件 metadata/构造器/工厂；允许验证私有格式及该 reader 本来需要的 payload/digest，不把 provider 自身的可信解释器等同目标插件代码。Java/Node/client 原 reader 是唯一解析实现；需调整资源入口以让句柄在打开 JarFile/stream 后立即登记。read 的 try-with-resources 不能吞 cleanup 失败或在失败后失去 owner。无需持有外部资源的读取完成后 close 可以成功空操作；取得失败也由已登记句柄统一关闭。

read/close 各推进一次并缓存终态；close 先于 read 可结束 inert 句柄并拒绝后续 read；已开始 read 则等真实终态，不取消。成功读取可在 close 前持有静态文件资源，关闭顺序为内层 stream→外层 JarFile；内层关闭失败保留所需外层，独立句柄继续清理。资源清理失败 retained handle 必须可达，driver 也索引同一活 owner以拒绝误报 close成功；不复制关闭权。无 ClassLoader 或 deployment lease；成功终态解除资源及原异常引用，失败仅保留未关闭现场。§7 的 command/cleanup 屏障沿用，不因此建立第二状态机。

首次read的准入在cache内部、实际worker调度前与close共用同步边界：已准入但尚未执行I/O也须等待真实终态。私有文件读取owner负责资源取得、唯一reader和一次close终态；对外query负责异步观察、read缓存与等待。原同步probe/inspect/prepare直接执行同类owner的读取与关闭，不反向阻塞等待query，不使用调度器特判或绕过NonBlocking检查。每个owner只经同步内部路径或外部query之一读取，不新增第二读取状态机。

prepare的取得节点在读取前引用同一文件owner，成功close后解除引用，失败则由candidate.close回放该cleanup失败并保留现场。driver索引同一owner以拒绝假关闭，不主动清理或复制关闭权。这样Engine沿原candidate清理门感知失败，不能把prepare里的文件关闭失败当成已清理的普通拒绝。关闭缓存由资源owner保证，query只缓存对同一终态的异步观察；部署构造器/metadata致命错误仍沿原调用栈传播，不通过把整个prepare搬到worker改变语义。

### 5.1 Java 声明与直接迁移

当前动态 Java 零到一个 definition，名称字段用单个 definitionId，不为了通用模型容量引入尚未有绑定协议的多入口数组：

```yaml
entrypoint: example.WidgetEntrypoint
definitionId: widget
```

META-INF/fibra/plugin.yaml 的原大小/重复键/未知字段/JAR Class-Path 校验保留。entrypoint 与 definitionId 均非空白；entrypoint 不存在且 definitionId 不存在为 contract-only，目录空；只有 definitionId 无 entrypoint为非法。entrypoint 存在而 definitionId 缺失为非法制品：凡实际调用Java reader（显式probe/inspect、目录、受影响entry的实际prepare）均按所在阶段拒绝，不执行 metadata 兜底、不保留旧部署分支。definitionId 非字符串或空白一律非法。PackageStore与仅安装/selection成功不承诺已检查Java私有格式；本期不增加Registry无条件probe流程。

所有可执行制品的 acquire 在真实 metadata 返回后、成功 wiring 发布和用户 bind 前要求 definition.name()与声明完全相等；不按包名/facet名自动修正。取得资源仍由§6同一 candidate owner承担。名称与入口必须同时存在或同时不存在；contract-only 没有实际 definition。

JavaFacetDescriptor 公共 record 增加 Optional<String> definitionId，只保留双参数规范构造器；删除单参数形状，不增加过载、deprecated shim 或适配器。entrypoint()仍表达同一字段，新完整值语义包含名称，所有调用者统一迁移。该签名变化必须纳入基线及外部消费者门，不能声称“只改 YAML、无公开 API 变化”。

用户明确“不需要有兼容代码”：只交付新的 runtime、SPI、descriptor 和作者合同，不承诺新旧混用。所有正式 executable Java 包、测试夹具与生成模板补声明并产生新 digest，contract-only 继续合法为空。外部 provider 重编实现必需方法，外部 executable Java 包重新打包，目标的精确包选择经原 CAS 更新；不自动改写旧 target，不覆盖原不可变包或同坐标 SNAPSHOT。

## 6. Java 私有取得协议（D38）

当前 createWiring 先创建 loader，再 validate/entry，成功后才放 wiringPool；失败 cleanup 仅 suppressed。实测反例详见证据。修复落在原 JavaRuntimeDriver 私有协议，原 candidate 路径共用，不另造 loader、全局 registry 或 resolver。

引入一个**私有取得 owner**，作为 Candidate 的字段，在任何 acquire 前创建。每次 root acquire 建一个节点先挂到 owner；递归子节点先挂到父节点，再开始实际取得。该树表达取得过程中真实依赖，不保存第二 desired 图。正常 wiringPool 继续只放已完成 metadata 的 wiring，禁止半成品复用。

| 步骤 | 可失败操作之前必须存在的归属 |
|---|---|
| 递归取得依赖 | 子节点已登记在父节点；每个成功 lease 即写入其节点，再取得下一依赖 |
| descriptor/class index 校验 | 已取得依赖仍在节点，失败时不可遗失局部 List |
| 创建 loader | 参数/URL/parentPackages 等先校验；new 返回后立即存入节点，之后才 dependencies/validate/entry |
| metadata 成功 | 建完整 wiring/lease；先让节点指向完整 lease，再发布可复用 wiring；资源只转交，不额外加一次 refcount |
| 返回 acquire 后 bind/plan | 节点仍归 candidate owner；先把含 lease 的 PreparedUnit 登记到 candidate 字段，再标记节点转交。不得以局部 values 成功后整表赋值留下窗口 |
| generation seal | 按原 candidate→generation 转交；不能在成功前清空原 owner |

可复用 wiring 的每次 acquire 仍增加一次 references，并立即以节点持有相应 lease；菱形依赖每条取得边具有独立 lease，沿原 wiring key 共享，禁止同一个 lease 被两个 owner 重复释放。节点登记/转交是同一受保护调用栈的 bookkeeping；不引入异步 yield 或外部回调。VM 无法分配内存等灾难不能承诺普遍恢复，但不得用此豁免普通 RuntimeException、AssertionError、LinkageError 或 cleanup 失败。

取得前置参数验证移到 new PluginClassLoader 之前；构造器内 `super` 后不再做会因常规非法输入抛错的工作。ClassSpace 只 validate/entry，不取得 loader，不创造虚构的 open owner。metadata 的 RuntimeException/非致命 Error 统一在私有边界记录并转换成可由 Reactor 正常交付的 JavaRuntimeException；VirtualMachineError/ThreadDeath 只保留已登记 owner 并向外抛出，不承诺受控恢复。

失败清理规则：

1. 先关闭本节点 loader/自身 wiring lease；失败则保留节点、本 loader、全部依赖，不释放其先决资源。若 metadata 还没完成，仍从节点访问原 loader，不需要从 wiringPool 寻找。
2. 本节点自身已释放/根本未创建 loader，才逆序清理独立子节点；某子节点失败保留它及其依赖，继续其他独立兄弟。成功释放的引用移除，失败引用留下。
3. acquisition owner 的 close 缓存本次完整终态。读取首错与 cleanup 附错分开存，不能让 read 的异常吞掉 close 的失败，也不重复尝试未知状态的 closer。
4. 沿现有 StaticLease 的精确 refcount/依赖部分完成记录工作；保留现有私有显式重试测试语义，但 candidate 的缓存 close **不自动重试**，不靠第二次碰巧成功消除首次失败。
5. candidate.close 包括尚未转交的取得节点和已登记 PreparedUnit。cleanup 失败不能返回成功或清空字段。driver.close 不可绕过仍有 owner 的 wiring；Engine 持有失败 candidate 到宿主受控退出。

driver 私有地跟踪同一 acquisition owner 的存活引用，第一次取得前登记，全部资源释放或成功转交正式受跟踪 lease 后移除；失败保留。它只索引现有 owner，不复制节点、refcount 或关闭权。driver.close 先拒绝仍在取得或持有失败未交付节点的 owner，再检查原 wiringPool；这样即使 provider 消费者直接调用 driver.close，也不能因 pool 为空误报成功。candidate 的已转交 PreparedUnit 仍由原 pool 引用计数约束。静态目录的文件句柄沿§5单独归属，不进入本协议。

这一有限调整覆盖相同 acquire 使用路径的 candidate bind/plan 失败，静态目录不会经过此取得协议，不能据此省略原部署故障修复。它不修改执行 unit 生命周期、服务解析、Java 委派策略或业务配置合同。

## 7. Engine 排队、结果与失败屏障

公开方法使用 `Mono.defer(() -> loop.submit(...))`，所有 accepted query 与 target writer 在原 command lane 顺序执行。准入以 submit/quiesce 的锁为线性化点；运行时再检查 state/mutationGate，但不因稍后 close 设置 closing 而丢弃已接纳的 query。NEW/FAIL_STOP/CLOSED 拒绝，RUNNING 即使当前 target BLOCKED 也允许。排队期间前一操作进入 FAIL_STOP 时拒绝尚未取得资源的后续 query。

Engine 用单个瞬态 `CatalogAttempt` 字段持有本次句柄（不是公开新状态机）。先创建并登记全部 inert query，再逐个 read、复制并检查结果，最后逆序 close。同步 create/read/close 抛错、null publisher 和异步 error 都走同一清理；已经登记的句柄不因后续创建失败丢失。必须 cleanup 真正成功后才发目录成功，不能用 doFinally 在成功通知后清理。

结果订阅取消只取消观察，不取消已接受命令/读取/清理。无全局查询 ID、TTL、worker 或缓存。观察回调可以刷新已有运行事实，但不能在查询中重读较新 target 拼接结果。正常查询成功或读失败均不 publish 假 deployment operation、不 save、不修改 current/desired/targetRevision；可见运行事实自身变化不被禁止。

不得直接复用会附加 lifecycleTimeout 的 `runtimeVoid/runtimeObservation` 包装来执行 query read/close；它们的 timeout 会取消源并造成假终态。查询只复用 lane 调度、异常归一化与原独立请求持有，不引入超时取消。若未来需要查询截止，必须先定义底层实际完成与资源 owner 协议，不能仅加 timeout。

read 失败而 cleanup 成功：释放 CatalogAttempt，按§3返回 READ_FAILED，保持原 mutation gate。cleanup 失败：保留 CatalogAttempt 与失败 query，关闭管理/贡献准入、Engine FAIL_STOP，并用 `FailureSubject.Engine + FailureStage.RELEASING + CATALOG_CLEANUP_FAILED` 请求既有 HostTerminationPort。不得 retroactively 把此前已完成的 Apply operation 改成 FAILED；抽取共用 fail-stop 的关门/通知部分时保留原部署失败语义，目录 failure 的 operationStage 为空、targetRevision 为捕获值。

shutdown 在 candidate/current/driver/store 资源下放前先观察 CatalogAttempt 的 close 终态。失败则保留整个依赖现场，返回同一失败；不进入一般 cleanupAll 去关闭仍被 query 依赖的 driver/store。单次失败即关门，不继续累积失败 attempt。Engine 原 close 可继续关闭通知/command 基础设施，但不能因此宣布资源已释放。应用总截止或进程终止仍归宿主；阻塞文件 I/O 或不合作 provider 仍可能阻塞 lane/close，不执行目标插件代码不等于承诺有界 I/O，本期不伪造有界关闭。

## 8. 直接迁移、交付与批准边界

批准范围为§3公开查询/值/错误合同、§5 RuntimeDriver必需方法+单facet句柄、JavaFacetDescriptor双字段规范构造、Java制品字段、§6私有取得修复；compiler/target/Node descriptor/client wire/依赖版本保持现状。用户已否决兼容代码：不新增旧provider默认分支、旧descriptor构造重载、旧制品部署/目录回退或格式双读。缺必需名称按阶段报制品读取错误，空集合仅用于合法无入口。

Java/Node provider、正式外部 provider fixture与动态制品声明同时交付；所有RuntimeDriver实现同步更新必需SPI，纯built-in查询仍直接读取metadata而不调用动态方法。同版provider/Java制品/公开签名消费者统一验证；旧格式负例验证拒绝和资源关闭，不再验证旧二进制可运行。签名/发行包/作者文档明确“不执行目标代码的声明查询”和相应错误。

用户已明确批准本期公共API/SPI、Java制品、D38及隔离Tela消费验证；不提交/push，不恢复Tela P3。Tela隔离消费影响包括client SPI、agent/model-api/model-deterministic/model-openai四份实际可执行Java描述符及其合成fixture、必要发行引用核对；不能仅适配client就宣称管理链可用。不改当前脏工作树。client全量集合协议与full-stack装配字段不列入本期目录实现。

隔离Tela适配沿同一必需SPI及原client reader，不给Fibra新增产品回调。原client prepare、激活前verify和资源服务read的实际walk/文件流都使用同类每次资源owner；candidate或Unit持有失败owner，driver仅索引同一对象并拒绝假关闭。保留原drain期间资源读取，stop ACK后关闭文件操作准入、观察已开始操作真实终态，成功才标记stopped；失败沿原stop/retire/driver关闭门可见。同步HTTP byte[]读取错误不承诺立即Engine fail-stop，不能为目录消费验证引入第二产品状态机或改client wire。此私有归属调整的真实并发/失败行为须在隔离消费者测试验证。

实现使用隔离 Maven repository，固定producer源码/制品SHA，不以相同SNAPSHOT覆盖全局缓存。公开制品消费者门含真实未知包管理链；多facet的自动组合仍需明确管理意图，目录不会自动创建全部definition。

## 9. 验收及阶段门

本期已获实施授权。下列门按实际实现及测试证据逐项关闭，当前状态见推进计划；不沿用旧部署/发行测试冒充新能力通过。

| 门 | 必须证明 |
|---|---|
| DC-01 未知包 | plugin/facet/name各不相同，install→selected→catalog→原CAS创建；不预先手写definitionId |
| DC-02 查询阶段 | disabled包可读；Java静态初始化/构造/definition调用计数全零；Node零进程，client零attach/import/create；target字节/revision/save不变 |
| DC-03 精确包 | r1/r2、stale、无target、缺root包、冲突/损坏；缺依赖/disabled依赖/cycle/无关坏包不阻塞声明；Apply仍拒绝相应部署错误 |
| DC-04 输出 | 多runtime/多facet、contract-only空；排序/不可变/跨facet同名；非法返回/未知driver/缺必需声明与合法空集合区分 |
| DC-05 声明验证 | Java名称相等成功，不等在wiring发布/bind前拒绝并清理；built-in实际集合不等在prepare拒绝；Node/client按阶段验证，不承诺未验证全量导出 |
| DC-06 取得 | 原metadata/构造/验证/bind/plan失败；未取得、部分依赖/菱形；RuntimeException/AssertionError/LinkageError；lease/refcount可追溯 |
| DC-07 关闭 | metadata+closer双失败；父失败依赖零释放；独立兄弟仍清理；文件stream/JarFile双失败；owner可达、缓存close不重试且不误报成功 |
| DC-08 并发 | 无/重复订阅、取消观察；read/close闸门；query/close准入顺序；挂起I/O不假完成 |
| DC-09 原路径 | Apply/reconcile/self-disable/current retirement回归；查询失败不改历史；cleanup fail-stop/termination一次；shutdown不关闭仍依赖的driver/store |
| DC-10 公开消费 | 同版provider/新Java制品/旧格式拒绝负例、签名/架构/reactor/适用发行门；隔离Tela client SPI和未知包管理链；producer/consumer hashes |

具体声明候选须独审§2阶段保证、§4无需依赖解析、§5文件资源与直接迁移、§6D38和§7失败屏障。独审通过后才提交可审阅的API/SPI/制品授权影响面，实施先RED，最终代码和生命周期独审及公开制品消费完成前总目标保持active。
