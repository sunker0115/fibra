# Fibra 内核设计决定

日期：2026-08-22
状态：已接受

完整且可执行的契约见 [Fibra Cordis 内核架构契约](./2026-08-21-fibra-kernel-architecture.md)。本文只记录不会随实现细节变化的设计决定。

## 目标

为 Java 版 DeepSeek Harness 提供 Cordis Core 4.0.1 的 Java 21 语义等价内核。Java 版允许用强类型接口、显式调用上下文和 Reactor 改善开发体验，但不得牺牲 Cordis 的生命周期、作用域、所有权、顺序、错误和完成边界。

项目完成形态不是整个 DeepSeek Harness 的 Java 翻译，也不是 Cordis JavaScript 语法的机械移植。`fibra-api` 与 `fibra-core` 构成等价内核；独立的 PF4J 适配层只补充插件 JAR、依赖图、ClassLoader、更新与回滚。Java 版 DeepSeek Harness 的 agent、tool、provider、session 等业务能力作为上层插件使用该内核，不反向进入内核仓库。

## 决定

1. 行为主基线固定为 DeepSeek Harness 提交 `141eb6fef83422698aef7a981029e843e8161534` 内置的 Cordis 4.0.1；独立 Cordis 提交 `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的 core tests 作为用例语料。
2. 内核固定为 `fibra-api`、`fibra-core`、`fibra-parity-tests`，外部装载能力按适配模块增加；API 不依赖实现，core 始终是唯一运行时，parity-tests 承载内核验收与全部公开 API 冻结。运行时使用 Java 21、Reactor Core 和 SLF4J API；异步主契约统一使用 `Mono`/`Flux`，不混用 `CompletableFuture` 和 fire-and-forget `Runnable` 表达同一生命周期。
3. Reactor 单线程 Scheduler 串行 Fibra 状态变更；插件工作 Publisher 可以在其他 Scheduler 执行，但状态决策必须回到 lifecycle Scheduler。
4. Cordis 的动态 `ctx.foo` 改为显式 `ServiceKey<T>`。服务名必须声明，不能从类名、字段名推断。
5. 服务调用者所有权通过 `BoundService<T>` 与 `InvocationContext` 显式传递。它比 ThreadLocal/JDK Proxy 更适合异步 Java；动态代理仅允许作为同步语法糖。
6. effect 的异步多值形态直接使用 Publisher，并以 `request(1)` 保留 Cordis async generator 的在途产出边界。业务代码不承担协作取消正确性。
7. 同一 effect 内逆序串行清理；Fibra 顶层 effects 并发清理并等待全部完成。手动清理传播局部错误，Fibra unload 在顶层边界记录并隔离错误。
8. `provide` 返回可等待的 `ServiceRegistration`；撤销必须等所有受影响依赖 Fibra 收敛后才完成。
9. PF4J 3.15.0 仅用于 `fibra-loader-pf4j` 的目录包、依赖图、扩展索引和 ClassLoader；PF4J `STARTED` 不等于 Fibra `ACTIVE`，也不替代 Cordis Fibra。OSGi DS 仅作静态贪婪依赖语义参照。
10. LoggerService、全部事件模式和 internal events 属于 core 行为，不因使用 SLF4J 或 Java 类型系统而删减。
11. Cordis accessor/mixin/association 用 `PropertyKey`、`PropertyAccessor`、`Associated` 显式建模；这是 Java 强类型替换，不允许删除关联对象的调用方服务解析能力。
12. 插件 runtime 按入口对象身份分组；类插件以 `PluginFactory` 为身份。批量移除返回 `Mono<Void>` 并等待所有 Fibra 完成。
13. `fibra-api` 的全部 public/protected 类型与签名用提交到仓库的 JDK 21 `javap -protected` 基线冻结；Cordis 12 组 71 个原始 `it` 必须逐项保留独立 Java 门禁。
14. `0.2.0` 的远程发布面是 `fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config` 五个自包含制品。根 POM与验证模块不发布。发布 POM必须展开 parent 与全部依赖版本，同时附带 sources/Javadoc，并通过 Java 21、API、deploy 边界及逐字节可复现门禁。
15. PF4J、Spring Plugin 与 gj.spring.pf4j 分属制品装载、宿主内策略路由、Spring 宿主资源桥接三个层次，不能合并成一套 core 抽象。PF4J 仍是当前唯一制品层实现。当前仓库不提供 Spring Plugin 策略注册表或 Spring 宿主适配：上层确需条件策略时，由业务插件通过类型化 `ServiceKey` 暴露包含选择规则的服务；未来若新增宿主适配模块，必须把外部资源注册转换为插件 Context 所有的 effect/disposer，不得创建第二生命周期容器。
16. Java DeepSeek Harness 使用 Spring Boot 作为静态宿主，但动态插件不进入 Spring BeanFactory。Spring AI 不作为首版 Harness 的基础模型层；自有 LLM、流式 chunk、重试、工具和会话契约保持权威，Spring AI 以后只能通过独立可选适配模块接入。完整边界见 [Fibra、Spring 与 Java DeepSeek Harness 集成架构](./2026-08-22-fibra-spring-harness-integration-architecture.md)。
17. Fibra `0.2.0` 新增框架中立的 `fibra-loader-config`，负责配置文件解析、校验、插件条目树装配、更新与回滚，并复用成熟 YAML/JSON 实现。该模块可以依赖 `fibra-loader-pf4j`，但不得依赖 Spring、Spring Boot 或 Spring AI；Spring Boot 的静态宿主配置仍属于 Java DeepSeek Harness，不得替代 Fibra 的动态插件配置模型。

## 非目标

- `fibra-core` 内的字节码 HMR 或 YAML/JSON 配置文件装载；配置装载只存在于 `fibra-loader-config`；
- Spring Plugin 策略注册表，以及 Spring、Hasor、Solon、OSGi 宿主适配；
- DeepSeek Harness 的 agent、tool、session 等业务插件。

这些能力只能建立在同一个 `Context/Fibra/ServiceKey/PluginDescriptor` 契约之上，不得另建生命周期容器。
