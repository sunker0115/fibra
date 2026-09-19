# Fibra 公共 API 与嵌入入口

本文描述 `0.5.0-SNAPSHOT` 当前公开边界。公共签名以各模块的 `*-public-signatures.txt` 基线和编译后的 API
为准；本文解释对象的职责、所有权和组合顺序。

## 生命周期内核

`FibraRuntime` 是不含 package、持久部署和 runtime driver 的最小内核。`RuntimeDomain` 持有唯一 lifecycle
lane；`Scope`、插件实例、服务、事件、effect 和贡献调用临时资源都进入同一所有权树。关闭父资源会先停止
准入、排空已接受调用，再按逆序释放子资源。

插件通过 `PluginDefinition` 声明配置类型、入口、服务依赖与提供关系。挂载得到的 `PluginInstance` 可观察
准备、激活、失败和关闭状态。注册到插件 `Context` 的服务、事件和 effect 自动归当前实例所有，不由调用方
维护另一份 registration 清单。

`InvocationContext` 分开能力语境和资源语境：`caller()` 决定 realm、intercept、logger 和服务解析；
`effects()` 与嵌套服务引用沿实际资源 owner 传播。两者必须处于同一 `RuntimeDomain`。

## Package 与不可变内容

`PluginPackage.read(Path)` 只读取根 `fibra-package.yaml`。格式 1 包含：

- package：`format`、`id`、`version`、非空 `facets`；
- facet：`id`、`role`、`runtime`、`target`、`payload`、`dependencies`、`capabilities`；
- dependency：精确的 `pluginId` 与 `facetId`。

解析会拒绝未知或重复字段、越界 payload、符号链接、不稳定快照和重复 facet。`packageDigest` 覆盖受控文件树；
每个 facet 另有 payload digest。

`PluginPackageStore.prepareInstall(source)` 返回 `PluginPackageInstallTransaction`。事务先复制并复核完整
package，再由 `save()` 发布 `PluginPackageRecord`。`find(pluginId, packageRevision)` 和 `history(pluginId)`
只返回 store 管理的不可变内容。store 持有目录单写者锁，关闭失败可重复观察。

## 完整部署目标

`DeploymentTarget` 是一次部署的完整 durable 事实，包含：

- 单调递增的 `targetRevision`；
- package `PluginSelection` 集合，每项锁定 `pluginId`、`packageRevision` 和 enabled；
- raw `DesiredInputGraph`；
- `ConfigContextSnapshot`；
- 不包含 revision 的规范内容摘要 `targetDigest`。

`DeploymentTargetStore.save(expectedRevision, target)` 执行单写者 CAS，并只在原子替换和目录持久化确认后签发
`DurableTargetToken`。`FileDeploymentTargetStore` 将完整目标保存为一个 `target.json`；保存结果不确定时关闭后续
写入准入，调用方必须重新打开存储并读取事实，不能把不确定解释成未保存。

`ApplyDeployment` 是完整 replacement 命令：调用方必须提交 expected revision、全部 selections、raw desired
graph 和 config context。`ReconcileCurrent` 不修改目标，只重新协调当前 durable target。

## Engine 与 Runtime SPI

Engine 的稳定入口是：

```java
FibraEngine.builder(PluginPackageStore, DeploymentTargetStore)
    .runtimeProvider(provider)
    .hostServices(hostServices)
    .contributionKinds(kinds)
    .build();

engine.startAsync();
engine.submit(command);
engine.published();
engine.snapshot();
engine.closeAsync();
```

`FibraEngine` 独占 command lane、target CAS、编译、运行时协调和事实发布。宿主不能绕过 Engine 直接改变
driver 或 execution unit。

`RuntimeProvider` 是 runtime 的进程级扩展点：

- `id()`：唯一 `RuntimeId`；
- `contractIdentity()`：provider 的进程内契约身份，参与 `compiledFingerprint` 与重编译判断，但不写入
  `DeploymentTarget` 或保存 CAS；
- `builtInPackages()`：无需外部 package 内容的内建 facet；
- `create(RuntimeHostServices)`：创建长期 `RuntimeDriver`。

provider 必须是配置不可变、可顺序复用的 factory。每次 `create` 返回只绑定当前 Host 的全新 driver，provider
不得缓存 Host services、driver 或 Host 生命周期资源；并发 `create` 不属于 P0 保证。Engine 在构造成功后取得
driver、package store 与 target store 的关闭所有权，构造中途失败也会释放已取得资源。

