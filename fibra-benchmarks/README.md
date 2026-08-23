# fibra-benchmarks

fibra-core 内核热路径 JMH 性能基准。本模块严格隔离：不发布、不进可复现构建集、不被任何生产模块依赖，仅通过根 pom 的 `benchmarks` profile 进入 reactor。

## 构建

```bash
mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests clean package
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
