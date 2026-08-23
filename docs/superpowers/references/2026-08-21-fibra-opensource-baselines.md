# Fibra 开源基线与取舍

> 本文记录到 `v0.2.0` 为止的已实现基线。`0.3.0` 对 PF4J、gj.spring.pf4j、IDEA/VS Code、Cordis HMR 和 DeepSeek Harness contract seam 的新取舍，以[插件制品与事务更新设计](../specs/2026-08-23-fibra-plugin-package-transaction-design.md)和 [`standardize-plugin-packages`](../../../openspec/changes/standardize-plugin-packages/) 为唯一目标；旧 `reloadArtifact` 描述不代表 `0.3.0` 方案。

## 采用

| 项目 | 版本 | 用途 |
|---|---:|---|
| Reactor Core | 3.8.6 | Publisher 协议、Mono/Flux、单线程 Scheduler |
| SLF4J API | 2.0.18 | 日志门面 |
| JUnit | 6.1.3 | 单元测试 |
| Reactor Test | 3.8.6 | StepVerifier 与响应式时序测试 |
| Awaitility | 4.3.0 | 必要时等待最终状态收敛 |
| PF4J | 3.15.0 | 独立 loader 中的目录发现、依赖解析、扩展索引与 ClassLoader |
| Jackson Databind/YAML | 3.1.5 | 配置字面值解析、YAML/JSON 写回和插件 ClassLoader typed config 转换 |
| SnakeYAML Engine | 3.0.1（Jackson YAML 传递依赖） | YAML token 与语法解析 |

采用这些库是为了复用成熟的异步协议、调度和测试工具；Cordis 特有的 Fibra 状态机、effect 所有权、隔离服务表和事件策略仍由内核实现。

PF4J 不进入 `fibra-core`；它只在 `fibra-pf4j-api` 与 `fibra-loader-pf4j` 中提供制品层机制。Jackson 与 SnakeYAML Engine 只进入 `fibra-loader-config`，任何公开签名都不暴露其类型。

## 源码审阅基线

本项目按实现而非项目名称比较插件方案，当前审阅基线固定为：

| 项目 | 源码基线 | 实际职责 |
|---|---|---|
| PF4J | `release-3.15.0` | properties 描述、目录 Classpath、依赖图、扩展索引、插件状态与每插件 ClassLoader |
| Spring Plugin | `312ce6d`（`4.2.0-SNAPSHOT`） | 同一 ClassLoader、同一 Spring 容器内的类型安全策略注册、排序与选择 |
| gj.spring.pf4j | `44b7174` | 在 PF4J 上叠加每插件 Spring 子容器和宿主资源注册器 |
| Spring AI | `db45fc548fd2b7eb2797758b6a69ac750554e52b` | Spring Boot AI 自动配置、ChatModel、模型 API、retry、工具循环、MCP、RAG 与观测 |
| Google ADK Java | `8049f7e5362ca654bf3706ea465f8d1021ee0346`（`v1.7.0-17-g8049f7e5`） | core 自有 `BaseLlm`，Spring AI 位于独立 `contrib/spring-ai` 适配模块 |
| Embabel Agent | `54c67cddad7036c1d5633d29faa928fc5786f069`（`v1.5.0`） | 以 Spring Boot、Spring AI `ChatModel` 和 AgentPlatform 为中心的 Spring 原生 agent 平台 |

本文中的“采用”表示依赖已进入当前生产模块并由现有代码调用；“已实现”表示仓库中已有代码和验收；“参考”或“未来约束”只表示设计规则，不表示已有模块、API 或运行能力；“不引入”表示 Fibra 仓库不增加该依赖，不限制上层应用在 Fibra 边界之外独立使用它。

PF4J 的默认批量装载和启动会隔离单个失败并继续，管理操作本身也不提供 Fibra 所需的事务与串行边界。已发布的 `0.2.0` 使用 `FibraJarPluginManager.loadPluginsStrict`、loader 级锁和 `reloadArtifact`；开发中的 `0.3.0` 由 OpenSpec change `standardize-plugin-packages` 直接替换为标准目录包、完整 prospective 图、逻辑事务门和持久 journal，不保留上述直接 JAR API。PF4J 的依赖解析、每插件 ClassLoader、依赖类可见性与卸载关闭 ClassLoader 继续复用，默认 ZIP 展开、best-effort 批量装载和扩展对象发现不进入新路径。

PF4J 3.15.0 的 `AbstractPluginManager.stopPlugins()` 使用迭代器遍历 `startedPlugins`，但它调用的 `doStopPlugin()` 又直接从同一列表删除元素，多插件停止会触发 `ConcurrentModificationException`。Fibra 不调用该批量 API；关闭路径直接调用 PF4J `unloadPlugins()`，由逐插件 `unloadPlugin -> stopPlugin` 完成停止并关闭 ClassLoader。这个规避点只针对 PF4J 管理层调用顺序，不复制或修改 PF4J 的停止算法。

PF4J 3.15.0 的 `ExtensionWrapper` 在首次 `getExtension()` 后保存同一个扩展对象。Fibra 的入口是运行实例工厂，跨 entry 或 reload 复用该对象会把可变状态和旧 ClassLoader 带到新实例，因此 loader 只读取主 JAR 自身 `extensions.idx` 的入口类声明，并按每次 mount/update/reload 恢复调用其无参构造器。这个差异是 Fibra 对 PF4J 扩展缓存语义的显式收紧，不依赖 PF4J 内部 wrapper 何时失效。

