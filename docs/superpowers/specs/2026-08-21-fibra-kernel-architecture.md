# Fibra Cordis 内核架构契约

日期：2026-08-22
状态：实现基线，代码与测试不得弱化本文语义

## 1. 目标与真源

Fibra 是 DeepSeek Harness 内 Cordis 4.0.1 的 Java 21 等价内核。Java API 可以利用强类型、接口、注解和 Reactor 增强表达，但以下可观测行为不得改变：服务可见性、作用域隔离、调用者所有权、effect 收集与清理时序、Fibra 状态和完成边界、事件顺序及错误传播。

验收真源按优先级固定为：

1. DeepSeek Harness 提交 `141eb6fef83422698aef7a981029e843e8161534` 下的 `vendor/cordis`，版本 `4.0.1`。源码文件摘要见 `../references/2026-08-21-fibra-cordis-mapping.md`。
2. Cordis 提交 `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 下 `packages/core/tests` 的 12 组测试。它提供公开行为用例；若与第一项源码存在差异，以第一项为准并增加 Fibra 回归测试。

本文只约束 Cordis core。PF4J/JAR/ClassLoader、配置与托管收敛均不进入 `fibra-core`；`0.4.0` 当前运行时架构以 [Fibra Engine 架构](./2026-08-24-fibra-engine-architecture.md)为准。早期 PF4J 与配置 loader 文档只记录历史实现基线，不是当前宿主入口。

## 2. 技术与模块

- Maven 聚合父工程：`com.sstlfsj:fibra:${revision}`；`revision` 是唯一项目版本真源。根 POM不远程发布，十个发布模块由 Flatten Maven Plugin 生成不依赖根 parent 的自包含发布 POM。
- 内核边界固定为：`fibra-api`（稳定公开契约）、`fibra-core`（唯一内核运行时实现）、`fibra-parity-tests`（Cordis 逐项门禁与 API 冻结）。框架中立运行时依赖方向为 `fibra-engine -> fibra-loader-config -> fibra-loader-pf4j -> fibra-core + fibra-pf4j-api -> fibra-api`；`fibra-core` 不依赖 PF4J、配置解析器、Engine 或 Spring，运行时实现不得反向进入 API 模块。完整十制品发布边界以 [`docs/release.md`](../../release.md) 为准；example、parity 和 verification 不属于稳定公共 API。
- Java 21。
- 第三方依赖、内部模块和 Maven 插件的版本集中在父 POM `properties`，依赖版本通过 `dependencyManagement` 传递，子模块不得重复声明。显式例外：`fibra-spring-boot-autoconfigure` 在自身模块 POM 内导入 Spring Boot BOM 并管理 `spring-boot.version`；父 POM只管理内部 Spring 模块坐标，不导入 Spring BOM或声明 Spring依赖，以保持六个框架中立运行时制品 Spring-free。
- 运行时依赖：Reactor Core 3.8.6、SLF4J API 2.0.18。core 不绑定日志 provider。
- 内核验收依赖只存在于 `fibra-parity-tests`：JUnit 6.1.3、Reactor Test 3.8.6、Awaitility 4.3.0。装载适配的真实 JAR 测试位于 `fibra-loader-pf4j`。时间相关测试使用虚拟时间或显式闩锁，禁止 `Thread.sleep` 猜时序。

Reactor 负责 Publisher 协议、`Mono`/`Flux` 组合、单线程 `Scheduler` 与测试虚拟时间；Fibra 只实现 Cordis 特有的状态机、作用域服务表、事件策略和 effect 所有权。

## 3. 公共契约

### 3.1 异步资源

```java
@FunctionalInterface
public interface Disposable {
    Mono<Void> dispose();
}

