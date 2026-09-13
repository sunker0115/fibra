# 内容清洗插件示例

这个场景在业务文本写入日志、发送给 LLM 或外部服务前，清除邮箱、Bearer Token 和 API Key。清洗逻辑由 Node 插件提供，Spring Boot 宿主只依赖稳定的贡献契约并通过 Starter 接入 Fibra。

```text
api/          Java 侧贡献契约、类型和跨语言 codec
node-plugin/  正式 Node 安装包（plugin.properties + payload/）
spring-host/  通过 Starter 接入并暴露 HTTP API 的宿主
```

`node-plugin` 不是 Maven 模块。它是可独立复制、安装和升级的 Node 安装包：根目录的 `plugin.properties` 声明 runtime 与 payload，`payload/fibra-plugin.yaml` 和 `payload/index.mjs` 是 Node 运行时内容。本例没有 npm 依赖。

## 构建与验证

要求 JDK 21、Maven 3.9.9+ 和 Node.js 20+。在仓库根目录执行：

```bash
mvn -pl :spring-host -am clean verify
```

Node 不在 `PATH` 时增加 `-Dfibra.test.node=/absolute/path/to/node`。集成测试会启动真实 Node sidecar，完成 deploy、贡献调用并验证 Spring HTTP 链路。

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
