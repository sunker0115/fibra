# Fibra 公共 API 使用手册

本文对应 `com.sstlfsj:fibra-api:${revision}`、`com.sstlfsj:fibra-core:${revision}`、`com.sstlfsj:fibra-pf4j-api:${revision}`、`com.sstlfsj:fibra-loader-pf4j:${revision}` 与 `com.sstlfsj:fibra-loader-config:${revision}` 五个中立内核/loader 制品的冻结公开契约。业务应用通常依赖 `fibra-core`；需要直接管理标准插件包时依赖 `fibra-loader-pf4j`；需要 YAML/JSON 动态组合时只需依赖 `fibra-loader-config`，后者会传递引入前两层。

在 Spring Boot 宿主中还可使用可选适配制品 `com.sstlfsj:fibra-spring-boot-starter:${revision}`，它按 `fibra.*` 属性自动装配 Fibra 装配与生命周期，公开签名见本目录 `fibra-spring-boot-starter-public-signatures.txt`。Spring 只存在于该可选制品内，不进内核。

## 1. 创建与关闭

运行时只有一个创建入口：

```java
import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.runtime.FibraRuntime;

try (Context root = FibraRuntime.create()) {
    // 注册服务、插件、事件和 effect
}
```

`Context.close()` 阻塞到整棵 Context 树清理完成；响应式链使用 `closeAsync()`。子 Context 调用关闭等价于关闭 root。`root()` 返回根 Context，`fibra()` 返回当前资源所有者。

`extend(Map)` 创建只读继承的元数据视图，`metadata(name)` 沿父链读取。`isolate(ServiceKey)` 创建独立服务作用域；`isolate(key, label)` 使用对象身份共享作用域。配置层使用 `isolate(String)`/`isolate(String, label)` 只声明名称与 token，不向服务类型表写入 `Object.class`。`intercept` 创建分层调用配置，原 Context 不变。

## 2. 服务

服务身份由名称和 isolate token 决定，`Class<T>` 只负责类型约束：

```java
interface Greeting {
    String greet(String name);
}

ServiceKey<Greeting> GREETING = ServiceKey.of("greeting", Greeting.class);
ServiceRegistration<Greeting> registration =
    root.provide(GREETING, name -> "你好，" + name);

Greeting direct = root.get(GREETING);
Greeting snapshot = root.get(GREETING, false);
root.set(GREETING, name -> "您好，" + name);
```

`get(key)` 只返回 ACTIVE provider，`get(key, false)` 允许读取非 ACTIVE provider。`BoundService.value()` 每次调用都会重新解析，但返回的原始 provider 不会随 reload 自动更新，不能跨调用缓存。需要调用方所有权或动态服务调用时必须使用 `BoundService.invoke`：

```java
String text = root.service(GREETING).invoke((invocation, service) -> {
    invocation.logger().debug("调用 greeting");
    invocation.effect(() -> Disposables.noop(), "request-resource");
    return service.greet("Fibra");
});
```

`InvocationContext` 明确保存 caller，且提供 `logger`、`service`、`associate`、`effect` 和 `plugin`。由这些入口创建的资源归 caller Fibra，不能归服务 provider。`Service<T>` 是构造即注册的类服务基类；`registrationContext()` 和 `registration()` 供子类访问。

`ServiceRegistration.dispose()` 的完成边界是：删除全局 binding，通知并等待依赖 Fibra 卸载，最后删除 provider 激活快照。重复 dispose 幂等。

## 3. 插件与 Fibra

```java
record Config(String value) {
    Config validated() {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value");
        return this;
    }
}

ServiceKey<String> RESULT = ServiceKey.of("result", String.class);
Config config = new Config("v1");
Config nextConfig = new Config("v2");

PluginDescriptor<Config> descriptor = PluginDescriptor.<Config>builder("consumer")
    .require(GREETING)
    .provide(RESULT)
    .validator(Config::validated)
    .build();

Fibra fibra = root.plugin(descriptor, (ctx, cfg) -> {
    ctx.provide(RESULT, cfg.value());
    return Mono.just(Disposables.noop());
}, config);

fibra.ready().block();
fibra.update(nextConfig).block();
fibra.restart().block();
fibra.dispose().block();
```

