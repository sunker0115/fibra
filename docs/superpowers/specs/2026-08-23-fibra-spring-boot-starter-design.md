# Fibra Spring 运行时集成设计

日期：2026-08-24

状态：架构已确认，作为 `0.4.0` 实现、OpenSpec 契约和验收的上游权威源

正式变更：[`openspec/changes/standardize-spring-runtime-integration`](../../../openspec/changes/standardize-spring-runtime-integration/)
历史说明：本文整段替代 `0.3.1` 的单模块 starter 草案；旧属性、旧构造器和旧模块边界不保留兼容层。

## 1. 目标与系统边界

`fibra-spring-boot-starter` 是 Fibra 面向 Spring Boot 宿主的可选依赖入口。它负责把 root `Context`、`FibraPluginLoader`、`FibraConfigLoader`、两类 watcher、启动就绪门禁、宿主服务桥接和有序关闭组成一个可直接使用的运行时。CLI、Web、Spring AI 或 Java DeepSeek Harness 都只是它的上层消费者，不得反向定义本模块的通用语义。

本设计冻结以下边界：

- `fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j` 和 `fibra-loader-config` 的生产依赖图不出现 Spring；
- 根父 POM 不导入 Spring BOM、不声明 Spring 依赖；Spring Boot 版本和 BOM 只存在于 Spring 自动配置实现模块；
- 插件对象不进入 Spring `BeanFactory`，不建立每插件 Spring `ApplicationContext`，不扫描插件内 `@Component`、`spring.factories` 或 Boot 自动配置；
- Spring 管理静态宿主装配，Fibra 管理动态插件的创建、依赖、服务、reload、dispose 和 ClassLoader；
- starter 不包含 Spring Shell、Spring Web、Spring AI、Actuator 或任何宿主业务；
- 开发阶段直接删除 `0.3.1` 的错误属性和公开签名，不提供别名、转发、弃用层或双模型识别。

目标版本为 `0.4.0-SNAPSHOT`。`v0.3.1` 继续指向已发布历史提交，不移动、不覆盖。

## 2. 已核实参照与取舍

### 2.1 Spring Boot 官方模块边界

Spring Boot 官方建议把自动配置实现与依赖入口拆分：自动配置模块保存代码和注册文件，starter 是依赖聚合入口。来源：

- [Spring Boot 创建自己的自动配置](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)；
- Spring Boot 4.1.0 BOM 当前解析 Spring Framework 7.0.8，已核对本机 `spring-boot-dependencies-4.1.0.pom`。

Fibra 吸收模块拆分、`AutoConfiguration.imports`、类型化条件和 `ApplicationContextRunner` 测试；不让 starter 捆绑 Web、Shell 或日志实现。宿主本身必须是 Spring Boot 应用，并自行选择 `spring-shell-starter`、`spring-boot-starter-web` 等应用形态。

### 2.2 Spring 生命周期真实失败语义

Spring Framework 7.0.8 `DefaultLifecycleProcessor.onRefresh()` 会在某个 lifecycle 自动启动失败后停止已经处于 running 的 lifecycle；失败中的 bean 若尚未把自身标记为 running，则不能依靠容器清理其部分启动资源。源码：

