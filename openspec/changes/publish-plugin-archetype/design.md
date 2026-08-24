## Context

总体边界以 [`docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md`](../../../docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md) 第 7 节为权威源。本 change 依赖 `establish-fibra-engine` 的 deployment package 和 `standardize-spring-runtime-integration` 的最终发布布局，但生成插件本身不依赖 Spring。

## Decisions

### D1：发布 Maven Archetype，不发布可复制源码目录

artifactId 固定为 `fibra-plugin-archetype`。用户通过标准 `mvn archetype:generate` 或 IDE Maven Archetype 界面生成；不要求克隆 Fibra 仓库。

### D2：生成项目完全独立

生成根 POM不继承 Fibra parent、不使用 `${revision}`、reactor `target/classes`、systemPath 或本地脚本。Fibra 版本位于生成项目 properties/dependencyManagement。

### D3：默认生成 contract + plugin 两模块

默认结构直接表达多插件共享类型的正确边界。contract 依赖 `fibra-api` provided；plugin 依赖 contract、`fibra-pf4j-api` 和 PF4J provided；私有业务库才进入 plugin ZIP `lib/`。

### D4：生成后必须直接验证

archetype 使用 Maven 3.4.1 官方 integration-test，在 `verify` 阶段生成项目并执行其 verify。外部脚本再检查 ZIP布局并由 `FibraEngine` 加载。

## Open Questions

无。

