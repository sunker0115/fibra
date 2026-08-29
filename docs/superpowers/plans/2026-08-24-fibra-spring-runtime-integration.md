# Fibra `0.4.0` Spring 运行时集成实施计划

日期：2026-08-24
状态：已实施、通过全量门禁并完成 OpenSpec 归档
架构真源：[Spring Boot 适配设计](../specs/2026-08-23-fibra-spring-boot-starter-design.md)
形式化真源：[`standardize-spring-runtime-integration`](../../../openspec/changes/archive/2026-08-24-standardize-spring-runtime-integration/)

本计划只实现 Spring 与已冻结 `FibraEngine` 的接缝，不实现第二套 source、watcher、reconcile、readiness、deployment、rollback 或关闭算法。若本计划、架构和 OpenSpec 不一致，必须先修正文档，不能用兼容代码同时保留两种语义。

## 1. 固定模块与依赖边界

修改根 `pom.xml`，在六个框架中立运行时 `artifact` 之后加入：

1. `fibra-spring`：只依赖 `fibra-engine` 与 Spring Context；
2. `fibra-spring-boot-autoconfigure`：依赖 `fibra-spring`，唯一导入 Spring Boot BOM并保存自动配置入口；
3. `fibra-spring-boot-starter`：只依赖 autoconfigure，无生产源码、自动配置入口或业务资源。

父 POM只管理三个内部模块的 `${revision}` 坐标，不导入 Spring BOM、不声明 Spring依赖。`fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config`、`fibra-engine` 的 compile/runtime 依赖树必须保持 Spring-free。

验证：`ReleaseArtifactBaselineTest` 检查九个运行时 `artifact` 附件和 starter 空主 JAR；`EngineDependencyBoundaryTest` 检查六个中立模块依赖边界。

## 2. 实现 Spring Framework 接缝

在 `fibra-spring` 实现两个公开 final 类型：

- `FibraSpringLifecycle` 构造器只接收 `FibraEngine`；`start()`、`stop()`、`stop(Runnable)`、`isRunning()` 只委托 Engine，callback 在关闭成功或失败时都恰好调用一次；
- `FibraServiceBridge` 构造器只接收 root `Context`，`register(ServiceKey<T>, T)` 只执行显式服务注册，不按类型扫描或把插件注册成 Spring Bean。

先由 `FibraSpringLifecycleTest` 锁定启动、关闭、callback 和异常传播，再实现生命周期；由 `FibraServiceBridgeTest` 锁定显式 key、服务所有权和撤销边界。Spring适配不得持有 loader、source、路径或配置属性。

## 3. 实现不可变 Boot 属性与自动配置

在 `fibra-spring-boot-autoconfigure` 实现 `@ConfigurationProperties("fibra")` 的不可变 record 图：

- `engine`：`resync-interval`、`retry-initial-backoff`、`retry-max-backoff`；
- `artifacts`：`installed-root`、`incoming-root`、`watch.enabled`、`watch.debounce`；
- `config`：`location`、`watch.enabled`、`watch.debounce`；
- `startup`：`required-entries`、`readiness-timeout`；
- `shutdown`：`root-close-timeout`。

默认值只定义一次并与 `FibraEngine.Builder` 一对一：30s resync、250ms/30s retry、两个 source 默认关闭且 debounce 1s、required entries 空、60s 总 readiness 预算、30s root close。自动配置创建 Engine 前一次性校验必需路径、正时长、退避顺序及 required entry 非空唯一；错误必须包含完整属性键和值。

`FibraAutoConfiguration` 以类级 `@ConditionalOnMissingBean({FibraEngine.class, Context.class})` 整体退让。默认托管单元只创建一个 Engine、其 root 只读视图、bridge 和 lifecycle。Engine 与 root bean 的 `destroyMethod` 为空，最终关闭只由 lifecycle 委托 Engine执行一次。`AutoConfiguration.imports` 只列这一入口。

测试：

- `FibraPropertiesTest` 锁定默认值、不可变绑定和完整配置元数据；
- `FibraAutoConfigurationTest` 锁定 builder 映射、属性失败、已有 Engine退让、已有 Context退让和无旧属性。

## 4. 迁移示例与 API 门禁

`fibra-example-spring-boot-application` 只直接依赖 starter。`application.yml` 使用本计划的属性图；上传暂存目录属于 `example.fibra.staging-root`，不能进入通用 `FibraProperties`。Web controller 只负责把本地 deployment 路径交给公开 `FibraEngine.applyDeployment(...)`，不接触 loader 或内部 coordinator。

`FibraSpringBootExampleApplicationIT` 必须从真实 Spring Boot 上下文验证自动配置启动、服务访问、deployment 应用、回滚和 ApplicationContext 关闭。测试不能用手工构造替代 Boot wiring。

API 签名分别写入：

- `docs/api/fibra-spring-public-signatures.txt`；
- `docs/api/fibra-spring-boot-autoconfigure-public-signatures.txt`。

删除 `fibra-spring-boot-starter-public-signatures.txt`。签名不得包含 bean 方法、loader/source/coordinator、旧 `FibraLifecycle` 或 starter Java 类型。

## 5. 完成门禁与提交边界

按顺序执行：

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

随后严格验证并归档 `establish-fibra-engine`、`standardize-spring-runtime-integration` 和 `publish-plugin-archetype`。归档后稳定 OpenSpec 必须只描述 Engine 托管 source 与 Spring三模块结构，不得保留 loader watcher 为当前能力。

完成标准：九个运行时 `artifact` 加一个 archetype 工具 `artifact` 全部通过附件和可复现门禁；Spring示例真实启动；六个中立 `artifact` 无 Spring；公开签名与文档一致；`main` 保持 `v0.3.1`，全部 `0.4.0-SNAPSHOT` 提交只存在于 `codex/0.4.0-development`。

最终提交边界：`feat: complete Fibra 0.4.0 runtime architecture`。
