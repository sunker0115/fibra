# 行为验收账本

复核日期：2026-09-13。状态：Cordis 原始行为、Fibra 额外回归、正式插件、正式宿主 CLI、可运行 ZIP、
仓库外真实调用、可复现发布、空 Maven 仓分发门禁、最终全仓与公开 API 复验均已有最终本地证据；平台
限定证据见第 5、6 节。

状态边界：第 6 节记录的正式宿主 CLI、REPL 与可运行 ZIP 是 vNext 架构第 1–10 节的历史完成证据；它们
没有公开 `fibra-cli-api`、动态 command contribution、受控终端租约或命令代竞态测试，不能冒充 F1。
第 7 节单独记录 F1 新证据，第 8 节单独记录 F2 新证据，第 9 节单独记录 F3 新证据，第 10 节单独记录
F4 新证据。当前第 1–10 节与 F1–F4 已完成。

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
0 skipped，Engine、Spring 及真实 Java/Node 跨运行时场景一并通过。该结果是当时架构阶段的定向回归；
CLI/ZIP 合并后的最终全仓、可复现和空仓证据独立记录在下文，不由历史结果拼接得出。

正式宿主 CLI 阶段使用 Maven 3.9.9、Zulu JDK 21.0.2 对 JLine 4.4.3 `jdk11` 制品执行干净定向编译与
测试，`FibraCliTest`、`CliReplTest` 共 23 项通过；随后执行 `mvn -o -pl fibra-cli -am test`，12 个
reactor 模块全部通过，`fibra-cli` 的 62 项为 0 failure、0 error、0 skipped。该证据覆盖严格 profile
制品清单、首次联合启动、保存目标恢复、显式 apply、目录锁、命令解析、JSON 边界、单行输出和 REPL
复用宿主。`fibra-tool-api` 的 `ToolApiTest`/`ToolOutcomesTest` 16 项固定成功产物、调用终态和 Node wire
schema v2；`FormalCliIT` 3 项与 `FormalMultiPluginIT` 5 项通过真实动态 storage/fs/search/shell 插件验证
REPL 共享状态、重启恢复、完整 apply、公开调用、局部更新和在途调用保留。

正式发行阶段在 macOS 26.6.2 arm64、Zulu JDK 21.0.2、Maven 3.9.9 上执行
`mvn --offline -pl fibra-distribution -am clean verify`，25 个相关 reactor 模块全部通过，耗时 1 分 34 秒；
`fibra-distribution/src/test/scripts/verify-archive.sh` 将
`fibra-distribution/target/fibra-0.5.0-SNAPSHOT-bin.zip` 复制到仓库外临时目录解压，并通过其中
`bin/fibra` 从空 data 首次启动。验证覆盖 12 个正式插件、`plugins list`、`tools list`、文件写入/读取、
全文搜索、Shell 命令、配置存储 put/load/changes、重启恢复、候选目录移动不改变保存目标、显式 fs
升级与停用时无关 Java 实例 identity 保持、宿主 JAR 不含正式插件，以及阻塞工具调用期间真实
`SIGTERM` 后 payload、supervisor 与叶子进程均停止。ZIP 同时检查许可证、非法路径、符号链接、构建残留、
仓库绝对路径泄漏和目标运行件权限。

同日执行 `scripts/verify-reproducible-release.sh` 通过：先建立 clean package 基准，再分别执行 clean 与
非 clean package，逐字节比较 26 个正式发布模块的 flattened POM、主 JAR、sources JAR、Javadoc JAR、
发行 ZIP，并比较完整发行目录中每个路径的类型、权限和 SHA。执行 `scripts/verify-distribution.sh` 也通过：
第一空仓对 27 项 reactor clean/deploy 耗时 6 分 09 秒，仅向临时仓部署 26 个正式发布物；第二空仓仅从该
临时仓和 Maven Central 独立构建 `fibra-distribution` 并完成仓库外 ZIP 验证，耗时 4 分 02 秒；随后 5 个
外部消费者耗时 4 分 20 秒通过，清除消费者仓中的 `com/sstlfsj` 后再次解析验证通过，archetype 在隔离仓
生成、编译并产生插件目录。门禁显式使用仓库内 settings，不继承用户级 Maven settings。

