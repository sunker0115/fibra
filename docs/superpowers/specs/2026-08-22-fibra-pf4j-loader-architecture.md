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

真实制品验收依赖方向为：

```text
fibra-example-consumer-plugin -> fibra-example-provider-plugin（provided）
fibra-example-host -> fibra-loader-pf4j
                   -> provider + consumer（test，仅用于 reactor 排序和复制制品）
```

## 2. 开源方案对比

| 方案 | 架构层 | 业务层 | 结论 |
|---|---|---|---|
| PF4J 3.13.0 | 轻量 JAR/ClassLoader/依赖/扩展机制，不提供 DI；默认批量操作偏 best-effort | 补足独立制品部署，不要求替换 Fibra；事务、完成与串行边界需由适配层收紧 | 采用 |
| Spring Plugin `312ce6d` | 同 ClassLoader、同 Spring 容器内的策略注册表，无安装、卸载和制品依赖图 | `supports`、排序和默认策略适合业务路由，不是运行时插件能力 | 不引入；上层按需用 Fibra 服务表达 |
| gj.spring.pf4j `44b7174` | PF4J 上叠加每插件 Spring Context、分阶段资源 registrar、Web/MyBatis/JPA 等宿主注册器 | 适合 Spring 应用模块化；资源逆序注销值得吸收，但会形成第二容器 | 只参考宿主资源桥接思想 |
| Hasor | 完整 IoC/AOP/插件生态 | 容器职责与 Fibra 重叠，迁移和运行边界更重 | 不采用 |
| Solon | 应用框架与插件体系一体化 | 适合以 Solon 为宿主的应用，不适合作为中立内核装载层 | 不采用 |