`Plugin<C>` 返回 `Publisher<? extends Disposable>`：Publisher 完成才算启动完成，0/1/N 个元素均可，每个元素都是卸载动作。类插件使用 `PluginFactory<C,P>` 与 `PluginInitializer<P>`；initializer 可异步完成并发出 0/1/N 个 `Disposable`，任何非空非 `Disposable` 元素都是错误。`PluginDescriptor.Builder.inject(type)` 把 `@InjectService` 字段/类依赖编译进 descriptor；方法注解由同一 Fibra 生命周期创建依赖子插件。

状态固定为 `PENDING`、`LOADING`、`ACTIVE`、`FAILED`、`UNLOADING`、`DISPOSED`。`await()`/`ready()` 语义相同：等待当前 inertia 收敛并传播 config/startup 原异常；缺少依赖并稳定在 `PENDING` 时正常完成，不表示已经 `ACTIVE`，也不等待未来 provider。宿主启动门禁必须在等待后显式检查 `state()`。`update` 先同步校验，再经过 `CoreEvents.UPDATE` waterfall；只有执行默认 `next` 才提交 config 和 restart。root `dispose()` 等价于 restart，root uid 永远为 0；普通 Fibra dispose 后 uid 为 null。

`PluginRegistry` 按插件入口对象身份分组，提供 `size`、`has`、`keys`、`values`、`entries`、全部/按入口 `fibras` 快照和可等待的 `remove`。同一入口可对应多个 Fibra。

`PluginDescriptor.Builder.require(String)` 与 `Fibra.require(String)` 供配置装载器声明只有服务名、尚不知道插件 ClassLoader 类型的依赖。类型化插件代码仍应优先使用 `ServiceKey<?>`。同一 descriptor 同时声明同名 typed 与 name-only 依赖会在构建时拒绝。

## 4. Effect 与清理

```java
List<String> sequence = new ArrayList<>();
EffectHandle one = root.effect(
    () -> Disposables.from(() -> sequence.add("socket")), "socket");

EffectHandle many = root.effectSync(sink -> {
    sink.add(Disposables.from(() -> sequence.add("second")));
    sink.add(Disposables.from(() -> sequence.add("first")));
}, "pair");
```

- `effect(Supplier)`：同步单值；注册异常在调用点直接抛出。
- `effectSync(SyncEffect)`：同步 0/N 值；用于替代 JavaScript generator，避免与 `Publisher` lambda 重载歧义。
- `effectMany(Iterable)`：已有同步集合。
- `effect(Publisher)`：异步 0/N 值，一次只 request 1；dispose 会等待已经在途的元素。

同一 effect 内严格逆序串行清理；Fibra 顶层 effects 并发清理。手动 `dispose()` 传播清理错误并截断当前局部链；Fibra unload 在每个顶层边界记录错误并继续兄弟清理。`ready()` 在 source 完成时成功，source 失败时先清理已收集资源再传播原异常。`EffectMetadata`/`effects()` 暴露带 label 的嵌套所有权树。

`Disposable` 的唯一方法是 `Mono<Void> dispose()`；`Disposables.noop()` 和 `Disposables.from(Runnable)` 用于同步适配。

## 5. 事件

先定义强类型 listener 契约：

```java
@FunctionalInterface
interface Changed {
    void onChanged(String value);
}

EventKey<Changed> CHANGED = EventKey.of("app/changed", Changed.class);
Disposable hook = root.on(CHANGED, value -> root.logger().info(value));
root.emit(CHANGED, listener -> listener.onChanged("v1"));
hook.dispose().block();
```

- `on`：持续监听；`once` 在用户 callback 前先注销。
- `emit`：同步顺序执行，首个异常立即传播。
- `parallel`：并发、all-settled，错误用 `AggregateEventException.causes()` 聚合。
- `serial`：串行等待，首个非 null/false 值 bail。
- `bail`：同步 bail。
- `waterfall`：外到内包装 `Next.call()`；不调用 next 即 veto。

