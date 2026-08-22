# Fibra PF4J 装载架构

日期：2026-08-22
状态：实现基线

## 1. 决定

PF4J 3.13.0 作为可选制品层，负责 JAR 描述、版本约束、制品依赖图、扩展索引和每插件 ClassLoader。Fibra 仍是唯一的服务、事件、effect、配置与业务生命周期运行时。

最终模块与依赖方向为：

```text
fibra-loader-pf4j -> fibra-core -> fibra-api
                  -> fibra-pf4j-api -> fibra-api
                  -> PF4J
```

`fibra-core` 不感知 PF4J；PF4J 类型不进入 `fibra-api`。当前只有 PF4J 一个装载实现，因此不增加没有第二实现支撑的通用 loader SPI。

## 2. 开源方案对比

| 方案 | 架构边界 | 与 Fibra 的贴合度 | 结论 |
|---|---|---|---|
| PF4J 3.13.0 | 轻量 JAR/ClassLoader/依赖/扩展机制，不提供 DI | 能补足制品层，且不要求替换 Fibra | 采用 |
| gj.spring.pf4j | PF4J 上叠加每插件 Spring Context、Web/MyBatis/JPA 等注册器 | 资源逆序注销与操作锁值得吸收，但会形成第二容器 | 只参考思想 |
| Hasor | 完整 IoC/AOP/插件生态 | 容器职责与 Fibra 重叠，迁移和运行边界更重 | 不采用 |
| Solon | 应用框架与插件体系一体化 | 适合以 Solon 为宿主的应用，不适合作为中立内核装载层 | 不采用 |

参考源码：[PF4J 3.13.0](https://github.com/pf4j/pf4j/tree/release-3.13.0)、[gj.spring.pf4j](https://github.com/wangpengxpy/gj.spring.pf4j)、[Hasor](https://github.com/zycgit/hasor)、[Solon](https://github.com/opensolon/solon)。

## 3. 制品契约

插件根目录必须已存在，只读取其直接子级中的 `.jar`；不递归目录，不接受 ZIP、展开目录或文件名版本推断。每个 JAR 必须：

- 在 Manifest 声明 `Plugin-Id` 与 SemVer `Plugin-Version`；制品依赖使用 PF4J 原生 `Plugin-Dependencies`；
- 不声明 `Plugin-Class`，避免 PF4J 与 Fibra 出现两套业务生命周期；
- 用 `@Extension` 提供且只提供一个 `FibraPluginEntrypoint`；
- 把 `fibra-*`、PF4J、Reactive Streams、Reactor、SLF4J 作为宿主提供依赖，禁止打入插件 JAR；
- 插件业务包不得使用保留前缀 `com.sstlfsj.fibra`。

装载前会扫描 JAR 并拒绝重复打包的共享类。批量装载先加入全部 JAR，再统一解析依赖；任一装载或依赖解析失败时回滚本批次，不能留下半装载制品。

## 4. 生命周期与完成边界

启动顺序固定为：

```text
PF4J 解析并启动制品依赖
  -> 为每个制品查找唯一 FibraPluginEntrypoint
  -> 在宿主 root Context 创建同名 Fibra
  -> 等待 Fibra ready() 收敛
```

停止和卸载顺序固定为：

```text
依赖方 Fibra dispose 完成
  -> 提供方 Fibra dispose 完成
  -> PF4J 按依赖逆序 stop
  -> PF4J unload 并 close ClassLoader
```

所有管理操作由 loader 级可重入锁串行。PF4J `STARTED` 只表示制品可贡献扩展；Fibra 仍可能因 `ServiceKey` 依赖缺失停在 `PENDING`，不能把两个状态合并。

PF4J `Plugin-Dependencies` 表达二进制/制品依赖；Fibra `PluginDescriptor.require` 表达运行期服务可用性。两层依赖不能互相推导。

## 5. ClassLoader 策略

默认保持 PF4J 的 PDA 顺序：插件私有依赖优先，其次制品依赖，最后宿主。以下共享包强制由宿主加载，并同时禁止出现在插件 JAR：

- `com.sstlfsj.fibra.*`
- `org.pf4j.*`
- `org.reactivestreams.*`
- `reactor.*`
- `org.slf4j.*`

这既保留插件私有库隔离，也避免 `Context`、`FibraPluginEntrypoint` 等跨边界类型产生 ClassLoader 身份分裂。ClassLoader 不是安全沙箱；当前只支持可信的进程内插件。不可信插件必须使用进程隔离。

## 6. 从 gj.spring.pf4j 吸收与排除

已吸收：

- 插件拥有自己注册的宿主资源；在 Fibra 中统一由 Context/effect 表达；
- 依赖方先于提供方逆序清理；
- 管理操作串行化；
- 批量失败不遗留半初始化状态。

后续做制品热更新时可继续吸收“按插件去抖、事件关联、关闭 WatchService/调度器”的思想，但更新顺序必须先等待 Fibra dispose，再替换 JAR 和关闭旧 ClassLoader。

明确排除：每插件 Spring ApplicationContext、Spring Bean 扩展工厂、全局 parent-first、按 JAR 文件名推断版本、自动删除插件目录、未被调用的配置仓库，以及 Web/MyBatis/JPA 等宿主专用注册器。

## 7. 当前非目标

- WatchService/HMR 与远程制品仓库；
- 删除磁盘制品；
- Spring/Hasor/Solon 宿主集成；
- 非 fat JAR 的 `lib/` 或 Manifest `Class-Path` 依赖布局；
- 不可信插件沙箱。
