# 行为验收账本

复核日期：2026-09-12。状态：Cordis 原始行为、Fibra 额外回归、正式插件、pre-CLI 分发证据和正式宿主
CLI 的本地证据已记录；ZIP 分发仍待交付，合并后的最终全仓、可复现及空仓分发门禁仍须统一复验；
平台限定证据见第 5、6 节。

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

在继续排查本地同类无输出等待时，`FibraEngineIncrementalTest` 的失败配置修正场景取得了两份 JVM dump：
命令等待在域收敛，Engine command 与 lifecycle 线程均空闲。根因是 FAILED/PENDING 终态先同步发布状态事件、
后清除 `transitioning`；既有 `settled()` 等待者收到 revision 后立即在 lifecycle lane 重采样，仍看到转换中，
再次等待下一 revision，而随后清除标志没有通知。内核现统一先清除转换标志再发布终态；
`RuntimeDomainSettlementTest.failedTransitionWakesAnExistingDomainSettlementWaiter` 与
`pendingTransitionWakesAnExistingDomainSettlementWaiter` 在旧顺序下稳定超时，修正后通过，原失败配置修正场景
也通过。`PluginInstanceLifecycleContractTest` 另验证 ACTIVE/FAILED 状态观察者同步发起下一次更新时，新旧
转换完成句柄互不串线，并固定已释放实例在用户 validator 前拒绝更新。这个缺陷具备造成远程无输出等待的
条件，但此前 `FibraEngineRuntimeAdapterTest` 停点没有线程栈，
仍不把历史 CI 停点追认为同一根因；超时和 dump 上传继续保留为最终分发门禁的诊断保护。

本次批量目标与终态通知修正完成后执行 `mvn -o -pl fibra-core,fibra-engine,fibra-parity-tests -am test`：
18 个依赖链模块全部通过，`fibra-core` 110 项、`fibra-parity-tests` 122 项均为 0 failure、0 error、
0 skipped，Engine、Spring 及真实 Java/Node 跨运行时场景一并通过。该结果是当前架构阶段的定向回归，
不替代 CLI/ZIP 合并后的最终全仓、可复现和空仓分发门禁。

正式宿主 CLI 阶段使用 Maven 3.9.9、Zulu JDK 21.0.2 对 JLine 4.4.3 `jdk11` 制品执行干净定向编译与
测试，`FibraCliTest`、`CliReplTest` 共 23 项通过；随后执行 `mvn -o -pl fibra-cli -am test`，12 个
reactor 模块全部通过，`fibra-cli` 的 62 项为 0 failure、0 error、0 skipped。该证据覆盖严格 profile
制品清单、首次联合启动、保存目标恢复、显式 apply、目录锁、命令解析、JSON 边界、单行输出和 REPL
复用宿主。`fibra-tool-api` 的 `ToolApiTest`/`ToolOutcomesTest` 16 项固定成功产物、调用终态和 Node wire
schema v2；`FormalCliIT` 3 项与 `FormalMultiPluginIT` 5 项通过真实动态 storage/fs/search/shell 插件验证
REPL 共享状态、重启恢复、完整 apply、公开调用、局部更新和在途调用保留。真实发行目录中的同一流程仍须
由 ZIP 解压门禁完成，不能由本阶段单元/集成测试替代。

正式安装单元阶段在 macOS 26.6.2 arm64、Zulu JDK 21.0.2 上执行 `mvn -o clean verify`，48 个 reactor
模块全部通过，耗时 1 分 35 秒。新增的 `ArtifactPackageTest` 20 项、`PluginArtifactProbeTest` 5 项、
`JavaArtifactPackageTest` 6 项及 Node runtime 22 项共同覆盖严格三字段包描述、整包 runtime 路由、
受管副本独立于原候选目录、Java 主 JAR 与排序私有依赖共用隔离 ClassLoader、拒绝隐式
`MANIFEST.MF Class-Path`、Node 目录 payload 与相对入口，以及拒绝裸 JAR、旧 Node 目录、越界路径和
符号链接。`fibra-plugin-archetype` 的运行时集成测试直接读取 `mvn package` 生成的
`target/<finalName>-plugin/` 并完成真实 Java adapter 装载；正式多插件组合验收 9 项继续通过。
该证据固定 CLI 前的插件安装边界，不证明 profile 配置文件、正式宿主、发行 ZIP 或仓库外解压启动已完成。