`EventOptions` 提供 `defaults`、`prepend`、`global`、`prependGlobal`。`EventTarget.accepts(listenerContext)` 做目标过滤。`CoreEvents` 固定公开 `PLUGIN`、`STATUS`、`SERVICE`、`UPDATE`、`GET`、`SET`、`LISTENER`、`DISPATCH` 八个内部扩展点；内部事件与普通事件共享 hook 表。

## 6. 关联属性与注入

Java 不动态修改对象原型，使用类型安全的 `PropertyKey<R,T>`、`PropertyAccessor<R,T>` 和 `Associated<R>`：

```java
final class Session {
    private int answer;
    int answer() { return answer; }
    void answer(int value) { answer = value; }
}

PropertyKey<Session, Integer> ANSWER =
    PropertyKey.of("session.answer", Session.class, Integer.class);

root.accessor(ANSWER, new PropertyAccessor<>() {
    public Integer get(Context ctx, Session session) { return session.answer(); }
    public void set(Context ctx, Session session, Integer value) { session.answer(value); }
});

Associated<Session> session = root.associate(new Session());
session.set(ANSWER, 42);
int answer = session.get(ANSWER);
```

`PropertyAccessor.readOnly` 创建只读 accessor。accessor 注册归当前 Fibra effect，卸载后撤销。`Associated.caller()` 永远是创建关联视图的 Context。

`@InjectService` 可用于可变实例字段、类和零参数实例方法；字段在类插件初始化前注入，方法被编译为依赖子 Fibra。字段类型可推断，类/方法注解必须填写 `type`。

## 7. 日志

`Context.logger()` 使用 Fibra 名；`logger(name)` 使用显式名；`LoggerIntercept` 可在调用者 Context 覆盖服务派生名称/级别。`FibraLogger` 提供 `error/info/warn/debug`。

`LoggerService.buffer()` 返回固定对象的时间顺序环形缓冲区，`bufferSize(int)` 可动态裁剪。`exporter(LogExporter)` 返回归当前 Fibra 所有的 disposer；`LogExporter.to` 可适配 Consumer 并指定最低级别。`LogMessage` 包含 sequence、timestamp、name、level、arguments 和弱引用 Fibra。最终 backend 走 SLF4J，core 不绑定 provider。

## 8. PF4J 标准插件包

插件候选是 ZIP，安装态是 `plugins/<plugin-id>/` 目录；目录根只有 `plugin.properties` 和 `lib/`，主 JAR 固定为 `lib/<plugin-id>-<plugin-version>.jar`。身份、版本和依赖只读取 `plugin.properties`，不读取 JAR Manifest。允许键只有 `plugin.id`、`plugin.version`、`plugin.dependencies` 以及可选的 `plugin.description/provider/license`；`plugin.class`、`plugin.requires` 和其他键一律拒绝。

插件工程依赖 `fibra-pf4j-api` 与 PF4J，作用域必须是 `provided`。Fibra API/Core、PF4J、Reactive Streams、Reactor 和 SLF4J 由宿主父 ClassLoader 提供，不得复制进 `lib/`。普通私有三方库使用 runtime scope 放入当前插件的 `lib/`；跨插件契约必须来自宿主公共 API 或独立 contract-only 插件，不能复制到 provider/consumer 各自的私有 JAR。

```java
@Extension
public final class GreetingEntrypoint implements FibraPluginEntrypoint<GreetingConfig> {
    public Class<GreetingConfig> configType() {
        return GreetingConfig.class;
    }

    public PluginDescriptor<GreetingConfig> descriptor(String entryId) {
        return PluginDescriptor.<GreetingConfig>builder(entryId).provide(GREETING).build();
    }

    public Plugin<GreetingConfig> create(String entryId) {
        return (context, config) -> Mono.just(
            context.provide(GREETING, name -> config.prefix() + name));
    }
}
```

