## ADDED Requirements

### Requirement: Engine 是框架中立的唯一托管所有者

系统 SHALL 以 `FibraEngine` 独占一个 root、一个 plugin loader、一个 config loader、两个可选 source、一个 reconcile worker 和一个 deployment journal root。engine compile/runtime 依赖图 MUST NOT 出现 Spring、Spring Boot、Spring Shell 或 Spring AI。

#### Scenario: 构建并启动 engine
- **WHEN** 用户以已存在安装目录和配置根构建并启动 engine
- **THEN** engine 完成 artifact 初载、config reconcile、required entry 总 readiness，再启动 source 并进入 RUNNING

#### Scenario: 启动失败
- **WHEN** 初载、配置、readiness 或 source 创建任一步失败
- **THEN** engine 保留原异常、逆序关闭所有已取得资源并进入不可重启终止态

### Requirement: Engine 提供最小只读运行视图

Engine SHALL 提供 root、plugin loader、config loader、运行状态和结构化状态快照；调用方 MUST NOT 通过这些视图绕过 engine 关闭资源。

#### Scenario: 正常关闭
- **WHEN** 用户关闭运行中的 engine
- **THEN** engine 停止接收工作、关闭 source、收敛 worker、关闭 config loader、plugin loader 和 root，重复关闭不重复执行资源销毁

