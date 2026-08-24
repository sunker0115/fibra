## MODIFIED Requirements

### Requirement: 管理操作共享逻辑事务门

制品 load/apply/stop/unload、entry mount/update/unmount 和配置 reconcile SHALL 使用同一个 loader 可重入逻辑事务门；事务门 MUST NOT 在文件操作、PF4J 调用、插件回调或等待 Fibra lifecycle 时持有物理锁。自动 source 与重试已迁入 Engine，loader 不再定义 watcher 竞争语义。

#### Scenario: 配置刷新与制品更新并发
- **WHEN** 一个线程正在 reconcile 配置，另一个线程调用 apply
- **THEN** 后到的同步操作立即收到 `FibraPluginLoaderBusyException`，两个操作不观察或提交交叉中间态

#### Scenario: Lifecycle 回调反向管理 loader
- **WHEN** Fibra lifecycle 或其他 Reactor non-blocking 线程调用同步 loader 管理 API
- **THEN** 调用立即收到 `FibraPluginLoaderBusyException`，不得等待 loader 事务或阻塞 lifecycle Scheduler

#### Scenario: 更新期间读取身份
- **WHEN** lifecycle 回调在 apply 期间读取 `artifactIds()` 或 `entryIds()`
- **THEN** 查询从上一次完整提交的不可变快照返回，不获取事务门且不死锁

#### Scenario: 活动事务期间关闭 loader
- **WHEN** 另一事务活动或 `runExclusive` 回调尚未返回时调用 `close()`
- **THEN** 调用立即收到 `FibraPluginLoaderBusyException`，loader 保持打开且不执行部分关闭

### Requirement: 稳定阶段错误

系统 SHALL 使用 `FibraArtifactException` 和阶段枚举报告跨阶段失败。`FibraPluginLoaderBusyException` SHALL 只表达同步管理 API 的事务竞争或 Reactor non-blocking 线程误用，不得包装为某个制品阶段失败。

#### Scenario: 结构预检失败
- **WHEN** 候选包结构无效
- **THEN** 异常 stage 为 `VALIDATE`，packages 和可确定的 artifact IDs准确定位本批次

#### Scenario: 同步调用失败不重复记录
- **WHEN** `applyArtifacts` 在调用线程抛出异常
- **THEN** loader 不在同一路径重复记录该异常，由调用方决定日志策略

### Requirement: 运行中失败恢复旧状态

制品更新 SHALL 由唯一 artifact change 实现 plan、prepare、commit、complete 和 rollback。单 artifact 调用由 loader 建立单参与者 journal；engine 联合部署时使用同一 change 作为参与者并由 engine journal 统一协调。无论哪种入口，更新后运行态 apply 或 readiness 失败时，系统 MUST 恢复原版本包、原 entry 配置工厂、原依赖图和原可运行状态，不得留下部分新版本、重复 entry 或孤儿 ClassLoader。

#### Scenario: 单资源更新失败
- **WHEN** 调用 loader 单资源便捷 API且新版本运行态 apply 失败
- **THEN** 便捷 API通过同一 change rollback 恢复旧状态，不存在另一套兼容实现

#### Scenario: 联合部署中的 artifact 参与者失败
- **WHEN** artifact change 已 commit 但 config 或 readiness 随后失败
- **THEN** engine 调用该 change rollback，previous 数据在 engine complete 前一直可用于恢复

## REMOVED Requirements

### Requirement: Watcher 只执行确定的单包自动升级

**Reason**：watcher、去抖、周期 resync、失败退避和期望状态选择已经统一迁入 `fibra-engine`。loader 只保留制品机制和事务参与者，不再发布 `FibraPluginWatcher` 或 watcher failure API。

**Migration**：托管宿主使用 `FibraEngine.Builder.artifactSource(...)`；非托管宿主显式调用 loader API并自行拥有完整生命周期，不存在旧 watcher 兼容入口。
