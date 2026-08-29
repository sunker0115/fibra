# Fibra 公共 API 使用手册

本文覆盖六个框架中立运行时 `artifact`：`fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config` 和 `fibra-engine`。只使用内核时依赖 `fibra-core`；需要低层 `artifact` 或配置机制时可直接使用对应 loader；需要长期运行、自动收敛或联合部署的宿主必须依赖 `fibra-engine`，不自行拼接 loader 和 watcher。

Spring Framework 接缝由 `fibra-spring` 提供，Boot 自动配置由 `fibra-spring-boot-autoconfigure` 提供，推荐依赖入口 `fibra-spring-boot-starter` 不包含生产 class。`fibra-plugin-archetype` 是第十个可发布 `artifact`，只用于生成插件项目，不进入运行时依赖链。Spring 只存在于三个可选适配 `artifact`，不进入六个框架中立 `artifact` 或根父 POM。

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

无配置函数插件使用 `PluginDescriptor<Void>` 重载，不需要在调用末尾传入 `null`：

```java
Fibra consumer = root.plugin(
    PluginDescriptor.<Void>builder("consumer").require(GREETING).build(),
    (ctx, ignored) -> Mono.empty());

Fibra named = root.plugin("named", (ctx, ignored) -> Mono.empty());
```

`InvocationContext` 同样提供 `plugin(PluginDescriptor<Void>, Plugin<Void>)`，并保持调用者所有权。类插件继续使用 `plugin(descriptor, factory, initializer, config)`；不增加省略配置的三参数重载，因为它会与现有函数插件的三参数调用在 `config == null` 时产生 Java 重载歧义。

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

Fibra 自身的运行诊断不进入 `LoggerService` 缓冲区，而是直接通过 SLF4J 输出。消息正文固定以 `event=fibra.<layer>.<subject>.<outcome>` 开头，后续关联字段采用 `key=value`，保证未配置键值渲染的 Spring Boot 默认 Logback 和其他 SLF4J provider 也能直接检索。常用关联字段为 `entryId`、`pluginIds`、`transactionId`、`deploymentId`、`stage`、`desiredRevision`、`appliedRevision` 和 `source`。宿主可按 `event` 定位类别，再用事务、部署或 revision 串联同一次问题；Fibra 不配置日志格式、不绑定日志 provider，也不会记录 typed config 值或凭据。

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

无配置入口可以实现 `VoidFibraPluginEntrypoint`，只需实现 `create(entryId)`。主 JAR 自身没有 `META-INF/extensions.idx` 或索引为空时是 contract-only：可以被其他插件依赖，但不能调用 `configType` 或 `mount`；executable 的自身索引必须恰好包含一个 `FibraPluginEntrypoint`。`pluginId` 是 `artifact` 身份，`entryId` 是运行实例身份，一个 executable 可以创建多个 entry。

首次启动只扫描已安装目录。`FibraPluginLoader` 是低层机制 API：`loadArtifacts()` 完成初载，`applyArtifacts(List<Path>)` 执行显式批量 `artifact` 事务，`mount/update/unmount` 操作运行 entry。生产托管宿主通常不直接调用这些方法，而由 `FibraEngine` 统一拥有 loader。

`applyArtifacts` 的候选先全部解压到预检区，完成格式、摘要、必需/optional SemVer 范围、循环、入口和 prospective 全图校验后，才允许拆除旧运行态。候选 ID 加上旧图/新图的传递依赖方构成受影响闭包；停止为 dependent-first，装载与启动为 dependency-first。任一步失败都会恢复旧目录、PF4J 状态和全部 entry。

`configType(pluginId)` 返回插件 ClassLoader 中的配置类型。配置类型定义在插件包内时必须使用 `configFactory(type -> ...)`，每次根据当前 ClassLoader 的 `type` 创建对象；不得把旧 typed config 传入新版本。schema 不兼容会使当前事务失败并回滚，不执行隐式兼容或迁移。

`runExclusive` 是两个 loader 共享的低层逻辑事务门。普通托管宿主不得绕过 Engine 调用它；需要低层 API 的非托管宿主必须独占 root 和两个 loader，并自行保证完整启动、回滚与关闭。loader 不包含 watcher、去抖、周期重读或失败重试。

