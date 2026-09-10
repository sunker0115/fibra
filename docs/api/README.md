# Fibra vNext 公共 API

## 嵌入式内核

`FibraRuntime.create()` 创建根所有者；`rootScope()` 是生命周期树根。`Context` 是绑定 Scope 的不可变能力视图，通过 `services()`、`events()`、`effects()` 和 `plugins()` 使用能力。

`PluginDefinition` 声明稳定名称、配置类型、校验器、必需服务、提供服务和实例工厂。`PluginInstance.settled()` 等待当前迁移落地；结果可能是 `ACTIVE` 或缺依赖的 `PENDING`，失败以 error 和 `FAILED` 状态同时暴露。失败实例只通过显式 `update` 重新收敛。

## 配置与制品

`fibra-config` 把 YAML/JSON 或程序化输入编译成不可变 `DesiredGraph`。文件 repository 是只读真源；可写 repository 通过 `prepareReplace` 提供带 revision 的事务写回。

`fibra-artifact` 以 `ArtifactId`、`RuntimeId` 和内容摘要管理制品。安装使用 `prepareInstall`、`commit`、`rollback`、`retire`，仓库拥有进程级文件锁并在打开时恢复遗留事务。

## Engine 与 runtime

`FibraEngine` 的公共入口只有：

```java
start()
submit(EngineCommand)
published()
close()
```

`start()` 返回初始 `PublishedView`。`PublishedRuntime.current()` / `views()` 是状态、诊断和贡献的唯一已发布事实源；`invoke(expectedViewRevision, kind, id, input)` 保证目录选择与调用不跨 revision。托管宿主不能取得 `FibraRuntime`、`Context` 或 `Scope`。

`PluginRuntimeAdapter` 把 Java、Node 或未来运行时接入同一 ChangeSet。adapter 的 `prepare` 返回候选 `PluginCatalog`、运行时 snapshot 以及 `commit/rollback/retire`。`PublishedView.engine()` 不暴露 `ClassLoader`、`Process` 或 RPC channel。

`FileTransactionJournal` 是托管场景的默认持久化 journal。启动时可证明尚未提交的事务回滚，可证明已提交的事务前向完成；停在不确定提交区间或恢复失败时关闭 mutation gate。

## Registry 与 Bridge

`PluginRegistry` 提供 `install`、`upgrade`、`deploy`、`enable`、`disable`、`uninstall`、`get`、`list`、`watch` 和 `history`。其中 `deploy` 在一个 ChangeSet 内联合提交多个 artifact 与完整 desired graph。Registry 只把请求翻译成 Engine command，并投影 artifact、desired、observed 三类事实。

`ContributionKind` 描述 descriptor、输入、输出和 codec。Java handler 与 Node remote endpoint 登记到所属 `RuntimeDomain` 的 `ContributionDirectory`，名字由场景 adapter 渲染；只有 Engine 当前发布代的不可变 route table 对宿主可见。旧代会先关闭调用准入，再等待在途调用排空。

## Java 插件入口

独立 JAR 实现 `PluginEntrypoint<C>`，并包含：

```text
META-INF/fibra/plugin.yaml
```

manifest 字段为 `id`、`version`、可选的 `entrypoint` 和 `requires`。可运行 artifact 只有一个入口；只承载共享类型的 contract-only artifact 省略入口，但仍参与 SemVer 依赖解析和 ClassSpace。ClassSpace 按 dependency-first 打开并按 dependent-first 关闭。

## Node 插件入口

目录制品包含 `fibra-plugin.yaml`，字段为 `id`、`version`、`protocol`、`entrypoint` 和 `contributions`。入口必须是制品真实目录内的普通 `.js`、`.mjs` 或 `.cjs` 文件。

## Spring

`@FibraService(name, type)` 显式导出宿主 bean。`FibraServiceExporter` 在 Spring 销毁前撤销注册。starter 默认创建 `ArtifactStore`、`FileTransactionJournal`、`JavaPluginRuntimeAdapter`、`FibraEngine`、`PluginRegistry` 和服务桥接；提供同类型 bean 可替换默认实现。

各模块的 `*-public-signatures.txt` 由 `javap -protected` 生成，并由 `fibra-parity-tests` 冻结。
