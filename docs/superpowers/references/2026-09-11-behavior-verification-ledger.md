# 行为验收账本

复核日期：2026-09-12。状态：Cordis 原始行为、Fibra 额外回归、正式插件和 pre-CLI 分发证据已记录；
正式宿主 CLI 与 ZIP 分发仍待交付，合并后的最终全仓、可复现及空仓分发门禁仍须统一复验；平台限定
证据见第 5、6 节。

本账本把两类当前验收对象分开记录：

- Cordis 原始行为：`parity` 包中的 12 个行为类共 71 个 `@Test`；每个类的注释指向固定 Cordis 提交
  `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的对应 spec。
- Fibra 额外回归：`migration` 包中的 11 个回归类共 44 个 `@Test`，固定 vNext 的 Java 表达、竞态、
  所有权和错误边界。

71 和 44 是两组独立门禁，新增架构、签名、发布或场景测试不能抵扣。表中每个方法名都是一条独立
记录，分别位于 `parity` 与 `migration` 测试包；标注“新 API 等价表达”的项目保留原行为不变量，
不恢复已废弃的公开入口。

共同执行证据：

```text
mvn -o -pl fibra-parity-tests -am test \
  -Dtest='*ParityTest,ApiSignatureBaselineTest,ArchitectureBaselineTest,VNextScenarioTest,ReadmeExampleTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：120 项通过，0 failure，0 error，0 skipped；其中 Cordis 71 项、Fibra 额外回归 44 项，另有 API
基线 1 项、架构基线 3 项和 vNext 场景 1 项。

2026-09-11 的插件开发前基线曾在 48 模块 reactor 上通过，但当时正式插件只有 POM 边界，该历史结果不作为
完成证据。2026-09-12 在当时的正式插件、跨运行时场景与分发修复全部纳入后重新执行 `mvn -o clean verify`：
48 个模块全部通过，耗时 1 分 19 秒；`fibra-parity-tests` 122 项为 0 failure、0 error、0 skipped，正式插件
组合验收 9 项也全部通过。该结果早于正式 `fibra-tool-storage`、CLI 和 ZIP 分发的最终组合，不能代替其后的
全仓复验。DSH 配置组合、源文件自动刷新、真实 Java/Node 局部更新和多插件应用仍分别使用第 4、5、6 节
证据，不用全仓绿色结果替代。DSH 的逐项采用边界见
[源码基线](2026-09-09-plugin-dependency-baselines.md)。

