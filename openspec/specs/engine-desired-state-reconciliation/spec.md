# engine-desired-state-reconciliation Specification

## Purpose
定义文件系统来源下的 level-triggered 收敛、revision 分量和 source 恢复边界，确保丢失事件、部分成功与启动前候选都能得到一致处理。
## Requirements
### Requirement: Source 只标记期望状态可能变化

Artifact 和 config source SHALL 只向同一个 engine 队列提交 dirty signal，MUST NOT 直接调用 loader apply、refresh、mount、unmount 或 close。

#### Scenario: 两类事件同时到达
- **WHEN** artifact ZIP 与配置文件在同一轮执行前后同时变化
- **THEN** coordinator 串行读取完整期望状态，不交叉提交，并在执行期再次变 dirty 时至少追加一轮 reconcile

#### Scenario: 松散变化不能联合预检
- **WHEN** 同一轮读取发现彼此强耦合、各自无法对当前运行态通过预检的 artifact 与 config 变化
- **THEN** coordinator 固定按 artifact、config 两个独立事务尝试并分别记录失败，不共享候选 catalog 使其联合成功

#### Scenario: 候选目录有同插件多个版本
- **WHEN** incoming 中存在同一插件 ID 的多个合法候选
- **THEN** engine 只选择唯一最高 SemVer，忽略低于已安装版本的候选，同版本同摘要为 no-op，同版本不同摘要拒绝

### Requirement: Reconcile 是 level-triggered 且可自愈

Coordinator SHALL 去重 dirty、比较最近观察的 desired revision 与实际 applied revision、失败后有界退避，并通过周期 resync 修复丢失事件。revision MUST 由 artifact/config 分量组合；applied config 分量 MUST 来自最后成功配置快照，不得重新读取失败后的磁盘文件。

#### Scenario: WatchService 丢失事件
- **WHEN** 文件系统最终状态变化但没有可用 watch event
- **THEN** 下一次周期 resync 发现 revision 差异并触发同一 reconcile 流程

#### Scenario: Reconcile 失败后输入修复
- **WHEN** 一轮 reconcile 因无效候选失败，随后文件被修复
- **THEN** engine 清除失败退避并最终推进 applied revision，不要求重启宿主

#### Scenario: 两个参与者只有一个成功
- **WHEN** artifact 事务成功而 config 事务失败，或反之
- **THEN** applied revision 由成功参与者的新分量和失败参与者的旧分量组成，准确表示当前运行态

#### Scenario: 启动前已有候选
- **WHEN** engine 初载与 readiness 成功时 incoming 目录已经存在候选
- **THEN** engine 启动 coordinator/source 后立即请求首轮 reconcile，无需等待新的文件事件

#### Scenario: 启动前已有坏候选
- **WHEN** installed artifact 与当前配置可以正常启动，但 incoming 已存在无效 ZIP
- **THEN** start 成功进入运行态，首轮异步 reconcile 将状态标为 DEGRADED并继续退避重试

### Requirement: Source 不与 Loader 操作门竞争

Config loader SHALL 发布无锁不可变 `sourcePaths()` 快照。Config source 读取该快照时 MUST NOT 进入 artifact/config 共享操作门；失效 WatchKey MUST 从注册集合移除，使目录或目标文件恢复后可以重新注册。

#### Scenario: Reconcile 与监听注册并发
- **WHEN** coordinator 正在执行 config refresh，source 同时刷新监听路径
- **THEN** sourcePaths 读取立即返回最近快照，不因 BusyException 终止监听线程

#### Scenario: 监听目录删除后重建
- **WHEN** 一个 config WatchKey 失效，随后目录和目标文件被重建
- **THEN** source 移除旧 key、重新注册并再次产生 dirty signal
