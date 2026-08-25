# Fibra 内核性能基准设计（fibra-benchmarks）

日期：2026-08-23
状态：已实施；`0.4.0-SNAPSHOT` 起进入默认 reactor

## 1. 目标与边界

用 JMH 为 `fibra-core` 内核热路径建立可信的性能基线，用数据判断"单线程 lifecycle 调度边界是否成为瓶颈"。这是[Fibra、Spring 与 Java DeepSeek Harness 集成架构](./2026-08-22-fibra-spring-harness-integration-architecture.md) §4.1 的前置门禁：*"只有基准证明该调度边界成为瓶颈时，才允许在不改变可见性和事件顺序的前提下设计读取快照。"*

边界：

- 只测内核（`fibra-core` + `fibra-api`）的服务解析、事件分发、lifecycle 调度往返。流式 chunk 吞吐属于尚不存在的 harness 层，不在本次范围；
- 基准模块**不发布、不进可复现构建集、不被任何生产模块依赖**；
- 本次只产出基准设施与基线数字，不据此修改内核。任何内核改动需另立设计，并以本基准复测。

## 2. 工具与版本

- JMH `1.37`（openjdk 官方当前稳定版）：`org.openjdk.jmh:jmh-core` + `org.openjdk.jmh:jmh-generator-annprocess`；
- 注解处理器走 `maven-compiler-plugin` 的 `annotationProcessorPaths`（不接会报 `Unable to find /META-INF/BenchmarkList`）；
- `maven-shade-plugin` 打可运行 uber jar，`ManifestResourceTransformer` 设 `mainClass=org.openjdk.jmh.Main`，`AppendingTransformer` 合并 `META-INF/BenchmarkList` 与 `META-INF/CompilerHints`（多 jar 合并时不 append 会丢失基准列表）；
- JMH 版本统一写入根 POM 的 `jmh.version`，`jmh-core` 与 `jmh-generator-annprocess` 坐标统一进入根 `dependencyManagement`；benchmark 模块及注解处理器路径均只引用该版本真源。

## 3. 模块与隔离

### 3.1 模块骨架

- 新模块 `com.sstlfsj:fibra-benchmarks`，`parent = com.sstlfsj:fibra:${revision}`，`relativePath=../pom.xml`；
- **不声明** `maven.deploy.skip/source.skip/javadoc.skip`——继承根 `pom.xml` 的默认 `true`（生产模块才显式翻 `false`，见 `fibra-core/pom.xml`）。因此天然不发布、不产 sources/javadoc jar，与 `fibra-parity-tests` 一致。

### 3.2 注册方式：默认 reactor

根 `pom.xml` 的默认 `<modules>` 在 `fibra-parity-tests` 后加入：

```xml
<module>fibra-benchmarks</module>
```

默认 `mvn clean verify` 必须编译并打包 benchmark，防止内核 API 演进后基准代码静默腐化。JMH 测量不绑定 Maven 生命周期，仍只由显式 `java -jar fibra-benchmarks/target/fibra-benchmarks.jar` 或独立性能 CI 触发。`benchmarks` profile 删除，不保留双入口。

### 3.3 隔离不变量（红线）

- 任何生产模块都**不得**在 `<dependency>` 中引用 `fibra-benchmarks`；
- benchmark 继承根 `maven.deploy.skip/source.skip/javadoc.skip=true`，不得进入十个可发布制品清单；
- 可复现发布与仓库外消费脚本继续使用显式模块列表，benchmark 不进入列表，因此发布字节与消费者依赖图不变；
- `ReleaseArtifactBaselineTest` 必须把 benchmark 归类为默认 reactor 中的非发布验证模块，并校验没有生产模块依赖它。

### 3.4 enforcer 收敛

模块继承根 enforcer（`requireJavaVersion [21,22)`、`dependencyConvergence`、`requirePluginVersions`）。JMH 传递引入 `net.sf.jopt-simple:jopt-simple:5.0.4` 与 `org.apache.commons:commons-math3:3.6.1`，单版本无冲突；若报收敛冲突，在本模块 depMgmt 钉死这两个传递依赖。