`artifact` 错误用 `FibraArtifactException` 的 `stage/packages/artifactIds` 定位：`READ` 为读取或 ZIP 问题，`VALIDATE` 为格式/摘要/入口错误，`RESOLVE` 为依赖图错误，`DISPOSE` 为旧运行态拆除失败，`INSTALL` 为目录交换失败，`APPLY` 为新运行态恢复失败，`ROLLBACK` 表示旧状态无法完整恢复。

## 8.1 YAML/JSON 配置机制

`FibraConfigLoader` 同样是低层机制 API。非托管宿主的最小组合如下；它不会自动监听文件：

```java
try (var root = FibraRuntime.create();
     var artifacts = new FibraPluginLoader(root, Path.of("plugins"));
     var config = FibraConfigLoader.builder(root, artifacts, Path.of("fibra.yaml"))
         .warningSink(warning -> root.logger().warn(warning))
         .build()) {
    artifacts.loadArtifacts();
    config.load();
    config.refresh();
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

`FibraConfigException` 的 `stage/path/entryId/pluginId` 是稳定定位字段：`READ` 为文件访问/真实路径失败，`PARSE` 为 YAML/JSON 语法失败，`VALIDATE` 为配置结构失败，`RESOLVE` 为插件 `artifact` 或配置类型解析失败，`CONVERT` 为字面值到 typed config 的映射失败，`DISPOSE/APPLY` 为运行态卸载/应用失败，`WRITE` 为文件暂存或原子替换失败，`ROLLBACK` 为恢复旧状态失败。`sourcePaths()` 返回根文件、成功 include 和最后一次失败解析尝试路径的无锁不可变快照，供 Engine 的配置 source 在 loader 事务进行期间继续维护监听；它本身不启动线程或刷新配置。

## 8.2 托管 Engine

动态插件宿主的标准入口是 `fibra-engine`：

```java
try (var engine = FibraEngine.builder(Path.of("plugins"), Path.of("fibra.yaml"))
    .artifactSource(Path.of("incoming"), Duration.ofSeconds(1))
    .configSource(Duration.ofSeconds(1))
    .requiredEntries(List.of("greeting-provider"))
    .readinessTimeout(Duration.ofSeconds(60))
    .build()) {
    engine.start();
    var status = engine.status();
    engine.requestReconcile();
    engine.applyDeployment(Path.of("incoming/release.zip"));
}
```

`start()` 依次完成崩溃恢复、安装图初载、配置装配、required entry readiness 和可选 source 启动；基础运行态建立后立即请求一次完整 reconcile，因此启动前已经存在的 incoming 候选也会被处理，坏候选只让已启动 Engine 进入 `DEGRADED` 并重试，不反向破坏基础启动。同一 Engine 只能启动一次。`requestReconcile()` 只标记期望状态可能变化；后台协调器重新读取完整状态并串行收敛。artifact 与 config 的松散变化分别提交，不能靠时间接近程度猜成联合事务。

`applyDeployment(Path)` 接受含 `deployment.properties`、`checksums.sha256`、`plugins/*.zip` 和 `config/` 的标准 deployment ZIP。摘要固定使用 SHA-256；插件和配置作为一个显式事务预检、提交、readiness 和回滚。持久化 `COMMITTED` journal 是唯一提交点；此前失败回滚并返回失败，此后的 receipt 写入、参与者清理或事务目录删除失败只保留 journal 供下次启动恢复并记录 WARN，调用仍返回已经生效的成功结果。

`FibraEngineStatus` 提供终止性状态、desired revision、applied revision 与按阶段结构化失败。两个公开 revision 都由内部 artifact/config 分量组合：desired 表示最近一次完整观察，applied 表示当前真实活动 catalog 与最后成功配置快照；一侧成功而另一侧失败时 applied 仍推进成功分量，不会伪装成整个旧状态。`RUNNING`/`DEGRADED` 由全部活动失败统一计算，单个阶段恢复不会遮蔽其他阶段的失败。

artifact、config 或 deployment 出现 `ROLLBACK` 表示旧运行图无法证明完整恢复。Engine 会进入粘性 mutation block：保留 `DEGRADED` 和结构化失败，拒绝后续 `requestReconcile()`、`applyDeployment()` 及已排队但尚未真正执行的部署。此状态不会由重试自动解除；调用方仍可读取 `status()`、使用 `root()` 查询现状并执行 `close()`，修复磁盘状态后必须重建 Engine，由启动恢复重新证明一致性。

Engine 独占 root、两个 loader、两个可选 source、协调线程和 journal；只公开 `root()` 作为服务桥接与查询视图，不公开内部 loader。`close()` 固定停止 source 与协调工作，再关闭 config loader、plugin loader 和 root，重复调用幂等。

## 8.3 Spring 适配

`fibra-spring` 冻结 `FibraSpringLifecycle` 与 `FibraServiceBridge`。`fibra-spring-boot-autoconfigure` 冻结 `FibraAutoConfiguration` 和不可变嵌套 `FibraProperties`；`fibra-spring-boot-starter` 没有 Java API，只是推荐依赖入口。

当前属性根固定为 `fibra.engine`、`fibra.artifacts`、`fibra.config`、`fibra.startup` 和 `fibra.shutdown`。默认值为 resync 30 秒、重试 250 毫秒至 30 秒、两个 source 关闭、去抖 1 秒、required entries 空、readiness 60 秒、root close 30 秒。`installed-root` 和 `config.location` 必填；artifact source 开启时 `incoming-root` 必填。

自动配置仅在没有 `FibraEngine` 且没有 Fibra `Context` 时创建完整托管单元，并只暴露 Engine、root、bridge 和 lifecycle。Spring 不托管插件对象，不暴露内部 loader，不按类型自动桥接宿主 bean。

## 8.4 插件工程 Archetype

`com.sstlfsj:fibra-plugin-archetype` 接受 `groupId`、`artifactId`、`version`、`package`、`pluginId` 和 `fibraVersion` 六个输入，生成不继承 Fibra parent 的独立项目。生成项目固定包含 `plugin-api`、`plugin-impl`、`config` 和 `deployment`：contract-only 模块保存共享接口，实现模块保存唯一 Fibra 入口和 typed config，配置模块保存 YAML，deployment 模块把两份标准插件 ZIP 与配置打成 SHA-256 联合部署包。

生成项目直接执行 `mvn verify`。共享 Fibra、PF4J、Reactor 和 contract 依赖使用 `provided`，不复制进插件私有 `lib/`；`Plugin-Class` 始终禁止。当前仓库的 archetype 集成测试会生成该项目、构建全部产物，再由真实 `FibraEngine` 安装并激活 deployment。

仓库内的 `fibra-example` 只保存 Engine 与 Spring Boot 可运行场景，不是模板副本；`verification/distribution` 只做仓库外发布坐标黑盒。三者的唯一职责边界见[示例与分发验收设计](../superpowers/specs/2026-08-25-fibra-examples-and-distribution-verification-design.md)。

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
| `loader.pf4j` | `FibraPluginLoader`、`FibraArtifactChange`、`FibraPluginCatalog`、`FibraArtifactDescriptor`、`PluginInstanceSpec`、`PluginConfigFactory` 及稳定错误类型 |
| `loader.config` | `FibraConfigLoader`、`FibraConfigChange`、`FibraConfigEntry`、`FibraConfigPatch`、`FibraConfigSnapshot`、`FibraConfigRuntimeEntry` 及稳定错误类型 |
| `engine` | `FibraEngine`、`FibraEngineStatus`、`FibraEngineState`、`FibraEngineFailure`、`FibraDeploymentResult` 及稳定错误类型 |
| `spring` | `FibraSpringLifecycle`、`FibraServiceBridge` |
| `spring.boot` | `FibraAutoConfiguration`、`FibraProperties` 及其嵌套 record |

`fibra-core` 只承诺 `com.sstlfsj.fibra.runtime` 包，其中当前唯一入口是 `FibraRuntime`。`com.sstlfsj.fibra.internal` 即使因实现协作需要包含 Java `public` 类型，也属于明确排除的实现细节，业务代码不得直接引用。

八个含 Java 公共类型的运行时 `artifact` 分别有 `*-public-signatures.txt`：五个原有 API/loader、`fibra-engine`、`fibra-spring` 和 `fibra-spring-boot-autoconfigure`。starter 无生产 class，archetype 是代码生成 `artifact`，因此两者没有 Java 签名基线。`ApiSignatureBaselineTest` 调用 JDK `javap -protected` 检查全部基线；任何新增、删除、可见性、泛型或方法签名变化都会使 `mvn verify` 失败，必须先完成 API 审核后显式更新基线。

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
