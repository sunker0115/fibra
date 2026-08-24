## Context

engine 权威源为 [`docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md`](../../../docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md)，Spring 接缝权威源为 [`docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md`](../../../docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md)。本 change 必须在 `establish-fibra-engine` 的 API 与所有权冻结后实施。

## Decisions

### D1：Spring、autoconfigure、starter 三层

`fibra-spring` 依赖 engine 与 `spring-context`；autoconfigure 依赖 `fibra-spring` 和 Boot；starter 只依赖 autoconfigure。starter 主 JAR不含 class 或 imports。

### D2：Spring 只有生命周期委托

`FibraSpringLifecycle` 构造器只接收 `FibraEngine`。它不持有 loader、source 或 properties，不实现 load、readiness、reconcile、rollback 或资源关闭顺序。

### D3：Boot 属性只映射 engine builder

不可变 `FibraProperties` 表达 engine 路径、source、readiness、resync 和关闭参数。完整属性图在 engine 创建前校验；autoconfigure 只执行一对一 builder 映射，不建立第二套默认值。

### D4：默认托管单元整体退让

自动配置仅在不存在 `FibraEngine` 和 `Context` 时创建一个 engine、lifecycle、bridge 及其只读资源视图。任何一个上游所有者已存在都整体退让。

### D5：版本和依赖边界

autoconfigure 保存唯一 Spring Boot 4.1.0 BOM并覆盖 Reactor为 Fibra冻结版本。`fibra-spring` 不导入第二个 BOM；根 POM只管理内部模块当前 reactor 版本。

## Open Questions

无。
