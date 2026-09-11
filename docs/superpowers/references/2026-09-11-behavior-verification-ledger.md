# 行为验收账本

复核日期：2026-09-11。状态：Cordis 原始行为与 Fibra 额外回归已通过；不代表整个项目交付完成。

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

本次先清理构建产物再重建，并在更新 API 基线后以普通校验模式重新通过以上 120 项。
随后执行全仓 `mvn -o verify`，28 个模块通过，包含真实 JAR、Node、Spring、archetype 与示例集成测试；
这证明当前已实现代码通过现有门禁，不抵扣下表未完成场景。最终空依赖仓库分发尚未执行。
DSH 的配置组合、源文件自动刷新、真实 Java/Node 局部更新和多插件应用场景不在这 120 项中，
必须各自提供行为证据，不能用内核门禁替代。DSH 的逐项采用边界见
[源码基线](2026-09-09-plugin-dependency-baselines.md)。

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
不要求配置文本逐字兼容。表中的未完成项不影响前两组计数，也不能被前两组绿色结果抵扣。

| 使用场景 | DSH 源码行为 | Fibra 当前证据与缺口 |
|---|---|---|
| include、分组与局部开关 | Include 挂载子树，group 保留条目层级；隔离策略从父条目继承 | `DesiredConfigCompilerTest`、`DesiredInputGraphTest` 和 `DesiredRealmIsolationTest` 覆盖树形采集、禁用 include 不读取、命名空间、局部/命名 realm 和重启重建 |
| 配置差量与启动交错 | Entry 无变化时跳过，普通 config 更新原 Fiber；Include 将初始装配和刷新排入同一队列 | `FibraEngineIncrementalTest` 8 项通过，包含启动未完成时提交新目标，两个已接受命令串行执行；旧 Scope/effect 释放，无关实例保留 |
| 多层补丁组合 | `applyEntryPatches` 深拷贝输入，按顺序执行，新增条目可被后续补丁匹配；根/分组追加、名称保护及未匹配告警跳过 | `DesiredConfigPatchTest` 21 项与 `DesiredConfigCompilerTest` 9 项通过；覆盖固定索引、根/分组追加、浅覆盖、字面 null、include 边界、跳过诊断及非法有效结构拒绝。Engine 启动/刷新门禁证明告警不阻止后续有效补丁，刷新返回 warnings，无变化实例不重启 |
| 源文件自动刷新、无效后修正 | HMR 将文件事件合并为 dirty 刷新，读取、解析和更新成功才接受新内容；关闭等待已接受刷新 | `AutomaticDesiredRefreshTest` 10 项、`DesiredSourceMonitorTest` 与 `DesiredInputBindingTest.invalidFileRefreshKeepsTheLastGoodTreeAndAcceptsTheNextValidEdit` 通过：文件事件与周期 resync 共用 Engine command lane；相同 source revision 不覆盖管理目标；已有持久目标启动时不被源覆盖，初次源观察失败无需等待长周期即可发布，瞬时失败后的首次成功观察只建立基线，但显式刷新仍立即导入；删除或无效源公开失败但保留 last-good 目标、实例、effects 和 mutation gate，恢复相同内容不重启；来源失败分类不会掩盖后续管理命令失败；关闭等待已接受刷新，关闭后的 monitor 更新无操作 |
| 条件配置与运行上下文 | Loader 在叶子 Fiber 的配置钩子求值；group/include 中的子条目配置保持字面值，避免提前使用错误上下文 | 配置层已建立 raw `when`/局部 `context`、独立 `ConfigContextSnapshot` 和受限 AST 求值，`ConfigExpressionEvaluatorTest`、`DesiredEvaluationTest`、`DesiredConfigCompilerTest` 与 `DeploymentManifestTest` 覆盖惰性分支、祖先停用、局部覆盖、补丁后校验、严格 JSON Pointer 及格式 3 持久化。Engine 尚未接入 context-only command、resolved config 差量和 Java/Node 端到端门禁，因此本场景仍未完成 |
| 插件主动停用与管理意图 | Loader 只对满足过滤条件的条目根 Fiber 自行 dispose 回写 disabled，不把普通失败、子插件退出或父树关闭当作用户停用 | Fibra 区分 desired 与 observed，所有目标修改经 EngineCommand；显式管理入口已有，但主动停用场景的等价使用证明仍须补齐，不能直接引入隐式目标回写 |

源码：[Include 与补丁](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/include/src/index.ts)、
[Loader 配置钩子与 self-dispose](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/loader/src/index.ts)、
[表达式求值](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/loader/src/config/utils.ts)。
