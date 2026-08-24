# Fibra

Fibra 是 Cordis Core 4.0.1 的 Java 21 语义等价实现，用作 Java 版 DeepSeek Harness 的生命周期、服务、插件、事件与日志基础设施。它不是整个 DeepSeek Harness 的翻译，也不是对 Cordis JavaScript 语法的机械照搬；Java API 用强类型契约表达相同的作用域、所有权、顺序、错误与完成边界。

## 项目定位

完整项目由三层组成：`fibra-api` 与 `fibra-core` 负责 Cordis Core 的 Java 等价运行时；`fibra-pf4j-api` 与 `fibra-loader-pf4j` 增加标准插件包、依赖图、ClassLoader 隔离、批量事务更新和崩溃恢复；`fibra-loader-config` 把 YAML/JSON 插件树事务化同步到 PF4J 制品和 Fibra 运行实例。PF4J 只承担制品层，配置 loader 只承担动态组合，两者都不替代 Fibra 生命周期。

目标使用场景是 Java 版 DeepSeek Harness、AI Agent 工具平台，以及需要可信进程内插件动态装载的纯 Java 或框架宿主。agent、tool、provider、session 等业务插件建立在 Fibra 之上，但不属于本仓库的内核实现；Spring、Hasor、Solon 也不进入内核。

工程按职责拆成以下模块。六个可发布制品由根 reactor 直接聚合，全部示例归入 `fibra-example` 聚合目录（统一 `install`、跳过远程 deploy），另有验证与基准两个非发布模块：

- `fibra-api`：稳定的内核公开契约；
- `fibra-core`：唯一的 Context/Fibra 运行时；
- `fibra-pf4j-api`：插件制品唯一启动扩展点；
- `fibra-loader-pf4j`：标准 ZIP/目录包、PF4J 依赖图、ClassLoader 与持久更新事务；
- `fibra-loader-config`：框架中立的 YAML/JSON 配置树、typed config、运行时事务和文件监听；
- `fibra-spring-boot-starter`：可选 Spring Boot 适配制品，把 Fibra 装配与生命周期接入 Spring 容器；Spring 只在该模块内自管，不进内核；
- `fibra-example`：示例聚合目录，含：
  - `fibra-example-contract-plugin`：独立 `Greeting` 类型的 contract-only 标准包；
  - `fibra-example-provider-plugin`：依赖 contract 并提供 Fibra 服务的 executable 多版本标准包；
  - `fibra-example-consumer-plugin`：只二进制依赖 contract、运行时等待 provider 服务的 executable 标准包；
  - `fibra-example-host`：使用真实 YAML 装配插件树的纯 Java 宿主示例与真实依赖链黑盒验收；
  - `fibra-example-spring-host-api` / `fibra-example-spring-host-plugin` / `fibra-example-spring-host`：Spring Boot 宿主示例，演示 HTTP 上传与请求驱动的插件热装载；
- `fibra-parity-tests`：Cordis 71 个逐项门禁、迁移测试和全部公开 API 冻结；
- `fibra-benchmarks`：JMH 内核性能基准，经根 POM `benchmarks` profile 门禁注册，不发布也不进任何生产制品。

## 构建

```bash
mvn clean verify
```

该命令同时生成六个可发布模块（五个中立内核/loader 制品 + 可选 Spring 适配制品 `fibra-spring-boot-starter`）的主 JAR、sources JAR、Javadoc JAR 和自包含发布 POM，并执行 Cordis 对等、公开 API、真实 PF4J 插件链、配置装载事务及发布制品门禁。连续构建的逐字节一致性使用：

```bash
scripts/verify-reproducible-release.sh
```

五个正式制品的仓库外消费能力使用：

```bash
scripts/verify-external-consumer.sh
```

该脚本把当前 `revision` 的五个正式制品部署到临时 Maven 仓库，再在仓库外的临时目录中构建并运行一个无 Fibra parent、未加入 Fibra reactor 的独立项目。该工程同时是可复制的用户插件模板，生成 contract/provider/consumer 的 v1/v2 标准 ZIP；独立 Host 只依赖 `fibra-loader-config`，验证 contract ClassLoader 唯一性、provider 私有依赖隔离、配置事务和一次三包关联升级。脚本不读取用户 Maven 本地仓库中的 Fibra 制品，也不读取本仓库的 `target/classes`，只在临时副本上从根 POM 覆盖开发版本与仓库 URL。

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

## Spring Boot 适配（可选）

在 Spring Boot 宿主中运行时，可加入可选适配制品 `com.sstlfsj:fibra-spring-boot-starter`。它以 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 声明自动装配，按 `fibra.*` 属性装配 root `Context`、`FibraPluginLoader`、`FibraConfigLoader` 与 `FibraServiceBridge`，并用 `FibraLifecycle`（`SmartLifecycle`）编排启动装载、就绪门禁与逆序关闭。Spring 只存在于该制品内，内核 `fibra-core`/`fibra-api` 与父 POM 保持 Spring-free。

```xml
<dependency>
  <groupId>com.sstlfsj</groupId>
  <artifactId>fibra-spring-boot-starter</artifactId>
  <version>${fibra.version}</version>
</dependency>
```

```yaml
fibra:
  plugins-root: /var/lib/app/plugins
  config-location: /etc/app/fibra.yaml
  startup-required-plugins:
    - greeting-provider
  readiness-timeout: 60s
  shutdown-timeout: 30s
```

宿主用 `FibraServiceBridge.register(ServiceKey, service)` 把自身 Spring 单例经类型化 `ServiceKey` 显式暴露给插件；桥接哪个 bean 由宿主决定，适配层不做按类型自动装配。所有 Fibra 资源 bean 声明为 `destroyMethod = ""`，关闭权交给 `FibraLifecycle` 有序编排，避免 Spring 默认 destroy 打乱关闭顺序。

## 文档入口

- [架构契约](docs/superpowers/specs/2026-08-21-fibra-kernel-architecture.md)
- [PF4J 装载架构](docs/superpowers/specs/2026-08-22-fibra-pf4j-loader-architecture.md)
- [配置装载架构](docs/superpowers/specs/2026-08-23-fibra-config-loader-architecture.md)
- [Spring 与 Java DeepSeek Harness 集成架构](docs/superpowers/specs/2026-08-22-fibra-spring-harness-integration-architecture.md)
- [仓库外多插件依赖验收设计（含关系图）](docs/superpowers/specs/2026-08-22-fibra-external-multi-plugin-verification-design.md)
- [公共 API 使用手册](docs/api/README.md)
- [发布与构建基线](docs/release.md)
- [fibra-api 公共签名基线](docs/api/fibra-api-public-signatures.txt)
- [fibra-core 运行时入口签名基线](docs/api/fibra-core-public-signatures.txt)
- [fibra-pf4j-api 公共签名基线](docs/api/fibra-pf4j-api-public-signatures.txt)
- [fibra-loader-pf4j 公共签名基线](docs/api/fibra-loader-pf4j-public-signatures.txt)
- [fibra-loader-config 公共签名基线](docs/api/fibra-loader-config-public-signatures.txt)
- [fibra-spring-boot-starter 公共签名基线](docs/api/fibra-spring-boot-starter-public-signatures.txt)
- [设计决定](docs/superpowers/specs/2026-08-21-fibra-kernel-design.md)
- [开源基线与取舍](docs/superpowers/references/2026-08-21-fibra-opensource-baselines.md)
- [Cordis 源码映射](docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md)
- [测试等价表](docs/superpowers/references/2026-08-21-fibra-cordis-test-parity.md)
