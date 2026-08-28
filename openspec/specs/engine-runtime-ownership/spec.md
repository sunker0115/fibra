# engine-runtime-ownership Specification

## Purpose
定义框架中立 engine 的唯一资源所有权、综合运行状态与关闭边界，防止宿主绕过协调域或后台工作脱离托管生命周期。
## Requirements
### Requirement: Engine 是框架中立的唯一托管所有者

系统 SHALL 以 `FibraEngine` 独占一个 root、一个 plugin loader、一个 config loader、两个可选 source、一个 reconcile worker 和一个 deployment journal root。engine compile/runtime 依赖图 MUST NOT 出现 Spring、Spring Boot、Spring Shell 或 Spring AI。

#### Scenario: 构建并启动 engine
- **WHEN** 用户以已存在安装目录和配置根构建并启动 engine
- **THEN** engine 完成 artifact 初载、config reconcile、required entry 总 readiness，再启动 source、进入 RUNNING 并立即请求首轮 reconcile

#### Scenario: 启动失败
- **WHEN** 初载、配置、readiness 或 source 创建任一步失败
- **THEN** engine 保留原异常、逆序关闭所有已取得资源并进入不可重启终止态

### Requirement: Engine 不泄露内部 Loader 所有权

Engine SHALL 提供 root、运行状态和结构化状态快照，但 MUST NOT 公开其内部 plugin loader 或 config loader。所有托管 artifact/config 变更 MUST 通过 engine API 进入同一协调域；root 的关闭权仍只属于 engine。

#### Scenario: 宿主读取托管运行态
- **WHEN** 宿主取得 engine 的 root 或状态快照
- **THEN** 宿主可桥接服务和查询状态，但没有可绕过 coordinator 直接 apply、refresh 或关闭内部 loader 的引用

#### Scenario: 正常关闭
- **WHEN** 用户关闭运行中的 engine
- **THEN** engine 停止接收工作、关闭 source、收敛 worker、关闭 config loader、plugin loader 和 root，重复关闭不重复执行资源销毁

### Requirement: 综合运行状态由活动失败统一决定

Engine SHALL 保留 `RUNNING` 与 `DEGRADED` 两个可运行状态。任一 reconcile 成功只能清除自身来源的活动失败；只要其它运行期活动失败尚存，状态 MUST 保持 DEGRADED。完整回滚的单次 deployment 请求失败 MUST 通过异常返回调用方，不得污染运行状态。

#### Scenario: 一个来源恢复但另一个仍失败
- **WHEN** artifact reconcile 已恢复而 config reconcile 仍失败
- **THEN** engine 保持 DEGRADED，直到所有运行期活动失败清除