无配置入口可以实现 `VoidFibraPluginEntrypoint`，只需实现 `create(entryId)`。主 JAR 自身没有 `META-INF/extensions.idx` 或索引为空时是 contract-only：可以被其他插件依赖，但不能调用 `configType` 或 `mount`；executable 的自身索引必须恰好包含一个 `FibraPluginEntrypoint`。`pluginId` 是制品身份，`entryId` 是运行实例身份，一个 executable 可以创建多个 entry。

首次启动只扫描已安装目录；安装和更新候选统一走一次显式批量 API：

```java
try (var root = FibraRuntime.create();
     var artifacts = new FibraPluginLoader(root, Path.of("plugins"))) {
    artifacts.loadArtifacts();
    artifacts.applyArtifacts(List.of(
        Path.of("incoming/greeting-contract-2.0.0.zip"),
        Path.of("incoming/greeting-provider-2.0.0.zip"),
        Path.of("incoming/greeting-consumer-2.0.0.zip")));
    artifacts.mount(PluginInstanceSpec.builder("greeting-one", "greeting-plugin")
        .parentContext(root)
        .config(new GreetingConfig("你好，"))
        .build());
}
```

`applyArtifacts` 的候选先全部解压到同文件系统预检区，完成格式、摘要、必需/optional SemVer 范围、循环、入口和 prospective 全图校验后，才允许拆除旧运行态。批次中的 candidate ID 加上旧图/新图的传递依赖方构成受影响闭包；停止为 dependent-first，装载与启动为 dependency-first。任一步失败都会恢复旧目录、PF4J 状态和全部 entry；持久 journal 让进程在 `INSTALLING/APPLYING/COMMITTED` 中崩溃后仍能确定恢复。

`configType(pluginId)` 返回插件 ClassLoader 中的配置类型，但读取完成后不会把原本未启动的 artifact 留在 `STARTED`。`mount/update/unmount` 只操作 entry；`stopArtifact/unloadArtifact` 操作制品及其全部受影响 entry。PF4J 可能缓存扩展对象，Fibra loader 不使用该对象缓存，而是为每次 mount、update 和事务恢复创建全新入口。

`config(Object)` 只能传 `null` 或父 ClassLoader 定义的共享配置对象。配置类型定义在插件包内时，必须使用 `configFactory(type -> ...)`，在每次调用中根据参数 `type` 创建当前 ClassLoader 的对象；动态配置更新使用 `updateWithFactory`，普通共享配置更新使用 `update`。生产宿主通常交给下一节的 `fibra-loader-config` 从不可变 YAML/JSON 值重新物化。事务快照保存配置工厂而不是旧 typed config，因此升级或降级后按新 `configType` 重建；schema 不兼容导致 apply 失败并回滚整个批次。

`runExclusive` 是 config reconcile 与制品事务共用的逻辑串行门。外层操作可以在同一调用线程重入；其他线程竞争时立即抛 `FibraPluginLoaderBusyException`，不会在持有物理锁时跨 Fibra lifecycle 线程等待。`artifactIds()`、`entryIds()` 使用最后成功提交的不可变身份快照，可在 lifecycle 回调中查询。Watcher 只接收原子发布到 incoming 目录的 `.zip`，按插件 ID 去抖且只提交严格更高版本；遇到 busy 会保留最新候选并重试。

制品错误用 `FibraArtifactException` 的 `stage/packages/artifactIds` 定位：`READ` 为读取或 ZIP 问题，`VALIDATE` 为格式/摘要/入口错误，`RESOLVE` 为依赖图错误，`DISPOSE` 为旧运行态拆除失败，`INSTALL` 为目录交换失败，`APPLY` 为新运行态恢复失败，`ROLLBACK` 表示旧状态无法完整恢复。`ROLLBACK` 必须停止启动并人工处理保留的事务诊断目录。

## 8.1 YAML/JSON 配置装载

生产宿主通常直接依赖 `fibra-loader-config`：