最终根聚合命令 `mvn --offline clean package` 不附加跳过参数，50 个 reactor 模块全部通过，耗时 1 分
36 秒，并自动生成 `fibra-distribution/target/fibra-0.5.0-SNAPSHOT/` 与同级
`fibra-0.5.0-SNAPSHOT-bin.zip`。最终补强后执行的 `mvn --offline clean verify` 对同一 50 个模块全部通过，
耗时 2 分 22 秒；`fibra-distribution` 的 verify 阶段再次完成仓库外 ZIP 验收。公开 API 与文档另执行
`mvn --offline -pl fibra-parity-tests -am test`，指定 `ApiSignatureBaselineTest`、
`ArchitectureBaselineTest`、`ReadmeExampleTest` 与 `surefire.failIfNoSpecifiedTests=false`；18 个相关模块与
5 项测试通过，0 failure、0 error、0 skipped。上述命令运行平台均为 macOS 26.6.2 arm64、Zulu JDK
21.0.2、Maven 3.9.9。

独立交付审核未发现 P0/P1，并识别出 3 个 P2 门禁缺口：运行件版本探测可能吞掉非零退出码、REPL 中
停用/恢复结果未断言、`SIGTERM` 后无截止等待可能把工具自然结束误记为排空成功。提交 `5c760ca` 已在
实现与测试中一并关闭：新增失败运行件装配契约测试，ZIP 验收显式断言 `fs-tools` 的停用与恢复状态，且
要求宿主在信号后 10 秒内退出，再检查 payload、supervisor 与叶子进程静默。修正后重新执行
`mvn --offline -pl fibra-distribution -am verify`，25 个模块全部通过，耗时 1 分 21 秒；可复现门禁也在
该提交上重新通过。空 Maven 仓门禁已在此前最终发行清单与隔离仓实现上完整通过；`5c760ca` 未改变依赖
图、发布坐标、26 制品清单或仓库隔离逻辑，按维护者指示不为这三个验收断言重复下载 Central 依赖。

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

以下表格保留当时基于 DSH `a66e4702047846cdaa10c66c9d3df3951f5ea70d` 执行的历史验收证据，记录使用
场景是否曾被证明，不要求配置文本逐字兼容。它不是后续架构契约真源；当前架构契约已经固定为 DSH
`0.1.5-rc.2`、`c291e7961a515f6d7af9304e7fd1d257929aef26`，F1 及后续契约测试必须以该提交复核。
表中各项独立于前两组计数，也不能被前两组绿色结果抵扣。

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

## 6. vNext 第 1–10 节最终架构与分发证据

