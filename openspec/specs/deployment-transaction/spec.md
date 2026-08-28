# deployment-transaction Specification

## Purpose
定义 artifact/config 联合部署的唯一持久事务、明确提交点、同步调用结果与崩溃恢复语义。
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

### Requirement: 同步命令结果与副作用一致

durable `COMMITTED` journal SHALL 是唯一对外提交点。提交点前失败 MUST 逆序回滚；提交点后 participant complete 或事务目录清理失败 MUST 保留 journal 供恢复、记录 WARN并返回成功，不得把已生效部署报告为失败。

#### Scenario: 等待线程在操作开始前被中断
- **WHEN** 调用线程等待队列中的 deployment 时被中断且 worker 尚未取得 operation
- **THEN** coordinator 移除 operation、恢复中断标志并返回取消失败，该 deployment 此后不得执行

#### Scenario: 等待线程在提交期间被中断
- **WHEN** worker 已取得 deployment operation 后调用线程被中断
- **THEN** coordinator 等待 operation 的真实结果，再恢复调用线程中断标志，不返回不确定结果

#### Scenario: COMMITTED 后清理失败
- **WHEN** journal 已持久化 COMMITTED，随后 participant complete 或事务目录删除失败
- **THEN** applyDeployment 返回 committed revision，保留 journal 供下次启动验证并清理