2026-09-12 在 pre-CLI 快照 `618091a` 上执行 GitHub Actions
[运行 #11、attempt 2](https://github.com/sunker0115/fibra/actions/runs/34675971947/attempts/2)：Ubuntu
runner 的 48 模块 `clean verify`、25 个正式发布制品可复现比较、空临时 Maven 仓部署及仓库外消费者验证
全部通过，作业总耗时 5 分钟，`clean verify` 步骤耗时 2 分 15 秒。同一 SHA 的 attempt 1 在
`PluginDisableTest` 中停止输出并于 15 分 21 秒时取消；随后
[运行 #12](https://github.com/sunker0115/fibra/actions/runs/34677393980) 的 attempt 1 在
`FibraEngineRuntimeAdapterTest` 启动后停止输出并于 7 分 7 秒时取消，开启 GitHub debug logging 的
attempt 2 则确认该类 12 项已通过，随后在 `PluginDisableTest` 启动后停止输出并于 3 分 6 秒时取消。
重复远程现象不能再归为单次 runner 噪音：`PluginDisableTest` 已确认使用瞬时 `PREPARING` 与异步
`STARTING` 快照作屏障，订阅错过该组合后又未释放启动闸门，导致测试超时进入等待命令排空的无界
`engine.close()`；修正后该用例在双核调度下连续 20 次通过，完整 `fibra-engine` 155 项通过。另一次
`FibraEngineRuntimeAdapterTest` 停点仍无线程栈，不能据此宣告同源或已修复；CI 因此为 Surefire/Failsafe
fork 增加 300 秒上限，从 180 秒起每 60 秒采集 Maven 进程组内 JVM 线程栈，并以 720 秒命令上限确保
失败现场能在作业总超时前上传。该结果证明正式 storage 纳入后的 pre-CLI 发布闭环，不证明尚不存在的
CLI/ZIP 发行结构。

修正提交 `e678dee` 的
[运行 #13](https://github.com/sunker0115/fibra/actions/runs/34679269936) 随后在 Ubuntu runner 全部通过：
48 模块 `clean verify` 耗时 2 分 14 秒，25 制品可复现比较耗时 1 分 9 秒，空临时 Maven 仓及仓库外消费者
验证耗时 1 分 43 秒，作业总计 5 分 15 秒。该结果验证了 `PluginDisableTest` 修正和 CI 诊断包装器的绿色
路径；此前 `FibraEngineRuntimeAdapterTest` 停点仍未取得线程栈，不把单次成功扩大为该未知停点已定位，
后续若复发由新的超时与失败制品保留现场。

联合部署故障另由 `ApplyDeploymentPersistenceBoundaryTest` 6 项、
`ApplyDeploymentMountFailureRecoveryTest` 1 项和 `EngineArtifactRecoveryTest` 2 项覆盖：同一携带新
artifact 与 desired graph 的 `ApplyDeployment` 验证多制品暂存、运行时准备、制品元数据发布和目标保存
边界；保存未确认关闭 mutation gate，并从磁盘完整新目标恢复；保存后退休失败关闭 mutation gate，且
正常关闭明确失败。新实例挂载失败由子 JVM 在确认完整目标已保存后直接 `halt`，父 JVM 再从新 artifact
和目标恢复；清单引用缺失 revision 或损坏对象时启动明确失败且不覆盖目标与损坏证据。这 9 项不进入
71/44/120 计数。

## 1. Cordis 原始行为：71 项

| 当前测试类 | 测试方法 | 验收状态 |
|---|---|---|
| `AssociateSpecParityTest` | `serviceInjection`<br>`propertyInjection`<br>`associatedTypeServiceInjection`<br>`associatedTypeAccessorInjection`<br>`inspect` | 5 项通过 |
| `DecoratorSpecParityTest` | `injectOnClassMethod` | 1 项通过 |
| `DisposeSpecParityTest` | `disposeByPlugin`<br>`disposeManually`<br>`yieldDispose`<br>`asyncReturn1`<br>`asyncReturn2`<br>`asyncYield1`<br>`asyncYield2Aborted`<br>`asyncYield3Aborted`<br>`asyncYield4AwaitDispose`<br>`returnWithError`<br>`yieldWithError`<br>`asyncReturnWithError`<br>`asyncYieldWithError` | 13 项通过；前三项以 `EffectHandle.metadata()` 表达只读所有权，并验证嵌套与幂等清理；`yieldWithError` 用 Java `Publisher` 表达“先产出再失败” |
| `EventsSpecParityTest` | `ctxOn`<br>`ctxOnce`<br>`ctxParallel`<br>`ctxEmit`<br>`ctxSerial`<br>`ctxBail`<br>`ctxWaterfall` | 7 项通过；`EventMode` 固化在 `EventKey`，跨域隔离另有门禁 |
| `FibraSpecParityTest` | `inertiaLock1`<br>`inertiaLock2`<br>`inertiaLock3`<br>`pluginError`<br>`disposeError`<br>`updateConfigOnWrappedFibra`<br>`restartWrappedFibra`<br>`updateConfigWhileInjectedServiceReloads` | 8 项通过；覆盖 provider epoch、配置预校验与清理失败隔离 |
| `InvokeSpecParityTest` | `functionalService`<br>`usesServiceShadowForCallableExtensions` | 2 项通过；PublishedRuntime lease 另有调用门禁 |
| `IsolateSpecParityTest` | `isolatedContext`<br>`sharedLabel`<br>`isolatedEvent` | 3 项通过；域内业务 realm 与独立 RuntimeDomain 隔离分开验证 |
| `LoggerSpecParityTest` | `keepsBoundedBufferInPlaceAndChronological`<br>`disposesExporterThatRegisteredDisposer`<br>`usesFibraNameOutsideService`<br>`honoursExplicitNameArgument`<br>`honoursInterceptName`<br>`usesServiceNameInsideServiceMethod`<br>`outerCallerInterceptOverridesServiceName`<br>`usesInnermostServiceNameAndRestoresOuter`<br>`usesServiceNameInsideServiceInit` | 9 项通过 |
| `PluginSpecParityTest` | `applyFunctionalPlugin`<br>`applyObjectPlugin`<br>`applyInvalidPlugin`<br>`inactiveContext`<br>`contextInspect`<br>`ctxRegistry`<br>`nestedPlugins`<br>`compareSnapshot`<br>`rootDispose`<br>`serviceInit` | 10 项通过 |
| `ReflectSpecParityTest` | `contextIs`<br>`accessCheck`<br>`serviceInjection`<br>`serviceInjectLeak` | 4 项通过 |
| `ServiceSpecParityTest` | `pendingInject`<br>`traceableEffectWithInject`<br>`traceableEffectWithoutInject`<br>`compareSnapshot`<br>`multipleInjects` | 5 项通过；独立域内同键并存由 RuntimeDomain 隔离测试覆盖 |
| `ShadowSpecParityTest` | `keepsCallerMetadataSeparateFromServiceShadow`<br>`exposesCallerWithoutPreservingShadowForNoShadowServices`<br>`exposesCallerToCallableServices`<br>`stripsServiceShadowBeforeCreatingPlugins` | 4 项通过 |
| 合计 | 71 | 完成 |

## 2. Fibra 额外回归：44 项

| 当前测试类 | 测试方法 | 验收状态 |
|---|---|---|
| `AnnotationInjectionParityTest` | `fieldInjectionBecomesARealFibraDependency`<br>`methodInjectionUsesANestedFibraAndReactsToServiceReplacement` | 2 项通过；`@InjectService` 进入 typed service 生命周期 |
| `ContextPropertyParityTest` | `typedAccessorIsEffectOwnedAndSupportsReadWrite`<br>`associatedAccessorResolvesServicesFromTheCallerFibra` | 2 项通过；覆盖 typed property 与关联访问器 |
| `CoreEventsParityTest` | `getAndSetUseInternalWaterfalls`<br>`listenerAndDispatchEventsAreWiredWithoutRecursiveDispatch`<br>`pluginStatusAndServiceEventsExposeLifecycleChanges`<br>`updateEventCanReplaceTheDefaultUpdateFlow` | 4 项通过；覆盖不可变 `withIntercept`、`EventKey` mode、`RuntimeDomainSnapshot` 与变更前 validator veto |
| `EffectParityTest` | `disposesCollectedValuesInReverseOrder`<br>`manualDisposePropagatesAndStopsTheLocalChain`<br>`disposeWaitsForTheAlreadyRequestedFirstAsyncValue`<br>`disposeWaitsForTheAlreadyRequestedNextAsyncValue`<br>`sourceFailureDisposesCollectedValuesBeforeReadyFails` | 5 项通过 |
| `EventParityTest` | `onOnceAndPrependShareOneOrderedHookTable`<br>`targetFilterCanRejectLocalHooksButNotGlobalHooks`<br>`parallelWaitsForEveryListenerAndAggregatesEveryFailure`<br>`serialAwaitsInOrderAndStopsOnTheFirstBailValue`<br>`bailTreatsNullAndFalseAsNonBailingValues`<br>`waterfallUsesTheSameHooksAndSupportsVeto` | 6 项通过；dispatch mode 固定在 `EventKey` |
| `FibraDisposalParityTest` | `startsTopLevelEffectsConcurrentlyAndWaitsForAll`<br>`unloadLogsAndIsolatesEachTopLevelFailure` | 2 项通过；清理并发开始、全量等待、逐项隔离并用 SLF4J 记录 |
| `FibraInertiaParityTest` | `dependencyCanDisappearDuringLoadAndReturnDuringUnload`<br>`removingAPluginProviderWaitsUntilItsConsumerIsPending` | 2 项通过；覆盖 provider epoch |
| `LoggerParityTest` | `keepsTheBoundedBufferObjectInChronologicalOrder`<br>`exporterDisposalRemovesTheExporterThatOwnsTheHandle`<br>`resolvesExplicitInterceptFibraAndNestedServiceNames`<br>`exporterLevelFiltersDebugButKeepsErrors`<br>`exporterBelongsToTheCallingPluginFibra` | 5 项通过 |
| `PluginAndInvocationParityTest` | `constructorServiceIsInvisibleUntilItsStartPublisherCompletes`<br>`nestedPluginIsDisposedWithItsParent`<br>`configValidationRunsOnInitialLoadAndUpdate`<br>`invocationEffectsBelongToTheCallerFibra`<br>`registryGroupsFactoryPluginsByFactoryIdentityAndAwaitsRemoval` | 5 项通过；覆盖配置校验顺序 |
| `ReadmeExampleTest` | `minimalUsageCompilesAndRunsWithoutModification` | 1 项通过；当前 README 示例原样编译运行 |
| `ServiceFibraParityTest` | `isolateUsesIdentityLabelsAndSharedLabelsJoinTheSameScope`<br>`nameOnlyIsolationDoesNotDeclareAServiceType`<br>`nameOnlyDependencyWaitsForTheTypedProviderWithoutDeclaringObjectType`<br>`pendingFibraCanAddANameOnlyDependencyDirectly`<br>`descriptorRejectsTheSameDependencyDeclaredByNameAndType`<br>`configurationRequirementsOverrideTypedInterceptWithoutLosingItsType`<br>`dependencyActivatesAndRevokeWaitsForConsumerCleanup`<br>`readyCompletesWhenMissingDependencyIsStablyPending`<br>`boundServiceInvocationRunsOnTheCallingThread`<br>`replacingAProviderReloadsTheConsumerWithANewEpoch` | 10 项通过；typed-only 契约覆盖隔离、PENDING、清理等待、调用线程和 provider epoch 行为 |
| 合计 | 44 | 完成 |

## 3. 独立结构核对

当前源码按 `@Test` 注解计数：`parity` 的 12 个 Cordis 映射类为 71，`migration` 的 11 个回归类为 44。
`ArchitectureBaselineTest`、`ApiSignatureBaselineTest` 与 `VNextScenarioTest` 单独计数，不进入上述两本账。

## 4. DSH 配置与动态管理的独立门禁

以下对照固定在 DSH `a66e4702047846cdaa10c66c9d3df3951f5ea70d`，记录使用场景是否被证明，
不要求配置文本逐字兼容。表中各项独立于前两组计数，也不能被前两组绿色结果抵扣。

| 使用场景 | DSH 源码行为 | Fibra 当前证据与缺口 |
|---|---|---|
| include、分组与局部开关 | Include 挂载子树，group 保留条目层级；隔离策略从父条目继承 | `DesiredConfigCompilerTest`、`DesiredInputGraphTest` 和 `DesiredRealmIsolationTest` 覆盖树形采集、禁用 include 不读取、命名空间、局部/命名 realm 和重启重建 |
| 配置差量与启动交错 | Entry 无变化时跳过，普通 config 更新原 Fiber；Include 将初始装配和刷新排入同一队列 | `FibraEngineIncrementalTest` 8 项通过，包含启动未完成时提交新目标，两个已接受命令串行执行；旧 Scope/effect 释放，无关实例保留 |
| 多层补丁组合 | `applyEntryPatches` 深拷贝输入，按顺序执行，新增条目可被后续补丁匹配；根/分组追加、名称保护及未匹配告警跳过 | `DesiredConfigPatchTest` 21 项与 `DesiredConfigCompilerTest` 9 项通过；覆盖固定索引、根/分组追加、浅覆盖、字面 null、include 边界、跳过诊断及非法有效结构拒绝。Engine 启动/刷新门禁证明告警不阻止后续有效补丁，刷新返回 warnings，无变化实例不重启 |
| 源文件自动刷新、无效后修正 | HMR 将文件事件合并为 dirty 刷新，读取、解析和更新成功才接受新内容；关闭等待已接受刷新 | `AutomaticDesiredRefreshTest` 11 项、`DesiredSourceMonitorTest` 与 `DesiredInputBindingTest.invalidFileRefreshKeepsTheLastGoodTreeAndAcceptsTheNextValidEdit` 通过：文件事件与周期 resync 共用 Engine command lane；相同 source revision 不覆盖管理目标；已有持久目标启动时不被源覆盖，初次源观察失败无需等待长周期即可发布，瞬时失败后的首次成功观察只建立基线，但显式刷新仍立即导入；删除、无效源或条件表达式求值失败公开失败但保留 last-good 目标、实例、effects 和 mutation gate，坏 source revision 不接受，恢复后仅导入一次；来源失败分类不会掩盖后续管理命令失败；关闭等待已接受刷新，关闭后的 monitor 更新无操作 |
| 条件配置与运行上下文 | Loader 在叶子 Fiber 的配置钩子求值；group/include 中的子条目配置保持字面值，避免提前使用错误上下文 | 配置层已建立 raw `when`/局部 `context`、独立 `ConfigContextSnapshot` 和受限 AST 求值；Engine 以 `ReplaceConfigContext` 提供独立 view/context CAS，在不保存 target、不创建 runtime resource update 的前提下消费 resolved config 并差量协调。`ConfigExpressionEvaluatorTest`、`DesiredEvaluationTest`、`DesiredConfigCompilerTest`、`DeploymentManifestTest`、`FibraEngineConditionalConfigTest` 7 项及自动刷新恢复测试覆盖严格求值、预检失败全量保留、条件子树启停、原实例 update、无关实例保留与重启派生。`CrossRuntimeConditionalConfigTest` 另以真实 JAR、真实 Node sidecar、`ArtifactStore` 和 `ApplyDeployment` 证明同一 raw target 下 Java 实收 resolved typed config 且实例/ClassLoader 不换；Node 条件实例按 context 激活，运行中 Node 的 resolved config 从 `before` 更新为 `after` 且仅该 sidecar 更换 PID；无关 Java 实例/effect、Node 实例/PID/runtime resource 及稳定 Node 的在途 RPC 保持，在途调用可由新 view 完成。失败预检不改任何已发布状态，source/target revision 与保存次数不变，最终 gate 开放且目标满足；Engine 关闭后 Java effect 仅清理一次，全部记录过的 Node PID 终止。本场景完成 |
| 插件主动停用与管理意图 | Loader 只对满足过滤条件的条目根 Fiber 自行 dispose 回写 disabled，不把普通失败、子插件退出或父树关闭当作用户停用 | `Plugins.requestDisable()` 采用显式管理意图，Engine 以精确运行 identity 校验条目根，先保存 `enabled=false` 的完整目标，再沿既有 ChangeSet 排空关闭；STARTING 请求在精确实例 settled 后重试，动态子插件、直接 dispose、失败、父 Scope 排空和 Engine 关闭均不改 desired。`PluginDisableRequestContractTest` 4 项、`PluginDisableTest` 7 项及 Node sidecar/adapter 13 项覆盖协议过滤、重复请求、启动交错、保存失败和非意图退出。`CrossRuntimeSelfDisableTest` 以真实 Java JAR、真实 Node sidecar、`ArtifactStore`、`ApplyDeployment` 与磁盘状态重启证明两种运行时均通过公开 API 持久停用；Node 首次保存失败时保留原目标、进程与实例，存储恢复后同一进程可再次请求并成功。目标保存可先于 Java effect 清理及 Node 进程退出观察，无关实例、ClassLoader、effect、Node PID/runtime resource 与在途 RPC 保持，重启后停用条目不恢复。该场景完成 |

源码：[Include 与补丁](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/include/src/index.ts)、
[Loader 配置钩子与 self-dispose](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/loader/src/index.ts)、
[表达式求值](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/loader/src/config/utils.ts)。

## 5. 正式插件交付门禁

正式插件交付与前述 71/44 项内核行为分开计数；契约存在或模块能打包不等于 provider、consumer 和真实
本阶段的契约、实现与组合证据如下；它们不替代后续 CLI/ZIP 合并后的最终门禁：

| 交付层 | 契约与实现证据 | 真实组合及结论 |
|---|---|---|
| 调用取消与宿主工具契约 | `CancellationSourceTest` 2 项、`InvocationContextCancellationTest` 2 项及 `ToolApiTest` 5 项覆盖 source/token 分离、沿 `ServiceRef` 传播、调用方资源 Context、同 RuntimeDomain 的资源 Scope 约束、不可变参数、结构化结果、稳定错误码、`fibra.tool` contribution kind 与可选 spill service key | `FormalMultiPluginIT` 通过 `PublishedRuntime` 触发真实搜索超时/取消和 Shell 取消；调用只在受管进程树静默后结算，插件卸载等待在途调用与调用 Scope 清理，能力 Context 与资源 Context 不混用 |
| 文件 | `FileSystemContractTest` 9 项、`fibra-fs-local` 33 项、`fibra-tool-fs` 3 项；覆盖 UTF-8、1-based 分页、空文件/目录/非文本、版本观察、原子写、POSIX mode、Windows DACL 接线、换行恢复、NUL 与歧义拒绝 | `FormalMultiPluginIT` 以真实 JAR 验证 provider/consumer 就绪、共享 provider、读写编辑、受保护写、撤销恢复和调用期间卸载；JNA 以 optional 依赖及原包名私有打包，分发门禁检查原生 DLL、许可证和非传递 POM。macOS 仅证明 Win32 注入调用次序/映射/打包，不声称 Windows 实机已执行 |
| 子进程 | `SubprocessContractTest` 4 项、`fibra-subprocess-local` 74 项；覆盖显式 argv/cwd/输出上限、受管 supervisor、Windows Job Object 生命周期、Linux user-systemd transient scope 选择/启动/停止/静默确认、建立前取消的成功排空与清理失败保留、不可用时的一次性较弱回退告警、Darwin PGID 边界、父进程先退出、后代排空、TERM/KILL、幂等终止与调用方资源归属 | 搜索与 Shell 共用唯一 `Subprocess` provider；真实超时、取消、插件卸载和 supervisor 失败测试均等待选定范围静默，不由工具自行创建 `ProcessBuilder`。Node sidecar 仍使用其自身的 supervisor/PGID 或 Windows `taskkill` 边界，不把 Java provider 的 Job Object/systemd 实现移植或宣称到 Node。当前 macOS 以真实进程与注入 seam 验证；Windows Job、Linux user-systemd 路径尚未在对应实机完成最终门禁 |
| Shell | `ShellContractTest` 4 项、`fibra-shell-local` 9 项、`fibra-tool-shell` 9 项；覆盖 fresh shell、workdir、stdout/stderr、非零退出、互斥 timeout/abort、截断、signal 与基础设施失败 | `FormalMultiPluginIT` 验证多级依赖、真实 bash 非零结果、超时/取消、在途调用排空、进程树清理和无关插件保持 |
| 配置存储 | `StorageContractTest` 4 项、`JsonConfigStoreTest` 10 项、`fibra-tool-storage` 4 项；覆盖缺失文件、版本拒绝、完整文档原子持久化、提交后目录同步告警、失败写恢复、顺序事件、listener 隔离、关闭排空与重启读取；正式工具以 `load`、`put`、`remove`、`changes` 公开 ConfigStore，其中 changes 为实例内 live-only 的 64 条有界快照，溢出标记 `dropped`，插件单测直接验证订阅 effect 随实例释放 | `FormalMultiPluginIT` 只经 `PublishedRuntime` 验证同 realm 两个 `tool-storage` consumer 共享 provider、隔离 realm 同名 key 分离、主动停用路由撤销、重新启用不回放、磁盘重启持久化及空变化快照；JSON 存储只位于应用插件，不进入 Engine 事务模型 |
| 搜索 | `fibra-tool-fs-search` 23 项覆盖固定 `rg --no-config` argv、glob/grep 差异、退出码 1、非法模式、原始输出上限、格式化上限、可选 spill、spill 失败、超时与取消 | `FormalMultiPluginIT` 使用真实 `rg` 和共享 subprocess 验证结果、spill、超时/取消及卸载清理；搜索不伪造 `fibra-fs` 依赖 |

`FormalPluginArtifactsIT` 4 项检查 12 个正式动态插件 JAR 的 manifest、精确 artifact 边、父提供
`fibra-tool-api`、动态 contract 加载及重复 class；`FormalMultiPluginIT` 5 项只经 `PluginRegistry`、
`PublishedRuntime` 和 contribution 公开 API 执行上述四个应用场景。该阶段 9 项为 0 failure、0 error、0 skipped。
公共签名门禁已纳入 `fibra-tool-api`、四个动态 contract 和 `fibra-api` 取消 API；版本断言读取 Maven 注入的
`${project.version}`，没有写死 snapshot 版本。

## 6. vNext 最终架构与分发证据

| 验收项 | 证据与结论 |
|---|---|
| 长期 RuntimeDomain 与差量协调 | `RuntimeDomainIsolationTest`、`RuntimeDomainSettlementTest`、`FibraEngineIncrementalTest`、`PublishedRuntimePublicationTest`、`PublishedRuntimeLeaseTest`、`ContributionDrainLifecycleTest`、`CrossRuntimeConditionalConfigTest` 和 `CrossRuntimeSelfDisableTest` 覆盖域间隔离、启动期间目标变化、配置原实例更新、反向依赖闭包替换、旧路由拒绝、嵌套实例、关闭/提交交错及受影响调用排空；改变 Java/Node 局部实例时，无关实例、ClassLoader、Node PID、effects、runtime resource 与在途调用保持 |
| ChangeSet、持久边界与恢复 | `ApplyDeploymentPersistenceBoundaryTest` 6 项、`ApplyDeploymentMountFailureRecoveryTest` 1 项、`EngineArtifactRecoveryTest` 2 项及 artifact/state store 测试覆盖重复内容、半份写入、替换后同步失败、目标保存前后边界、保存后崩溃、缺失/损坏引用、mutation gate 和按完整清单重建；不恢复整代并存、切换或回滚模型 |
| 诊断与 best-effort | `FilePluginAuditRepositoryTest`、`CleanupFailureDiagnosticsTest`、`DynamicPluginDiagnosticsTest` 及部署边界测试证明审计/清理失败可诊断，审计失败不反转成功部署，也不丢失错误证据 |
| 真实 Java 与 Node | Java runtime 测试使用真实 JAR、依赖 DAG、父优先动态契约、资源委派、ClassSpace 和 ClassLoader 回收；Node runtime/sidecar 测试使用真实进程覆盖握手、RPC、心跳、超时、取消、异常退出、父进程退出和其既有 supervisor/进程组终止边界；Node 不使用也不宣称 Java `fibra-subprocess-local` 的 Linux systemd scope 或 Windows Job Object |
| 结构与公开面 | `ApiSignatureBaselineTest`、`ArchitectureBaselineTest`、模块依赖门禁、Spring、README、示例和 archetype 测试通过；生产源码无 PF4J、旧 loader、`Engine.runtime()`、共享可变 `ContributionBridge` 或兼容转发残留，历史文档中的旧名不作为生产残留 |
| 可复现发布 | `scripts/verify-reproducible-release.sh` 比较 25 个正式发布模块（含 `fibra-tool-storage`）的 clean 与非 clean 两次打包结果，包括 flattened POM、主 JAR、sources JAR 和 Javadoc JAR；pre-CLI 快照 `618091a` 已在 GitHub Ubuntu runner 通过。聚合 POM、acceptance、example、parity 和 benchmark 明确不发布；CLI 与 ZIP 合并后仍须按新增制品清单最终复验 |
| 空仓与隔离分发 | `scripts/verify-distribution.sh` 从空临时 Maven 仓部署并解析 25 个正式制品，检查每个模块恰有 POM、主/sources/javadoc JAR，并在仓库外 fixture 验证 12 个正式动态插件不在宿主 classpath、以公开 API 调用 fs/search/shell/storage；pre-CLI 快照 `618091a` 已在 GitHub Ubuntu runner 通过，CLI/ZIP 解压启动及合并后的空仓门禁仍待实现和最终执行 |

2026-09-12 的 `618091a` 已完成 25 制品、12 个动态插件的 pre-CLI 全仓、可复现与空仓/隔离分发验证；
后续 CLI/ZIP 合并后必须按最终制品和真实解压启动场景重新执行，不能沿用该快照结果关闭完整交付。
Windows 文件发布仍明确保留平台证据边界：当前完成实现、注入测试和制品打包验证，不把 macOS 上未运行
的 Win32 原生路径记为实机通过。