| 验收项 | 证据与结论 |
|---|---|
| 长期 RuntimeDomain 与差量协调 | `RuntimeDomainIsolationTest`、`RuntimeDomainSettlementTest`、`FibraEngineIncrementalTest`、`PublishedRuntimePublicationTest`、`PublishedRuntimeLeaseTest`、`ContributionDrainLifecycleTest`、`CrossRuntimeConditionalConfigTest`、`CrossRuntimeSelfDisableTest` 和 `NodePublishedCancellationOwnershipTest` 覆盖域间隔离、启动期间目标变化、配置原实例批量目标登记、终态收敛通知、反向依赖闭包替换、旧路由拒绝、嵌套实例、关闭/提交交错、受影响调用排空及同一 sidecar 的请求级取消隔离；改变 Java/Node 局部实例或取消单次请求时，无关实例、ClassLoader、Node PID、effects、runtime resource 与在途调用保持 |
| ChangeSet、持久边界与恢复 | `ApplyDeploymentPersistenceBoundaryTest` 8 项、`ApplyDeploymentMountFailureRecoveryTest` 1 项、`EngineArtifactRecoveryTest` 2 项及 artifact/state store 测试覆盖重复内容、半份写入、替换后同步失败、目标保存前后边界、保存后崩溃、缺失/损坏引用、mutation gate 和按完整清单重建；不恢复整代并存、切换或回滚模型 |
| 诊断与 best-effort | `FilePluginAuditRepositoryTest`、`CleanupFailureDiagnosticsTest`、`DynamicPluginDiagnosticsTest` 及部署边界测试证明审计/清理失败可诊断，审计失败不反转成功部署，也不丢失错误证据 |
| 真实 Java 与 Node | Java runtime 测试使用标准包内真实主 JAR、排序私有依赖、依赖 DAG、父优先动态契约、资源委派、ClassSpace 和 ClassLoader 回收；`JavaArtifactPackageTest` 6 项另证明删除候选目录后仍从受管副本装载私有类、资源和服务，并拒绝隐式 Class-Path、错误 identity 和旧裸 JAR。Node runtime 39 项使用正式目录 payload 和真实进程覆盖受管副本启动、绝对/越界入口拒绝、握手、RPC、心跳、发送前 deadline 所有权、请求级超时/取消、共享 sidecar 隔离、取消宽限耗尽后的实例级终止、异常退出、父进程退出、关闭线程中断、严格 JSON-RPC 响应边界、JSON `null` 响应语义、tool wire schema v2 及 supervisor 范围静默证明；证明缺失或失败时请求排空失败并保留诊断现场。Node 不使用也不宣称 Java `fibra-subprocess-local` 的 Linux systemd scope 或 Windows Job Object |
| 结构与公开面 | `ApiSignatureBaselineTest`、`ArchitectureBaselineTest`、模块依赖门禁、Spring、README、示例和 archetype 测试通过；archetype 的 `mvn package` 自动产生 `target/<finalName>-plugin/plugin.properties` 与 `lib/plugin.jar`，同时保留标准 Maven 主 JAR。生产源码无 PF4J、旧 loader、裸制品 fallback、`Engine.runtime()`、共享可变 `ContributionBridge` 或兼容转发残留，历史文档中的旧名不作为生产残留 |
| 正式宿主 CLI | `ProfileArtifactSourceTest` 30 项、`CliPathsTest` 4 项、`CliHostTest` 6 项、`FibraCliTest` 17 项和 `CliReplTest` 6 项覆盖完整制品清单、首次启动/保存恢复/apply、profile 锁、插件管理命令、PublishedView 工具入口、严格 JSON、关闭钩子和单宿主 REPL；Picocli 4.7.7 与 JLine 4.4.3 `jdk11` 在 Java 21 下干净编译并运行。`verify-archive.sh` 从仓库外真实运行相同命令树，并向阻塞工具调用的真实宿主进程发送 `SIGTERM`，验证取消传播、Engine drain、payload/supervisor/叶子进程静默和宿主退出 |
| 正式发行结构 | 顶层 `fibra-distribution` 是根 reactor 的正式聚合模块；根 `mvn clean package` 与 `mvn -pl fibra-distribution -am package` 均自动产生 `target/fibra-<version>/` 和 `fibra-<version>-bin.zip`。目录包含 `bin/fibra`、宿主 `lib/`、12 个正式插件包、默认 profile/bundle、目标平台 Node/rg、固定 `/bin/bash` 转发、LICENSE 与第三方声明；不预置 data，不把插件打入宿主 JAR，不嵌入仓库绝对路径 |
| 可复现发布 | `scripts/verify-reproducible-release.sh` 已实际通过 26 个正式发布模块的 clean/再次 clean/非 clean 比较，包括 flattened POM、主 JAR、sources JAR、Javadoc JAR、ZIP 字节和发行目录路径/类型/权限/SHA。聚合 POM、acceptance、example、parity、benchmark 和 distribution 聚合明确不发布 |
| 空仓与隔离分发 | `scripts/verify-distribution.sh` 已实际从相互隔离的空 Maven 本地仓完成 26 个正式制品 clean/deploy、独立 `fibra-distribution` clean verify、仓库外 ZIP 真实调用、Maven core/多插件/Engine/Spring 外部消费及 archetype 生成打包。临时发布仓严格只有 26 个 artifactId；删除消费者仓的 Fibra 坐标后仍能从临时仓重新解析。固定 settings 隔离用户级仓库配置，证明发行不依赖工作区 reactor 或历史缓存中的未声明 Fibra 制品 |

平台边界：最终 ZIP 实测平台为 macOS 26.6.2 arm64，携带该目标平台的 Node 与 ripgrep。Windows 文件
发布、Job Object 和 Linux user-systemd 已有实现、注入测试及既有 Ubuntu 构建证据，但本次未在 Windows
或 Linux 实机解压最终 ZIP，因此不记录为对应平台的最终运行门禁通过。

## 7. F1 公开 CLI 组合边界证据

F1 不复用第 6 节的旧 CLI/ZIP 结果抵扣完成条件；新增证据按公开契约、真实运行路径和仓外发行三层记录：

