# Fibra PF4J 装载架构

本文是 `fibra-loader-pf4j` 0.3.0 的当前实现契约。格式、事务状态机和逐项不变量的完整定义见[插件制品与事务更新设计](./2026-08-23-fibra-plugin-package-transaction-design.md)；本文只说明生产代码边界和使用路径，不维护第二套不同语义。

## 1. 边界

PF4J 层只管理可信进程内插件的二进制制品、依赖图和 ClassLoader；Fibra Core 仍是业务插件生命周期、服务、事件和 effect 的唯一运行时。禁止 PF4J `Plugin-Class`，不创建 PF4J 业务生命周期，也不创建 Spring 子容器。

```text
标准 ZIP候选 ──预检/事务──> plugins/<plugin-id>/
                                      │
                           PF4J 依赖图与 ClassLoader
                                      │ FibraPluginEntrypoint
                                      ▼
                           Fibra entry 与服务依赖图
```

`pluginId` 标识一个已安装制品、PF4J 依赖节点和 ClassLoader；`entryId` 标识一个由 executable 创建的 Fibra 运行实例。一个 `pluginId` 可以创建多个 `entryId`。PF4J `plugin.dependencies` 只描述二进制类型可见性，Fibra `require`/配置 `inject` 只描述服务就绪，两张图不互相推导。

## 2. 标准包

安装态固定为：

```text
plugins/
  <plugin-id>/
    plugin.properties
    lib/
      <plugin-id>-<plugin-version>.jar
      <private-dependency>.jar
```

候选 ZIP 必须只有一个同名顶层目录，内部结构与安装态完全相同。ZIP 文件名没有身份语义。`plugin.properties` 只允许：

```properties
plugin.id=example.consumer
plugin.version=2.0.0
plugin.dependencies=example.contract@>=2.0.0 & <3.0.0
```

另允许 `plugin.description`、`plugin.provider`、`plugin.license`。`plugin.class`、`plugin.requires` 和其他键拒绝。身份和依赖不从主 JAR Manifest 读取；不生成或使用 Manifest `Class-Path`。

主 JAR 自身没有 `META-INF/extensions.idx` 或索引为空时为 contract-only，可以装载、启动并作为 PF4J 依赖，但不能 `configType`/`mount`。executable 的自身索引必须恰好有一个实现 `FibraPluginEntrypoint` 的 public concrete class；缺类、链接失败、错误 ClassLoader 或继承关系错误都在预检期拒绝。

## 3. ClassLoader 与契约

每个插件使用独立 PDA ClassLoader：自身 `lib/*.jar`、显式 PF4J 依赖、宿主。Fibra API/Core、PF4J、Reactive Streams、Reactor 和 SLF4J 强制由父 ClassLoader 提供，包内出现这些类立即拒绝。私有第三方 JAR 只对当前插件可见。

跨插件类型只有三种合法归属：宿主公共 API、独立 contract-only 插件、单个 executable 内部且不跨边界。provider 与 consumer 共享类型时都以 Maven `provided` 依赖独立 contract 模块，并在 `plugin.dependencies` 声明同一 contract 包；consumer 是否等待 provider 服务由 Fibra 配置另行声明。出现同限定名类型不可转换、`ClassCastException`、`LinkageError` 或接口链接失败时，首先检查是否把 contract 复制进多个 `lib/`，不能增加反射桥或兼容 ClassLoader。

## 4. 装载与运行实例

```java
try (var root = FibraRuntime.create();
     var loader = new FibraPluginLoader(root, Path.of("plugins"))) {
    loader.loadArtifacts();
    loader.applyArtifacts(List.of(Path.of("incoming/plugin-2.0.0.zip")));
    loader.mount(PluginInstanceSpec.builder("entry-one", "plugin")
        .parentContext(root)
        .configFactory(type -> createConfig(type))
        .build());
}
```

