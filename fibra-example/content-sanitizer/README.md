# 内容清洗插件示例

这个场景在业务文本写入日志、发送给 LLM 或外部服务前，清除邮箱、Bearer Token 和 API Key。清洗逻辑由 Node 插件提供，Java 宿主只依赖稳定的贡献契约；同一插件分别接入纯 Java 和 Spring Boot。

```text
api/          Java 侧贡献契约、类型和跨语言 codec
node-plugin/  Node 插件实现与 fibra-plugin.yaml
java-host/    手工组合 Fibra 的纯 Java 宿主
spring-host/  通过 Starter 接入并暴露 HTTP API 的宿主
```

`node-plugin` 不是 Maven 模块。它是可独立复制、安装和升级的 Node 制品，本例没有 npm 依赖，不需要改变其制品形态来迎合 Java reactor。

## 构建与验证

要求 JDK 21、Maven 3.9.9+ 和 Node.js 20+。在仓库根目录执行：

```bash
mvn -pl :java-host,:spring-host -am clean verify
```

Node 不在 `PATH` 时增加 `-Dfibra.test.node=/absolute/path/to/node`。集成测试会启动真实 Node sidecar，完成 deploy、贡献调用、disable、uninstall，并验证 Spring HTTP 链路。

## 纯 Java 接入

```bash
FIBRA_NODE=/absolute/path/to/node \
java -jar fibra-example/content-sanitizer/java-host/target/fibra-example-java-host.jar \
  fibra-example/content-sanitizer/node-plugin \
  'Email alice@example.com with Bearer abcdefghijklmnop or key sk_1234567890abcdef'
```

建议按以下顺序读：

1. [`ContentSanitizerContribution`](api/src/main/java/com/sstlfsj/fibra/example/sanitizer/ContentSanitizerContribution.java)：定义宿主拥有的贡献类型与协议。
2. [`fibra-plugin.yaml`](node-plugin/fibra-plugin.yaml) 和 [`index.mjs`](node-plugin/index.mjs)：声明并实现 Node 贡献。
3. [`ContentSanitizerScenario`](java-host/src/main/java/com/sstlfsj/fibra/example/ContentSanitizerScenario.java)：组合 Bridge、Node runtime、Engine 与 Registry。
4. [`JavaHost`](java-host/src/main/java/com/sstlfsj/fibra/example/JavaHost.java)：业务代码的类型化调用入口。

## Spring Boot 接入

```bash
java -jar fibra-example/content-sanitizer/spring-host/target/spring-host-0.5.0-SNAPSHOT-exec.jar \
  --example.sanitizer.node-executable=/absolute/path/to/node \
  --example.sanitizer.plugin-directory="$(pwd)/fibra-example/content-sanitizer/node-plugin"
```

```bash
curl --request POST http://localhost:8080/api/sanitize \
  --header 'Content-Type: application/json' \
  --data '{"text":"Email alice@example.com with Bearer abcdefghijklmnop or key sk_1234567890abcdef"}'

curl http://localhost:8080/api/plugins
```

Spring 接入只负责把场景能力投影成配置、应用服务和 HTTP API，不接触 sidecar、JSON-RPC 或制品目录细节。