Spring Plugin 的 `Plugin<S>.supports(S)`、`PluginRegistry` 首个/全部/默认选择和 `OrderAwarePluginRegistry` 只解决宿主 classpath 内的策略路由。其 BeanFactory 适配通过 `Supplier<List<Plugin<S>>>` 在使用时查询当前容器，而不是在 registry 中复制一份实例集合。Fibra 吸收的是这一条所有权规则：`fibra-loader-config` 为配置声明的 group/include scope 持有自己创建的 Fibra，但插件 entry 只保存不可变原始配置和身份；`resolve` 始终向 `FibraPluginLoader` 查询当前插件运行实例，不缓存该实例。Spring Plugin 没有插件制品、安装、卸载、依赖图、ClassLoader 或运行期生命周期，不能替代 PF4J，也不应进入 Fibra core。若上层业务需要“按条件选择策略”，应作为普通 Fibra 服务或业务插件实现，不能再建立一套与 `ServiceKey`、`PluginDescriptor.require` 并行的注册表。

gj.spring.pf4j 的插件容器在每次 start 创建新的插件 `ApplicationContext`，在 stop 时关闭并注销，unload 时还会删除插件作用域的 `ObjectMapper` 等缓存；`PluginLifecycleEngine` 另外把宿主资源桥接拆为刷新前、刷新后、关闭前三个阶段，并在关闭阶段逆序执行 registrar。Fibra 不复制 Spring 子容器，但吸收相同的 ClassLoader 生命周期结论：typed config 必须在当前插件 ClassLoader 下重新物化，旧运行实例和转换缓存不得跨 reload 留存。资源归属与逆序撤销原则只作为未来宿主适配的设计约束。当前仓库没有 Spring、HTTP 或数据访问宿主适配模块。以后若新增此类模块，每项宿主资源注册都必须转换为当前插件 Context 所有的 effect/disposer，由 Fibra 统一等待完成和处理错误；不得复制每插件 Spring Context 或 registrar 的异常吞并策略。

Spring AI 的 `DeepSeekApi.chatCompletionStream` 会合并流式工具调用 chunk，`DeepSeekChatModel` 通过 Spring AI retry 执行模型调用，`ChatClient` 的 `ToolCallingAdvisor` 可以接管工具循环。这些职责与 DeepSeek Harness 对原始 `StreamChunk`、单次 adapter 尝试、独立重试插件、工具流水线和持久会话事实的边界不等价。因此 Spring AI 不作为 Java DeepSeek Harness 首版模型主干，只允许以后通过独立可选模块实现 Harness 自有 seam。Google ADK Java 采用“核心模型契约自有、Spring AI 外置适配”的依赖方向，与本项目约束一致；Embabel 的 Spring 原生平台方向适合另一类产品目标，不作为 DeepSeek Harness 一比一迁移基线。完整决定见 [Fibra、Spring 与 Java DeepSeek Harness 集成架构](../specs/2026-08-22-fibra-spring-harness-integration-architecture.md)。

## 参考但不进入 core

| 项目 | 参考点 | 不直接采用的原因 |
|---|---|---|
| Spring Plugin | `supports` 条件选择、排序、首个/全部/默认策略 | 仅是宿主 classpath 内的 Spring Bean 注册表，与制品生命周期无关，且会重复 Fibra 服务注册职责 |
| gj.spring.pf4j | 分阶段资源 registrar、逆序注销、操作串行化、热更新去抖 | Spring 子 ApplicationContext、全局 parent-first、文件名版本推断和非事务卸载再安装均不适合 Fibra |
| Spring AI | ChatModel 生态、模型自动配置、MCP、RAG、vector store 与观测 | 首版模型协议会丢失 Harness 原始流式事实，并把 retry、工具循环等产品语义移入框架 |
| Google ADK Java | 自有模型契约与 Spring AI 独立适配模块的依赖方向 | Agent、LLM 与会话语义不是 DeepSeek Harness 的行为真源 |
| Embabel Agent | Spring 原生 AgentPlatform 的宿主装配与可观测性 | 核心围绕 Spring AI ChatModel，产品语义和动态插件边界与 Harness 不同 |
| OSGi Declarative Services | 动态、贪婪依赖和组件可见性 | 容器模型和部署成本超出 DeepSeek Harness 当前约束 |
| IntelliJ Disposer | 资源树、幂等和逆序清理 | Fibra 还需要 Publisher、异步完成边界和 Cordis 特有的局部/顶层错误规则 |
| Netty EventLoop | 单写线程设计 | Reactor Scheduler 已覆盖当前 Publisher 技术栈，无需再引入 Netty |

## 明确不自造

- 不自造 Promise、事件循环、背压协议或日志 backend。
- 不用 `CompletableFuture` 建第二套异步生命周期。
- 不用 `System.Logger`、`System.out` 或具体日志实现替代 SLF4J。
- core 类插件不反射猜测构造器，使用 `PluginFactory`；PF4J 入口的 public 无参构造器是独立制品协议，由 loader 明确校验并为每个运行生命周期调用。
- 不用动态代理或 ThreadLocal 隐式传播 caller；使用 `BoundService` 与 `InvocationContext`。

## 项目适配结论

Reactor 与 SLF4J 保持内核语义，PF4J 只承担它擅长的制品层机制。Spring Plugin 所代表的条件策略路由不属于当前仓库；上层业务确有该需求时，由一个业务插件通过类型化 `ServiceKey` 暴露包含选择策略的服务实现。gj.spring.pf4j 所代表的宿主资源桥接当前尚未实现；若以后新增，只能位于独立可选适配模块。Spring AI、Google ADK Java 和 Embabel 只用于确定未来 Harness 的宿主与模型依赖方向，不进入 Fibra 生产模块。以上能力都不能改变 `fibra-core` 是唯一生命周期与服务运行时的边界。
