# Fibra 公共 API 使用手册

本文对应 `com.sstlfsj:fibra-api:${revision}`、`com.sstlfsj:fibra-core:${revision}`、`com.sstlfsj:fibra-pf4j-api:${revision}` 与 `com.sstlfsj:fibra-loader-pf4j:${revision}` 的冻结公开契约。业务应用通常依赖 `fibra-core`；需要运行时 JAR 插件时再依赖 `fibra-loader-pf4j`。

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

`extend(Map)` 创建只读继承的元数据视图，`metadata(name)` 沿父链读取。`isolate(ServiceKey)` 创建独立服务作用域；`isolate(key, label)` 使用对象身份共享作用域。`intercept` 创建分层调用配置，原 Context 不变。

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

`get(key)` 只返回 ACTIVE provider，`get(key, false)` 允许读取非 ACTIVE provider。需要调用方所有权时必须使用 `BoundService.invoke`：

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

状态固定为 `PENDING`、`LOADING`、`ACTIVE`、`FAILED`、`UNLOADING`、`DISPOSED`。`await()`/`ready()` 等待当前 inertia 收敛并传播 config/startup 原异常。`update` 先同步校验，再经过 `CoreEvents.UPDATE` waterfall；只有执行默认 `next` 才提交 config 和 restart。root `dispose()` 等价于 restart，root uid 永远为 0；普通 Fibra dispose 后 uid 为 null。

`PluginRegistry` 按插件入口对象身份分组，提供 `size`、`has`、`keys`、`values`、`entries`、全部/按入口 `fibras` 快照和可等待的 `remove`。同一入口可对应多个 Fibra。

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

## 8. PF4J JAR 插件

插件工程依赖 `fibra-pf4j-api`，作用域必须是 `provided`。以下 POM 可直接构建符合 loader 契约的 fat JAR；替换项目坐标、入口类业务和插件元数据即可：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>greeting-plugin</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <fibra.version>0.1.0-SNAPSHOT</fibra.version>
    <pf4j.version>3.13.0</pf4j.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.sstlfsj</groupId>
      <artifactId>fibra-pf4j-api</artifactId>
      <version>${fibra.version}</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.1</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.pf4j</groupId>
              <artifactId>pf4j</artifactId>
              <version>${pf4j.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.7.1</version>
        <configuration>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
          <appendAssemblyId>false</appendAssemblyId>
          <archive>
            <manifestEntries>
              <Plugin-Id>greeting-plugin</Plugin-Id>
              <Plugin-Version>1.0.0</Plugin-Version>
              <Plugin-Provider>example</Plugin-Provider>
              <Plugin-Dependencies></Plugin-Dependencies>
            </manifestEntries>
          </archive>
        </configuration>
        <executions>
          <execution>
            <id>plugin-jar</id>
            <phase>package</phase>
            <goals><goal>single</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

入口类只实现 Fibra 生命周期，不继承 PF4J `Plugin`，也不创建 Spring Context：

```java
package com.example.greeting;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class GreetingPlugin implements FibraPluginEntrypoint {
    public static final ServiceKey<String> GREETING =
        ServiceKey.of("greeting.text", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        context.provide(GREETING, "你好，Fibra");
        return Mono.just(Disposables.noop());
    }
}
```

PF4J 的注解处理器会生成 `META-INF/extensions.idx`。构建后把 `target/greeting-plugin-1.0.0.jar` 放到已存在的插件根目录，再由宿主装载：

```java
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;

var pluginsRoot = java.nio.file.Path.of("plugins");
try (var root = FibraRuntime.create();
     var loader = new FibraPluginLoader(root, pluginsRoot)) {
    loader.loadPlugins();
    loader.startPlugins();
    // 运行应用
}
```

资源按声明逆序关闭，因此 loader 先 dispose 全部插件 Fibra，再 stop/unload PF4J 和关闭 ClassLoader，最后 root Context 关闭。`loadPlugins()` 只扫描根目录直接子级 fat JAR；共享的 Fibra/PF4J/Reactor/SLF4J 类不得打入插件 JAR，Manifest 不得声明 `Plugin-Class`，每个制品必须且只能有一个 `FibraPluginEntrypoint`。

`Plugin-Dependencies` 只表示制品和类加载依赖；业务服务依赖仍由入口创建的 Fibra/子 Fibra 使用 `PluginDescriptor.require` 声明。PF4J `STARTED` 不等于 Fibra `ACTIVE`。

显式更新使用插件根目录外的候选 JAR：

```java
Path candidate = Path.of("releases/greeting-plugin-2.0.0.jar");
String pluginId = loader.reloadPlugin(candidate);
```

`reloadPlugin` 会根据候选 Manifest 定位当前插件，停止并卸载全部传递依赖方，等待 Fibra dispose，关闭旧 ClassLoader，在插件根目录内原子替换 JAR，再批量装载并恢复原启动状态。候选无法装载或启动时恢复旧 JAR 和旧运行状态；候选文件本身不会被移动或删除。候选路径必须位于插件根目录外，且不能等于当前 JAR。

自动更新监听独立的 incoming 目录。生产方必须先在目录外完整写出 JAR，再通过同一文件系统的原子 move 发布；监听器只消费 `ENTRY_CREATE`，不会观察半写文件：

```java
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import java.time.Duration;

Path incoming = Path.of("plugin-incoming");
try (var watcher = new FibraPluginWatcher(loader, incoming, Duration.ofSeconds(2))) {
    watcher.start();
    // 应用主循环
    watcher.lastFailure().ifPresent(failure ->
        root.logger().error("候选插件更新失败：{}", failure.candidate(), failure.cause()));
}
```

同一插件在去抖窗口内出现多个候选时，监听器使用 PF4J SemVer 选择最高版本；同版本使用文件最后修改时间选择较新的候选，低于当前运行版本的候选被忽略。人工降级仍可直接调用 `reloadPlugin`。监听器不删除 incoming 文件；异步失败同时写入 SLF4J 并由 `lastFailure()` 暴露。关闭顺序必须是 watcher、loader、root。

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
| `pf4j` | `FibraPluginEntrypoint` |
| `loader.pf4j` | `FibraPluginLoader`、`FibraPluginWatcher`、`FibraPluginWatchFailure` |

`fibra-core` 只承诺 `com.sstlfsj.fibra.runtime` 包，其中当前唯一入口是 `FibraRuntime`。`com.sstlfsj.fibra.internal` 即使因实现协作需要包含 Java `public` 类型，也属于明确排除的实现细节，业务代码不得直接引用。

四个生产制品的完整 public/protected JVM 签名分别见本目录中的 `*-public-signatures.txt`。`ApiSignatureBaselineTest` 扫描全部制品的公开类型集合并调用 JDK `javap -protected`；任何新增、删除、可见性、泛型或方法签名变化都会使 `mvn verify` 失败，必须先完成 API 审核后显式更新基线。

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
