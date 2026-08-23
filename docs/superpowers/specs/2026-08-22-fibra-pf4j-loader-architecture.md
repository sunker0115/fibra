# Fibra PF4J 装载架构

日期：2026-08-22
状态：`0.2.0` 当前实现契约

> `0.3.0` 开发分支不再以本文作为实现目标。目录插件包、contract-only、完整图预检、批量事务和崩溃恢复的唯一目标设计见[插件制品与事务更新设计](./2026-08-23-fibra-plugin-package-transaction-design.md)，形式化行为见 [`standardize-plugin-packages`](../../../openspec/changes/standardize-plugin-packages/)。本文只用于准确说明 `v0.2.0` 已发布行为；`0.3.0` 实现完成时将整体重写，不保留直接 JAR语义。

## 1. 边界

PF4J 3.13.0 只负责 JAR 描述、版本约束、制品依赖图、扩展索引和每制品 ClassLoader。Fibra 是唯一的服务、事件、effect、配置和业务生命周期运行时。

```text
fibra-loader-config -> fibra-loader-pf4j -> fibra-core -> fibra-api
                    -> fibra-pf4j-api ----^
fibra-loader-pf4j   -> PF4J
```

`fibra-core` 不感知 PF4J，PF4J 类型不进入 `fibra-api`。当前只有 PF4J 一个制品装载实现，因此不增加通用 loader SPI。

## 2. 身份与入口

`pluginId` 来自 Manifest `Plugin-Id`，标识 JAR 制品、ClassLoader 和 PF4J 依赖节点。`entryId` 标识一棵运行配置树中的 Fibra 实例。一个 `pluginId` 可以同时创建多个 `entryId`；停止、卸载或更新制品必须处理它的全部 entry 及传递依赖方 entry。

每个 JAR 必须提供且只提供一个工厂入口：

```java
public interface FibraPluginEntrypoint<C> extends ExtensionPoint {
    Class<C> configType();
    PluginDescriptor<C> descriptor(String entryId);
    Plugin<C> create(String entryId);
}
```

PF4J 的 `ExtensionWrapper` 会缓存一次创建的扩展对象，因此 loader 只使用 PF4J 的扩展类发现结果，不调用会返回缓存实例的扩展对象 API。mount 与 reload remount 都从入口类的无参构造器创建新入口，再调用 `descriptor(entryId)` 和 `create(entryId)`；update 也创建一次性入口，但只读取当前 `configType`，随后更新已有 Fibra，不创建新的 descriptor 或插件回调。不同 entry 和同一 entry 的不同生命周期都不共享可变入口或插件回调。无配置插件实现 `VoidFibraPluginEntrypoint`，其配置类型固定为 `Void.class` 且只接受 `null`。

## 3. 制品契约

插件根目录必须存在，只扫描直接子级 `.jar`。每个 JAR 必须：

- 在 Manifest 声明非空 `Plugin-Id` 与 SemVer `Plugin-Version`；制品依赖只使用 PF4J `Plugin-Dependencies`；
- 不声明 `Plugin-Class`，避免 PF4J 与 Fibra 出现两套业务生命周期；
- 通过 `@Extension` 和 `META-INF/extensions.idx` 提供唯一入口；
- 不内嵌 `com.sstlfsj.fibra.*`、`org.pf4j.*`、`org.reactivestreams.*`、`reactor.*`、`org.slf4j.*`；
- 不使用保留业务包前缀 `com.sstlfsj.fibra`。

插件间共享契约归 provider 制品所有。consumer 以 Maven `provided` scope 编译，并通过 `Plugin-Dependencies` 从 provider ClassLoader 获取同一个类型；不得复制 provider 契约到 consumer 或 host。

批量 `loadArtifacts()` 先加入全部 JAR，再统一解析依赖；任何装载或解析失败都回滚本批次。PF4J 默认的 best-effort 批量方法不作为 Fibra 公开语义。

## 4. 运行实例 API

`PluginInstanceSpec` 固定包含 `entryId`、`pluginId`、`parentContext`、`PluginConfigFactory` 和 name-only requirements，并由 builder 构造。工厂接收当前入口返回的 `configType`，必须为每次 mount、update 和 JAR reload 恢复生成属于当前插件 ClassLoader 的配置对象。`config(Object)` 只适用于 `null` 或由宿主/父 ClassLoader 定义的共享配置类型；插件私有配置类型必须使用 `configFactory(...)`，否则 loader 在首次 mount 时直接拒绝，不能把旧 ClassLoader 对象带入 reload。

```text
loadArtifacts/loadArtifact/reloadArtifact  制品装载和替换
configType                                 读取插件 ClassLoader 中的配置类型
mount/update/unmount                       entry 运行实例操作
stopArtifact/unloadArtifact                制品及其依赖方运行实例操作
artifactIds/entryIds/fibra                 状态查询
runExclusive                              跨配置与 JAR 更新的事务协调
```

`configType(pluginId)` 可以在制品未启动时读取入口类型：loader 临时启动目标及其依赖，创建一次性入口，随后只停止本次调用新启动且没有运行 entry 的制品，不留下隐藏的 `STARTED` 状态。`mount` 自动启动目标制品及其 PF4J 依赖，再创建指定 entry。PF4J `STARTED` 只表示制品可贡献扩展；Fibra 可能因服务依赖缺失稳定在 `PENDING`。PF4J `Plugin-Dependencies` 只表达二进制/ClassLoader 依赖，Fibra `require` 和配置 `inject` 只表达运行时服务就绪，两者不得互相推导。