public interface EffectHandle extends Disposable {
    Mono<EffectHandle> ready();
    boolean isDisposed();
    EffectMetadata metadata();
}
```

`dispose()` 幂等；重复调用返回同一个完成结果。`ready()` 在 effect source 正常结束后完成，source 失败时在已收集资源清理完成后传播原错误。

Context/Fibra 提供三类入口：

```java
EffectHandle effect(Supplier<? extends Disposable> source, String label);
EffectHandle effectMany(Iterable<? extends Disposable> source, String label);
EffectHandle effect(Publisher<? extends Disposable> source, String label);
```

同步多值使用 `effectSync(SyncEffect, label)`，避免 Java 中 `SyncEffect` 与 `Publisher` 单抽象方法产生 lambda 重载歧义。以上入口覆盖 Cordis 的同步单值、同步多值、异步单值和异步多值；异步单值是 0..1 Publisher，异步多值是 0..N Publisher。

### 3.2 服务

```java
ServiceKey<FooApi> FOO = ServiceKey.of("foo", FooApi.class);
ServiceRegistration<FooApi> registration = ctx.provide(FOO, implementation);
BoundService<FooApi> bound = ctx.service(FOO);
```

- 服务冲突和隔离以 `name + isolate token` 判定；`Class<T>` 只做类型校验，不能通过类名或字段名猜服务名。
- `get(key, strict)` 是命令式读取；`strict=true` 只返回 ACTIVE provider。
- 插件依赖必须写入 `PluginDescriptor.dependencies()`，依赖到位才激活，provider 身份变化触发 consumer 重载。
- `provide` 返回可等待的 `ServiceRegistration.dispose()`：先移除全局 binding，通知并等待所有受影响 Fibra 收敛，最后移除 provider 自己的激活快照。
- 每个 ACTIVE Fibra 保留依赖实现快照 `fibra.store`。服务访问沿当前 Fibra、父 Fibra 逐层解析并检查 isolate token，不能只查询全局表。

### 3.3 调用者 Context

Cordis 会把服务调用中的 `ctx` 绑定到调用者，因此服务方法注册的 effect、listener 和 nested plugin 必须归调用者 Fibra，而不是服务注册 Fibra。

Java 的规范入口是显式调用上下文：

```java
ctx.service(FOO).invoke((invocation, service) ->
    service.execute(invocation, request)
);
```

`InvocationContext` 暴露 caller `Context`，并提供 service、effect、plugin 和 logger 便捷入口；事件能力通过 caller `Context` 使用。该对象可被异步 Publisher 显式捕获。JDK 动态代理可以作为纯同步接口的便捷层，但不承担规范语义，也不宣称能透明传播任意异步链。

Cordis accessor、mixin 与 association 在 Java 中使用 `PropertyKey<R,T>`、`PropertyAccessor<R,T>` 和 `Associated<R>`。属性注册归当前 Fibra effect 所有；关联读取始终使用创建 `Associated` 的 caller Context 解析服务。Java 不动态改变对象类型，但读写能力、调用方作用域和卸载边界不能退化。

### 3.4 插件与 Fibra

```java
@FunctionalInterface
public interface Plugin<C> {
    Publisher<? extends Disposable> apply(Context context, C config);
}

