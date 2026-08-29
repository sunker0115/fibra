# Fibra 仓库外多插件验收设计

状态：已被 `0.4.0` 的[示例与分发验收设计](2026-08-25-fibra-examples-and-distribution-verification-design.md)取代。

当前唯一有效结论如下：

- 用户插件工程的唯一模板是 `fibra-plugin-archetype`；
- 仓内可运行场景位于 `fibra-example/engine` 与 `fibra-example/spring-boot`；
- 仓库外黑盒工程位于 `verification/distribution`，不加入 Fibra reactor，也不继承 Fibra parent；
- `scripts/verify-distribution.sh` 必须验证十个发布 `artifact`、九个运行时 `artifact`、独立 Java/Engine/Spring Boot application，以及已部署 archetype 生成项目；
- 多插件图继续覆盖 contract-only、consumer-first、双 isolate、配置更新与失败恢复、不完整关联升级拒绝和完整三包升级。

本文不再定义第二套目录、模块或验收命令。所有实现与验收细节只读取上面的当前设计真源。