## 4. 依赖（fibra-benchmarks/pom.xml）

- `com.sstlfsj:fibra-core`（compile，被测内核，传递带入 fibra-api + reactor + slf4j-api）；
- `org.openjdk.jmh:jmh-core`（compile，版本由根 POM 统一管理）；
- `org.openjdk.jmh:jmh-generator-annprocess`（仅注解处理期，放 `annotationProcessorPaths`，不进运行时 classpath）；
- `org.slf4j:slf4j-simple`（runtime，消除无绑定告警，配合 `simplelogger.properties` 设 `defaultLogLevel=off`）。

**不设** `<proc>none</proc>`（基准必须开启注解处理生成 `*_jmhType`/`BenchmarkList`）；**不用** Lombok（fixture 是一次性简单类，手写字段更直观）。

## 5. 基准类设计（全部基于核实过的真实 API）

统一注解基线：`@BenchmarkMode(Mode.AverageTime)` `@OutputTimeUnit(NANOSECONDS)` `@Warmup(iterations=5)` `@Measurement(iterations=8)` `@Fork(2)`；`@State(Scope.Benchmark)` 用 `FibraRuntime.create()` 建 root `Context`，`@TearDown` 调 `ctx.close()`。

引用的真实 API（出处）：`FibraRuntime.create()`（`runtime/FibraRuntime.java:11`）、`Context.provide/get/service/on/emit/bail/waterfall`（`fibra-api/Context.java`）、`Context.service` 默认方法返回 `BoundService`（`Context.java:61`）、`BoundService.invoke`（`BoundService.java:19`）、`ServiceKey.of`（`ServiceKey.java:13`）、`EventKey.of`（要求 listenerType 为接口，`event/EventKey.java:11`）、`Next.call()`（`event/Next.java`）、`Context.close` → `closeAsync().block()`（`Context.java:139-142`）。

### 5.1 BenchmarkFixtures（公共契约常量）

```java
interface Echo { int ping(); }
ServiceKey<Echo> ECHO = ServiceKey.of("bench/echo", Echo.class);

interface Ticker { void onTick(); }
EventKey<Ticker> TICK = EventKey.of("bench/tick", Ticker.class);

interface Step { Integer step(Integer in, Next<Integer> next); }
EventKey<Step> WF = EventKey.of("bench/wf", Step.class);

interface ResolveLoop { long run(int times); }
EventKey<ResolveLoop> RESOLVE = EventKey.of("bench/resolve", ResolveLoop.class);
```

### 5.2 ServiceResolutionBenchmark —— 跨线程往返 vs 同线程直调

意图：暴露 `LifecycleDispatcher.call` 对外部线程走 `Mono.fromCallable(...).subscribeOn(scheduler).block()`（`LifecycleDispatcher.java:26-28`），而同线程短路直调（`:23-24`）的差异。`ctx.get` → `waterfall(CoreEvents.GET)` → `EventBus.waterfall` → `lifecycle.call`（`DefaultContext.java:198-201`）。

- `getOutside`：外部线程 `ctx.get(ECHO)`，每 op 一次完整跨线程往返；
- `invokeOutside`：外部线程 `bound.invoke((ic, echo) -> echo.ping())`；
- `resolveInside`：`@OperationsPerInvocation(1000)`，通过一次 `ctx.bail(RESOLVE, l -> l.run(1000))` 进入 lifecycle 线程，listener 内循环 1000 次嵌套 `ctx.get(ECHO)`（已在 lifecycle 线程 → 走直调分支）；一次 bail 握手被摊销。
- 结论：`getOutside/invokeOutside − resolveInside ≈ 跨线程往返净开销`。
- DCE 防护：返回对象/基本值由 JMH 消费；`bail` 需 listener 返回非 false 才短路（`EventBus.isBailed`，`EventBus.java:207-209`），listener 返回非零 `long` 命中；只注册 1 个 RESOLVE hook，避免遍历副作用。

