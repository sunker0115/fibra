# fibra-benchmarks 内核性能基准 Implementation Plan

> 历史实施计划，记录 `0.3.1` 首次引入 benchmark 时的 profile 方案，不再作为当前执行入口。`0.4.0-SNAPSHOT` 起的现行结构与命令只以[当前设计](../specs/2026-08-23-fibra-benchmarks-design.md)和 `fibra-benchmarks/README.md` 为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 JMH 1.37 为 `fibra-core` 内核热路径（服务解析、事件分发、lifecycle 调度往返）建立可信性能基线，且严格隔离不污染生产 `artifact` 与可复现构建。

**Architecture:** 新增 `fibra-benchmarks` 模块，通过根 pom 的 `benchmarks` profile 门禁注册（默认构建不进 reactor）；JMH 版本与 depMgmt 只写在本模块，根 pom 仅加一段 profile；用 `maven-shade-plugin` 打可运行 uber jar。

**Tech Stack:** Java 21、Maven、JMH 1.37（jmh-core + jmh-generator-annprocess）、maven-compiler-plugin（annotationProcessorPaths）、maven-shade-plugin、slf4j-simple。

**设计真源:** [fibra-benchmarks 内核性能基准设计](../specs/2026-08-23-fibra-benchmarks-design.md)

---

## 已核实的真实 API（供各 Task 直接引用）

- `ServiceKey.of(String, Class<T>)` → `ServiceKey<T>`（`ServiceKey.java:13`，record，`name`/`type` 校验非空）
- `EventKey.of(String, Class<L>)` → `EventKey<L>`（`EventKey.java:16`，listenerType 必须为接口）
- `Next<R>.call()` → `R`（`Next.java`，`@FunctionalInterface`）
- `FibraRuntime.create()` → `Context`（`FibraRuntime.java:11`）
- `Context.provide(ServiceKey<T>, T)` → `ServiceRegistration<T>`；`Context.get(ServiceKey<T>)` → `T`；`Context.service(ServiceKey<T>)` → `BoundService<T>`（默认方法）；`Context.on/emit/bail/waterfall`（见 `Context.java`）
- `BoundService.invoke(BiFunction<InvocationContext,T,R>)` → `R`（`BoundService.java:19`）
- `Context.close()` → `closeAsync().block()`（`Context.java:139-142`）
- `bail` 的短路语义：listener 返回非 `false` 即短路（`EventBus.isBailed`，返回 `value != null && !Boolean.FALSE.equals(value)`）

---

### Task 1: 根 pom 加 benchmarks profile

**Files:**
- Modify: `pom.xml`（在 `</project>` 前新增 `<profiles>` 段；不动 `<modules>`、不加 JMH 版本属性）

- [ ] **Step 1: 新增 profile 段**

在 `pom.xml` 末尾的 `</project>` 之前，插入：

```xml
  <profiles>
    <profile>
      <id>benchmarks</id>
      <modules>
        <module>fibra-benchmarks</module>
      </modules>
    </profile>
  </profiles>
```

- [ ] **Step 2: 验证默认构建模块集不变**

Run: `mvn -q help:evaluate -Dexpression=project.modules -DforceStdout | tr ',' '\n' | grep -c fibra-benchmarks`
Expected: 无输出（默认 profile 下 `fibra-benchmarks` 不在模块列表，返回非 0 或空）

Run: `mvn -q -Pbenchmarks help:evaluate -Dexpression=project.modules -DforceStdout | tr ',' '\n' | grep fibra-benchmarks`
Expected: `fibra-benchmarks`

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: 根 pom 新增 benchmarks profile 门禁注册 fibra-benchmarks"
```

---

### Task 2: fibra-benchmarks 模块 pom

**Files:**
- Create: `fibra-benchmarks/pom.xml`

- [ ] **Step 1: 写模块 pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.sstlfsj</groupId>
    <artifactId>fibra</artifactId>
    <version>${revision}</version>
  </parent>

  <artifactId>fibra-benchmarks</artifactId>
  <description>fibra-core 内核热路径 JMH 性能基准（隔离，不发布，不进可复现构建集）。</description>

  <properties>
    <jmh.version>1.37</jmh.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.sstlfsj</groupId>
      <artifactId>fibra-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.openjdk.jmh</groupId>
      <artifactId>jmh-core</artifactId>
      <version>${jmh.version}</version>
    </dependency>
    <dependency>
      <groupId>org.openjdk.jmh</groupId>
      <artifactId>jmh-generator-annprocess</artifactId>
      <version>${jmh.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>${slf4j.version}</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.openjdk.jmh</groupId>
              <artifactId>jmh-generator-annprocess</artifactId>
              <version>${jmh.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <outputFile>${project.build.directory}/fibra-benchmarks.jar</outputFile>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>org.openjdk.jmh.Main</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
                  <resource>META-INF/BenchmarkList</resource>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
                  <resource>META-INF/CompilerHints</resource>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

注：`jmh-generator-annprocess` 既在 `annotationProcessorPaths` 声明（注解处理期），也以 `provided` 依赖声明（满足 enforcer 依赖收敛且不进运行时 classpath）。`slf4j.version` 由根 depMgmt 提供。

- [ ] **Step 2: 验证可编译（尚无源码，仅验证 pom 解析）**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests validate`
Expected: BUILD SUCCESS（pom 无语法/收敛错误）

