# deployment-transaction Specification

## Purpose
TBD - created by archiving change establish-fibra-engine. Update Purpose after archive.
## Requirements
### Requirement: 联合部署只有一个持久事务真源

Engine SHALL 为一次 deployment 建立唯一 journal，并把 artifact/config change 作为参与者协调。参与者 MUST NOT 在联合部署中创建独立顶层 journal。

#### Scenario: 成功联合部署
- **WHEN** 两个参与者完成 prepare、commit 且全部 required entry readiness 成功
- **THEN** engine 原子推进 committed/applied revision，再调用参与者 complete 释放 previous 数据

#### Scenario: 任一阶段失败
- **WHEN** prepare、commit 或 readiness 任一步失败
- **THEN** engine 保留原异常，按 config 后 artifact 的逆序 rollback，恢复旧插件、旧配置和旧运行实例

### Requirement: 崩溃恢复不猜测运行图

Engine SHALL 根据 journal、prepared 数据和 previous 数据逐阶段恢复。无法证明前态或后态完整时 MUST 拒绝启动并报告 ROLLBACK。

#### Scenario: COMMITTING 中途崩溃
- **WHEN** 部分参与者已切换、部分尚未切换时进程终止
- **THEN** 下次构建 engine 时依据逐参与者 journal 状态恢复一致前态，不暴露部分新 deployment
