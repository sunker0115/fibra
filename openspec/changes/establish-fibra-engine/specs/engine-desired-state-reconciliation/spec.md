## ADDED Requirements

### Requirement: Source 只标记期望状态可能变化

Artifact 和 config source SHALL 只向同一个 engine 队列提交 dirty signal，MUST NOT 直接调用 loader apply、refresh、mount、unmount 或 close。

#### Scenario: 两类事件同时到达
- **WHEN** artifact ZIP 与配置文件在同一轮执行前后同时变化
- **THEN** controller 串行读取完整期望状态，不交叉提交，并在执行期再次变 dirty 时至少追加一轮 reconcile

#### Scenario: 松散变化不能联合预检
- **WHEN** 同一轮读取发现彼此强耦合、各自无法对当前运行态通过预检的 artifact 与 config 变化
- **THEN** controller 固定按 artifact、config 两个独立事务尝试并分别记录失败，不共享候选 catalog 使其联合成功

#### Scenario: 候选目录有同插件多个版本
- **WHEN** incoming 中存在同一插件 ID 的多个合法候选
- **THEN** engine 只选择唯一最高 SemVer，忽略低于已安装版本的候选，同版本同摘要为 no-op，同版本不同摘要拒绝

### Requirement: Reconcile 是 level-triggered 且可自愈

Controller SHALL 去重事件、比较 desired/applied revision、失败后有界退避，并通过周期 resync 修复丢失事件。失败 MUST 保留最后成功运行态。

#### Scenario: WatchService 丢失事件
- **WHEN** 文件系统最终状态变化但没有可用 watch event
- **THEN** 下一次周期 resync 发现 revision 差异并触发同一 reconcile 流程

#### Scenario: Reconcile 失败后输入修复
- **WHEN** 一轮 reconcile 因无效候选失败，随后文件被修复
- **THEN** engine 清除失败退避并最终推进 applied revision，不要求重启宿主