### 5.3 EventDispatchBenchmark —— hook 数量对 emit / waterfall 的影响

- `@Param({"1","8","64"}) int hooks`；`@Setup` 注册 `hooks` 个 `TICK`（`() -> counter++`）与 `WF`（`(in, next) -> next.call() + 1`）监听；
- `emit`：`ctx.emit(TICK, Ticker::onTick)`，顺序跑全部 hook（`EventBus.java:64-73`），经 `counter` 副作用返回；
- `waterfall`：`ctx.waterfall(WF, (l, next) -> l.step(0, next), () -> 0)`，递归穿透 hook 链（`EventBus.java:129-147`）；
- 如实包含每次 `resolve` 对非 `internal/` key 触发的一次 `emitInternal(CoreEvents.DISPATCH)`（`EventBus.java:150-156`），这是内核真实成本，不规避。

### 5.4 LifecycleDispatchBenchmark —— 纯调度往返基线

- `@Setup` 只建 `ctx`，不注册任何 hook；类内定义 `EventKey<Ticker> EMPTY = EventKey.of("bench/empty", Ticker.class)`；
- `roundTrip`：`ctx.emit(EMPTY, Ticker::onTick)`（`EMPTY` 无 hook），`lifecycle.call` 在外部线程执行空循环，隔离出单次 park/unpark + Reactor 包装的调度往返净开销，作为 5.2/5.3 的成本基线；
- README 注明该项含一次空 hook 的 `DISPATCH` 内部事件常量项（可忽略）。

### 5.5 附属文件

- `src/main/resources/simplelogger.properties`：`org.slf4j.simpleLogger.defaultLogLevel=off`，压制 setup 期内核绑定告警；基准代码内不出现任何 `Logger`；
- `README.md`：运行说明与各基准含义。

## 6. 运行

```bash
# 构建 uber jar（benchmark 已在默认 reactor）
mvn -pl fibra-benchmarks -am -DskipTests clean package

# 运行全部基准
java -jar fibra-benchmarks/target/fibra-benchmarks.jar

# 只跑某组 + JSON 输出
java -jar fibra-benchmarks/target/fibra-benchmarks.jar ServiceResolution -rf json -rff result.json
```

## 7. 实现清单

1. 根 `pom.xml`：默认 `<modules>` 加入 benchmark，删除 `benchmarks` profile，统一管理 JMH 版本与依赖；
2. `fibra-benchmarks/pom.xml`：parent、无版本依赖、compiler `annotationProcessorPaths`、shade（含 `AppendingTransformer`、`mainClass`、`outputFile=fibra-benchmarks.jar`、`createDependencyReducedPom=false`）；
3. `BenchmarkFixtures`（契约常量）；
4. 三个 Benchmark 类；
5. `simplelogger.properties` + `README.md`；
6. 隔离验证：`mvn clean verify` 必须包含 benchmark；`bash scripts/verify-reproducible-release.sh` 和 `bash scripts/verify-distribution.sh` 的发布/消费模块集保持不变；再执行 benchmark 冒烟运行确认 JMH 元数据有效。

## 8. 风险点

1. **发布门禁**：红线是任何生产模块都不得依赖 fibra-benchmarks，且不覆盖 `deploy.skip`；
2. **可复现构建**：发布脚本显式选择十个可发布制品；benchmark 进入默认 reactor 不改变其选择集或产物；
3. **enforcer 收敛**：JMH 传递依赖必要时在本模块 depMgmt 钉死；
4. **shade 元数据**：漏配 `AppendingTransformer` 会导致 `java -jar` 找不到任何基准；
5. **基准腐化**：默认 `mvn clean verify` 编译并打包 benchmark；实际性能数字仍不得放入普通 CI，避免共享 runner 噪声被误当成回归；
6. **测量真实性**：`resolveInside` 的 `@OperationsPerInvocation(1000)` 摊销一次 bail 握手，BATCH 需足够大让同线程直调主导；只注册 1 个 RESOLVE hook 规避 `isBailed` 遍历副作用。