| 验收项 | F1 证据与结论 |
|---|---|
| 注册身份与准入 | `ContributionCallTest`、`ContributionDrainLifecycleTest`、`PublishedRuntimePublicationTest` 与 `PublishedRuntimeLeaseTest` 覆盖单调且不复用的 `registrationIdentity`、错误身份拒绝、revision 冲突、准入后旧 route 持有至 invocation Scope 清理以及受影响变更排空；`PublishedRuntime` 不保留省略注册身份的兼容重载 |
| 公开 CLI 边界 | 新的 `fibra-cli-api` 只依赖 `fibra-api`/`fibra-bridge`，公开 `CliApplication`、Fibra descriptor/handler、`CliInvocation`、输出、退出状态和终端租约；`ArchitectureBaselineTest` 禁止其依赖 Engine、Registry、Picocli、JLine 或 Spring，`ApiSignatureBaselineTest` 同时冻结 `fibra-cli-api` 与 `FibraCli.run(CliApplication, ...)` |
| 命令代 | `CommandGenerationTest` 在真实 Engine 和 `ContributionDirectory` 上先捕获并解析旧代，再同名重注册；更新后旧解析、help 与补全仍只见旧 descriptor，未订阅 invocation 以旧 revision/identity 准入失败，旧、新 handler 均未误调用，新代才可调用新 handler。Picocli `CommandSpec` 每次操作重建，不作为并发快照或命令代身份 |
| 一次性与 REPL | `FibraCliTest` 以标准 Java 插件包经 `RuntimeDomain → ContributionDirectory → PublishedView` 发布动态命令，覆盖一次性执行、动态 help、同一 REPL 下一行重新捕获、停用后消失、动态发现宿主失败稳定返回 3，以及同路径冲突稳定返回 4 且不向调用方抛异常；`CliApplication` bootstrap command 与动态 command 共用结构化请求和退出状态，不取得 `Context`、Engine、Registry 或 `PublishedRuntime` |
| 终端租约 | `CliTerminalControllerTest`、`CliReplTest` 与 `FibraCliTest` 覆盖非交互 `UNSUPPORTED`、同 lane `BUSY`、关闭后 `CLOSED`，以及动态命令成功、异常或遗忘关闭时由 invocation scope 强制释放、下一行可重新取得。raw mode、resize、`0x03` 和调用级信号仍属 F3/F4，未宣称完成 |
| 仓外消费者与发行 | `verification/distribution/java-plugin` 仅以 `provided` 依赖发布的 `fibra-cli-api`，同一真实插件继续贡献并调用已发布工具；`scripts/verify-distribution.sh` 从隔离空 Maven 仓构建消费者，再从 ZIP `bin/fibra` 执行动态命令和 `external-cli echo --help`，停用插件后验证命令消失。正式发布集合增至严格 27 个，`fibra-cli-api` 同时进入可复现制品比较 |

F1 证据本身不抵扣 F2–F4：持久安全历史、高亮和完整交互补全由第 8 节证明；普通 REPL `Ctrl+C`、raw
terminal lease 的 `0x03`、外部 `SIGINT/SIGTERM` 汇流与调用级取消由第 9 节证明；resize/redisplay、
兼容性规则和 CLI 框架冻结仍属于 F4。

## 8. F2 安全历史、补全与终端降级证据

F2 不复用第 6 节或 F1 的旧 CLI/ZIP 结果抵扣完成条件；本节证据限定为安全历史、捕获命令代的交互辅助与
终端降级，不抵扣第 9 节的 F3 取消/信号证据，也不证明 F4 的 resize、redisplay 或兼容性冻结。

