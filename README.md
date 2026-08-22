# Fibra

Fibra 是面向 Java 21 的 Cordis 4.0.1 等价内核，用作 Java 版 DeepSeek Harness 的生命周期、服务、插件、事件与日志基础设施。

工程按职责拆成五个模块：

- `fibra-api`：稳定的内核公开契约；
- `fibra-core`：唯一的 Context/Fibra 运行时；
- `fibra-pf4j-api`：插件制品唯一启动扩展点；
- `fibra-loader-pf4j`：PF4J JAR、依赖图和 ClassLoader 适配；
- `fibra-parity-tests`：Cordis 71 个逐项门禁、迁移测试和全部公开 API 冻结。

## 构建

```bash
mvn verify
```

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
- [fibra-api 公共签名基线](docs/api/fibra-api-public-signatures.txt)
- [fibra-core 运行时入口签名基线](docs/api/fibra-core-public-signatures.txt)
- [fibra-pf4j-api 公共签名基线](docs/api/fibra-pf4j-api-public-signatures.txt)
- [fibra-loader-pf4j 公共签名基线](docs/api/fibra-loader-pf4j-public-signatures.txt)
- [设计决定](docs/superpowers/specs/2026-08-21-fibra-kernel-design.md)
- [Cordis 源码映射](docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md)
- [测试等价表](docs/superpowers/references/2026-08-21-fibra-cordis-test-parity.md)