构造器先恢复或拒绝未完成事务，再创建活动 PF4J manager。`loadArtifacts()` 只装载已安装目录并校验完整图；候选安装、升级和降级的唯一入口是 `applyArtifacts(List<Path>)`，单包也是长度为 1 的批次。`configType` 临时启动目标及依赖后恢复原启动集合；`mount` 自动启动二进制依赖并创建全新入口；`update/updateWithFactory/unmount` 只操作 entry；`stopArtifact/unloadArtifact` 处理该制品及传递依赖方的全部 entry。

`PluginConfigFactory` 只能捕获父 ClassLoader 类型和不可变原始值，不能捕获插件 `Class<?>`、typed config、入口对象或旧 ClassLoader。每次 mount、更新和制品恢复都用当前入口的 `configType` 重新物化；跨版本 schema 不兼容属于 `APPLY` 失败并回滚。

## 5. 批量事务与崩溃恢复

`applyArtifacts` 在任何运行态拆除前完成所有候选解压、结构/共享类/摘要/入口校验，以及候选替换后的完整 prospective 图解析。缺失必需依赖、必需或 optional 版本范围不匹配、循环、重复候选 ID、同版本不同 SHA-256 都直接拒绝；相关 contract/provider/consumer 可以在同一批次中共同升级。

持久时序固定为：

```text
PREPARED -> INSTALLING -> APPLYING -> COMMITTED -> 清理
             │              │
             └────失败───────┴──> 反向恢复旧图
```

`PREPARED` journal 是事务目录的第一个持久动作。无 journal 的预检残留是可删除垃圾；有效 journal 包含每个 ID 的旧/新存在性、规范 SHA-256 和运行态快照。安装按 ID 原子交换目录，正式 apply 先装载/启动新图并重建全部受影响 entry，成功后才写 `COMMITTED`。失败按反向顺序恢复；恢复成功后写 `cleanup.outcome=ROLLBACK` 再清理，使清理期再次崩溃仍可重复完成。摘要无法闭合或回滚失败时构造器以 `ROLLBACK` 拒绝启动并保留诊断目录，不猜测一个图继续运行。

受影响集合是 candidate ID 与旧图、新图传递依赖方的并集。拆除顺序 dependent-first，装载与启动顺序 dependency-first；contract ClassLoader 更新会重建所有实际依赖方。

## 6. 串行门与 Watcher

制品事务和 config reconcile 共用 `runExclusive` 逻辑事务门。所有者线程可重入；其他线程竞争立即抛 `FibraPluginLoaderBusyException`。门只在短临界区更新所有者/深度/最后成功身份快照，不在跨 Fibra lifecycle 阻塞等待时持有 `Lock` 或 monitor，因此生命周期回调查询 `artifactIds()/entryIds()` 不会形成反向锁序。

`FibraPluginWatcher` 只监听 incoming 根目录原子创建的 `.zip`，按 `pluginId` 去抖，窗口内选择最高 SemVer，同版本选择更新时间较新的候选；只提交严格高于已安装版本的包。busy 不写入 `lastFailure`，候选保持 dirty 并重试；格式、预检或 apply 失败写入最后失败并记录 SLF4J。Watcher 不自动解析或下载依赖，关联升级必须由调用方显式提交一次批量 `applyArtifacts`。

## 7. 错误与关闭

`FibraArtifactException` 稳定字段为 `stage()`、`packages()`、`artifactIds()`：

- `READ`：文件、ZIP 或真实路径读取失败；
- `VALIDATE`：包协议、摘要、共享类或入口错误；
- `RESOLVE`：完整依赖图无解；
- `DISPOSE`：旧运行态拆除失败；
- `INSTALL`：持久 journal 或目录交换失败；
- `APPLY`：新图装载、启动、typed config 或 entry 重建失败；
- `ROLLBACK`：旧磁盘/运行态无法完整恢复，必须停止启动并人工处理。

关闭顺序固定为插件 watcher、config watcher、config loader、PF4J loader、root Context；try-with-resources 按相反声明顺序即可。loader close 拒绝新事务，等待当前所有者结束，逆序卸载 entry，再停止并关闭全部插件 ClassLoader。ClassLoader 不是安全沙箱，插件签名、权限与恶意代码隔离不属于本模块。