| 验收项 | F2 证据与结论 |
|---|---|
| 同代编辑与工具候选 | `CliRepl` 在每次读行前捕获 `CommandGeneration`，把同一代交给该行执行、补全、高亮和敏感参数识别；`CommandGenerationTest` 在真实 Engine 同名重注册后验证旧代仍只给出旧 command/option/argument/tool 候选，旧 identity/revision 准入失败且两个 handler 均不调用，新代才给出新候选并调用新 handler。Picocli `CommandLine` 只是同一捕获事实的 lane-local 派生物。 |
| 历史与诊断安全 | `CliHistory` 以 JLine `DefaultHistory.attach` 绑定并恢复 history 文件；`CliReplTest` 验证普通命令重启后保留、`tools invoke --input` 只写不可重放摘要，且历史保存失败仍关闭终端。`FibraCliTest` 分别覆盖动态命令和 bootstrap 的 `sensitive=true` option、Picocli 短选项附着值、以 `-` 开头的分离敏感值、解析失败及 handler 失败；历史与 CLI 诊断均不可检出样本原值，诊断仍保留非敏感错误上下文。敏感性只由内置命令契约或捕获代中的显式 descriptor 决定，采用 DSH schema `role('secret')`/`recordInput` 的显式声明思路；不扫描 workspace、storage 或业务输出，也不采用 Codex `save-all` 历史或 best-effort 正则作为正确性边界。 |
| 人类/机器终端分离 | `CliReplTest` 验证非 dumb terminal 的 profile/workspace/tool 数量/revision 摘要只写 stderr，dumb terminal JSON stdout 不含 ANSI；一次性命令路径不变。`CliCommandCompleter`/`CliCommandHighlighter` 均为 `fibra-cli` 私有 JLine 实现，`fibra-cli-api` 未引入 JLine 或 Picocli。 |
| 仓外 ZIP | `fibra-distribution/src/test/scripts/verify-archive.sh` 用仓库外解压 ZIP 跑 REPL、持久化 history，并以不命中工具的敏感 JSON 样本检查 history 与 CLI 诊断都不泄漏原值；同一脚本继续回归 storage、fs、search、shell、配置恢复与分发结构。实际执行通过。 |
| P2 接受项 | 当前可控自动化表面不允许驱动本机 iTerm，`script`/`expect` PTY 又不模拟 JLine 所需的终端能力协商响应，故未把该环境的伪终端当作真实 TTY 通过。接受理由是 F2 未定义 raw mode、resize、redisplay 或调用级中断，且 `CommandGenerationTest` 覆盖补全/高亮和同代语义，`CliReplTest` 覆盖非 dumb/dumb reader、历史重启与 stderr 分离，仓外 ZIP 覆盖管道与敏感历史；F4 冻结前必须在真实桌面 TTY 重新执行 history、补全、高亮、窄终端与重启手工门禁。 |

## 9. F3 调用级取消与信号证据

F3 不把 JLine、DSH、AgentCLI、PaiCLI 或 Codex 的中断实现当作 Fibra 排空证明。参考源码只证明输入模式、
取消请求、终端恢复和进程关闭的可行模式；以下 Fibra 测试与发行门禁独立证明三类入口汇入一个幂等协调器，
并在恢复下一条命令或退出进程前等待本项目自己的 invocation 完成边界。

