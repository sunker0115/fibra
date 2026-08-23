## ADDED Requirements

### Requirement: 单一批量 Apply API
系统 SHALL 以 `applyArtifacts(List<Path>)` 作为安装新包、升级、降级和关联多包更新的唯一公开候选入口，并 MUST NOT 提供旧直接 JAR API 转发。

#### Scenario: 显式合法降级
- **WHEN** 调用方提交较低版本候选且最终完整图仍有效
- **THEN** apply 按正常事务完成降级

#### Scenario: 空批次
- **WHEN** 调用方提交空列表
- **THEN** 系统以 `IllegalArgumentException` 拒绝，不创建事务目录

### Requirement: 管理操作共享串行边界
制品 load/apply/stop/unload、entry mount/update/unmount 和配置 reconcile SHALL 使用同一个 loader 可重入独占锁。

#### Scenario: 配置刷新与制品更新并发
- **WHEN** 一个线程正在 reconcile 配置，另一个线程调用 apply
- **THEN** 两个操作按锁获取顺序串行完成，不观察或提交交叉中间态

### Requirement: 受影响运行态按依赖顺序重建
系统 SHALL 处理候选 ID加旧图和新图传递 dependents 的并集，dependent-first dispose/unload，dependency-first load/start，并按原稳定 entry 顺序 remount。

#### Scenario: 三层依赖链成功更新
- **WHEN** 底层 contract 或 provider 更新影响两层 dependents
- **THEN** 停止事件从最上游 dependent 到 dependency，启动事件从 dependency 到 dependent，全部旧 ClassLoader关闭

#### Scenario: 同制品多 entry 恢复
- **WHEN** 更新制品在多个 Context 中有多个 `entryId`
- **THEN** 每个 entry 都用原 `PluginInstanceSpec` 和当前 ClassLoader配置类型重建，不能只恢复一个实例

### Requirement: 安装目录交换具有持久 Journal
每个非 no-op 批次 SHALL 在 `plugins/.fibra-transactions/<txid>` 保存输入、新旧目录和原子更新的状态 journal，完整结构与状态见设计文档第 7.1 节。

#### Scenario: 多目录成功提交
- **WHEN** 多个候选完成目录交换和运行态恢复
- **THEN** 系统先持久化 `COMMITTED`，再清理事务目录，对外只暴露完整新图

#### Scenario: 外部候选保留
- **WHEN** 批次成功或失败
- **THEN** 调用方提供的 ZIP路径和字节均不被移动、删除或改写

### Requirement: 运行中失败恢复旧状态
正式 apply 任一步失败时，系统 SHALL 卸载新运行态、逆向恢复旧目录、旧 PF4J started 状态和全部旧 entries；恢复失败 MUST 形成 `ROLLBACK`。

#### Scenario: 新业务入口启动失败
- **WHEN** prospective 结构图有效但新入口在正式 mount 时失败
- **THEN** 旧目录版本、旧服务值、旧 entry 集合和旧 started 状态全部恢复，调用方收到 `APPLY` 异常

#### Scenario: 恢复自身失败
- **WHEN** 原 apply 失败且一个或多个恢复动作也失败
- **THEN** 最终异常 stage 为 `ROLLBACK`，原 apply 异常为 cause，恢复失败按发生顺序位于 suppressed，事务目录保留

### Requirement: 启动前恢复未完成事务
构造 loader 时 SHALL 在创建活动 PF4J manager 前扫描事务目录；未提交事务恢复旧图，已提交事务保留新图并完成清理，无法闭合的 journal MUST 阻止启动。

#### Scenario: INSTALLING 期间进程退出
- **WHEN** 下次启动发现非 `COMMITTED` journal和部分已交换目录
- **THEN** 系统根据 journal/previous 恢复完整旧安装图后才允许 load

#### Scenario: COMMITTED 后清理前退出
- **WHEN** 下次启动发现 `COMMITTED` journal仍有 previous/next 垃圾
- **THEN** 系统保留当前新安装图并清理事务垃圾

#### Scenario: Journal 损坏
- **WHEN** 事务目录无法证明应恢复的 ID、旧存在状态或目录闭合关系
- **THEN** loader 构造以 `ROLLBACK` 失败，不猜测一个插件图继续运行

### Requirement: 配置工厂不持有旧 ClassLoader对象
系统 SHALL 只快照可重复执行的 `PluginConfigFactory`，并可在正式 mount和失败恢复时多次调用；不得保存旧 typed config。

#### Scenario: 插件私有配置随更新重建
- **WHEN** 新版本在新 ClassLoader中定义配置类型
- **THEN** loader 把当前 `configType` 传给原配置工厂并创建新类型对象，不把旧 typed config传入新入口

### Requirement: Watcher 只执行确定的单包自动升级
Watcher SHALL 只对已安装 ID的严格更高版本 ZIP执行单包 apply；相同/更低版本忽略，多插件事务必须由部署协调器显式提交。

#### Scenario: 自动严格升级
- **WHEN** 去抖窗口内同 ID出现多个更高版本候选
- **THEN** Watcher 选择最高版本并调用一次单包 apply

#### Scenario: 自动候选需要关联升级
- **WHEN** 单包升级会破坏现有 dependent 范围
- **THEN** Watcher 暴露失败并保持旧状态，不等待或猜测其他文件组成批次

#### Scenario: 相同或更低版本
- **WHEN** Watcher看到版本不高于当前安装版本的候选
- **THEN** 它忽略候选且不创建事务

### Requirement: 稳定阶段错误
系统 SHALL 使用设计文档第 3.3 节定义的 `FibraArtifactException` 和阶段枚举报告跨阶段失败；异步 Watcher SHALL 同时通过 SLF4J 和 `lastFailure()` 暴露。

#### Scenario: 结构预检失败
- **WHEN** 候选包结构无效
- **THEN** 异常 stage 为 `VALIDATE`，packages 和可确定的 artifact IDs准确定位本批次

#### Scenario: 同步调用失败不重复记录
- **WHEN** `applyArtifacts` 在调用线程抛出异常
- **THEN** loader 不在同一路径重复记录该异常，由调用方决定日志策略

