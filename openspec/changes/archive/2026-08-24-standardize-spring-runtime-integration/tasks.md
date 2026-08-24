## 1. 版本与模块边界

- [x] 1.1 验证 engine change 已将 reactor revision 切换为 `0.4.0-SNAPSHOT`，保持 `v0.3.1` 不变
- [x] 1.2 新增可发布 `fibra-spring` 和 `fibra-spring-boot-autoconfigure`
- [x] 1.3 把 starter 改为无生产代码依赖入口，统一九个运行时制品门禁

## 2. 配置契约

- [x] 2.1 以 TDD 实现不可变 `FibraProperties`、默认值和 engine builder 一对一映射
- [x] 2.2 以 TDD 实现创建 engine 前的完整属性校验和精确错误
- [x] 2.3 删除旧属性、旧 getter/setter 和通用 staging，不提供兼容代码

## 3. Spring Framework 适配

- [x] 3.1 以 TDD 实现只委托 `FibraEngine` 的 `FibraSpringLifecycle`
- [x] 3.2 保持 `FibraServiceBridge` 显式 ServiceKey 桥接，禁止插件 bean 自动注入
- [x] 3.3 验证 lifecycle callback、异常传播和 engine 唯一关闭所有权

## 4. Boot 自动配置

- [x] 4.1 以 TDD 实现完整托管单元及已有 Engine/Context 时整体退让
- [x] 4.2 只读暴露 root 和 bridge，不注册 engine 内部 loader bean，禁止 Spring destroy 重复关闭
- [x] 4.3 生成准确配置元数据并验证无旧属性

## 5. 示例、API 与发布

- [x] 5.1 Web 示例只引入 starter，staging 迁到示例命名空间
- [x] 5.2 用真实 engine 黑盒验证 Boot wiring、reconcile 和 ApplicationContext 关闭
- [x] 5.3 重建 spring/autoconfigure 签名，删除 starter 和旧 lifecycle 签名
- [x] 5.4 执行全 reactor、九运行时制品、依赖边界、外部消费和可复现构建
- [x] 5.5 更新文档、完成审查并归档 change

精确实施细节由 `docs/superpowers/plans/2026-08-24-fibra-spring-runtime-integration.md` 作为唯一权威源；计划须在 engine plan 后重写并通过人工闸门。
