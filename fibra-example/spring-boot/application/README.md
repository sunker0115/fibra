# fibra-example-spring-boot-application

用 `fibra-spring-boot-starter` 在 Spring Boot Web application 中演示托管链路：
**上传 deployment（仅暂存）→ `FibraEngine` 联合提交插件与配置 → 通过 URL 调用插件服务**。

本模块是非发布示例（跳过远程 deploy），也充当 starter 的端到端黑盒。

## 结构

- `fibra-example-spring-boot-api`：application 公共 API `Greeting` SPI（`com.sstlfsj.fibra.example.springboot.Greeting`）。因落在 `com.sstlfsj.fibra.*` 共享前缀，运行时由插件 ClassLoader 委派回父加载器，application 与插件共用同一类型。
- `fibra-example-spring-boot-plugin`：以 `provided` scope 依赖 `Greeting` 的 executable 插件，打成标准 ZIP，由 deployment 配置创建 entry 并注册 `Greeting` 实现。
- `fibra-example-spring-boot-application`：Spring Boot Web application。控制器是 application 自己的静态 `@RestController`（不是插件贡献路由）。

application 只注入 `FibraEngine`、root `Context` 和显式 `FibraServiceBridge`，不能取得 engine 内部 loader。`fibra-spring-boot-starter` 只负责引入自动配置；运行代码分别位于 `fibra-spring` 与 `fibra-spring-boot-autoconfigure`。

## 端点

- `POST /deployments/upload`（multipart `file`）：仅把 ZIP 存到 `fibra.example.staging-root`，**不生效**。
- `POST /deployments/apply`（body：`{"package":"name.zip"}`）：通过 `FibraEngine.applyDeployment` 联合提交插件与配置。
- `GET /greet?name=X`：经 `Greeting.KEY` 调用当前 ACTIVE 插件 provider。
- `GET /deployments`：查询 engine 状态和暂存 deployment。

## 安全告警（重要）

`POST /deployments/apply` 会加载并运行插件代码，**等同于任意代码执行**。本示例仅演示机制，**没有任何鉴权或签名校验**；Fibra 只校验 deployment 结构和 SHA-256 完整性，SHA-256 不证明发布者身份。

生产环境必须在 `apply` 之前完成：调用方鉴权与授权、候选来源可信校验、 `artifact` 签名验证、以及对上传/apply 端点的访问控制。**禁止在生产中裸开上传/apply 端点。** Fibra 的非目标明确不含不可信插件安全沙箱，候选下载与信任由 application 或外部 `artifact` 系统负责。
