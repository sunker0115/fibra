# Fibra

Fibra 是 Cordis Core 4.0.1 的 Java 21 语义等价实现，用作 Java 版 DeepSeek Harness 的生命周期、服务、插件、事件与日志基础设施。它不是整个 DeepSeek Harness 的翻译，也不是对 Cordis JavaScript 语法的机械照搬；Java API 用强类型契约表达相同的作用域、所有权、顺序、错误与完成边界。

## 项目定位

完整项目由两层组成：`fibra-api` 与 `fibra-core` 负责 Cordis Core 的 Java 等价运行时；`fibra-pf4j-api` 与 `fibra-loader-pf4j` 在内核之外增加插件 JAR、依赖图、ClassLoader 隔离、原子更新和失败回滚。PF4J 只承担制品层，不替代 Fibra 生命周期。

目标使用场景是 Java 版 DeepSeek Harness、AI Agent 工具平台，以及需要可信进程内插件动态装载的纯 Java 或框架宿主。agent、tool、provider、session 等业务插件建立在 Fibra 之上，但不属于本仓库的内核实现；Spring、Hasor、Solon 也不进入内核。

工程按职责拆成八个模块：

- `fibra-api`：稳定的内核公开契约；
- `fibra-core`：唯一的 Context/Fibra 运行时；
- `fibra-pf4j-api`：插件制品唯一启动扩展点；
- `fibra-loader-pf4j`：PF4J JAR、依赖图和 ClassLoader 适配；
- `fibra-example-provider-plugin`：拥有跨插件服务契约的真实 provider 及多版本制品；
- `fibra-example-consumer-plugin`：通过 PF4J 依赖 ClassLoader 消费 provider 的真实插件；
- `fibra-example-host`：纯 Java 宿主示例与真实依赖链黑盒验收；
- `fibra-parity-tests`：Cordis 71 个逐项门禁、迁移测试和全部公开 API 冻结。

## 构建

```bash
mvn clean verify
```

该命令同时生成四个正式模块的主 JAR、sources JAR、Javadoc JAR 和自包含发布 POM，并执行 Cordis 对等、公开 API、真实 PF4J 插件链及发布制品门禁。连续构建的逐字节一致性使用：

```bash
scripts/verify-reproducible-release.sh
```

正式发布边界、deploy 行为和对外发布前置条件见[发布与构建基线](docs/release.md)。

## 最小用法

```java
import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import reactor.core.publisher.Mono;

interface Greeting {
    String greet(String name);
}

var greeting = ServiceKey.of("greeting", Greeting.class);
var root = FibraRuntime.create();
var registration = root.provide(greeting, name -> "你好，" + name);

var descriptor = PluginDescriptor.<Void>builder("consumer")
    .require(greeting)
    .build();
var consumer = root.plugin(descriptor, (ctx, ignored) -> {
    var text = ctx.service(greeting).invoke((invocation, service) -> service.greet("Fibra"));
    ctx.logger().info(text);
    return Mono.just(Disposables.noop());
}, null);

consumer.ready().block();
registration.dispose().block();
root.close();
```

`ServiceRegistration.dispose()` 会等待依赖插件完成卸载；服务调用需要保留调用方资源所有权时，使用 `BoundService.invoke` 提供的 `InvocationContext`。

## 文档入口

- [架构契约](docs/superpowers/specs/2026-08-21-fibra-kernel-architecture.md)
- [PF4J 装载架构](docs/superpowers/specs/2026-08-22-fibra-pf4j-loader-architecture.md)
- [公共 API 使用手册](docs/api/README.md)
- [发布与构建基线](docs/release.md)
- [fibra-api 公共签名基线](docs/api/fibra-api-public-signatures.txt)
- [fibra-core 运行时入口签名基线](docs/api/fibra-core-public-signatures.txt)
- [fibra-pf4j-api 公共签名基线](docs/api/fibra-pf4j-api-public-signatures.txt)
- [fibra-loader-pf4j 公共签名基线](docs/api/fibra-loader-pf4j-public-signatures.txt)
- [设计决定](docs/superpowers/specs/2026-08-21-fibra-kernel-design.md)
- [Cordis 源码映射](docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md)
- [测试等价表](docs/superpowers/references/2026-08-21-fibra-cordis-test-parity.md)
