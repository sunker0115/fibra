## Why

当前 artifact/config watcher 直接调用两个 loader 的一步式变更方法。共享 gate 能阻止并行提交，但不能提供事件去重、丢事件恢复、统一状态、跨 plugin/config 预检或联合部署事务。若直接在 Spring 层编排这些命令式 API，纯 Java与其他框架宿主无法复用，Spring 也会错误成为运行时核心。

`fibra-engine` 需要成为框架中立的托管层；两个 loader 同时重构为可计划、可准备、可提交、可回滚的事务参与者，使持续 reconcile 和显式 deployment package 共享同一底层机制。

## What Changes

- **BREAKING**：新增可发布 `fibra-engine`，作为 root、两个 loader、source、reconcile、readiness、deployment 和关闭的唯一所有者。
- **BREAKING**：删除 loader 层直接执行业务变更的 watcher 公共 API；文件 source 只存在于 engine 内部并只提交 dirty signal。
- **BREAKING**：两个 loader 的一步式内部流程重构为 plan/prepare/commit/complete/rollback；单资源便捷方法调用同一底层流程，不保留双实现。
- 新增 level-triggered 单 worker reconcile、去重、执行期 dirty、周期 resync、有界退避和结构化状态。
- 新增标准 deployment package，把多个 plugin ZIP 和目标 config 作为显式联合事务处理，摘要固定 SHA-256。
- engine 联合部署使用唯一持久 journal；参与 loader 不创建互相独立的顶层 journal。
- 保持 `fibra-api`、`fibra-core`、Cordis 71 用例和 PF4J 插件入口语义不变。
- 可发布 `artifact` 在本 change 阶段从六个增加为七个；Spring 与 archetype 由后续 change 增加。

## Capabilities

### New Capabilities

- `engine-runtime-ownership`：定义 engine 公共入口、资源所有权、启动、readiness、状态和关闭。
- `engine-desired-state-reconciliation`：定义双 source、单队列、level-triggered 收敛、重试和 resync。
- `deployment-package-format`：定义 plugin/config 联合发布包格式、身份、摘要和安全校验。
- `deployment-transaction`：定义 loader 参与者、唯一 journal、prepare/commit/readiness/complete/rollback 和崩溃恢复。

### Modified Capabilities

- `plugin-update-transaction`：artifact 单资源事务改为可被 engine 联合事务协调的参与者模型。

## Impact

- 新增：`fibra-engine` 生产模块、测试、API签名和发布 `artifact`。
- 重构：`fibra-loader-pf4j`、`fibra-loader-config` 的 watcher、计划、事务和公开签名。
- 协调：当前 Spring change 必须改为依赖 `FibraEngine`，不能再直接持有 loader/watcher。
- 示例：纯 Java host 改用 engine；loader 测试继续验证手工单资源操作。
- 发布：内部 dependencyManagement、API baseline、外部消费和可复现构建增加 engine。