联合部署故障另由 `ApplyDeploymentPersistenceBoundaryTest` 8 项、
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
| `FibraSpecParityTest` | `inertiaLock1`<br>`inertiaLock2`<br>`inertiaLock3`<br>`pluginError`<br>`disposeError`<br>`updateConfigOnWrappedFibra`<br>`restartWrappedFibra`<br>`updateConfigWhileInjectedServiceReloads` | 8 项通过；覆盖 provider epoch、配置预校验、整组目标先登记后收敛与清理失败隔离 |
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
| 配置差量与启动交错 | Entry 无变化时跳过，普通 config 更新原 Fiber；同一 JavaScript 调用栈先写各 Fiber 目标配置，实际 reload 在微任务边界后执行；Include 将初始装配和刷新排入同一队列 | `FibraEngineIncrementalTest` 9 项与 `RuntimeDomainSettlementTest` 的批量契约通过：同一 ChangeSet 的既有实例先在一个 lifecycle turn 内登记全部配置目标，再独立收敛，provider 与 consumer 同改只观察到 `1:old`、`2:new`；批量登记前失败不应用任何目标，登记后实例失败以 `PLUGIN_BATCH_UPDATE_FAILED` 区分且不回滚。另覆盖启动未完成时提交新目标、两个已接受命令串行执行；旧 Scope/effect 释放，无关实例保留 |
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
| 正式安装单元 | `ArtifactPackageTest` 20 项严格校验 `plugin.properties` 的 `formatVersion`、`runtime`、`payload` 三字段、包根与 payload 规范路径、完整树无符号链接，并拒绝裸制品；`PluginArtifactProbeTest` 5 项验证按唯一 runtime adapter 路由且 adapter 不得篡改 runtime 或包根 | `ArtifactStore` 保存整个包根而不是单个 payload；Java/Node runtime 的 inspect、prepare 和启动只重新读取受管副本。删除原候选目录后，Java 私有类、资源和 `ServiceLoader` provider 仍可从受管包正常装载；Node sidecar 也只从受管 payload 启动 |
| 调用取消与宿主工具契约 | `CancellationSourceTest` 2 项、`InvocationContextCancellationTest` 2 项及 `ToolApiTest`/`ToolOutcomesTest` 16 项覆盖 source/token 分离、沿 `ServiceRef` 传播、调用方资源 Context、同 RuntimeDomain 的资源 Scope 约束、不可变参数、`content`/`structuredContent` 成功结果、缺失值与 JSON `null`、已知/未知调用失败边界、稳定错误码、版本化 `fibra.tool` schema v2 远端描述/输入/输出/失败协议与可选 spill service key | `FormalMultiPluginIT` 通过 `PublishedRuntime` 触发真实搜索超时/取消和 Shell 取消；调用只在受管进程树静默后结算，插件卸载等待在途调用与调用 Scope 清理。`NodePublishedCancellationOwnershipTest` 另以同一真实 sidecar 的两个并发工具请求证明：取消 A 只发出请求级取消，A 的远端终态到达前实例停用保持排空，B 正常完成，PID 与实例 identity 不变；A 终态后才释放调用 Scope、完成停用并关闭进程单元。能力 Context 与资源 Context 不混用 |
| 文件 | `FileSystemContractTest` 9 项、`fibra-fs-local` 33 项、`fibra-tool-fs` 3 项；覆盖 UTF-8、1-based 分页、空文件/目录/非文本、版本观察、原子写、POSIX mode、Windows DACL 接线、换行恢复、NUL 与歧义拒绝 | `FormalMultiPluginIT` 以标准安装包内的真实 Java JAR 验证 provider/consumer 就绪、共享 provider、读写编辑、受保护写、撤销恢复和调用期间卸载；JNA 以 optional 依赖及原包名私有打包，分发门禁检查原生 DLL、许可证和非传递 POM。macOS 仅证明 Win32 注入调用次序/映射/打包，不声称 Windows 实机已执行 |
| 子进程 | `SubprocessContractTest` 4 项、`fibra-subprocess-local` 74 项；覆盖显式 argv/cwd/输出上限、受管 supervisor、Windows Job Object 生命周期、Linux user-systemd transient scope 选择/启动/停止/静默确认、建立前取消的成功排空与清理失败保留、不可用时的一次性较弱回退告警、Darwin PGID 边界、父进程先退出、后代排空、TERM/KILL、幂等终止与调用方资源归属 | 搜索与 Shell 共用唯一 `Subprocess` provider；真实超时、取消、插件卸载和 supervisor 失败测试均等待选定范围静默，不由工具自行创建 `ProcessBuilder`。Node sidecar 仍使用其自身的 supervisor/PGID 或 Windows `taskkill` 边界，不把 Java provider 的 Job Object/systemd 实现移植或宣称到 Node。当前 macOS 以真实进程与注入 seam 验证；Windows Job、Linux user-systemd 路径尚未在对应实机完成最终门禁 |
| Shell | `ShellContractTest` 4 项、`fibra-shell-local` 9 项、`fibra-tool-shell` 9 项；覆盖 fresh shell、workdir、stdout/stderr、非零退出、互斥 timeout/abort、截断、signal 与基础设施失败 | `FormalMultiPluginIT` 验证多级依赖、真实 bash 非零结果、超时/取消、在途调用排空、进程树清理和无关插件保持 |
| 配置存储 | `StorageContractTest` 4 项、`JsonConfigStoreTest` 10 项、`fibra-tool-storage` 4 项；覆盖缺失文件、版本拒绝、完整文档原子持久化、提交后目录同步告警、失败写恢复、顺序事件、listener 隔离、关闭排空与重启读取；正式工具以 `load`、`put`、`remove`、`changes` 公开 ConfigStore，其中 changes 为实例内 live-only 的 64 条有界快照，溢出标记 `dropped`，插件单测直接验证订阅 effect 随实例释放 | `FormalMultiPluginIT` 只经 `PublishedRuntime` 验证同 realm 两个 `tool-storage` consumer 共享 provider、隔离 realm 同名 key 分离、主动停用路由撤销、重新启用不回放、磁盘重启持久化及空变化快照；JSON 存储只位于应用插件，不进入 Engine 事务模型 |
| 搜索 | `fibra-tool-fs-search` 23 项覆盖固定 `rg --no-config` argv、glob/grep 差异、退出码 1、非法模式、原始输出上限、格式化上限、可选 spill、spill 失败、超时与取消 | `FormalMultiPluginIT` 使用真实 `rg` 和共享 subprocess 验证结果、spill、超时/取消及卸载清理；搜索不伪造 `fibra-fs` 依赖 |

