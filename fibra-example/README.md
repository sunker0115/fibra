# Fibra 真实插件示例

这个示例解决一个具体问题：业务文本写入日志、发送给 LLM 或外部服务前，需要清除邮箱、Bearer Token 和 API Key。清洗逻辑由 Node 插件提供，Java 宿主只依赖稳定的类型契约；同一插件分别接入纯 Java 和 Spring Boot，展示 Fibra 如何把不同语言实现变成宿主可直接调用的能力。

## 目录与职责

```text
content-sanitizer-api/       Java 侧贡献契约、类型和跨语言 codec
content-sanitizer-plugin/    Node 插件清洗实现与 fibra-plugin.yaml
java-host/                   手工组合 Fibra 的最小 Java 宿主
spring-host/                 通过 Starter 接入并暴露 HTTP API 的宿主
```

`content-sanitizer-plugin` 故意不是 Maven 模块。它是可独立复制、安装和升级的 Node 制品，本例又没有 npm 依赖，不应为了迎合 Java reactor 而改变插件形态。

运行链路如下：

```text
业务代码
  -> ContentSanitizerContribution（宿主拥有的类型契约）
  -> ContributionBridge（按 provider/localName 定位并管理并发调用）
  -> Fibra Engine（统一生命周期、Scope、事务与代际切换）
  -> NodePluginRuntimeAdapter（sidecar + JSON-RPC）
  -> content-sanitizer-plugin/index.mjs

PluginRegistry
  -> deploy / disable / uninstall
  -> 制品状态、期望状态与审计
```

## 一次构建并验证

要求 JDK 21、Maven 3.9.9+ 和 Node.js 20+。在仓库根目录执行：

```bash
mvn -pl :fibra-example-content-sanitizer-api,:fibra-example-java-host,:fibra-example-spring-host \
  -am clean verify
```

Node 不在 `PATH` 时显式指定：

```bash
mvn -pl :fibra-example-content-sanitizer-api,:fibra-example-java-host,:fibra-example-spring-host \
  -am clean verify \
  -Dfibra.test.node=/absolute/path/to/node
```

这不是只编译示例：测试会启动真实 Node sidecar，完成插件部署、贡献调用、disable、uninstall，并验证 Spring HTTP 链路。

## 纯 Java 接入

构建后直接运行可执行 JAR：

```bash
FIBRA_NODE=/absolute/path/to/node \
java -jar fibra-example/java-host/target/fibra-example-java-host.jar \
  fibra-example/content-sanitizer-plugin \
  'Email alice@example.com with Bearer abcdefghijklmnop or key sk_1234567890abcdef'
```

预期清洗结果：

```text
Email [REDACTED] with [REDACTED] or key [REDACTED]
```

建议按以下顺序读代码：

1. [`ContentSanitizerContribution`](content-sanitizer-api/src/main/java/com/sstlfsj/fibra/example/sanitizer/ContentSanitizerContribution.java)：定义宿主拥有的贡献种类和跨语言协议。
2. [`fibra-plugin.yaml`](content-sanitizer-plugin/fibra-plugin.yaml)：声明插件入口、贡献名、协议版本和描述信息。
3. [`index.mjs`](content-sanitizer-plugin/index.mjs)：实现受限 JSON-RPC 方法，不感知 Java 宿主结构。
4. [`ContentSanitizerScenario`](java-host/src/main/java/com/sstlfsj/fibra/example/ContentSanitizerScenario.java)：组合 Bridge、Node runtime、Engine 与 Registry，并用一次 `deploy` 原子安装和启用插件。
5. [`JavaHost`](java-host/src/main/java/com/sstlfsj/fibra/example/JavaHost.java)：业务代码只传入 `SanitizeRequest`，得到 `SanitizeResult`。

## Spring Boot 接入

启动可执行应用：

```bash
java -jar fibra-example/spring-host/target/fibra-example-spring-host-0.5.0-SNAPSHOT-exec.jar \
  --example.sanitizer.node-executable=/absolute/path/to/node \
  --example.sanitizer.plugin-directory="$(pwd)/fibra-example/content-sanitizer-plugin"
```

调用插件能力：

```bash
curl --request POST http://localhost:8080/api/sanitize \
  --header 'Content-Type: application/json' \
  --data '{"text":"Email alice@example.com with Bearer abcdefghijklmnop or key sk_1234567890abcdef"}'
```

查询宿主观察到的插件状态：

```bash
curl http://localhost:8080/api/plugins
```

Spring 接入只增加三层场景代码：

- [`SanitizerRuntimeConfiguration`](spring-host/src/main/java/com/sstlfsj/fibra/example/SanitizerRuntimeConfiguration.java) 在 Starter 默认 Java runtime 之外加入 Node runtime 和 Bridge；
- [`SanitizerPluginManager`](spring-host/src/main/java/com/sstlfsj/fibra/example/SanitizerPluginManager.java) 在应用启动后部署插件并提供类型化调用；
- [`SanitizerController`](spring-host/src/main/java/com/sstlfsj/fibra/example/SanitizerController.java) 把插件能力投影为 HTTP API，不接触 sidecar、JSON-RPC 或制品目录细节。

## 换成自己的场景

扩展 Fibra 时，边界应保持不变：

1. 场景拥有自己的 `ContributionKind`、请求、响应和 codec；Fibra 不内置“工具”“前端”或“Harness”语义。
2. Java Native 或 Node 插件实现该贡献；运行时差异止于 runtime adapter。
3. 宿主通过 `PluginRegistry` 管理安装、启停、升级和审计，通过 `ContributionBridge` 调用能力。
4. Spring、Harness、Web 平台等上层只负责把场景输入输出适配到自己的接口。

因此后续增加内容审核、文档解析、工具调用或前端资产贡献时，不需要复制一套插件生命周期和注册中心，只需新增场景契约及对应贡献实现。
