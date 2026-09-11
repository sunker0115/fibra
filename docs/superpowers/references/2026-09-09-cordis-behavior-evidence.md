# Cordis 行为映射与验收证据

复核日期：2026-09-11。vNext 已完成 71 项 Cordis 原始行为与 44 项 Fibra 额外回归的逐项验收；
完整方法清单和执行命令见同目录的[行为验收账本](2026-09-11-behavior-verification-ledger.md)。

## 固定真源

- DeepSeek Harness：`b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`。
- `vendor/cordis/src` Git tree：`745dea6282ba11f41fa0eb87171a4a56afe9730f`。
- Cordis 原始测试语料：`8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的 `packages/core/tests`。
- Fibra 当前验收源码：`fibra-parity-tests` 的 `parity` 与 `migration` 测试包。

用户提供的文章
[《Cordis 插件系统：插件、服务与事件如何协作》](https://mp.weixin.qq.com/s/Xm_jMF-wIepmuO5Q3BFDAQ)
用于提示 Fiber、Service、Effect 与 Event 的协作和诊断问题，不作为高于上述固定源码的行为真源。

### 源码文件摘要

以下摘要已对本地 `b0a7d2c` 重新计算。`4.0.1 -> 4.0.2` 期间 `vendor/cordis/src` 内容未变；
`package.json` 因版本元数据变化不列入源码摘要。

| 文件 | SHA-256 |
|---|---|
| `context.ts` | `96b388162d6013c1898de61e35f28917abb93809de24174980f2a1a348d0b115` |
| `events.ts` | `96565a2b5fbf35c26b78cbdc785b2b2e3f01fa55a9fd1f6af5355ab35ccc343c` |
| `fiber.ts` | `750555b47603f88e7ef7a05d1a8d629b355c176a1185af411aac6e3e1e2b7ba3` |
| `index.ts` | `c2232f082c763488225eafcd33530640ad26af0c7d6ea871a4f7e8ebf0eb3704` |
| `logger.ts` | `5121e76fd9f55b9b90dfea09d6e60d122e7530246b8b19970ddebd9c35ccf7d8` |
| `reflect.ts` | `6847b9781044023d65aebb5896a873b9f0a3d777eaf7fa0dd2a7a408eb6b74a6` |
| `registry.ts` | `34bbd60ae502b4f85201c204afb52a9acabde6878d9278493478c1b2183debd3` |
| `service.ts` | `614205c6ce7cf1a057b8b352e96aa4fcd734c967a3d2283a126c9c61f268e3be` |
| `utils.ts` | `8fe273424583d21267ef13f248840e97454d09d836a0ac08323a9482c2069263` |
| `LICENSE` | `034fb52b1d57360ecbae6cb1632a88f86fd7c3d3f5631a5f082710203dda0be7` |

## 当前映射

| Cordis 职责 | vNext 所有者 | 已有验证范围 |
|---|---|---|
| Context 派生与作用域 | `Context`、`Scope` | `RuntimeScopeContractTest`：独立子树关闭、派生视图所有权、关闭幂等 |
| Fiber 状态与服务依赖 | `PluginInstanceImpl`、`ServiceRegistry` | `PluginInstanceLifecycleContractTest`：PENDING、激活、失败更新恢复、监督资源失败 |
| effect 与调用者资源 | `DefaultEffects`、`ServiceRef`、`InvocationContext` | `ScopeOwnedCapabilitiesContractTest`：调用方资源、realm、撤销；`ScopeRegistrationRaceTest`：注册与关闭竞态 |
| effect 初始化与清理 | `OwnedResource` | `DisposeSpecParityTest`：13 条清理路径；`EffectCreationContractTest`、`EffectCleanupContractTest`：初始化异常与失败状态、重入关闭、双等待者终止 |
| 托管服务链收敛 | `FibraEngine` | `FibraEngineDesiredStateTest`：多级服务链发布前收敛 |
| 事件 | `DefaultEvents`、`EventBus` | `EventsSpecParityTest`：上游七项事件断言；`EventDispatchContractTest`：Publisher 完成边界、订阅错误隔离、截断值、全局/前置监听与 Scope 撤销 |
| 日志完整行为 | `DefaultLoggerService`、`BoundLoggerService` | 原始 9 项与额外 5 项日志回归均已验收通过 |

## 逐项行为验收

每个测试方法的完整清单与执行结果见
[行为验收账本](2026-09-11-behavior-verification-ledger.md)。

固定测试语料包含 12 个原始 spec、71 个 `it`：associate 5、decorator 1、dispose 13、events 7、fiber 8、invoke 2、isolate 3、logger 9、plugin 10、reflect 4、service 5、shadow 4。

Fibra 自身的额外回归位于 `migration` 测试包，共 **44 项**：

| 当前额外回归类 | 项数 | 验收状态 |
|---|---:|---|
| `AnnotationInjectionParityTest` | 2 | 完成 |
| `ContextPropertyParityTest` | 2 | 完成 |
| `CoreEventsParityTest` | 4 | 新 API 等价表达完成 |
| `EffectParityTest` | 5 | 完成 |
| `EventParityTest` | 6 | 完成 |
| `FibraDisposalParityTest` | 2 | 完成 |
| `FibraInertiaParityTest` | 2 | 完成 |
| `LoggerParityTest` | 5 | 完成 |
| `PluginAndInvocationParityTest` | 5 | 完成 |
| `ReadmeExampleTest` | 1 | 新 API 等价表达完成 |
| `ServiceFibraParityTest` | 10 | typed-only 新 API 等价表达完成 |
| 合计 | 44 | 完成 |

原始 71 项与上述 44 项分别记账；新增测试、API 签名测试和发布基线测试均不能用数量抵扣。完成标准是原始断言逐项有源码依据、明确的新 API 落点和执行证据；语义差异必须在整体架构中明确解决，不能单纯改预期或标为跳过。该要求是项目最终验收条件，不是仅恢复文档清单。

当前 71 + 44 已与 API、架构和 vNext 场景门禁一起执行，共 120 项全部通过。vNext 不包含旧兼容入口；
内部事件、name-only service 与同步 generator 使用当前 API 表达相同的不变量。

### 事件断言映射

已直接读取固定 Cordis 提交的 `packages/core/tests/events.spec.ts`，并与当前 Java 断言逐项核对。

| 原始 `it` | 当前测试方法 | 验证内容 |
|---|---|---|
| `ctx.on()` | `EventsSpecParityTest.ctxOn` | 重复分派、显式撤销后不再调用 |
| `ctx.once()` | `EventsSpecParityTest.ctxOnce` | 一次调用、调用后再次显式撤销安全 |
| `ctx.parallel()` | `EventsSpecParityTest.ctxParallel` | 空监听集、target 过滤、同步抛错不截断异步监听、等待完成并聚合错误 |
| `ctx.emit()` | `EventsSpecParityTest.ctxEmit` | 空监听集、target 过滤、原异常同步传播 |
| `ctx.serial()` | `EventsSpecParityTest.ctxSerial` | 空监听集、target 过滤、原异常经异步结果传播 |
| `ctx.bail()` | `EventsSpecParityTest.ctxBail` | 空监听集、target 过滤、原异常同步传播 |
| `ctx.waterfall()` | `EventsSpecParityTest.ctxWaterfall` | 包装顺序、结果与调用次数、截断后不进入后续监听和最终行为 |

Java 表达边界：通过 `context.events()` 使用能力；`EventTarget` 显式判断注册 Context，不复制 JS 的动态属性与 `this`；`parallel/serial` 的结果通过订阅 `Mono` 执行和等待，不宣称与 JS Promise 的立即执行时机相同。上述原始映射验证分派结果与过滤语义，不证明所有重入、并发或插件 ClassLoader 更新后的事件契约行为。

`OnceEventContractTest` 单独验证 Fibra 的注册级至多一次调用保证：覆盖 `parallel/serial` 已捕获快照的
异步重叠、重复订阅、失败与取消，`emit/bail` 前置监听器重入，以及 `waterfall` 重复 continuation。
未到达的一次性监听器不因提前截断或取消而被消耗，普通监听器仍可重复派发。七个原始复现用例在
修正前均因一次性监听器被调用两次而失败，修正共用调用准入后通过；其余用例补充错误和取消边界。
这是 Fibra 的补充语义，不计入 71 项原始映射或 44 项历史回归，也不声称上游已有相同并发保证：
DSH 固定提交 `a66e4702047846cdaa10c66c9d3df3951f5ea70d` 的 `vendor/cordis/src/events.ts`
在 `once` 包装器中只执行注销再调用，注销本身不能阻止另一份已捕获快照再次执行该包装器。

额外的 Reactor 回归测试不计入原始 71 项。`parallel` 必须等待监听器 Publisher 的终止而非首个元素；每次订阅的错误集合独立。两个测试曾分别复现首元素取消后续流、下一次订阅携带旧错误，修正后通过。`serial` 仍是单结果截断模型，本轮没有把它改为流式结果聚合。

### 清理断言映射

已读取固定 Cordis 提交的 `packages/core/tests/dispose.spec.ts`，并核对 DeepSeek 内置 `fiber.ts` 的 effect 初始化登记、在途等待和逆序清理实现。异步测试用可控制的 Publisher 推进生成过程，不以睡眠等待推测时序。

| 原始 `it` | `DisposeSpecParityTest` 方法 | 验证内容与边界 |
|---|---|---|
| `dispose by plugin` | `disposeByPlugin` | 实例卸载清理、幂等、`EffectHandle.metadata()` 所有权表达 |
| `dispose manually` | `disposeManually` | 显式清理、幂等、匿名标签与句柄所有权 |
| `yield dispose` | `yieldDispose` | 嵌套元数据、严格 LIFO、嵌套监听撤销不影响独立监听 |
| `async return 1` | `asyncReturn1` | 初始化先完成，后续清理产生 `[1, 2]` |
| `async return 2` | `asyncReturn2` | 提前撤销仍等待返回的资源，并清理为 `[1, 2]` |
| `async yield 1` | `asyncYield1` | 完整收集再清理，序列为 `[1, 3, 5, 6, 4, 2]` |
| `async yield 2 (aborted)` | `asyncYield2Aborted` | 初次产出前撤销，等待在途项后取消，序列为 `[1, 2]` |
| `async yield 3 (aborted)` | `asyncYield3Aborted` | 首项后撤销，等待第二个在途项后取消，序列为 `[1, 3, 4, 2]` |
| `async yield 4 (await dispose)` | `asyncYield4AwaitDispose` | `ready()` 等待收集完成，返回原句柄，再完成逆序清理 |
| `return with error` | `returnWithError` | 同步 Supplier 的原始异常同步传播 |
| `yield with error` | `yieldWithError` | Java `Publisher` 表达先产出再失败，异常经 `ready()` 传播且已收集资源先回收 |
| `async return with error` | `asyncReturnWithError` | 原异常经 `ready()` 传播，无未生成资源的清理 |
| `async yield with error` | `asyncYieldWithError` | 原异常经 `ready()` 传播前，已收集资源完成清理 |

vNext 不公开 `Scope` 或 `PluginInstance` 的顶层可变资源列表；`EffectHandle.metadata()` 是稳定的只读
所有权表达，配合实际清理次数、顺序与作用域断言覆盖原测试不变量。Java 没有同步 generator，故以
`Publisher` 表达“先产出再失败”，没有恢复旧 `effectSync` 兼容接口。

补充回归边界：同步 `effect(Supplier)` 与异步 `collect(Publisher)` 共用同一个所有权/清理实现，但同步 Supplier 不经 Reactor 转换异常；初始化抛错可被插件启动路径捕获并进入 `FAILED`。资源在执行 Supplier 前登记，重入 Scope 关闭仍能等待初始化结果。提前撤销遇到清理失败时，未完成的 `ready()` 与 `dispose()` 都必须终止，不能遗留永久等待者；初始化和清理复用同一个异常对象时也不能因自抑制异常而中断终止信号。监督资源若在 `onSubscribe` 前撤销，同样完成句柄等待，晚到的 Subscription 只取消、不请求数据。

顶层多个 effect 的并发 all-settled、清理失败隔离、日志缓冲与调用者归属已由 44 项额外回归覆盖。
[行为验收账本](2026-09-11-behavior-verification-ledger.md)记录了共同执行证据；这些行为门禁已关闭。