| 验收项 | F3 证据与结论 |
|---|---|
| 普通 REPL `Ctrl+C` | `CliReplTest.ctrlCWhileEditingClearsTheLineAndKeepsTheSessionOpen` 在 JLine 已接管行编辑后触发 `Terminal.Signal.INT`，证明当前缓冲被丢弃、未 dispatch，随后 `next` 正常执行且会话返回 0。原生 xterm PTY 另实测输入未提交的 `partial` 后键入 `Ctrl+C`，新 prompt 出现，动态 `external-cli echo edit-ok` 正常执行；此入口发生在 dispatch 前，不创建 CLI invocation，也不取得 route 租约。 |
| raw lease `0x03` | `CliTerminalControllerTest` 证明 lease 进入 raw mode 后 `ISIG`/`ICANON` 清除，字节 `0x03` 不交给 handler、当前 token 只取消一次、阻塞的 `NonBlockingInputStream` 在最多 100 ms 轮询周期内返回且不关闭共享输入，租约关闭和重复关闭只恢复一次原属性，下一 invocation 可读取后续字节。`FibraCliTest.rawCtrlCCancelsTheAdmittedDynamicInvocationAndThenRestoresTheRepl` 让动态 handler 只传播 `InterruptedIOException` 而不返回 `CANCELLED`，框架在 token 仍可观察时投影 130，并通过真实 Engine/PublishedRuntime 验证取消后下一条命令执行；`rawCancellationIsProjectedByTheFrameworkForBootstrapHandlers` 对 bootstrap handler 证明相同语义，并证明中断后的独立业务失败和 `suppressed` 清理失败仍按失败呈现。`cancellationOnlyOverridesSuccessfulHandlerResults` 证明 token 已取消也不能覆盖 handler 显式返回的 4/7。原生 xterm PTY 实测 `external-cli read-key` 输出 `ready` 后键入 `Ctrl+C`，输出 `cancelled`，随后 `external-cli echo raw-ok` 成功并以 0 退出。 |
| 统一准入与完成屏障 | `CliInvocationCoordinatorTest` 证明 raw 中断只取消 current 且保持会话准入，进程 stop 则一次性关闭准入、取消全部 active invocation，并只在每个 invocation 完成后兑现同一个 `drained` future；重复 stop 复用该 future。`CliProcessShutdownTest.signalClosesAdmissionBeforeDeferredShutdownWorkRuns` 用只排队、不执行的 worker 确定性证明 signal handler 返回以前已同步关闭准入，潜在阻塞取消仍由 worker 执行。CLI 的 bootstrap、动态 command 和工具调用都在 `finally` 结束 invocation；动态命令同步等待 `PublishedRuntime.invoke(...).block()` 返回，因此不会在 Engine 清理未完成时提前显示下一 prompt。 |
| Scope、route、远端与资源排空 | `PublishedRuntimeLeaseTest.cancelledInvocationKeepsItsLeaseUntilCleanupAndRejectsNewCallsDuringShutdown` 已直接证明取消后 route 租约保留至 invocation Scope cleanup 完成；`NodePublishedCancellationOwnershipTest.cancellingOnePublishedToolCallDrainsItBeforeTheNodeInstanceCanStop` 证明远端取消请求未终态时受影响更新继续等待、共享 Node sidecar/PID 与另一条在途调用保持，远端终态后才停止实例；正式 subprocess/shell 取消测试证明受管进程树按所有权停止。`CliInvocationCoordinatorTest` 另证明 raw 中断不取消非 current invocation；`FibraCliTest` 在 raw 取消前后两次读取完整插件快照并断言字节一致，再调用同一动态 handler，证明插件 instance identity、ClassLoader/effects 没有被当前取消替换。以上均为 Fibra 自身证据，不把 DSH abort-race 或 AgentCLI/PaiCLI `Future.cancel(true)` 当成排空证明。 |
| 外部 `SIGINT/SIGTERM` | `CliProcessSignalHandlersTest` 分开注册并逆序恢复 `INT`/`TERM`；`CliProcessShutdownTest` 证明首个信号决定结果，重复/竞态信号不二次取消、关闭或退出，并在 signal handler 返回前同步关闭准入，随后由异步 worker 取消并等待 active invocation 归零，再关闭当前 CLI 拥有的唯一 `CliHost`。从首次信号启动的独立 5 秒截止覆盖同步取消回调、invocation 排空、阻塞 Host close 以及未返回的 `System.exit` 全链；关闭协调器不执行诊断 I/O，任一步不终止均由独立状态仲裁调用 `Runtime.halt` 并投影 8。正常完成以同一原子仲裁关闭后续信号准入，已接受信号则独占退出结果。当前没有 attached/shared client 运行形态。成功的 `SIGINT` 投影 130、`SIGTERM` 投影 0，宿主关闭失败投影 7，普通业务失败继续为 4。 |
| 发行与仓外消费者 | `fibra-distribution/src/test/scripts/verify-archive.sh` 从仓外解压 ZIP，在 shell tool 的受管 payload、supervisor 和叶子进程仍运行时分别发送真实 `SIGINT`/`SIGTERM`，验证 10 秒观察截止内三层进程均停止，宿主退出码精确为 130/0；后台启动前显式恢复 `INT` trap，避免测试 shell 把继承的忽略 disposition 误作产品行为。`verification/distribution/java-plugin` 已扩展仅依赖发布 API 的 `external-cli read-key` fixture；当前 F3 通过根 `mvn clean verify` 执行 ZIP 信号门禁，并在原生 xterm PTY 实测仓外插件 raw 取消与下一命令恢复。包含最新 F3 改动的空 Maven 仓 clean/deploy、五类消费者和 archetype 全套隔离门禁按阶段约定留到 F4 完成后统一执行；此前 F1/F2 的空仓结果不冒充本项已通过。 |
| F4 边界 | F3 只实现 raw lease 获得可靠 `0x03` 所必需的模式切换、可取消读取和属性恢复；终端 resize、异步 redisplay、渐进 renderer、公开 API 兼容性规则及完整桌面交互冻结仍属于 F4。F2 保留的 history/补全/高亮/窄终端直接桌面复验 P2 也继续作为 F4 冻结门禁，不由本节的中断 PTY 实测抵扣。 |

## 10. F4 CLI 框架冻结与最终交付证据

F4 不把 JLine、Codex、DSH、AgentCLI 或 PaiCLI 的实现当作 Fibra 完成证明。JLine 4.4.3 直接证明
`printAbove`、raw mode、WINCH、bracketed paste 和按键序列等物理机制；Codex 0.154.0 证明单一输入 broker、
事件化 resize/draw、重绘合并与终端恢复的成熟组合；DSH 0.1.5-rc.2 证明产品 command/question/approval、
renderer 与 Session 事实应由插件组合。以下项目测试和仓外真实 PTY 门禁独立证明 Fibra 自定的终端事件、
会话所有权、PublishedRuntime 准入和排空契约。

