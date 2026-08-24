## Context

完整模块、职责、控制流、部署格式、事务所有权、Spring 接缝和测试边界以 [`docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md`](../../../docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md) 为唯一上游权威源。本文件只记录实施决策。

## Decisions

### D1：保留原有五个基础模块并重构两个 loader

不重命名、合并或删除 `fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config`。watch 和宿主策略从 loader 移出；loader 保留机制责任并提供唯一事务底层。

### D2：新增独立 `fibra-engine`

engine 是有生产代码和独立公共 API 的可发布 JAR，不是聚合 POM。它按源码真实使用关系直接依赖 `fibra-api`、`fibra-core`、`fibra-loader-pf4j`、`fibra-loader-config`、PF4J 与 SLF4J；不得依靠 config loader 的传递依赖偶然取得其它类型。任何 Spring 坐标不得进入其 compile/runtime 依赖图。

### D3：双 source 只触发 level-triggered reconcile

source 不携带可直接执行的操作。controller 使用有界去重队列，重新读取完整期望状态；执行中到达的新 dirty 保证当前轮后至少再执行一轮，周期 resync 纠正丢失事件。

### D4：联合部署需要显式 package

不根据两个文件事件的时间接近程度猜测一个事务。强耦合 plugin/config 更新只能通过标准 deployment ZIP；松散目录变化继续按最终状态 reconcile。

### D5：联合事务只有一个 journal

artifact/config change 是可逆参与者。engine journal 是联合部署唯一真源，readiness 在 commit 后、complete 前执行；失败逆序 rollback。单独 loader 操作使用同一参与者实现，由 loader 自己建立单参与者 journal。

### D6：当前 Spring change 依赖本 change

Spring adapter 只构建或委托 `FibraEngine`。删除 loader watcher API 与迁移 Spring 消费点必须在同一可编译提交边界完成，不保留临时兼容类型。

### D7：托管 Engine 不公开真实 Loader

`FibraEngine` 只公开 root、状态、reconcile 和 deployment 能力，不返回内部 `FibraPluginLoader`/`FibraConfigLoader`。否则调用方仍可绕过 controller 直接 apply、refresh 或 close，唯一串行域无法由类型边界保证。需要 loader 低层能力的非托管宿主自行构造并拥有 loader，不与 engine 混用。

## Open Questions

无。实施细节由本 change 的 tasks 和对应 implementation plan 冻结后执行。
