# 变更记录

本文件记录 Fibra 各版本的对外变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

内核 `fibra-api`/`fibra-core` 的公开 API 由 `ApiSignatureBaselineTest` 与六份 `javap` 基线冻结；标注「内核语义无变化」表示这些基线未变。

## [0.3.1] - 未发布（0.3.1-SNAPSHOT）

本版新增「可选 Spring Boot 宿主接入」落地路径，并用 JMH 基准把内核性能从经验判断变为可复现数据。内核语义无变化，全部增量位于外围（可选适配制品 + 基准模块）。

### 新增

- **`fibra-spring-boot-starter`（可选 Spring 适配制品）**：把「Spring 只在可选模块内、不进内核」落地为真实制品。
  - `FibraProperties`：按 `fibra.*` 属性绑定，必填属性 fail-fast。
  - `FibraServiceBridge`：宿主 Spring 单例经类型化 `ServiceKey` 显式桥接给插件；桥接哪个 bean 由宿主决定，不做按类型自动装配。
  - `FibraLifecycle`（`SmartLifecycle`）：启动就绪门禁 + 逆序有序关闭，就绪超时与关闭超时分离。
  - `FibraAutoConfiguration`：按 `META-INF/spring/...AutoConfiguration.imports` 自动装配 root `Context`、`FibraPluginLoader`、`FibraConfigLoader`、`FibraServiceBridge`；所有 Fibra 资源 bean 声明 `destroyMethod = ""`，关闭权交给 `FibraLifecycle`。
- **`fibra-example-spring-host`（示例宿主）**：演示 HTTP 上传 + 请求驱动的插件热装载。
- **`fibra-benchmarks`（JMH 内核性能基准，隔离模块）**：通过根 pom 的 `benchmarks` profile 门禁注册，不发布、不进可复现构建集、不被任何生产模块依赖。含三组基准：服务解析（跨线程往返 vs 同线程直调）、事件分发（1/8/64 hook 的 `emit`/`waterfall`）、lifecycle 调度往返。参考基线见 `fibra-benchmarks/README.md`。

### 变更

- 远程可发布制品由 5 个增至 **6 个**（5 个中立内核/loader 制品 + 1 个可选 `fibra-spring-boot-starter`）；`fibra-spring-boot-starter` 纳入可复现构建集、发布制品基线与公开 API 签名基线门禁。
- 示例模块归入 `fibra-example` 聚合目录。

### 说明

- 内核 `fibra-core`/`fibra-api` 与父 POM 保持 Spring-free；Spring 仅存在于 `fibra-spring-boot-starter` 内部。
- 内核性能基准结论：跨线程调度往返约 3.7µs 为「每次跨线程调用的固定税」，同线程业务逻辑约 80ns，事件分发 hook 数量影响微小；据此判断内核当前无需为性能改动（集成架构 §4.1 的读取快照触发条件未满足）。

## [0.3.0]

- 将 `fibra-loader-pf4j` 从「插件根目录直接放 JAR、单 JAR 事后回滚」重构为「标准目录安装包、ZIP 候选、完整依赖图预检、批量事务替换、崩溃恢复」。详见 openspec change `standardize-plugin-packages`（已归档）与[插件制品与事务更新设计](docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md)。

[0.3.1]: docs/superpowers/specs/2026-08-23-fibra-benchmarks-design.md
[0.3.0]: docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md