`RuntimeDriver` 探测 `PluginFacetSource`、检查 `ManagedFacet`、从完整 `RuntimeTargetSlice` 创建 candidate，并
公开只读 snapshot。candidate 的协议是 `prepareAsync()`、`preparedPlan()`、`seal(compiledSlice)` 和
`closeAsync()`；只有准备成功的 candidate 才能封存 generation。

`PreparedRuntimeGeneration.units()` 以 `ExecutionUnitKey` 返回本代 execution unit。unit 通过
`reconcileAsync(operationId)`、`closeAdmission()`、`drainAsync(operationId, deadline)`、
`stopAsync(operationId, deadline)` 和 `snapshot()` 参加统一生命周期。Engine 按受影响闭包替换 unit；依赖
facet 改变会重建依赖方，即使依赖方 payload 未变。

`RuntimeHostServices` 为 driver 提供 runtime scope、按 unit 创建的贡献准入、远程贡献调用和单调身份分配，
以及 `releaseScope`、reconcile、recompile、带 `RuntimeUnitFence` 的观察刷新和带 fence/原因的自停用请求。
`releaseScope` 在关闭 driver 拥有的 child Scope 后核验其中没有残余清理失败；driver 只有在它成功后才能释放
ClassLoader、payload、sidecar 或 generation lease。capability snapshot
不是 live service；Engine 在每次编译时捕获并冻结到 `RuntimeTargetSlice`。key 存在即表示 capability 可用，
value 只是不可变描述；每个 active unit 校验其完整传递静态 facet 闭包的要求。`requestReconcile` 接受一个
不可拆分的 `Set<RuntimeUnitFence>` 批次。所有回调回到 Engine lane；缺失、错误、过期或已退役 fence 不会
修改新一代 unit。

`RuntimeProviderRegistry` 是 Engine 内部 composition 细节，不属于公开 API。宿主只通过
`FibraEngine.Builder.runtimeProvider(...)` 注册 providers；driver 的创建与关闭所有权始终归 Engine。

Java 和 Node 只是该 SPI 的两个实现。任何外部 provider 都必须只依赖公开 Maven 制品；
`fibra-distribution/src/test/consumers/runtime-provider-application` 持续验证这一消费方式。

## 编译、观察与发布

`DeploymentTargetCompiler` 将完整目标按 runtime 切分，解析 package/facet 依赖、desired entry、配置、realm、
intercept、publication requirement 和执行目标。`desiredEntryId` 保证同一 facet 的多次实例化不会折叠。

`ExecutionObservation` 是 runtime 对 unit 的唯一观察事实，区分 `PENDING`、`ACTIVE`、`FAILED`，并携带 unit
target revision、runtime instance、operation 和已发布定义/贡献。driver 状态变化必须请求 Engine 重新采样；
Engine 用同一次采样生成 view 与 target-satisfied 判断，不能混用缓存状态。

`PublishedRuntime` 是托管宿主读取事实和调用能力的唯一入口：

```java
PublishedView current();
Flux<PublishedView> views();
Mono<O> invoke(String expectedViewRevision,
               long expectedRegistrationIdentity,
               ContributionKind<D, I, O> kind,
               ContributionId id,
               I input);
```

`views()` 只发布订阅后的变化，不重放历史；需要覆盖当前与后续事实时，先订阅再读取 `current()`。
`ContributionId(providerInstanceId, localName)` 是稳定业务身份；`viewRevision` 和
`registrationIdentity` 是调用准入 fence。旧 view、旧注册或已关闭准入的 route 必须明确失败，不能转向同名
新 handler。一次已准入调用拥有独立临时 Scope，成功、失败和取消都在释放 route 租约前清理该 Scope。

## Registry 与 Bridge

`PluginRegistry` 只把管理用例翻译成完整 Engine command，不建立第二状态机。它提供 package 安装、升级、
启停、卸载，desired entry 的 upsert、enable、disable、move、remove，完整 deploy，当前目标 reconcile，查询、
watch 和审计。

安装或升级先通过同一个 `PluginPackageStore` 保存 package，再提交锁定精确 package revision 的完整 target。
逻辑卸载只移除 selection；不可变历史仍由 store 保留。审计投递失败不会改写 Engine 的真实结果。

`fibra-bridge` 的 `ContributionDirectory` 持有本地与远程能力的统一注册。`ContributionKind` 定义 descriptor、
输入、输出和 codec；`ContributionAdmission` 负责注册身份、调用准入和 drain。Bridge 不负责安装、目标保存或
生命周期决策。

