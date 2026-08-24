## Why

`fibra-spring-boot-starter` 在 `0.3.1` 已能创建 root、两个 loader 和服务桥接，但公开的 watcher 配置没有创建任何 watcher，`staging-root` 把 Web 上传策略混入通用适配，`startup-required-plugins` 实际检查的却是 entryId。当前单一模块还同时承载自动配置实现和用户依赖入口，公开签名错误冻结了 lifecycle 构造器和自动配置 bean 方法。

更严重的是，当前 `FibraLifecycle.start()` 在完成 artifact load 或 config reconcile 后发生 readiness 失败时不会自行反向回滚；因为 lifecycle 尚未进入 running，不能假设 Spring 会替失败中的 bean 关闭部分资源。继续局部补 watcher 会把错误所有权和错误契约带入后续交互式 CLI。

`0.4.0` 需要先冻结通用 Spring 运行时集成，使 Web、CLI、Spring AI 或其他宿主共享同一正确边界，再单独建设上层能力。

## What Changes

- **BREAKING**：新增 `fibra-spring-boot-autoconfigure` 保存全部自动配置实现；`fibra-spring-boot-starter` 改为无生产代码的依赖入口。
- **BREAKING**：将 reactor 版本切换为 `0.4.0-SNAPSHOT`，不移动已发布 `v0.3.1`。
- **BREAKING**：删除旧扁平属性和公共 `FibraLifecycle` API，改为 `artifacts/config/startup/shutdown` 四段不可变配置结构，不提供旧属性兼容。
- 删除通用 `staging-root`；上传暂存迁到 Web 示例自己的属性命名空间。
- artifact watcher 与 config watcher 分别按属性启用，由单一 lifecycle 在初始装载和 readiness 后创建、运行并逆序关闭。
- readiness 明确按 entryId 检查，全部必需 entry 共用一个总时限，稳定 PENDING 直接按状态失败。
- 启动任一步失败时，由 lifecycle 对已完成阶段执行完整反向回滚，保留原异常并有序附加回滚异常。
- 正常关闭固定为 artifact watcher、config watcher、config loader、plugin loader、root Context；超时属性只准确约束 root 异步关闭。
- 自动配置采用完整托管单元：宿主已有 Fibra `Context` 时整体退让，不拼接部分宿主资源与部分框架资源。
- 发布、公开 API、Web 示例、README、API 手册、release、可复现构建和依赖边界验收同步为七个可发布制品。

## Capabilities

### New Capabilities

- `spring-adapter-packaging`：定义自动配置实现模块、无代码 starter、发布制品和 Spring-free 边界。
- `spring-runtime-configuration`：定义唯一 `fibra.*` 属性结构、条件必填、默认值、校验和 staging 责任归属。
- `spring-runtime-lifecycle`：定义启动、readiness、两个 watcher、失败回滚、关闭顺序和服务桥接边界。

### Modified Capabilities

无。仓库此前没有 Spring 适配的 OpenSpec 稳定规格，`0.3.1` 行为只存在于实现、README 和 superpowers 草案中。

## Impact

- 生产模块：新增 `fibra-spring-boot-autoconfigure`；移动 `fibra-spring-boot-starter` 的 Java 源码、测试和自动配置资源；Web 示例新增自己的 staging 属性。
- 公开 API：`FibraProperties` 改为嵌套不可变结构；`FibraLifecycle` 转为内部类型；starter 不再有 Java API；`FibraServiceBridge` 语义不变。
- 构建发布：revision 进入 `0.4.0-SNAPSHOT`，内部 dependencyManagement、发布模块列表、签名门禁和可复现构建从六制品调整为七制品。
- 运行语义：两个 watcher 真正按属性启动；启动失败不再泄漏 loader、watch service、线程或 root Scheduler。
- 文档与示例：删除旧属性和“starter 已托管 watcher”的错误描述，Spring Web staging 归宿主所有，CLI/Spring Shell 仍属于后续独立 change。
- 依赖：Spring Boot 4.1.0 继续只由 Spring 自动配置实现模块管理；五个中立制品和根父 POM不引入 Spring 依赖。
