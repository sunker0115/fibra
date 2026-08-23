# plugin-update-transaction Specification

## Purpose
TBD - created by archiving change standardize-plugin-packages. Update Purpose after archive.
## Requirements
### Requirement: 单一批量 Apply API
系统 SHALL 以 `applyArtifacts(List<Path>)` 作为安装新包、升级、降级和关联多包更新的唯一公开候选入口，并 MUST NOT 提供旧直接 JAR API 转发。

#### Scenario: 显式合法降级
- **WHEN** 调用方提交较低版本候选且最终完整图仍有效
- **THEN** apply 按正常事务完成降级

#### Scenario: 空批次
- **WHEN** 调用方提交空列表
- **THEN** 系统以 `IllegalArgumentException` 拒绝，不创建事务目录

### Requirement: 运行态先由完整安装图初始化
构造 loader SHALL 只恢复磁盘事务；宿主 MUST 先调用可重复的 `loadArtifacts()` 校验并同步完整安装图，之后才能 apply 或操作运行实例。`unloadArtifact` MUST NOT 删除标准安装目录，后续 `loadArtifacts()` SHALL 能重新装载仍在磁盘但未活动的制品。

#### Scenario: 初始化前提交候选
- **WHEN** 构造 loader 后尚未成功调用 `loadArtifacts()` 就调用 apply 或 mount
- **THEN** 系统以 `IllegalStateException` 拒绝，不创建事务、不创建活动 ClassLoader

#### Scenario: 显式卸载后重新同步
- **WHEN** 制品被 `unloadArtifact` 从活动 manager 卸载但标准安装目录仍存在，随后再次调用 `loadArtifacts()`
- **THEN** 系统按当前完整安装图重新校验并装载该制品，不需要旧单包 load API

### Requirement: 管理操作共享逻辑事务门
制品 load/apply/stop/unload、entry mount/update/unmount 和配置 reconcile SHALL 使用同一个 loader 可重入逻辑事务门；事务门 MUST NOT 在文件操作、PF4J 调用、插件回调或等待 Fibra lifecycle 时持有物理锁。

#### Scenario: 配置刷新与制品更新并发
- **WHEN** 一个线程正在 reconcile 配置，另一个线程调用 apply
- **THEN** 后到的同步操作立即收到 `FibraPluginLoaderBusyException`，两个操作不观察或提交交叉中间态

#### Scenario: Watcher 遇到活动事务
- **WHEN** 配置或制品 watcher 在另一个事务活动期间触发
- **THEN** watcher 保留 dirty 状态并在事务释放后重新执行，不把报忙当作最终 reload failure

#### Scenario: Lifecycle 回调反向管理 loader
- **WHEN** Fibra lifecycle 或其他 Reactor non-blocking 线程调用同步 loader 管理 API
- **THEN** 调用立即收到 `FibraPluginLoaderBusyException`，不得等待 loader 事务或阻塞 lifecycle Scheduler

#### Scenario: 更新期间读取身份
- **WHEN** lifecycle 回调在 apply 期间读取 `artifactIds()` 或 `entryIds()`
- **THEN** 查询从上一次完整提交的不可变快照返回，不获取事务门且不死锁

#### Scenario: 活动事务期间关闭 loader
- **WHEN** 另一事务活动或 `runExclusive` 回调尚未返回时调用 `close()`
- **THEN** 调用立即收到 `FibraPluginLoaderBusyException`，loader 保持打开且不执行部分关闭

### Requirement: 受影响运行态按依赖顺序重建
系统 SHALL 处理候选 ID加旧图和新图传递 dependents 的并集，dependent-first dispose/unload，dependency-first load/start，并按原稳定 entry 顺序 remount。

#### Scenario: 三层依赖链成功更新
- **WHEN** 底层 contract 或 provider 更新影响两层 dependents
- **THEN** 停止事件从最上游 dependent 到 dependency，启动事件从 dependency 到 dependent，全部旧 ClassLoader关闭

#### Scenario: 同制品多 entry 恢复
- **WHEN** 更新制品在多个 Context 中有多个 `entryId`
- **THEN** 每个 entry 都用原 `PluginInstanceSpec` 和当前 ClassLoader配置类型重建，不能只恢复一个实例

### Requirement: 安装目录交换具有持久 Journal
每个候选批次 SHALL 先在 `plugins/.fibra-preflight/<txid>` 完成无运行态副作用的预检；每个非 no-op 正式批次 SHALL 在 `plugins/.fibra-transactions/<txid>` 以原子发布并 force 的 `PREPARED` journal 作为第一个持久动作，再保存输入、新旧目录并推进状态。每次 journal 和目录 move MUST 在进入下一步前 force 对应文件及父目录；完整结构与状态见设计文档第 7.1 节。

