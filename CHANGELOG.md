# 变更记录

本文件记录 Fibra 各版本的对外变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

内核 `fibra-api`/`fibra-core` 的公开 API 由 `ApiSignatureBaselineTest` 与六份 `javap` 基线冻结；标注「内核语义无变化」表示这些基线未变。

## [未发布]

## [0.4.0] - 2026-08-29

### 新增

- 新增框架中立的 `fibra-engine`，作为插件制品、动态配置、source、串行 reconcile、readiness、部署事务和关闭顺序的唯一托管入口。
- 新增带清单摘要的 deployment ZIP 协议，把多个插件候选与配置作为一个联合事务执行预检、提交、回滚和崩溃恢复。
- 新增 `fibra-plugin-archetype`，生成可在 Fibra 源码仓库之外独立构建和验证的标准多模块插件项目。
- 新增纯 Java Engine、Spring Boot 和仓库外分发验收工程，覆盖多插件关联升级、配置更新、失败恢复和发布坐标消费。
- 新增 Apache-2.0 根许可证、贡献指南、安全策略，以及带人工发布门的 Maven Central Portal 发布流程。

### 变更

- PF4J loader 与 config loader 改为可组合的 prepare/commit/rollback 机制；watcher、自动重试和周期重读统一移入 Engine，不再由 loader 各自维护。
- Engine 使用制品与配置的语义摘要分别跟踪 desired/applied revision，启动时立即执行首次收敛，并在单侧变化时只推进实际成功提交的分量。
- Spring 集成拆分为 `fibra-spring`、`fibra-spring-boot-autoconfigure` 和无生产代码的 `fibra-spring-boot-starter`；Spring 层只负责生命周期委托、属性映射和显式服务桥接。
- 运行时远程发布边界调整为九个运行时制品；另发布一个插件 Archetype，共十个制品。
- `fibra-benchmarks` 从可选 `benchmarks` profile 移入默认 reactor，使完整构建持续校验基准源码；它仍不发布、不进入可复现发布集，普通 Maven 构建也不执行 JMH 测量。
- 无源码 `fibra-spring-boot-starter` 使用 Maven Source Plugin 原生生成空 sources JAR，补齐每个发布制品固定的主 JAR、sources JAR、Javadoc JAR 和 POM 附件集合。
- 十个发布 POM 统一携带项目、许可证、开发者、SCM 和 Issue 元数据；十个主 JAR 统一携带项目许可证与第三方声明。
- Fibra 自有运维日志统一为 `event=fibra.* key=value` 格式，关键部署、对账、source 和生命周期事件可在默认日志后端中直接检索。
- Engine revision 使用带版本域的流式摘要计算，避免把完整制品读入内存，并明确区分摘要协议版本。

### 修复

- artifact/config source 在监听根目录或文件被删除后能够重新注册，并以周期重读补偿文件系统事件丢失。
- reconcile 调用线程被中断时，已排队操作会取消，已开始操作会等待真实结果，避免调用方看到与运行态不一致的失败。
- deployment journal 的 `COMMITTED` 成为唯一提交点；提交后的备份清理失败只记录警告，不再把已经生效的部署报告为失败。
- config loader 发布不可变的 source path 快照，避免异步 reconcile 读取尚未提交的配置来源。
- 不完整回滚后 Engine 会阻断所有后续变更入口，避免继续在不可信状态上提交部署。
- 同一插件最高版本存在不同内容摘要时拒绝部署；相同内容的重复路径按确定性顺序处理。

## [0.3.1] - 2026-08-24

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

## [0.3.0] - 2026-08-23

将 `fibra-loader-pf4j` 从「插件根目录直接放 JAR、单 JAR 事后回滚」重构为标准包 + 事务更新。

### 新增

- 定义标准插件包协议（`plugin.properties` + `lib/` 目录，ZIP 候选）。
- 完整 prospective 依赖图预检：缺失依赖、循环、版本范围在拆除运行态前判定。
- 批量事务替换 + 持久 journal + 崩溃恢复；相关 contract/provider/consumer 可一次关联升级。
- loader 逻辑事务门（`runExclusive`），在等待 Fibra lifecycle 时不持有物理锁，避免反向锁序。

### 变更

- PF4J 升级至 `3.15.0`。
- 删除直接 JAR API（`loadArtifact`/`reloadArtifact`），不保留兼容转发。

详见 openspec change `standardize-plugin-packages`（已归档）与[插件制品与事务更新设计](docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md)。

## [0.2.0] - 2026-08-23

- 新增框架中立的配置装载：配置驱动的多实例插件装载（`fibra-loader-config`），示例宿主改用配置装载装配插件树。

## [0.1.1] - 2026-08-23

- 新增仓库外多插件依赖链验收（独立进程黑盒验证 contract/provider/consumer 的 ClassLoader 隔离与依赖链）。
- 固化 Fibra 与 Spring Harness 的集成边界，明确插件方案参考边界。

## [0.1.0] - 2026-08-22

- 首个版本：Fibra Cordis 内核（Cordis Core 的 Java 语义等价实现，含严格 parity 验收）。
- PF4J 插件装载架构：插件原子更新与目录监听、真实 PF4J 插件依赖链黑盒验收。
- 建立可发布制品基线。