```java
try (var root = FibraRuntime.create();
     var artifacts = new FibraPluginLoader(root, Path.of("plugins"));
     var config = FibraConfigLoader.builder(root, artifacts, Path.of("fibra.yaml"))
         .warningSink(warning -> root.logger().warn(warning))
         .build()) {
    artifacts.loadArtifacts();
    config.load();
    try (var watcher = config.watch(Duration.ofMillis(200),
        failure -> root.logger().error(failure.exception()))) {
        // 应用主循环
    }
}
```

根文件扩展名只接受 `.yaml`、`.yml`、`.json`，顶层必须是数组。节点恰好分为三种：插件节点使用 `name`，分组使用 `group: true` 和子数组 `config`，include 使用相对路径 `include`。完整 entry ID 用 `:` 连接祖先 ID，raw `id` 禁止包含 `:`。

```yaml
- id: agents
  group: true
  isolate:
    greeting: primary
  config:
    - id: provider
      name: greeting-plugin
      config:
        prefix: "你好，"
    - id: consumer
      name: consumer-plugin
      inject: [greeting]
```

`load()` 只能成功一次；`refresh()` 重新解析并事务化应用，内容等价且运行态未漂移时返回同一 snapshot 对象；若托管 entry 被 loader 外部卸载，等价 refresh 会按同一 snapshot 自动补回。只有 config 变化时保持 Fibra 身份并调用 update；entry/plugin/父节点/inject/isolate/intercept/节点类型变化会替换实例。disabled 条目也校验 artifact 和 typed config，禁用不能隐藏配置错误。任一条目失败时严格逆序回滚，`snapshot()` 和运行态仍是上一份成功状态。

`create(parentId, position, entry)` 在指定 group/include 下创建节点；`parentId == null` 表示根，`position < 0` 表示尾部，非负位置大于长度时按尾部处理。`update(entryId, overridePatch, parentId, position)` 修改字段并可移动节点；`remove(entryId)` 删除节点。三者先校验序列化结果仍满足 loader 的大小、深度、字符串和 entry 数限制，再暂存同目录临时文件并完成运行态事务，最后以原子 rename 提交源文件。单文件失败时源文件和 snapshot 不变；多文件没有文件系统级整体原子提交，rename 失败时 loader 恢复已替换文件和旧运行态，若恢复本身失败则报告 `ROLLBACK`，各恢复失败直接位于 suppressed。include 子节点写回其 include 文件；由 builder patch 插入、原文件中不存在的合成节点不可写，update/remove 返回 `VALIDATE`。

patch 分 `insert` 与 `override`，按列表顺序应用；override 的显式 `null` 删除字段。缺失目标、目标不是 group 或 expected plugin 不匹配会通过 `warningSink` 报告并跳过。配置只接受字面值，不执行 SpEL、JEXL、JavaScript、反射构造或环境表达式；宿主必须把环境/profile 解析为显式 `FibraConfigPatch`。

`FibraConfigException` 的 `stage/path/entryId/pluginId` 是稳定定位字段：`READ` 为文件访问/真实路径失败，`PARSE` 为 YAML/JSON 语法失败，`VALIDATE` 为配置结构失败，`RESOLVE` 为插件制品或配置类型解析失败，`CONVERT` 为字面值到 typed config 的映射失败，`DISPOSE/APPLY` 为运行态卸载/应用失败，`WRITE` 为文件暂存或原子替换失败，`ROLLBACK` 为恢复旧状态失败。watcher 合并根文件、全部 include 文件以及失败候选尝试访问路径的 create/modify/delete，并补充路径存在状态检查；即使新增 include 文件及其父目录暂时不存在，随后创建也会自动触发恢复。刷新失败保留最后成功运行态，通过 failure sink 和 SLF4J 同时报告。watcher close 会等待在途 refresh 与 failure callback 完成，config loader 进入关闭状态后不允许并发安装新 watcher。关闭顺序固定为 config watcher、config loader、PF4J loader、root Context；try-with-resources 按上例声明即可得到该逆序。

## 9. 稳定错误