#### Scenario: 文件系统缺少持久原子能力
- **WHEN** journal 或插件目录所在文件系统不支持要求的原子 move 或目录 force
- **THEN** 正式事务以当前阶段失败并恢复，不退化为普通 move、复制覆盖或非持久 journal

#### Scenario: 预检期间进程退出
- **WHEN** 下次启动只发现无 journal 的 `.fibra-preflight` 工作区或空正式事务目录
- **THEN** loader 将其作为未触碰安装图的垃圾清理，不报告 `ROLLBACK`

#### Scenario: 无 Journal 却存在旧目录
- **WHEN** 正式事务目录没有 journal 但存在 `previous/<id>`
- **THEN** loader 以 `ROLLBACK` 拒绝启动，因为该组合违反 journal-first 不变量

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
- **THEN** 系统按候选安装逆序，以每个 ID 的旧存在状态、旧/新摘要及 `plugins/previous/next` 合法组合恢复完整旧安装图后才允许 load

#### Scenario: 已存在 ID 尚未开始交换
- **WHEN** journal 声明旧 ID 存在、`previous/<id>` 不存在、当前安装目录匹配旧摘要且 `next/<id>` 匹配新摘要
- **THEN** 系统判定该 ID 尚未交换并保留当前旧目录

#### Scenario: 已存在 ID 已经完成单项交换
- **WHEN** `previous/<id>` 匹配旧摘要且新目录恰好位于当前安装目录或 `next/<id>` 之一
- **THEN** 新目录若在当前安装目录则先撤回空的 `next/<id>`，随后系统原子恢复旧目录

#### Scenario: 新安装 ID 已经放入安装目录
- **WHEN** journal 声明旧 ID 不存在且当前安装目录匹配新摘要
- **THEN** 系统把新目录撤回空的 `next/<id>`，恢复该 ID 原本不存在的状态

#### Scenario: 新目录位置无法闭合
- **WHEN** 当前安装目录与 `next/<id>` 同时存在、同时缺失或任一摘要不匹配 journal
- **THEN** loader 以 `ROLLBACK` 拒绝启动，不删除或覆盖其中任一目录来猜测恢复

#### Scenario: COMMITTED 后清理前退出
- **WHEN** 下次启动发现 `COMMITTED` journal仍有 previous/next 垃圾
- **THEN** 系统保留当前新安装图并清理事务垃圾

#### Scenario: 清理期间再次退出
- **WHEN** 成功提交、成功回滚或构造期恢复正在清理事务目录
- **THEN** 成功回滚先原子记录 `cleanup.outcome=ROLLBACK`，系统再删除 `previous/next/input`，最后删除 journal 和空事务目录，使下次启动仍能从 `COMMITTED` 或回滚清理标记重复证明目标图，或只清理空目录

#### Scenario: Journal 损坏
- **WHEN** 事务目录无法证明应恢复的 ID、旧存在状态或目录闭合关系
- **THEN** loader 构造以 `ROLLBACK` 失败，不猜测一个插件图继续运行

### Requirement: 配置工厂不持有旧 ClassLoader对象
系统 SHALL 只快照可重复执行的 `PluginConfigFactory`，并可在正式 mount和失败恢复时多次调用；不得保存旧 typed config。

#### Scenario: 插件私有配置随更新重建
- **WHEN** 新版本在新 ClassLoader中定义配置类型
- **THEN** loader 把当前 `configType` 传给原配置工厂并创建新类型对象，不把旧 typed config传入新入口

#### Scenario: 目标版本配置类型不兼容
- **WHEN** 升级、降级或失败恢复时配置工厂无法为目标 ClassLoader 的 `configType` 创建对象
- **THEN** 当前正式 apply 以 `APPLY` 失败并执行完整批次回滚，不调用跨版本配置兼容或迁移逻辑

#### Scenario: 完全不兼容的配置 Schema 变更
- **WHEN** 旧配置不能同时物化为新类型且部署不接受 apply 回滚
- **THEN** 宿主必须先用 config reconcile 禁用或移除受影响 entry，再 apply 制品，最后写入新配置并重新启用；系统不得把配置文件隐式并入制品事务

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

`FibraPluginLoaderBusyException` SHALL 只表达同步管理 API 的事务竞争或 Reactor non-blocking 线程误用，不得包装为某个制品阶段失败，也不得写入 watcher 的 `lastFailure()`。

#### Scenario: 结构预检失败
- **WHEN** 候选包结构无效
- **THEN** 异常 stage 为 `VALIDATE`，packages 和可确定的 artifact IDs准确定位本批次

#### Scenario: 同步调用失败不重复记录
- **WHEN** `applyArtifacts` 在调用线程抛出异常
- **THEN** loader 不在同一路径重复记录该异常，由调用方决定日志策略

