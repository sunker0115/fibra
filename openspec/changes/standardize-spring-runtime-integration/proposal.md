## Why

`0.3.1` 的 `fibra-spring-boot-starter` 同时承载代码和依赖入口，公开属性包含未实际创建 watcher 的开关、Web staging 策略和名不副实的 required plugin。最终架构已经把装载、readiness、source、reconcile、deployment、回滚和关闭统一归入框架中立 `fibra-engine`；Spring 层若再次持有 loader 或 watcher，会产生第二套运行时所有权。

## What Changes

- **BREAKING**：新增 `fibra-spring`，只提供 `FibraSpringLifecycle` 和 `FibraServiceBridge`。
- **BREAKING**：新增 `fibra-spring-boot-autoconfigure`，保存 Boot 属性、校验、自动配置、配置元数据和唯一 imports。
- **BREAKING**：`fibra-spring-boot-starter` 改为空生产代码的推荐依赖入口。
- **BREAKING**：删除旧扁平属性和公共 `FibraLifecycle`，改为 `engine/artifacts/config/startup/shutdown` 不可变配置，不提供兼容代码。
- 自动配置只构建一个 `FibraEngine`；只额外暴露 root 和 bridge，不把 engine 内部 loader 注册成 Spring bean，关闭权不转移。
- 宿主已有 `FibraEngine` 或 Fibra `Context` 时完整托管单元整体退让，不拼接部分资源。
- staging/upload 继续属于具体宿主，Web 示例使用独立命名空间。
- Spring change 依赖 `establish-fibra-engine`；本 change 不实现 watcher、reconcile、readiness 或 loader 事务算法。
- 本阶段可发布运行时制品由 engine change 完成后的七个增加为九个；archetype change 最终增加为十个。

## Capabilities

### New Capabilities

- `spring-adapter-packaging`：定义 `fibra-spring`、autoconfigure、空 starter 和 Spring-free 边界。
- `spring-runtime-configuration`：定义 Boot 属性、完整校验、元数据和宿主 staging 边界。
- `spring-engine-lifecycle`：定义 `SmartLifecycle` 委托、自动配置所有权退让和显式服务桥接。

## Impact

- 新增 `fibra-spring`、`fibra-spring-boot-autoconfigure`；清空 starter。
- Spring Boot 4.1.0 BOM只存在于 autoconfigure；根 POM和 engine 不出现 Spring依赖。
- Web example 改为只依赖 starter 和自己的 staging 属性。
- 新增两个公开签名基线；删除 starter 和旧 `FibraLifecycle` 签名。
- 九个运行时制品进入发布和可复现构建，最终十制品门禁由 archetype change 收口。