| 验收项 | F4 证据与结论 |
|---|---|
| 公开嵌入与所有权 | `CliSessionTest` 证明 `CliSession` 借用且不关闭 `PublishedRuntime` 和调用方 streams，跨多个有限 invocation 复用一个执行 lane；`distinctSessionsOwnIndependentTerminalLanesWhileSharingOneRuntime` 让两个会话借用同一 runtime 并同时持有各自 renderer lease，证明 lane 以物理 terminal 为边界而非进程全局，关闭 A 不关闭 B 且 B 的消息不会写入 A。`close()` 唤醒空闲 REPL、取消并等待本会话 active invocation、终端恢复和输出关闭，且不关闭 Host。`CliSession` 成为 `fibra-cli` 唯一公开嵌入入口，参考 `FibraCli` 只保留 owner 进程入口。 |
| 应用原始输入 | `CliApplication` 可选 `CliInputHandler`；`CliSessionTest.inputHandlerReceivesEachRawLineAsItsOwnFiniteInvocation` 证明完整原文在 trim、shell 分词、退出词和 Picocli 解析前交给应用，每行仍有独立 invocation；应用以 `CliInputResult.exitWith` 显式结束，通用命令历史、补全和高亮不参与。仓外 PTY 另验证未配对引号、前导空白和字面 `/exit` 不被框架改写，且不生成通用 history。 |
| 事件、尺寸与输入归一化 | `CliTerminalControllerTest` 覆盖初始正数尺寸、WINCH 后最新尺寸、Unicode、方向键、独立 ESC、明确编码的 Shift-Tab/Ctrl-Enter，以及只报告 Shift/Control、不从大写字符推断 Shift。bracketed paste 成对启停并作为一个事件交付，`CRLF`/`CR` 归一为 `LF`，其中换行、`0x03` 和 slash 均不触发命令或取消。 |
| 渐进 renderer 与并发输出 | `CliTerminalControllerTest` 证明 renderer 回调只在 terminal lane 串行发生，跨线程 `render` 请求合并，完整不可变帧按显示单元格和物理行限制校验；已准入的并发 stdout/stderr 在同一队列中暂停画面、写入并重绘，stop 后的新输出稳定拒绝。`CliSessionTest.asynchronousHumanMessageUsesPrintAboveAndPreservesTheEditedLine`、`lineEditorHandoffWaitsForAnAdmittedPrintAbove` 与 `lineEditingWaitsForAnAdmittedNonEditorMessage` 证明后台消息经 JLine `printAbove` 或人类 stderr 呈现，编辑进入/离开均等待已准入消息且保留编辑缓冲。`rendererAndBackgroundProducerCanUseTheSameInvocationOutputWithoutDeadlock` 证明输出生命周期锁不跨越 terminal lane。 |
| 降级与恢复 | dumb/非 TTY 不输出 prompt、ANSI 或终端控制序列，机器 stdout 保持 JSON/业务数据；`NO_COLOR` 仅移除 SGR。renderer `start`、初始化、回调、取消、EOF 或恢复失败均进入同一关闭链；`CliTerminalControllerTest` 覆盖 raw 只在 `run` 内进入/恢复、未启动 lease 无物理副作用、renderer lane 禁止自关闭、Display 清理失败后仍逐项尝试 paste/keypad/flush/attributes/WINCH、恢复失败由 `run/close/controller` 共享且封锁原 owner 再次 acquire。成功恢复后下一 invocation 可重新取得 terminal。`CliSessionTest.stopWakesTheLineReaderBeforeJLineInstallsItsOwnSignalHandler` 关闭 JLine handler 安装前的唤醒空窗，`repeatedSessionCloseWrapsTheSharedFailureForEachObserver` 证明重复关闭不会触发 Throwable 自抑制。 |
| 真实 TTY | `verification/distribution/verify-cli-tty.py` 在原生 xterm PTY 中直接验证历史重启、Tab 补全、已知命令高亮、编辑行上的异步 `printAbove`、32x10 窄终端、真实 `SIGWINCH` resize、bracketed paste、方向键/ESC/Shift-Tab/Ctrl-Enter、渐进 renderer、失败与取消后的恢复。它同时通过发行 ZIP 的动态 Java command 验证 raw `0x03` 取消后下一命令正常执行，关闭了 F2 遗留的直接 TTY P2。 |
| 兼容性与发行 | `ApiSignatureBaselineTest` 以 `javap -protected` 冻结 `fibra-cli-api` 与 `fibra-cli` 全部 public/protected 类型；仓外 `cli-application` 只依赖发布制品即可创建自定义 `CliApplication`、多次执行和后台消息，不取得 JLine 私有对象。最终工作树投影到临时全新 checkout 后，根 50 模块 `mvn clean verify`、发行 ZIP 仓外验收和三轮 `scripts/verify-reproducible-release.sh` 全部通过，其中 CLI 137 项测试全绿。F4 的 27 个正式制品、依赖坐标、发行脚本和消费者集合冻结后，`scripts/verify-distribution.sh` 已从相互隔离的空 Maven 仓完成部署、发行 ZIP、五类消费者、archetype、删除消费者 Fibra 坐标后的重新解析和两组真实 PTY 门禁；其后最终审查只修正关闭状态机、对应测试和文档，没有改变这些发行输入。按用户明确要求不为未变化的依赖图重复下载空仓。 |

