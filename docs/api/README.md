# Fibra vNext 公共 API

## 嵌入式内核

`FibraRuntime.create()` 创建根所有者；`rootScope()` 是生命周期树根。`Context` 是绑定 Scope 的不可变能力视图，通过 `services()`、`events()`、`effects()` 和 `plugins()` 使用能力。

`PluginDefinition` 声明稳定名称、配置类型、校验器、必需服务、提供服务和实例工厂。`definition.prepare(config)`
只校验配置，`plugins.mount(id, prepared)` 创建实例而不重复校验。`update(config)` 校验新配置，
`updatePrepared(prepared)` 接受同一 definition 对象预校验的配置，不重复执行校验器；两者共用生命周期协议，
保留 `update(null)` 的合法配置语义。`PluginInstance.settled()` 等待当前迁移落地；结果可能是 `ACTIVE`
或缺依赖的 `PENDING`，失败以 error 和 `FAILED` 状态同时暴露。失败实例只通过显式更新重新收敛。

`PluginInstance.identity()` 是 Runtime 内创建时分配、生命周期不变且不复用的运行身份，不是持久配置 ID。
`RuntimeDomain.snapshot()` 在 lifecycle lane 上采集全域事实；`snapshots()` 异步通知最新不可变快照，
慢订阅者可跳过中间状态，域关闭后流完成。快照中的实例 identity 与句柄一致，可区分不同 Scope 的同名实例。

## 配置与制品

`fibra-config` 把 YAML/JSON 或程序化输入采集成不可变 `DesiredInputGraph` 条目树，不解析插件类型。
`roots()` 保留插件、分组和 include；`plugins()` 是按完整 ID 索引的插件投影；`effective(id)` 查询祖先
启停与策略继承，保留策略声明 owner。条目 `id()` 是局部 ID，分组不添加前缀，include 才创建命名空间。
`upsert(parentId, node)`、`move(id, parentId, position)`、`remove(id)` 和 `withEnabled(id, enabled)`
返回新树，不修改原树；根父节点用 `null` 表示。未采集的 include 不能有效启用，也不能被当作空组插入子节点。

配置、realm 与 intercept 使用 `LiteralValue`；来源路径单独保存在 `DesiredCompilation.entrySources`。
`realm: true` 属于声明节点的局部范围，字符串标签共享范围，`false/null` 回到默认范围；intercept 的
`null` 恢复 definition 默认值。Engine 只绑定有效启用声明，停用声明不要求 definition 已安装。
文件 repository 是只读输入源；可写 repository 通过 `prepareReplace` 提供带 revision 的事务写回。

include 的 `patches` 按顺序浅覆盖条目：`id` 定位当前文档中的条目，`plugin` 只作可选名称保护。
`insert` 为列表，无 `id` 时追加根条目，有 `id` 时追加到对应分组。例如：

```yaml
- id: bundle
  include: base.yaml
  patches:
    - {id: worker, plugin: sample, config: {mode: local}}
    - insert:
        - {id: extra, plugin: sample}
    - {id: extra, enabled: false}
```

匹配范围不跨 include，分组不增加命名空间；显式插入的条目可被后续补丁匹配，普通 `entries` 整体
覆盖产生的新后代不重新入索引。缺失目标、名称不符等可跳过项保存在 `DesiredCompilation.diagnostics`，
Engine 采集时记录告警，刷新结果通过 `EngineCommandResult.warnings` 返回；非法结构和重复 ID 仍拒绝。

`fibra-artifact` 以 `ArtifactId`、`RuntimeId` 和内容摘要管理不可变制品。`prepareInstall` 返回已登记、位于稳定路径的候选；`save()` 保存精确 revision，`rollback()` 只释放未保存的准备资源，不删除已保存内容。读取必须指定 `find(id, revision)`；活动选择只属于完整部署目标，制品仓库没有 current 指针。仓库拥有进程级文件锁并在打开时检查遗留准备记录。

## Engine 与 runtime

`DeploymentManifest` 表示完整、未绑定的部署目标：制品 ID 到精确 revision 的选择，以及完整
`DesiredInputGraph`。其 `revision()` 是规范编码的内容摘要，保留树的顺序、分组归属、本地开关及
include 采集状态，不携带运行资源或来源文件路径。存储格式 2 不接受旧扁平清单。

`FibraEngine` 的公共入口只有：

```java
start()
submit(EngineCommand)
published()
close()
closeAsync()
```

构建 Engine 时可用 `FibraEngine.Builder.autoRefresh(Duration)` 显式开启 desired source 自动刷新。
文件事件只产生可合并 dirty signal，周期 resync 执行真实重新采集，并与上一次已接受的 source revision
比较；相同源不会覆盖 `ReplaceDesiredGraph` 等管理变更。源读取或解析失败会公开 `FAILED` 诊断，但
last-good 目标仍可满足且 mutation gate 保持开放；恢复为相同内容时只清除源错误，不重启实例。

`start()` 返回初始 `PublishedView`。`PublishedRuntime.current()` / `views()` 是状态、诊断和贡献的唯一已发布事实源；`invoke(expectedViewRevision, kind, id, input)` 保证目录选择与调用不跨 revision。托管宿主不能取得 `FibraRuntime`、`Context` 或 `Scope`。