- [ ] **Step 3: Commit**

```bash
git add fibra-benchmarks/pom.xml
git commit -m "build: fibra-benchmarks 模块 pom（JMH 1.37 + shade uber jar）"
```

---

### Task 3: BenchmarkFixtures 契约常量

**Files:**
- Create: `fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/BenchmarkFixtures.java`

- [ ] **Step 1: 写契约常量类**

```java
package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.Next;

/** 基准共用的服务与事件契约常量。 */
public final class BenchmarkFixtures {

    public interface Echo {
        int ping();
    }

    public static final ServiceKey<Echo> ECHO = ServiceKey.of("bench/echo", Echo.class);

    public interface Ticker {
        void onTick();
    }

    public static final EventKey<Ticker> TICK = EventKey.of("bench/tick", Ticker.class);

    public interface Step {
        Integer step(Integer in, Next<Integer> next);
    }

    public static final EventKey<Step> WF = EventKey.of("bench/wf", Step.class);

    public interface ResolveLoop {
        long run(int times);
    }

    public static final EventKey<ResolveLoop> RESOLVE = EventKey.of("bench/resolve", ResolveLoop.class);

    private BenchmarkFixtures() {
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/BenchmarkFixtures.java
git commit -m "test: 基准契约常量 BenchmarkFixtures"
```

---

### Task 4: ServiceResolutionBenchmark

**Files:**
- Create: `fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/ServiceResolutionBenchmark.java`

- [ ] **Step 1: 写 benchmark 类**

```java
package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.BoundService;
import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class ServiceResolutionBenchmark {

    private static final int BATCH = 1000;

    private Context ctx;
    private BoundService<Echo> bound;

    @Setup
    public void setup() {
        ctx = FibraRuntime.create();
        ctx.provide(ECHO, () -> 42);
        bound = ctx.service(ECHO);
        ctx.on(RESOLVE, times -> {
            long acc = 0;
            for (int i = 0; i < times; i++) {
                acc += System.identityHashCode(ctx.get(ECHO));
            }
            return acc;
        });
    }

    @TearDown
    public void tearDown() {
        ctx.close();
    }

    @Benchmark
    public Echo getOutside() {
        return ctx.get(ECHO);
    }

    @Benchmark
    public int invokeOutside() {
        return bound.invoke((ic, echo) -> echo.ping());
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public long resolveInside() {
        return ctx.bail(RESOLVE, l -> l.run(BATCH));
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/ServiceResolutionBenchmark.java
git commit -m "test: 服务解析基准（跨线程往返 vs 同线程直调）"
```

---

### Task 5: EventDispatchBenchmark

**Files:**
- Create: `fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/EventDispatchBenchmark.java`

- [ ] **Step 1: 写 benchmark 类**

```java
package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class EventDispatchBenchmark {

    @Param({"1", "8", "64"})
    private int hooks;

    private Context ctx;
    private long counter;

    @Setup
    public void setup() {
        ctx = FibraRuntime.create();
        for (int i = 0; i < hooks; i++) {
            ctx.on(TICK, () -> counter++);
            ctx.on(WF, (in, next) -> next.call() + 1);
        }
    }

    @TearDown
    public void tearDown() {
        ctx.close();
    }

    @Benchmark
    public long emit() {
        counter = 0;
        ctx.emit(TICK, Ticker::onTick);
        return counter;
    }

    @Benchmark
    public int waterfall() {
        return ctx.waterfall(WF, (l, next) -> l.step(0, next), () -> 0);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/EventDispatchBenchmark.java
git commit -m "test: 事件分发基准（1/8/64 hook 的 emit/waterfall）"
```