## Java 与 Node provider

Java facet 的 payload 是包含 `META-INF/fibra/plugin.yaml` 的 JAR。descriptor 只声明 Java 本地定义和可选
entrypoint；空对象表示 contract-only JAR。`JavaRuntimeProvider` 为每个 execution unit 建立隔离 class space
和 unit-local service/control realm，按 facet 依赖图委派，按 dependent-first 顺序关闭。Java 实例状态变化会
触发实时观察刷新。

Node facet 的 payload 是包含本地 descriptor 和 `.js`、`.mjs` 或 `.cjs` 入口的目录。
`NodeRuntimeProvider` 以受管进程范围启动 sidecar，处理有界消息、deadline、取消、心跳和异常退出。运行时不
执行包管理器，也不联网补依赖；第三方依赖必须已经进入 payload。

## Client API 与协议

Java 模块 `fibra-client-protocol` 提供协议版本 1 的 value、严格 codec 和共享 fixture。正式 npm 包是：

- `@sstlfsj/fibra-client-api`：`ClientScope`、`ClientInstanceContext`、`HostCaller`、
  `ClientModuleDefinition`、`ClientEntryModule` 与 `ClientModule`；
- `@sstlfsj/fibra-client-protocol`：session/lifecycle/call fence、snapshot assignment、resource descriptor、
  contribution 和协议 envelope codec。

生命周期消息以完整 `SessionFence(hostInstanceId, clientExecutionId)`、unit target revision、runtime instance
和 operation id 防止跨代完成。调用消息另携带 expected view revision 与 registration identity。所有 Java
`long` wire 值使用规范十进制字符串；通用数值使用显式 `NUMBER` 字面量，不能降为 JavaScript `Number`。

每个 assignment 必须显式携带自身的 `desiredEntryId`、`definitionId`、`unitTargetRevision`、
`runtimeInstanceId` 与 resolved `config`；全局 target revision 不能替代 retained unit 的创建 revision。
产品 runner 加载 entry module 后，在 `definitions[]` 中精确选择一个 definition，并为每个 assignment 调用
一次 `create(ClientInstanceContext)`。context 一次绑定上述身份、config、独立 scope 与 Host caller；返回
module 的 `prepare/activate/drain/stop` 均无参数。同一 definition 可为多个 entries 创建独立实例，禁止全局
registry 或跨 generation 实例缓存。

assignment 的资源描述只包含 package 内相对路径、digest 和 byte length，entry module 必须属于该资源集合。
协议不定义资源下载方式或连接实现。浏览器端执行器、Web 资源装载器、页面渲染绑定、网络连接、重连和产品
状态恢复均由产品仓实现，且不属于上述三个正式制品。

## CLI 组合 API

`fibra-cli-api` 公开应用元数据、bootstrap/dynamic command descriptor、`CliInvocation`、输出、退出状态和
受管终端契约，不暴露 Picocli、JLine、Engine、Registry 或物理 terminal 实现。

`CliSession` 借用调用方已有的 `PublishedRuntime`、`CliProfile` 和 streams，拥有自己的执行 lane、REPL、
history、调用协调和终端恢复，但不关闭借入的 Engine 或 streams。每次命令捕获当时的 descriptor、view
revision 和 registration identity；解析对象不跨线程、输入行或 view 复用。

profile 的期望树来自 `<profile>.yaml`，package 列表来自 `<profile>.packages.yaml`。`apply` 将二者编译为一个
完整 replacement；后续启动从 `DeploymentTargetStore` 恢复。

## Spring Boot

`fibra-spring-boot-starter` 默认装配 `PluginPackageStore`、`FileDeploymentTargetStore`、
`JavaRuntimeProvider`、`NodeRuntimeProvider`、`FibraEngine`、`PluginRegistry`、审计仓库和
`PublishedRuntime`。唯一配置项 `fibra.storage-root` 默认为 `.fibra`。

`@FibraService(name, type)` 显式导出宿主 Bean；`FibraServiceExporter` 在 Engine 启动前收集 binding。
Spring 继续拥有 Bean，Engine 拥有运行域和调用排空。自定义 package/target store Bean 必须使用
`destroyMethod = ""`，避免容器与 Engine 重复关闭同一资源。

## 公共签名门禁

各模块的 `*-public-signatures.txt` 由 `javap -protected` 生成并由 `fibra-parity-tests` 冻结。公开类型、方法、
模块依赖或 npm declaration 变化时，必须同步更新权威设计、签名基线、契约测试和仓库外消费者。