`EngineSnapshot.instances()` 只包含 Engine 持有的声明实例，其快照提供 `publicationRequirement()` 和
`requirementSatisfied()`。`RuntimeDiagnostics.plugins()` 则保留全域实例事实，包括没有配置声明的
动态子插件；两者通过运行 identity 关联，不能仅凭局部 instanceId 连接。声明状态与全域诊断取自
同一次域采样；贡献目录以采样前后的单调 revision 校验一致性，冲突时让出执行权后重采。

`PluginRuntimeAdapter.create()` 返回长期 `RuntimeResourceOwner`。制品变化时，Engine 先登记 `createUpdate(target)` 返回的 `RuntimeResourceUpdate`，再执行 `prepareAsync()`；目标集合完整，但 update 只拥有本次新增或被替换资源。`catalog()` 在准备成功后可读，`snapshot()` 在准备或失败期间也能诊断资源。`adopt()` 只交换所有权，不执行 I/O；此前 `closeAsync()` 清理新资源，此后清理被替换的旧资源，借用资源始终归 owner。准备和关闭共享完整终态，关闭后不能重新准备。纯配置变更不创建 runtime update，无变化实例及资源保留。

`FileEngineStateStore` 持久保存单个完整 `DeploymentManifest`。Engine 先保存不可变制品，再保存目标，然后差量协调长期域中的实例。`EngineDiagnostics` 区分目标 revision、变更阶段、真实达成情况和 mutation gate；`EngineChangeException.targetSaved()` 表示目标已确认保存。若 cause 为 `SaveUnconfirmedException`，不能把 `targetSaved() == false` 解释成未写入。保存后启动或清理失败不回滚目标；保存结果不确定或资源清理失败会关闭后续变更准入。重启严格读取完整目标，损坏或缺失引用明确报错。

`DrainingDisposable` 为受管资源提供排空阶段：先停止准入并等待已接受调用，再执行普通清理。排空沿现有 Scope、插件和 effect 所有权关系传播；provider 资源释放还须等待使用旧激活快照的实际消费者完成清理。失败资源的身份与失败信息保留在 `RuntimeDiagnostics.cleanupFailures()`，不暴露 `ClassLoader`、`Process`、RPC channel 或可变资源句柄。

## Registry 与 Bridge

审计的 `TargetSaveState` 区分未保存、已保存和保存未确认，不等同于操作成功。投递失败可从 `auditFailures()` 和 Registry 查询/返回快照读取，不改变 Engine 原结果。`watch()` 只跟随 Engine 事实流，不因审计失败单独通知。

`PluginRegistry` 提供 `install`、`upgrade`、`deploy`、`enable`、`disable`、`move`、`uninstall`、`get`、
`list`、`watch` 和 `history`。`RegistrySnapshot.desiredGraph()` 保留整个目标树，`get/list` 只投影插件，
使用完整 ID。`enable(entryId)/disable(entryId)` 支持既有插件、分组和 include，不覆盖子条目的本地意图；
`enable(PluginEnableRequest)` 创建或替换插件，`parentId` 指定归属，未指定表示根。`deploy` 在一个
ChangeSet 内提交多个 artifact 与完整 desired graph。Registry 只把请求翻译成 Engine command，
并投影 artifact、desired、observed 三类事实。

`ContributionKind` 描述 descriptor、输入、输出和 codec。Java handler 与 Node remote endpoint 登记到长期 `RuntimeDomain` 的 `ContributionDirectory`，名字由场景 adapter 渲染；宿主只使用已发布不可变路由。受影响条目先停止准入再排空，无关条目的调用不必等待。每次宿主调用拥有独立临时 Scope，成功、失败、取消均先清理 Scope，再释放调用租约。

## Java 插件入口

独立 JAR 实现 `PluginEntrypoint<C>`，并包含：

```text
META-INF/fibra/plugin.yaml
```

manifest 字段为 `id`、`version`、可选的 `entrypoint` 和 `requires`。可运行 artifact 只有一个入口；只承载共享类型的 contract-only artifact 省略入口，但仍参与 SemVer 依赖解析和 ClassSpace。ClassSpace 按 dependency-first 打开并按 dependent-first 关闭。

## Node 插件入口

目录制品包含 `fibra-plugin.yaml`，字段为 `id`、`version`、`protocol`、`entrypoint` 和 `contributions`。入口必须是制品真实目录内的普通 `.js`、`.mjs` 或 `.cjs` 文件。

## Spring

`@FibraService(name, type)` 显式导出宿主 bean。`FibraServiceExporter` 在 Spring 销毁前撤销注册。starter 默认装配 Java runtime、Engine、Registry 和服务桥接。`fibra.source.refresh-interval` 默认为 `0s`；设置为正值时开启上述自动刷新。默认 `ArtifactStore`、`FileEngineStateStore` 是 Engine 内部资源，不单独注册为 Spring bean；显式提供同类型存储 bean 可以替换默认实现，但必须声明 `@Bean(destroyMethod = "")`，由 Engine 唯一负责关闭，避免容器绕过 Engine 的失败资源保留边界。

各模块的 `*-public-signatures.txt` 由 `javap -protected` 生成，并由 `fibra-parity-tests` 冻结。
