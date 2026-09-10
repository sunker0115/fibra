# 行为验收账本

复核日期：2026-09-11。状态：完成。

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
mvn -pl fibra-parity-tests -am test \
  -Dtest='*ParityTest,ApiSignatureBaselineTest,ArchitectureBaselineTest,VNextScenarioTest,ReadmeExampleTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：120 项通过，0 failure，0 error，0 skipped；其中 Cordis 71 项、Fibra 额外回归 44 项，另有 API
基线 1 项、架构基线 3 项和 vNext 场景 1 项。

## 1. Cordis 原始行为：71 项

| 当前测试类 | 测试方法 | 验收状态 |
|---|---|---|
| `AssociateSpecParityTest` | `serviceInjection`<br>`propertyInjection`<br>`associatedTypeServiceInjection`<br>`associatedTypeAccessorInjection`<br>`inspect` | 5 项通过 |
| `DecoratorSpecParityTest` | `injectOnClassMethod` | 1 项通过 |
| `DisposeSpecParityTest` | `disposeByPlugin`<br>`disposeManually`<br>`yieldDispose`<br>`asyncReturn1`<br>`asyncReturn2`<br>`asyncYield1`<br>`asyncYield2Aborted`<br>`asyncYield3Aborted`<br>`asyncYield4AwaitDispose`<br>`returnWithError`<br>`yieldWithError`<br>`asyncReturnWithError`<br>`asyncYieldWithError` | 13 项通过；前三项以 `EffectHandle.metadata()` 表达只读所有权，并验证嵌套与幂等清理；`yieldWithError` 用 Java `Publisher` 表达“先产出再失败” |
| `EventsSpecParityTest` | `ctxOn`<br>`ctxOnce`<br>`ctxParallel`<br>`ctxEmit`<br>`ctxSerial`<br>`ctxBail`<br>`ctxWaterfall` | 7 项通过；`EventMode` 固化在 `EventKey`，跨域隔离另有门禁 |
| `FibraSpecParityTest` | `inertiaLock1`<br>`inertiaLock2`<br>`inertiaLock3`<br>`pluginError`<br>`disposeError`<br>`updateConfigOnWrappedFibra`<br>`restartWrappedFibra`<br>`updateConfigWhileInjectedServiceReloads` | 8 项通过；覆盖 provider epoch、配置预校验与清理失败隔离 |
| `InvokeSpecParityTest` | `functionalService`<br>`usesServiceShadowForCallableExtensions` | 2 项通过；PublishedRuntime lease 另有调用门禁 |
| `IsolateSpecParityTest` | `isolatedContext`<br>`sharedLabel`<br>`isolatedEvent` | 3 项通过；业务 realm 与 generation domain 分开验证 |
| `LoggerSpecParityTest` | `keepsBoundedBufferInPlaceAndChronological`<br>`disposesExporterThatRegisteredDisposer`<br>`usesFibraNameOutsideService`<br>`honoursExplicitNameArgument`<br>`honoursInterceptName`<br>`usesServiceNameInsideServiceMethod`<br>`outerCallerInterceptOverridesServiceName`<br>`usesInnermostServiceNameAndRestoresOuter`<br>`usesServiceNameInsideServiceInit` | 9 项通过 |
| `PluginSpecParityTest` | `applyFunctionalPlugin`<br>`applyObjectPlugin`<br>`applyInvalidPlugin`<br>`inactiveContext`<br>`contextInspect`<br>`ctxRegistry`<br>`nestedPlugins`<br>`compareSnapshot`<br>`rootDispose`<br>`serviceInit` | 10 项通过 |
| `ReflectSpecParityTest` | `contextIs`<br>`accessCheck`<br>`serviceInjection`<br>`serviceInjectLeak` | 4 项通过 |
| `ServiceSpecParityTest` | `pendingInject`<br>`traceableEffectWithInject`<br>`traceableEffectWithoutInject`<br>`compareSnapshot`<br>`multipleInjects` | 5 项通过；跨代同键并存由 RuntimeDomain 隔离测试覆盖 |
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
