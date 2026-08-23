# fibra-example-spring-host

用 `fibra-spring-boot-starter` 在 Spring Boot Web 宿主里演示 Fibra 动态插件的完整链路：
**上传（仅暂存）→ 请求驱动热装（apply）→ mount → 通过 URL 调用插件服务 → 热卸**。

本模块是非发布示例（跳过远程 deploy），也充当 starter 的端到端黑盒。

## 结构

- `fibra-example-spring-host-api`：宿主公共 API `Greeting` SPI（`com.sstlfsj.fibra.spring.host.Greeting`）。因落在 `com.sstlfsj.fibra.*` 共享前缀，运行时由插件 ClassLoader 委派回宿主父加载器，宿主与插件共用同一类型。
- `fibra-example-spring-host-plugin`：以 `provided` scope 依赖 `Greeting` 的 executable 插件，打成标准 ZIP，`apply` 时向 Fibra 注册 `Greeting` 实现。
- `fibra-example-spring-host`：Spring Boot Web 宿主。控制器是宿主自己的静态 `@RestController`（不是插件贡献路由）。

## 端点

- `POST /plugins/upload`（multipart `file`）：仅把 ZIP 存到 `fibra.staging-root`，**不生效**。
- `POST /plugins/apply`（body：暂存候选名列表）：显式事务热装 `applyArtifacts`。
- `GET /greet?name=X`：经 `Greeting.KEY` 调用当前 ACTIVE 插件 provider。
- `GET /plugins`：列制品/entry/暂存。
- `DELETE /plugins/{pluginId}`：热卸。

## 安全告警（重要）

`POST /plugins/apply` 会加载并运行插件代码，**等同于任意代码执行**。本示例仅演示机制，**没有任何鉴权/校验/签名**。

生产环境必须在 `apply` 之前完成：调用方鉴权与授权、候选来源可信校验、制品签名验证、以及对上传/apply 端点的访问控制。**禁止在生产中裸开上传/apply 端点。** Fibra 的非目标明确不含不可信插件安全沙箱，候选下载与信任由宿主或外部制品系统负责。
