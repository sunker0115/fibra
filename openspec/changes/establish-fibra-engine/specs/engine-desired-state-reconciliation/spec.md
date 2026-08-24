## ADDED Requirements

### Requirement: Source 只标记期望状态可能变化

Artifact 和 config source SHALL 只向同一个 engine 队列提交 dirty signal，MUST NOT 直接调用 loader apply、refresh、mount、unmount 或 close。

#### Scenario: 两类事件同时到达
- **WHEN** artifact ZIP 与配置文件在同一轮执行前后同时变化
- **THEN** controller 串行读取完整期望状态，不交叉提交，并在执行期再次变 dirty 时至少追加一轮 reconcile

### Requirement: Reconcile 是 level-triggered 且可自愈

Controller SHALL 去重事件、比较 desired/applied revision、失败后有界退避，并通过周期 resync 修复丢失事件。失败 MUST 保留最后成功运行态。

#### Scenario: WatchService 丢失事件
- **WHEN** 文件系统最终状态变化但没有可用 watch event
- **THEN** 下一次周期 resync 发现 revision 差异并触发同一 reconcile 流程

#### Scenario: Reconcile 失败后输入修复
- **WHEN** 一轮 reconcile 因无效候选失败，随后文件被修复
- **THEN** engine 清除失败退避并最终推进 applied revision，不要求重启宿主