参数为空、名称空白、类型不匹配等调用错误使用 `IllegalArgumentException`。运行时状态错误使用 `FibraException`，通过 `code()` 判断：

| code | 含义 |
|---|---|
| `CONTEXT_CLOSED` | Context 已关闭 |
| `SERVICE_INACTIVE` | required service 当前不可用 |
| `SERVICE_DUPLICATE` | 同名同 isolate token 重复 provide |
| `EFFECT_INACTIVE` | 已卸载/已 dispose 的 Fibra 创建 effect |

插件 config/startup 原异常、event listener 原异常和手动 disposer 原异常不包装；调用者可直接判断原类型。

## 10. API 类型索引与冻结规则

| 包/分组 | 类型 |
|---|---|
| 核心 | `Context`、`Fibra`、`FibraState`、`FibraException` |
| 服务 | `ServiceKey`、`ServiceRegistration`、`Service`、`BoundService`、`InvocationContext` |
| 插件 | `Plugin`、`PluginDescriptor`、`PluginFactory`、`PluginInitializer`、`PluginRegistry`、`ConfigValidator` |
| 资源 | `Disposable`、`Disposables`、`EffectHandle`、`EffectMetadata`、`EffectSink`、`SyncEffect` |
| 关联属性 | `PropertyKey`、`PropertyAccessor`、`Associated` |
| `annotation` | `InjectService`、`InjectServices` |
| `event` | `EventKey`、`EventOptions`、`EventTarget`、`Next`、`AggregateEventException`、`CoreEvents` 及其 8 个 listener 契约 |
| `logging` | `FibraLogger`、`LoggerService`、`LogExporter`、`LogMessage`、`LogLevel`、`LoggerIntercept` |
| `pf4j` | `FibraPluginEntrypoint`、`VoidFibraPluginEntrypoint` |
| `loader.pf4j` | `FibraPluginLoader`、`PluginInstanceSpec`、`PluginConfigFactory`、`FibraPluginWatcher`、`FibraPluginWatchFailure` |
| `loader.config` | `FibraConfigLoader`、`FibraConfigEntry`、`FibraConfigPatch`、`FibraConfigSnapshot`、`FibraConfigRuntimeEntry`、`FibraConfigException`、`FibraConfigErrorStage`、`FibraConfigWarning`、`FibraConfigWatcher`、`FibraConfigReloadFailure` |

`fibra-core` 只承诺 `com.sstlfsj.fibra.runtime` 包，其中当前唯一入口是 `FibraRuntime`。`com.sstlfsj.fibra.internal` 即使因实现协作需要包含 Java `public` 类型，也属于明确排除的实现细节，业务代码不得直接引用。

六个可发布制品（五个中立内核/loader 制品 + 可选 Spring 适配制品 `fibra-spring-boot-starter`）的完整 public/protected JVM 签名分别见本目录中的 `*-public-signatures.txt`。`ApiSignatureBaselineTest` 扫描全部制品的公开类型集合并调用 JDK `javap -protected`；任何新增、删除、可见性、泛型或方法签名变化都会使 `mvn verify` 失败，必须先完成 API 审核后显式更新基线。

### 10.1 兼容性清单

以下变化属于破坏公开契约，禁止在未审核时更新签名基线：

- 删除、重命名或跨包移动公开类型；
- 收窄类型、构造器、方法或字段的可见性；
- 改变继承关系、接口列表、泛型边界、参数、返回值或声明异常；
- 改变枚举常量、注解成员、record component 或函数式接口的抽象方法；
- 把 `fibra-core` 的新实现类型放到 `com.sstlfsj.fibra.runtime`，或把 `internal` 类型暴露给 API 签名。

以下变化不破坏冻结契约：

- 不改变可观测行为和完成边界的内部实现调整；
- `com.sstlfsj.fibra.internal` 内部类型的调整；
- 文档、测试和私有成员调整。

新增公开能力同样需要 API 审核；审核通过后必须同时更新使用文档、对应签名基线和测试。项目不提供为旧签名兜底的兼容适配层。