---

### Task 6: LifecycleDispatchBenchmark

**Files:**
- Create: `fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/LifecycleDispatchBenchmark.java`

- [ ] **Step 1: 写 benchmark 类**

```java
package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.Ticker;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class LifecycleDispatchBenchmark {

    private static final EventKey<Ticker> EMPTY = EventKey.of("bench/empty", Ticker.class);

    private Context ctx;

    @Setup
    public void setup() {
        ctx = FibraRuntime.create();
    }

    @TearDown
    public void tearDown() {
        ctx.close();
    }

    @Benchmark
    public void roundTrip() {
        ctx.emit(EMPTY, Ticker::onTick);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add fibra-benchmarks/src/main/java/com/sstlfsj/fibra/benchmarks/LifecycleDispatchBenchmark.java
git commit -m "test: lifecycle 调度往返基准"
```

---

### Task 7: simplelogger.properties 与 README

**Files:**
- Create: `fibra-benchmarks/src/main/resources/simplelogger.properties`
- Create: `fibra-benchmarks/README.md`

- [ ] **Step 1: 写日志压制配置**

`fibra-benchmarks/src/main/resources/simplelogger.properties`：

```properties
org.slf4j.simpleLogger.defaultLogLevel=off
```

- [ ] **Step 2: 写 README**

`fibra-benchmarks/README.md`：

```markdown
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
```

- [ ] **Step 3: 打包并跑一个短基准冒烟**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests clean package`
Expected: BUILD SUCCESS，产出 `fibra-benchmarks/target/fibra-benchmarks.jar`

Run: `java -jar fibra-benchmarks/target/fibra-benchmarks.jar LifecycleDispatch -f 1 -wi 1 -i 2`
Expected: 输出 `LifecycleDispatchBenchmark.roundTrip` 结果，无异常

- [ ] **Step 4: Commit**

```bash
git add fibra-benchmarks/src/main/resources/simplelogger.properties fibra-benchmarks/README.md
git commit -m "docs: 基准运行说明与日志压制"
```

---

### Task 8: 隔离门禁验证

**Files:** 无新建（验证 + 视结果修正）

- [ ] **Step 1: 默认构建不含基准**

Run: `mvn clean verify -DskipTests`
Expected: BUILD SUCCESS，且 `fibra-benchmarks` 未参与编译（reactor 列表无该模块）

- [ ] **Step 2: 可复现构建门禁不受影响**

Run: `bash scripts/verify-reproducible-release.sh`
Expected: 脚本通过（5 个生产 `artifact` 逐字节比对一致）

- [ ] **Step 3: 仓库外消费门禁不受影响**

Run: `bash scripts/verify-distribution.sh`
Expected: 脚本通过（临时仓库恰好 5 个生产 `artifact`）

- [ ] **Step 4: 基准可独立编译打包**

Run: `mvn -Pbenchmarks -pl fibra-benchmarks -am -DskipTests clean package`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit（若前几步需修正则先修）**

```bash
git add -A
git commit -m "chore: fibra-benchmarks 隔离门禁验证"
```

---

## Self-Review

**Spec coverage:**
- spec §3 模块与隔离（profile 门禁、不声明 deploy.skip、红线）→ Task 1、2、8；
- spec §4 依赖（jmh-core / annprocess / slf4j-simple / 不用 lombok / 不设 proc:none）→ Task 2；
- spec §5.1 BenchmarkFixtures → Task 3；
- spec §5.2 ServiceResolution → Task 4；
- spec §5.3 EventDispatch → Task 5；
- spec §5.4 LifecycleDispatch → Task 6；
- spec §5.5 附属文件 → Task 7；
- spec §6 运行 / §7 清单 / §8 风险（shade 元数据、隔离验证）→ Task 2（AppendingTransformer）、Task 8。

**Placeholder scan:** 无 TBD/TODO；每处改动均给完整代码；每个运行命令给预期结果。

**Type consistency:** `ECHO`/`TICK`/`WF`/`RESOLVE` 常量名与三组 benchmark 引用一致；`ServiceKey.of`/`EventKey.of`/`Next.call()`/`ctx.bail/waterfall/emit/get/service` 均与已核实签名一致；`EventKey` 的 listenerType 均为接口（Echo/Ticker/Step/ResolveLoop 均声明为 interface）。
