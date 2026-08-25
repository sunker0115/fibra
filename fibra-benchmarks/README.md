# fibra-benchmarks

fibra-core 内核热路径 JMH 性能基准。本模块参加默认 reactor，确保每次完整构建都能发现基准代码与内核 API 的漂移；它仍严格隔离：不发布、不进可复现发布集、不被任何生产模块依赖。默认 Maven 生命周期只编译并打包基准，不执行 JMH 测量。

## 构建

```bash
mvn -pl fibra-benchmarks -am -DskipTests clean package
```

## 运行

```bash
# 全部基准
java -jar fibra-benchmarks/target/fibra-benchmarks.jar

# 只跑某组 + JSON 输出
java -jar fibra-benchmarks/target/fibra-benchmarks.jar ServiceResolution -rf json -rff result.json
```

## 基准含义

- `ServiceResolutionBenchmark`：对比服务解析在 **lifecycle 线程内（直调）** vs **外部线程（跨线程往返）** 的开销。`getOutside`/`invokeOutside` 每 op 一次完整 `subscribeOn(scheduler).block()` 往返；`resolveInside` 用一次 `bail` 握手进入 lifecycle 线程后批量解析（`@OperationsPerInvocation(1000)` 摊销握手），差值即跨线程净开销。
- `EventDispatchBenchmark`：`emit`/`waterfall` 在 1/8/64 个 hook 下的开销，如实包含每次 `resolve` 触发的 `DISPATCH` 内部事件成本。
- `LifecycleDispatchBenchmark`：空 hook 的 `emit`，隔离出单次调度往返（park/unpark + Reactor 包装）净开销，作为前两组的成本基线；含一次空 hook 的 `DISPATCH` 常量项，可忽略。

## 参考基线

环境：Apple M1 Max（10 核）、macOS、JDK 21.0.2（Zulu 21.32.17 arm64）、JMH 1.37。测量参数 fork 2 / warmup 5×1s / measurement 8×1s，`Mode.AverageTime`。

采集日期：2026-08-23。

| 基准 | ns/op (±误差) |
|---|---|
| `LifecycleDispatch.roundTrip` | 3748 ± 85 |
| `ServiceResolution.getOutside` | 3748 ± 25 |
| `ServiceResolution.invokeOutside` | 3752 ± 39 |
| `ServiceResolution.resolveInside` | 79.5 ± 6.4 |
| `EventDispatch.emit`（hook 1/8/64） | 3769 / 3940 / 4336（±~36） |
| `EventDispatch.waterfall`（hook 1/8/64） | 3796 / 3827 / 4507（±~41） |

结论（本机量级参考，跨机器会有出入）：

- 跨线程服务解析/事件分发 ≈ 3.7µs，与空往返基线一致——主导成本是 `LifecycleDispatcher.call` 对非 lifecycle 线程的 `subscribeOn(scheduler).block()` 跨线程往返（约 3668ns），而非业务逻辑；
- 同线程直调 `resolveInside` 仅 ~80ns，比跨线程快约 47 倍；
- 事件分发 hook 数量影响微小（每 hook ~10ns），64 hook 仅比 1 hook 慢 ~15%；
- 结论：调度边界是「每次跨线程调用的固定税」，非随负载增长的瓶颈。优化方向（若将来需要）是减少跨线程往返次数，而非改业务逻辑。
