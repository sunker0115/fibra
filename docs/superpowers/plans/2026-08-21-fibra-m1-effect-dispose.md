# M1：Effect 与清理语义交付记录

状态：已完成

## 已实现契约

- 同步单值、同步多值、异步单值和异步多值统一进入 Reactive Streams `Publisher<Disposable>`。
- `EffectHandle.ready()` 表示 source 已正常结束；source 失败时，先清理已收集资源再传播原异常。
- 同一 effect 内按收集逆序严格串行清理。
- 手动 `dispose()` 传播当前局部错误，并停止尚未开始的局部 disposer。
- Fibra unload 并发启动所有顶层 effect，等待全部完成，并在每个顶层边界隔离和记录错误。
- 嵌套 effect 从顶层所有权列表摘除，进入父 effect 的 metadata children。
- `dispose()` 幂等；不会重复执行 disposer。

## 异步取消边界

`EffectHandleImpl` 使用一次一个的 `request(1)`。请求已经在途时调用 dispose，不抢占该元素；元素到达后仍被收集，然后取消订阅、停止继续请求并执行逆序清理。这是 Cordis async generator epoch 检查在 Java Reactive Streams 中的等价实现。

## 验收用例

- `EffectParityTest`
- `FibraDisposalParityTest`

测试不得使用固定时长 sleep；Publisher 时序用 Reactor sink 或 `StepVerifier` 控制。