参考源码：[PF4J 3.13.0](https://github.com/pf4j/pf4j/tree/release-3.13.0)、[Spring Plugin `312ce6d`](https://github.com/spring-projects/spring-plugin/tree/312ce6d2c3f36f7487fdf8fd7652144bce0e386a)、[gj.spring.pf4j `44b7174`](https://github.com/wangpengxpy/gj.spring.pf4j/tree/44b7174a6c4ff8b34a8be076119b36d487b6ea99)、[Hasor](https://github.com/zycgit/hasor)、[Solon](https://github.com/opensolon/solon)。

表中的“采用”表示当前生产代码已依赖；“只参考”表示只记录设计取舍，不表示当前已有对应模块或 API。当前仓库没有 Spring Plugin、Spring、Hasor 或 Solon 适配模块。

## 3. 制品契约

插件根目录必须已存在，只读取其直接子级中的 `.jar`；不递归目录，不接受 ZIP、展开目录或文件名版本推断。每个 JAR 必须：

- 在 Manifest 声明 `Plugin-Id` 与 SemVer `Plugin-Version`；制品依赖使用 PF4J 原生 `Plugin-Dependencies`；
- 不声明 `Plugin-Class`，避免 PF4J 与 Fibra 出现两套业务生命周期；
- 用 `@Extension` 提供且只提供一个 `FibraPluginEntrypoint`；
- 把 `fibra-*`、PF4J、Reactive Streams、Reactor、SLF4J 作为宿主提供依赖，禁止打入插件 JAR；
- 插件业务包不得使用保留前缀 `com.sstlfsj.fibra`。

插件间共享的业务服务契约归 provider 制品所有。consumer 使用 `provided` Maven 依赖编译，并在 Manifest 通过 `Plugin-Dependencies` 声明运行时制品依赖；不得把 provider 契约复制或打入 consumer JAR，也不得为示例契约增加宿主共享 API 模块。

装载前会扫描 JAR 并拒绝重复打包的共享类。批量装载先加入全部 JAR，再统一解析依赖；任一装载或依赖解析失败时回滚本批次，不能留下半装载制品。

版本更新候选必须位于插件根目录外。loader 先复制到插件根目录内的非 JAR 临时文件，再执行同文件系统原子替换；候选文件不归 loader 所有，不移动也不删除。

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

### 4.1 制品更新事务

`reloadPlugin(candidate)` 的事务边界固定为：

1. 完整校验候选 Manifest、SemVer、共享类和插件 ID；
2. 捕获目标及全部传递依赖方的制品路径和启动状态；
3. 依赖方优先完成 Fibra dispose、PF4J stop/unload 和 ClassLoader close；
4. 备份旧 JAR，并在同一插件目录内原子安装候选；
5. 批量装载全部受影响制品，按依赖顺序恢复原启动集合；
6. 任一步失败，卸载新制品、原子恢复旧 JAR，并恢复旧启动集合；恢复失败作为 suppressed cause 暴露，禁止伪报成功。

### 4.2 外部候选监听

`FibraPluginWatcher` 只监听独立 incoming 目录的 `ENTRY_CREATE`。生产方必须在目录外完成写入，再原子 move 发布，因此 watcher 不需要猜测文件是否写完。

- 按 `Plugin-Id` 去抖；窗口内用 PF4J SemVer 选择最高版本，同版本按最后修改时间选择；
- 低于当前运行版本的自动候选被忽略；显式 `reloadPlugin` 仍允许人工降级；
- watcher 只触发 `reloadPlugin`，不复制生命周期逻辑，也不删除 incoming 文件；
- WatchService overflow、候选校验和更新失败必须记录 SLF4J，并通过 `lastFailure()` 可观测；
- `close()` 关闭 WatchService、取消待执行更新并等待正在执行的更新完成。

## 5. ClassLoader 策略

默认保持 PF4J 的 PDA 顺序：插件私有依赖优先，其次制品依赖，最后宿主。以下共享包强制由宿主加载，并同时禁止出现在插件 JAR：

- `com.sstlfsj.fibra.*`
- `org.pf4j.*`
- `org.reactivestreams.*`
- `reactor.*`
- `org.slf4j.*`

这既保留插件私有库隔离，也避免 `Context`、`FibraPluginEntrypoint` 等跨边界类型产生 ClassLoader 身份分裂。ClassLoader 不是安全沙箱；当前只支持可信的进程内插件。不可信插件必须使用进程隔离。

provider 的插件私有服务类型由 provider ClassLoader 定义，consumer 必须通过 PF4J 依赖 ClassLoader 获得同一类型。跨兄弟插件发布服务时，provider 在 root Context 注册并把 `ServiceRegistration` 交回自身生命周期持有；最后一个绑定撤销后，core 同时释放服务名保存的动态 `Class<?>`，避免阻止旧 ClassLoader 回收，并允许新版本以新的类身份重新注册。

## 6. 三个插件项目的吸收与排除

### 6.1 PF4J

直接复用 JAR 描述、依赖拓扑与版本约束、扩展索引、PDA ClassLoader 和卸载时关闭 ClassLoader。PF4J `AbstractPluginManager` 的默认 `loadPlugins()`/`startPlugins()`会记录单个失败后继续，且不负责跨 PF4J/Fibra/磁盘制品的更新事务，因此 Fibra 必须继续使用严格批量装载、loader 级串行锁和显式回滚，不能直接暴露 PF4J 的宽松批量语义。

### 6.2 Spring Plugin

`Plugin<S>.supports(S)` 与有序 `PluginRegistry` 是宿主内策略选择，不是制品插件生命周期。Fibra 不引入该依赖，也不在 core 增加通用策略注册表。上层确有条件路由需求时，由一个业务插件通过类型化 `ServiceKey` 提供包含选择规则的服务实现，或者在该业务插件内部使用普通 Java 组合；不得向 core 增加另一套发现 API。这样避免“PF4J 扩展、Spring Plugin registry、Fibra service”形成三套并行发现机制。

### 6.3 gj.spring.pf4j

当前已经由 Fibra 自身实现：

- 插件拥有自己注册的宿主资源；在 Fibra 中统一由 Context/effect 表达；
- 依赖方先于提供方逆序清理；
- 管理操作串行化；
- 批量失败不遗留半初始化状态；
- watcher 按插件去抖，并在关闭时终止 WatchService 和调度器。

未来宿主适配约束，当前尚未实现：Spring、HTTP 或数据访问适配器可以按明确阶段组织资源桥接，但每项注册都必须转换为插件 Context 所有且可等待撤销的 effect/disposer；更新仍必须复用 Fibra dispose 与 `reloadPlugin` 事务，不得建立适配器或监听器专属生命周期。

明确排除：每插件 Spring ApplicationContext、Spring Bean 扩展工厂、全局 parent-first、按 JAR 文件名推断版本、先卸载再安装且无旧制品回滚的热更新、自动删除插件目录、registrar 异常吞并，以及把 Web/MyBatis/JPA 等宿主专用注册器放入通用 loader。宿主专用能力若未来实现，只能位于新增的独立适配模块，不能进入 `fibra-api`、`fibra-core` 或通用 PF4J 制品契约。

## 7. 真实制品黑盒验收

`fibra-example-provider-plugin` 使用与外部插件相同的 Maven 构建链和 PF4J 注解处理器。一次编译生成三个同 `Plugin-Id` 制品：主 JAR 为 1.0.0，`v2` classifier 为 2.0.0，`broken` classifier 为缺少扩展索引的 3.0.0。入口通过 JAR `Implementation-Version` 构造 provider 服务，因此更新结果来自实际 ClassLoader 所装载的制品，不依赖文件名或测试替身。

`fibra-example-consumer-plugin` 独立编译并生成自己的唯一扩展索引；它的 JAR 不包含 provider 服务契约，通过 `Plugin-Dependencies: fibra-example-provider` 从 provider ClassLoader 解析契约，并向宿主暴露 `consumer->provider-<version>` 字符串结果。provider 与 consumer 不得在同一源码模块中靠 JAR include/exclude 拆分，否则 PF4J 注解处理器生成的合并扩展索引会破坏“一制品一个入口”契约。

`fibra-example-host` 的 Failsafe 黑盒测试必须从宿主测试 classpath 排除插件 artifact，并验证：

- provider v1 与 consumer v1 能由有限执行的纯 Java 宿主按依赖顺序加载；
- incoming 原子发布 provider v2 后，consumer 会先停止再重新启动，并把结果更新为 `consumer->provider-2.0.0`；
- broken provider 会真实进入启动失败路径，随后 provider、consumer、磁盘 JAR 和运行时服务都恢复到 v2；
- 外部候选文件不被 loader 或 watcher 删除；
- consumer JAR 不包含 provider 契约，宿主 `Class.forName` 也无法找到 provider 契约及两个插件入口，防止 classpath 泄漏制造假通过。

该链路只在 Maven `verify` 阶段运行；全仓标准命令固定为 `mvn clean verify`。

## 8. 当前非目标

- JVMTI/Instrumentation 字节码原地重定义与远程制品仓库；
- 删除磁盘制品；
- Spring Plugin 策略注册表，以及 Spring/Hasor/Solon 宿主集成；
- 非 fat JAR 的 `lib/` 或 Manifest `Class-Path` 依赖布局；
- 不可信插件沙箱。