`FormalPluginArtifactsIT` 4 项检查 12 个正式动态插件 JAR 的 manifest、精确 artifact 边、父提供
`fibra-tool-api`、动态 contract 加载及重复 class；`FormalMultiPluginIT` 先把这些真实 JAR 组织为标准安装
目录，再以整个包根经 `PluginArtifactProbe`、`PluginRegistry`、`PublishedRuntime` 和 contribution 公开 API
执行上述四个应用场景。该阶段 9 项为 0 failure、0 error、0 skipped。
公共签名门禁已纳入 `fibra-tool-api`、四个动态 contract 和 `fibra-api` 取消 API；版本断言读取 Maven 注入的
`${project.version}`，没有写死 snapshot 版本。

## 6. vNext 最终架构与分发证据

| 验收项 | 证据与结论 |
|---|---|
| 长期 RuntimeDomain 与差量协调 | `RuntimeDomainIsolationTest`、`RuntimeDomainSettlementTest`、`FibraEngineIncrementalTest`、`PublishedRuntimePublicationTest`、`PublishedRuntimeLeaseTest`、`ContributionDrainLifecycleTest`、`CrossRuntimeConditionalConfigTest`、`CrossRuntimeSelfDisableTest` 和 `NodePublishedCancellationOwnershipTest` 覆盖域间隔离、启动期间目标变化、配置原实例批量目标登记、终态收敛通知、反向依赖闭包替换、旧路由拒绝、嵌套实例、关闭/提交交错、受影响调用排空及同一 sidecar 的请求级取消隔离；改变 Java/Node 局部实例或取消单次请求时，无关实例、ClassLoader、Node PID、effects、runtime resource 与在途调用保持 |
| ChangeSet、持久边界与恢复 | `ApplyDeploymentPersistenceBoundaryTest` 8 项、`ApplyDeploymentMountFailureRecoveryTest` 1 项、`EngineArtifactRecoveryTest` 2 项及 artifact/state store 测试覆盖重复内容、半份写入、替换后同步失败、目标保存前后边界、保存后崩溃、缺失/损坏引用、mutation gate 和按完整清单重建；不恢复整代并存、切换或回滚模型 |
| 诊断与 best-effort | `FilePluginAuditRepositoryTest`、`CleanupFailureDiagnosticsTest`、`DynamicPluginDiagnosticsTest` 及部署边界测试证明审计/清理失败可诊断，审计失败不反转成功部署，也不丢失错误证据 |
| 真实 Java 与 Node | Java runtime 测试使用标准包内真实主 JAR、排序私有依赖、依赖 DAG、父优先动态契约、资源委派、ClassSpace 和 ClassLoader 回收；`JavaArtifactPackageTest` 6 项另证明删除候选目录后仍从受管副本装载私有类、资源和服务，并拒绝隐式 Class-Path、错误 identity 和旧裸 JAR。Node runtime 39 项使用正式目录 payload 和真实进程覆盖受管副本启动、绝对/越界入口拒绝、握手、RPC、心跳、发送前 deadline 所有权、请求级超时/取消、共享 sidecar 隔离、取消宽限耗尽后的实例级终止、异常退出、父进程退出、关闭线程中断、严格 JSON-RPC 响应边界、JSON `null` 响应语义、tool wire schema v2 及 supervisor 范围静默证明；证明缺失或失败时请求排空失败并保留诊断现场。Node 不使用也不宣称 Java `fibra-subprocess-local` 的 Linux systemd scope 或 Windows Job Object |
| 结构与公开面 | `ApiSignatureBaselineTest`、`ArchitectureBaselineTest`、模块依赖门禁、Spring、README、示例和 archetype 测试通过；archetype 的 `mvn package` 自动产生 `target/<finalName>-plugin/plugin.properties` 与 `lib/plugin.jar`，同时保留标准 Maven 主 JAR。生产源码无 PF4J、旧 loader、裸制品 fallback、`Engine.runtime()`、共享可变 `ContributionBridge` 或兼容转发残留，历史文档中的旧名不作为生产残留 |
| 正式宿主 CLI | `ProfileArtifactSourceTest` 30 项、`CliPathsTest` 3 项、`CliHostTest` 6 项、`FibraCliTest` 17 项和 `CliReplTest` 6 项覆盖完整制品清单、首次启动/保存恢复/apply、profile 锁、插件管理命令、PublishedView 工具入口、严格 JSON、关闭钩子和单宿主 REPL；Picocli 4.7.7 与 JLine 4.4.3 `jdk11` 在 Java 21 下干净编译并运行。当前只形成 Maven CLI 制品；真实多插件 ZIP 解压调用，以及阻塞工具调用期间向真实 CLI 进程发送 `SIGTERM` 后的取消传播、Engine drain、受管进程树静默和宿主退出，仍待最终分发门禁 |
| 可复现发布 | `scripts/verify-reproducible-release.sh` 已把 `fibra-cli` 纳入 26 个正式发布模块的 clean/非 clean 比较清单，包括 flattened POM、主 JAR、sources JAR 和 Javadoc JAR；pre-CLI 快照 `618091a` 的 25 制品结果只作历史证据，当前 26 制品清单须在 ZIP 合入后最终执行。聚合 POM、acceptance、example、parity、benchmark 和 distribution 聚合明确不发布 |
| 空仓与隔离分发 | `scripts/verify-distribution.sh` 已把 `fibra-cli` 纳入 26 个正式 Maven 制品的空仓部署清单；pre-CLI 快照 `618091a` 只证明当时 25 制品及仓库外 core/多插件/Engine/Spring/archetype 消费。当前 26 制品、CLI Maven 消费、ZIP 解压启动及同一真实多插件命令流程仍待最终门禁 |

2026-09-12 的 `618091a` 已完成 25 制品、12 个动态插件的 pre-CLI 全仓、可复现与空仓/隔离分发验证；
后续 CLI/ZIP 合并后必须按最终制品和真实解压启动场景重新执行，不能沿用该快照结果关闭完整交付。
Windows 文件发布仍明确保留平台证据边界：当前完成实现、注入测试和制品打包验证，不把 macOS 上未运行
的 Win32 原生路径记为实机通过。
