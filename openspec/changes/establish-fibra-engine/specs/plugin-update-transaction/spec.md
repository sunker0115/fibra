## MODIFIED Requirements

### Requirement: 运行态 apply 失败时恢复原版本、原配置和原 ACTIVE 状态

制品更新 SHALL 由唯一 artifact change 实现 plan、prepare、commit、complete 和 rollback。单 artifact 调用由 loader 建立单参与者 journal；engine 联合部署时使用同一 change 作为参与者并由 engine journal 统一协调。无论哪种入口，更新后运行态 apply 或 readiness 失败时，系统 MUST 恢复原版本包、原 entry 配置工厂、原依赖图和原可运行状态，不得留下部分新版本、重复 entry 或孤儿 ClassLoader。

#### Scenario: 单资源更新失败
- **WHEN** 调用 loader 单资源便捷 API且新版本运行态 apply 失败
- **THEN** 便捷 API通过同一 change rollback 恢复旧状态，不存在另一套兼容实现

#### Scenario: 联合部署中的 artifact 参与者失败
- **WHEN** artifact change 已 commit 但 config 或 readiness 随后失败
- **THEN** engine 调用该 change rollback，previous 数据在 engine complete 前一直可用于恢复