`stopArtifact` 和 `unloadArtifact` 先按依赖方优先、子 entry 优先 dispose 全部受影响 Fibra，再停止/卸载 PF4J 制品并关闭 ClassLoader。`FibraPluginLoader.close()` 执行同一完整清理，不关闭调用者拥有的 root Context。

## 5. JAR 更新事务

`reloadArtifact(candidate)` 要求候选位于插件根目录外。固定过程为：

1. 校验候选 Manifest、SemVer、共享类和 `Plugin-Id`；
2. 快照目标制品、传递依赖方及其全部 `PluginInstanceSpec`；快照只保留配置工厂，不保留插件私有 typed config；
3. 依赖方优先 dispose entry，随后 stop/unload 制品并关闭 ClassLoader；
4. 在插件根目录内备份旧 JAR并原子安装候选；候选文件本身不移动、不删除；
5. 重新装载受影响制品并按旧 entry 顺序恢复全部实例；
6. 任一步失败时卸载新制品、原子恢复旧 JAR、旧 PF4J 状态和全部旧实例；恢复错误按发生顺序加入 suppressed。

所有公开管理操作和配置事务共用 loader 级可重入锁。`runExclusive` 只提供闭包式协调，不暴露锁、tryLock、超时或手工事务状态。

## 6. 候选监听

`FibraPluginWatcher` 监听独立 incoming 目录的 `ENTRY_CREATE`。生产方必须在目录外写完候选，再用同文件系统原子 move 发布。

- 按 `Plugin-Id` 去抖；窗口内选最高 SemVer，同版本选修改时间较新者；
- 低于当前版本的自动候选忽略；显式 `reloadArtifact` 允许人工降级；
- watcher 只调用 `reloadArtifact`，不复制事务，也不删除候选；
- overflow、校验和更新失败通过 SLF4J 与 `lastFailure()` 暴露；
- `close()` 停止接收新事件，并等待正在执行的更新结束。

## 7. ClassLoader 与服务

默认采用 PF4J PDA 顺序：插件自身、声明的插件依赖、宿主。共享包强制由宿主加载且禁止出现在插件 JAR。ClassLoader 不是安全沙箱，本模块只支持可信的进程内插件。

服务是否对子插件或兄弟插件可见取决于注册 Context 与 isolate token，不取决于 PF4J 依赖。provider 在自身 Context 注册的服务只对自身及其后代可见；需要兄弟 entry 共享时，配置层必须把它们挂在共同父 Context 并使用相同 isolate 标签，或由插件明确在共同父/root 注册且把 disposer 交给自身生命周期。停止实例后 loader 先移除入口引用，再等待 Fibra dispose；core 在最后一个绑定撤销后释放服务名持有的动态类型，之后 PF4J 才能关闭并回收 ClassLoader。

## 8. 开源方案取舍

| 方案 | 可吸收能力 | 不进入当前实现的能力 | 结论 |
|---|---|---|---|
| PF4J 3.13.0 | JAR/ClassLoader/依赖/扩展类发现 | 默认 best-effort 管理语义与 `ExtensionWrapper` 实例缓存 | 直接依赖；外层收紧事务并自行创建一次性入口 |
| Spring Plugin `312ce6d` | `Supplier` 查询当前 BeanFactory 的动态视图 | 同容器策略 registry | 吸收“不另存运行实例”的查询思想，不引入依赖 |
| gj.spring.pf4j `44b7174` | 每次启动创建、停止关闭插件 Context，卸载清理插件级缓存 | 每插件 Spring Context、Web/MyBatis/JPA 通用化 | 吸收跨 ClassLoader 重建与资源清理思想 |
| Hasor | 插件/IoC 的完整方案对照 | 第二套容器和 AOP 体系 | 不采用 |
| Solon | 框架一体化插件方案对照 | 以 Solon 替代宿主与内核 | 不采用 |

Spring、Hasor、Solon、Spring Plugin 不进入五个生产模块。未来宿主资源桥接必须位于独立适配模块，并把每项资源转换为 Context 所有、可等待撤销的 effect/disposer；不得建立第二套插件生命周期。

## 9. 验收

仓内 `FibraPluginLoaderTest` 与 `FibraPluginWatcherTest` 必须覆盖同制品多 entry、依赖顺序、失败装载、停止/卸载、JAR 更新全部 entry 恢复及失败回滚。`fibra-example-host` 必须通过 `fibra-loader-config` 读取真实 YAML，并用真实 provider/consumer JAR 验证 ClassLoader 依赖、v1 到 v2 更新和 broken JAR 回滚；它不得继续把 `PluginInstanceSpec` 作为宿主推荐接入方式。仓库外五制品验收另见[独立消费设计](./2026-08-22-fibra-external-multi-plugin-verification-design.md)。

当前非目标：非可信插件沙箱、同一 `pluginId` 多版本并存、远程制品仓库、JVMTI 字节码重定义、Spring/Hasor/Solon 宿主集成。