- [DefaultLifecycleProcessor.java（v7.0.8）](https://github.com/spring-projects/spring-framework/blob/v7.0.8/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java)；
- 本机已逐行核对 `spring-context-7.0.8-sources.jar` 中 `onRefresh`、`doStart` 和 `doStop`。

因此 Fibra 必须由一个所有权明确的协调器在 `start()` 内自行完成部分失败回滚，不能把资源拆给多个互不知情的生命周期 bean。

### 2.3 本地参考项目

本地 `/Users/sunke/dev/ai-project/disruptor-spring-boot` 已采用：

来源：已核对该仓库根 POM、`disruptor-spring-boot-autoconfigure/pom.xml` 和 `disruptor-spring-boot-starter/pom.xml`。

```text
disruptor-spring-boot-autoconfigure  # 自动配置实现
disruptor-spring-boot-starter        # 无代码依赖入口
```

Fibra 吸收该分层和“对具体 lifecycle 类型退让”的测试思想；但 Fibra 不复制它的简单启停模型，因为 Fibra 还必须协调配置 reconcile、两个 watcher、插件 ClassLoader 和可等待 root 关闭。

### 2.4 Fibra 现有真实约束

以下结论直接来自当前生产源码：

- `FibraPluginLoader` 构造时要求安装根已经存在，并在构造期执行崩溃恢复；来源：`fibra-loader-pf4j/.../FibraPluginLoader.java`；
- `FibraPluginWatcher` 构造时要求 incoming 目录已经存在，构造只分配 watch service 和 scheduler，调用 `start()` 后才启动监听线程；来源：`fibra-loader-pf4j/.../FibraPluginWatcher.java`；
- `FibraConfigLoader.watch(...)` 要求初始 `load()` 已完成，调用后立即启动 `FibraConfigWatcher` 工作线程；来源：`fibra-loader-config/.../FibraConfigLoader.java` 和 `FibraConfigWatcher.java`；
- `Fibra.ready()` 是 `await()` 的语义别名；稳定 `PENDING` 会正常完成而不是等待未来 provider，因此 readiness 必须在完成后显式检查 `ACTIVE`；来源：`fibra-api/.../Fibra.java` 和 `fibra-core/.../DefaultFibra.java`；
- plugin watcher、config watcher、config reconcile 和 artifact apply 已共享 loader 事务门；starter 不复制串行化算法。

### 2.5 未采用方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 在当前单模块 starter 中补一个 watcher bean | 拒绝 | 保留错误属性、混合发布边界和启动泄漏，只是局部补丁 |
| 拆模块并使用 runtime/watcher 两个 `SmartLifecycle` | 拒绝 | watcher 部分启动和 runtime 失败恢复跨 bean 分散，资源所有权不再单一 |
| 拆模块并由单一 `FibraLifecycle` 统筹 | 采用 | 模块职责清楚，所有启动、回滚和关闭顺序只有一个权威实现 |
| 每插件 Spring 子容器 | 拒绝 | 会形成第二套业务生命周期和插件 ClassLoader 强引用，破坏 Fibra 所有权语义 |

## 3. Maven 模块与发布边界

### 3.1 最终模块图

性质：本变更冻结的目标结构，不是当前 `0.3.1` 代码现状。

```text
fibra-spring-boot-starter
    └── fibra-spring-boot-autoconfigure
            ├── fibra-loader-pf4j
            ├── fibra-loader-config
            └── spring-boot-autoconfigure
```

`fibra-spring-boot-autoconfigure`：

- 保存全部 Java 生产代码、配置元数据处理器和 `AutoConfiguration.imports`；
- 自己定义唯一 `spring-boot.version=4.1.0` 并 import `spring-boot-dependencies`；
- 显式覆盖 `reactor-core` 为根项目已经冻结的版本，防止 Boot BOM 改写 Fibra 运行时版本；
- Spring Boot 自动配置依赖是传递依赖，使无代码 starter 不需要重复声明 Spring 版本；
- 不依赖 Spring Web、Spring Shell、Spring AI 或 Actuator。

`fibra-spring-boot-starter`：

- JAR 中没有生产 class、自动配置注册文件或业务资源；
- 只依赖 `fibra-spring-boot-autoconfigure`；
- 作为用户唯一推荐坐标发布。

Spring 宿主还需自行引入与应用形态匹配的 Boot starter。CLI 使用 Spring Shell，Web 示例使用 Spring Web，Fibra starter 不替宿主做该选择。

### 3.2 版本与制品

- reactor 唯一版本真源进入 `0.4.0-SNAPSHOT`；
- 五个框架中立制品不变，新增 `fibra-spring-boot-autoconfigure` 后，可发布制品从六个变为七个；
- 根 `dependencyManagement` 增加两个 Fibra 内部模块坐标，只管理当前 reactor 版本，不引入 Spring BOM；
- 两个 Spring 制品都必须生成主 JAR、sources JAR、Javadoc JAR和展开后的发布 POM，并进入可复现构建门禁；
- `fibra-spring-boot-starter` 的主 JAR必须没有 `.class` 和 `AutoConfiguration.imports`，防止实现重新回流到入口模块。

## 4. 公共配置数据结构

完整字段定义只以本节为权威源。实现使用不可变 `@ConfigurationProperties("fibra")` 及嵌套 record；不保留 `0.3.1` getter/setter 形状。

性质：本变更冻结的公共数据结构。

```text
FibraProperties
├── Artifacts artifacts                         必填
│   ├── Path installedRoot                      必填
│   ├── Path incomingRoot                       artifact watch 启用时必填
│   └── Watch watch                             默认 Watch(false, 1s)
├── Config config                               必填
│   ├── Path location                           必填
│   └── Watch watch                             默认 Watch(false, 1s)
├── Startup startup                             默认 Startup([], 60s)
│   ├── List<String> requiredEntries            默认空，不允许空白或重复
│   └── Duration readinessTimeout               必须大于 0
└── Shutdown shutdown                           默认 Shutdown(30s)
    └── Duration rootCloseTimeout               必须大于 0

Watch
├── boolean enabled                             默认 false
└── Duration debounce                           默认 1s，必须大于 0
```

唯一 YAML 映射：

性质：由上述公共数据结构直接映射的目标配置，不是旧属性兼容示例。

```yaml
fibra:
  artifacts:
    installed-root: ./run/plugins
    incoming-root: ./run/incoming
    watch:
      enabled: false
      debounce: 1s
  config:
    location: ./run/fibra.yaml
    watch:
      enabled: false
      debounce: 1s
  startup:
    required-entries: []
    readiness-timeout: 60s
  shutdown:
    root-close-timeout: 30s
```

校验在创建 root、loader、watch service 或线程前一次完成：

- `installed-root` 必须是已存在目录；starter 不创建目录，避免拼错路径后静默启动到空环境；
- `config.location` 必须是已存在普通文件，因为初始 reconcile 是启动事务的一部分；
- artifact watch 开启时，`incoming-root` 必须是已存在目录；关闭时允许不配置；
- 两个 debounce、readiness timeout 和 root close timeout 必须满足前述范围；
- required entry 去重后必须与原列表等长，任一值 trim 后为空即拒绝；
- 校验错误必须指明完整属性键和无效值，不用底层 `NullPointerException` 代替配置诊断。

`staging-root` 被删除。上传暂存、签名校验、鉴权和批次选择是宿主部署策略；Web 示例改用自己的 `example.fibra.staging-root`，CLI 后续也使用自己的输入通道，不污染通用 starter。

## 5. 自动配置组件

### 5.1 `FibraAutoConfiguration`

公共类名，用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册。bean 方法与内部协作类型不是公共 API。

默认自动配置是不可拆分的托管单元：只有当宿主不存在 `Context` 时才创建完整 root、plugin loader、config loader、bridge 和 lifecycle。若宿主已经提供 `Context`，整个托管运行时退让；宿主必须排除自动配置并按公开 loader API自行装配和关闭，starter 不把宿主资源与自己的部分资源拼成未知所有权图。

这一规则刻意替代“每个 bean 都单独 `@ConditionalOnMissingBean`”。后者会让 lifecycle 无法判断一个资源应由谁关闭，并可能与宿主 destroy method 双重管理。

### 5.2 `FibraServiceBridge`

保持唯一公共桥接机制：

来源：当前 `fibra-spring-boot-starter/.../FibraServiceBridge.java` 已存在的签名，本变更继续冻结。

```java
public final class FibraServiceBridge {
    public <T> ServiceRegistration<T> register(ServiceKey<T> key, T service);
}
```

它只把宿主对象显式注册到 root `Context`。不按类型扫描 Spring bean，不产生 `@Autowired` 适配，不缓存插件提供者或 `BoundService.value()`。注册返回的 `ServiceRegistration` 仍由调用方或 root effect 树撤销。

### 5.3 `FibraLifecycle`

自动配置内部、包级可见的单一 `SmartLifecycle`。它独占默认运行时的启动和关闭权；root、两个 loader bean 均声明 `destroyMethod=""`，避免 Spring 自己以未知顺序再次关闭。

`FibraLifecycle` 不把 watcher 声明为普通 Spring bean：

- artifact watcher 可以先构造后 start，但提前构造已经占用 watch service 和 scheduler；
- config watcher 调用 `FibraConfigLoader.watch()` 后立即启动线程，必须晚于初始 `load()` 和 readiness；
- 两者都由 lifecycle 在正确阶段创建、保存引用、报告失败并逆序关闭。

## 6. 启动、就绪与失败回滚

### 6.1 正常启动

性质：本变更冻结的目标控制流。

```text
校验完整属性图
  → 创建 root / plugin loader / config loader / bridge / lifecycle
  → pluginLoader.loadArtifacts()
  → configLoader.load()
  → 在一个总 deadline 内检查全部 requiredEntries
  → configLoader.watch(...)（仅启用时）
  → new FibraPluginWatcher(...).start()（仅启用时）
  → running = true
```

配置 watcher 先启动，制品 watcher 后启动。这样一旦允许新的制品事务进入，配置监听已经具备接收刷新事件的能力；两者实际变更仍由 loader 事务门串行。

### 6.2 Readiness

- `requiredEntries` 表示 Fibra `entryId`，不表示 PF4J `pluginId`；
- 配置初始 reconcile 完成后先确认每个 entry 存在；缺失立即失败并指明 entryId；
- 全部 `Fibra.ready()` 共享一个 `readinessTimeout` 总预算，不按 entry 逐个重新计时；
- `ready()` 完成后必须检查 `state()==ACTIVE`；稳定 `PENDING`、`INACTIVE` 或其他非 ACTIVE 状态都令启动失败；
- 业务启动异常保留为 cause，错误同时列出未 ACTIVE entry 及其状态；不把 PENDING 伪装成超时。

### 6.3 启动失败回滚

任何一步失败都由当前 `start()` 按已经完成的阶段逆序回滚：

性质：本变更冻结的目标控制流。

```text
关闭已创建 artifact watcher
  → 关闭已创建 config watcher
  → configLoader.close()
  → pluginLoader.close()
  → root.closeAsync().block(rootCloseTimeout)
```

规则：

- 原始启动异常始终是主异常；
- 每个回滚失败按发生顺序加入 suppressed，后续回滚继续；
- watcher 构造函数在对象引用返回前失败时，构造路径自身必须关闭已分配的 watch service、scheduler 或 worker；外层 lifecycle 不能假装能关闭一个尚未返回的对象；
- `running` 只有全部阶段成功后才设为 true；
- 已失败并回滚的 lifecycle 进入终止态，不允许在同一 `ApplicationContext` 再次 start；
- Spring 随后的 context close 可以重复调用 stop，但不得重复创建 watcher 或重复装载。

## 7. 运行与关闭

### 7.1 运行期 watcher 失败

- artifact watcher 沿用底层严格升级、单包事务和 `lastFailure()` 语义；
- config watcher 沿用 failure sink + SLF4J，失败保留最后成功配置运行态；
- transient `FibraPluginLoaderBusyException` 由底层 watcher dirty/retry 处理，不升级为最终失败；
- starter 不吞异常、不自动退出应用、不猜测多插件批次；
- 本阶段不新增 Actuator endpoint 或新的通用状态 SPI，CLI/Web 可通过日志和现有 loader 管理 API表达自己的运维面。

### 7.2 正常关闭

性质：本变更冻结的目标控制流。

```text
artifact watcher.close()
  → config watcher.close()
  → configLoader.close()
  → pluginLoader.close()
  → root.closeAsync().block(rootCloseTimeout)
  → running = false
  → SmartLifecycle callback
```

`root-close-timeout` 只约束异步 root 关闭，不能虚假承诺强制中断同步 watcher/loader close。任一阶段失败时记录 SLF4J、收集 suppressed 并继续关闭后续阶段；`stop(Runnable)` 无论成功失败都必须最终调用 callback，避免 Spring shutdown phase 永久等待。

关闭是幂等且终止性的。Spring CRaC pause/restart 不会停止本 bean，因为它不声明 pauseable；ApplicationContext 关闭后不能重新启动同一 root Scheduler。

## 8. 公共 API 与兼容策略

`fibra-spring-boot-autoconfigure` 的公共签名基线只冻结：

- `FibraAutoConfiguration` 类名；
- `FibraProperties` 及第 4 节嵌套 record；
- `FibraServiceBridge`。

不冻结：

- 自动配置 `@Bean` 方法；
- `FibraLifecycle` 构造器和内部方法；
- watcher 引用和回滚辅助类型；
- Spring 条件实现细节。

`fibra-spring-boot-starter` 没有 Java 公共签名基线，只冻结“无生产 class 的依赖入口”制品规则。

从 `0.3.1` 删除且不兼容：

- 单模块内置实现；
- `plugins-root`、`staging-root`、`config-location`、`startup-required-plugins`、单一 `watcher.*` 和 `shutdown-timeout`；
- 公共 `FibraLifecycle` 构造器；
- `FibraAutoConfiguration` bean 方法作为公开 API 的误冻结。

## 9. 测试与验收

### 9.1 属性与自动配置

- 完整绑定默认值、两类 watcher 和嵌套路径；
- 每条无效属性都有精确键名诊断；
- artifact watch 关闭时允许 incoming root 缺失，开启时必须存在；
- 宿主存在 `Context` 时整个托管运行时退让，不产生混合所有权资源；
- 配置元数据包含全部字段、默认值和说明。

### 9.2 生命周期

- 用调用序列桩锁定正常启动和正常关闭顺序；
- 对 load、config reconcile、每个 readiness 阶段和两个 watcher 启动分别注入失败，验证完整反向回滚；
- 对两个 watcher 构造中途失败分别验证没有泄漏 watch service、scheduler 或 worker；
- 验证原异常与 suppressed 顺序；
- 多 required entry 使用一个总 deadline；
- PENDING 立即按状态失败，不被误报为等待超时；
- stop 幂等且 callback 恰好调用一次。

### 9.3 真实 watcher 黑盒

- Spring Boot 宿主从已安装标准包完成初始 load + config mount；
- 修改根配置或 include 后，config watcher 自动 reconcile；
- incoming 目录出现更高版本 ZIP 后，artifact watcher 完成事务升级；
- watcher 与显式管理操作竞争时最终收敛且不交叉提交；
- 关闭后无 watcher 线程、旧插件实例或旧 ClassLoader 强引用。

异步断言使用 Awaitility 4.3.0，不使用 `Thread.sleep`。

### 9.4 发布与文档

- 两个 Spring 模块的 POM、sources、Javadoc、主 JAR和可复现构建；
- starter JAR无 `.class` 和自动配置注册文件；
- autoconfigure JAR包含唯一注册文件和配置元数据；
- 五个中立制品的依赖树继续无 Spring；
- README、API 手册、release、示例、公开签名和发布模块基线同步为七制品；
- Web 示例的 staging 配置迁到示例命名空间；
- 旧属性和旧模块实现描述从文档中直接删除，不保留历史残渣。

## 10. 范围外

- Spring Shell 动态命令注册桥接；它是 starter 完成后的独立 CLI change；
- Spring AI、模型客户端和 agent 协议；
- Actuator、健康检查 endpoint 或 Micrometer 指标；
- 插件签名、远程市场、自动依赖下载和不可信代码沙箱；
- 每插件 Spring Context、插件 bean 自动注入或按类型服务选择；
- 对 `0.3.1` 属性、类或模块布局的任何兼容。

## 11. Open Questions

无。模块边界、属性模型、目录责任、生命周期所有权、readiness 时限、watcher 时序、失败回滚、关闭语义、公开 API 和版本边界均已确定。