Fibra fibra = ctx.plugin(descriptor, plugin, config);
Mono<Fibra> ready = fibra.ready();
Mono<Void> disposed = fibra.dispose();
```

`PluginDescriptor<C>` 明确包含 name、dependencies、provide、intercept 与 config validator。运行时按插件入口对象身份分组，同一插件定义可有多个 Fibra；最后一个 Fibra 移除后才删除 runtime。`Context.registry()` 提供 `size`、`has`、Fibra 快照和可等待的批量 `remove`。类插件按 `PluginFactory` 身份分组，不能按每次新建的 adapter lambda 分组。

类插件使用明确的 `PluginFactory<C>` 构造器引用，不猜测构造函数。注解适配层只负责把 `@Inject`/插件元数据编译成同一个 descriptor，不建立第二套生命周期。

Fibra 状态固定为：`PENDING`、`LOADING`、`ACTIVE`、`FAILED`、`UNLOADING`、`DISPOSED`。

- root uid 为 0；插件 uid 单调递增；dispose 后 uid 为空。
- epoch 是全部依赖 provider uid 的有序指纹；任一依赖缺失时使用内部 `INACTIVE` epoch，公开状态在收敛后为 `PENDING`。
- 同一 Fibra 任何时刻只有一个 reload 或 unload 在途。目标 epoch 在操作期间变化时，只记录新目标，当前操作完成后再反向收敛。
- reload 和 unload 开始前各让出一个 lifecycle tick，保持 Cordis 再入边界。
- startup/config 错误进入 FAILED，`await()`/`ready()` 传播原异常；其他 Fibra 不受影响。两者只等待当前 reload/unload 收敛且语义相同；缺少依赖并稳定在 `PENDING` 时正常完成，不表示已经 `ACTIVE`，也不等待未来 provider。
- `update` 先经过 `internal/update` waterfall；`restart` 只按当前配置重启，两者不可合并。
- root 的 dispose 等价于 restart，不进入 DISPOSED；`Context.close()` 是 Java 增强，用于最终关闭 lifecycle Scheduler。

## 4. Effect 精确语义

### 4.1 两层清理规则

- 同一个 effect 内：按收集逆序严格串行 dispose；前一个异步完成后才执行下一个。
- Fibra 顶层 effects：先清空所有权列表，再并发启动每个顶层 effect 的 dispose，并等待全部完成。
- Fibra unload 在每个顶层 effect 边界独立 catch/log，某个 effect 失败不阻止兄弟 effect。
- 手动 `EffectHandle.dispose()` 不吞错误；局部 disposer 失败向调用者传播，并按 Cordis 行为截断该 effect 尚未执行的局部链。
- 嵌套 effect 被父 effect 收集时，从 Fibra 顶层列表移除，metadata 进入父 children；因此只清理一次且孩子先于父。

### 4.2 异步多值取消

内核订阅 effect Publisher 后一次只 `request(1)`：

1. 每个元素必须是非空 `Disposable`，收到后加入当前 effect。
2. 未 dispose 时再请求一个。
3. dispose 只设置停止标志，不立即取消已经在途的 request。
4. 在途元素到达后仍收集；随后取消 subscription、不再 request，并开始逆序清理。
5. 若 source error，先清理已收集项，再让 `ready()` 传播 source error。

这对应 Cordis 在每次 `iter.next()` 之前检查 epoch 的行为，必须分别保留：dispose 发生在第一次 await 中时仍接收第一项；dispose 发生在第一项后、第二次 request 已在途时仍接收第二项。

## 5. 生命周期串行化

每个 root Context 拥有一个 Reactor 单线程 Scheduler；整棵 Context 树共享。以下状态只在该 Scheduler 上读写：Fibra state/epoch/inertia/store、全局 service bindings、plugin runtimes、event hooks、effect 所有权列表。

同步公共方法从外部线程调用时把操作投递到 lifecycle Scheduler 并等待结果；若已经在 lifecycle 线程则内联，避免自死锁。异步完成信号可来自任意线程，但所有状态决策必须 `publishOn(lifecycleScheduler)` 后执行。

插件同步段以及同步 `emit`/`bail`/`waterfall` listener 运行在 lifecycle 线程，必须非阻塞；耗时工作返回 Publisher。`BoundService.invoke` 的服务解析经过 lifecycle 线程，但用户 invocation 在原调用线程执行；`parallel`/`serial` 的用户 Publisher 按订阅链决定线程。core 不修改 Reactor 全局 Hooks。

## 6. Context 与隔离

- `extend(metadata)` 创建子 Context，元数据按父链查找，父对象不变。
- `isolate(key)` 为服务名安装新 token；传同一 label 的多个 Context 共享作用域。
- `intercept(key, config)` 建立分层覆盖链，插件 descriptor 只消费声明过的 intercept。
- 子 Context 共享 root 的 service/event/plugin registry 和 lifecycle Scheduler，但保留自己的 parent、Fibra、isolate/intercept/metadata 视图。
- 同一服务名存在绑定期间只能对应同一个 `Class<?>`；最后一个绑定完成撤销后必须释放该类型声明，使动态插件 ClassLoader 可回收，并允许新版本以新的类身份重新注册。

## 7. 事件

全部事件模式共享一个按 EventKey 存储的 hook 表。`on` 返回归当前 Fibra 所有的 disposer；`once` 在调用用户 callback 前先注销。

- `emit`：同步顺序调用 `Consumer` listener；同步异常立即传播。异步 listener 必须使用 `parallel` 或 `serial`，不能把未订阅 Publisher 交给 `emit`。
- `parallel`：并发启动全部 listener，all-settled 后把所有错误聚合传播。
- `serial`：按注册顺序等待，遇首个 bail 值停止。
- `bail`：同步顺序执行，遇首个非 null/false 值停止，异常传播。
- `waterfall`：用同一组 hooks 从外到内包装 `next`，不调用 `next` 即 veto。
- 支持 prepend、global、dispatch thisArg/filter。
- 非 `internal/*` 事件分派前触发 `internal/dispatch`。

内部事件不得删减：`internal/plugin`、`internal/status`、`internal/service`、`internal/update`、`internal/get`、`internal/set`、`internal/listener`、`internal/dispatch`。

## 8. LoggerService

SLF4J 是最终日志 backend，不替代 Cordis LoggerService 的可观测语义。LoggerService 必须保留：

- 固定对象的环形 buffer 与可动态调整容量；
- exporter 注册、按身份精确注销和级别过滤；
- 显式名称、intercept 名、Fibra 名；
- 服务调用时使用最内层服务名，嵌套调用返回后恢复外层名称；
- 调用者 `InvocationContext` 对名称和 effect 所有权的覆盖。

## 9. 验收门槛

- Cordis 12 组 core spec 的 71 个 `it` 均有一个独立 Java `@Test`，类名与原 spec 一一对应；不得合并两个原始场景。
- `docs/api/fibra-api-public-signatures.txt` 冻结 `fibra-api` 全部 public/protected 签名；`docs/api/fibra-core-public-signatures.txt` 冻结 `fibra-core` 的 `com.sstlfsj.fibra.runtime` 受支持入口，明确排除 `com.sstlfsj.fibra.internal` 实现空间。门禁同时检查公开类型集合与 JDK `javap -protected` 输出。
- 所有物理不可达的 JS 语法必须有 Java API 等价测试，不能整组排除 `associate`、`invoke`、`shadow` 或 `logger`。
- 单元测试覆盖 effect 四态、两层错误边界、revoke 完成边界、isolate 共享 label、事件五模式、Fibra 三组 inertia 翻转。
- 端到端测试覆盖 provider → consumer 激活 → 服务替换/撤销 → consumer unload/reload → parent dispose。
- `mvn verify` 是交付门槛；禁止使用跳过测试、固定 sleep 或 fire-and-forget 来隐藏未完成生命周期。
- 远程发布包含 [`docs/release.md`](../../release.md) 定义的九个运行时制品和一个插件开发工具制品；每个模块必须同时生成主 JAR、sources JAR、Javadoc JAR 和自包含 POM。根、examples、host、parity、benchmarks 与 verification 必须跳过 deploy。
- Java 21、Maven 3.9.9、依赖收敛和 Maven 插件显式版本由 Enforcer 强制；十个发布制品的两次干净构建必须逐字节一致。