Linux 收口证据：`447ef6e` 让验证器只在 PTY 已进入非规范读取时发送 EOF，移除会被 `printAbove` 重绘
干扰的提示符计数同步；该变更没有修改生产代码或公共契约。GitHub Actions
[第 29 次全量门禁](https://github.com/sunker0115/fibra/actions/runs/34810039690)在 `ubuntu-latest` 上完成根
`clean verify`、27 个发布制品可复现检查和仓库外分发验证，包括两组原生 xterm PTY 门禁，结果全绿。

平台边界：F4 最终本地证据来自 macOS arm64，以上远端门禁补齐 Linux；Windows 的 JLine 输入、终端尺寸、
本地进程和安装包仍须由对应平台发布流水线证明，不能由 macOS/Linux 结果替代。该限制不影响 Java 公开
契约和 dumb/非 TTY 降级冻结，但对应平台发行时必须重新运行相同门禁。

## 11. Java/Node 长稳与仓外生命周期收口证据

| 验收项 | 证据与结论 |
|---|---|
| 发布视图与旧代回收 | `ContributionDirectory.views()` 与 `FibraEngine.views()` 改为只发布订阅后的变化，当前事实继续由 `current()` 提供；慢订阅者只保留最新变化。`JavaPublishedViewRetentionTest`、`ContributionDirectoryTest` 与 `PublishedRuntimeBackpressureTest` 证明旧 descriptor、Java `PluginClassLoader` 和中间 Engine view 不会被 replay 节点或 `publishOn` 预取队列长期持有；公开文档给出“先订阅、再读 current”的无缝观察顺序。 |
| Java/Node 仓外生命周期 | `LifecycleConsumerTest` 只通过 Registry、PublishedRuntime 与 contribution 公开 API，对 Java/Node 共同验证探测、安装但不自动启用、启用调用、完整配置更新、停用后配置保留、升级、相同字节幂等升级、同版本内容变更、在途调用排空后的原子移除、重装及停用后卸载。Java fixture 的 `LifecycleEntrypoint` 仅进入独立测试插件 JAR；Node fixture 仅使用 Node 内建模块，并直接核对 payload PID 与 Fibra supervisor PID 的存活和退出。 |
| 资源曲线与基准样本 | macOS arm64、Zulu JDK 21.0.2、Node 20.20.2 下，Java 25 轮更新后 active loader 从 3 回到 0、FD 从稳定 37 回到 34，创建/关闭资源均为 51；Node 24 轮中 Node 线程始终为 0、FD 始终为 34，全部更新完成。JMH 单 fork 短样本为 contribution 194.781 ns/op、Engine transaction 600.341 us/op，只作为后续对比基线，不作为优化或容量证明。 |
| 发布门禁 | 根 50 模块 `mvn -o clean verify` 通过；`scripts/verify-reproducible-release.sh` 连续三次构建并通过 27 个正式制品、flattened POM、发行 ZIP 与目录 manifest 的字节比较；`scripts/verify-distribution.sh` 使用现有 `~/.m2`，不创建或清空 Maven 本地仓库，临时部署目标严格包含 27 个正式 artifactId，仓外消费者、archetype、发行 ZIP、`CliSession` 与动态插件真实 TTY 门禁全部通过。core 与 engine 外部测试均使用 test-scope `slf4j-nop`，不再出现“未找到 SLF4J provider”警告，也不进入正式制品。 |

本地短超时脚本单元测试 4/4 通过；真实诊断采样在当前受限沙箱中因 `/bin/ps: Operation not permitted`
无法生成 JVM dump，因此不记录为本机通过，留待同一提交的 GitHub Actions Linux runner 验证。以上本地结果
不能替代 Windows 实机门禁；Windows 仍保持未实测声明。
