# Fibra 开源基线与取舍

## 采用

| 项目 | 版本 | 用途 |
|---|---:|---|
| Reactor Core | 3.8.6 | Publisher 协议、Mono/Flux、单线程 Scheduler |
| SLF4J API | 2.0.18 | 日志门面 |
| JUnit | 6.1.3 | 单元测试 |
| Reactor Test | 3.8.6 | StepVerifier 与响应式时序测试 |
| Awaitility | 4.3.0 | 必要时等待最终状态收敛 |
| PF4J | 3.13.0 | 独立 loader 中的 JAR 发现、依赖解析、扩展索引与 ClassLoader |

采用这些库是为了复用成熟的异步协议、调度和测试工具；Cordis 特有的 Fibra 状态机、effect 所有权、隔离服务表和事件策略仍由内核实现。

PF4J 不进入 `fibra-core`；它只在 `fibra-pf4j-api` 与 `fibra-loader-pf4j` 中提供制品层机制。

## 参考但不进入 core

| 项目 | 参考点 | 不直接采用的原因 |
|---|---|---|
| gj.spring.pf4j | 每插件资源归属、逆序注销、操作串行化、热更新去抖 | Spring 子 ApplicationContext、全局 parent-first、文件名版本推断均不适合 Fibra |
| OSGi Declarative Services | 动态、贪婪依赖和组件可见性 | 容器模型和部署成本超出 DeepSeek Harness 当前约束 |
| IntelliJ Disposer | 资源树、幂等和逆序清理 | Fibra 还需要 Publisher、异步完成边界和 Cordis 特有的局部/顶层错误规则 |
| Netty EventLoop | 单写线程设计 | Reactor Scheduler 已覆盖当前 Publisher 技术栈，无需再引入 Netty |

## 明确不自造

- 不自造 Promise、事件循环、背压协议或日志 backend。
- 不用 `CompletableFuture` 建第二套异步生命周期。
- 不用 `System.Logger`、`System.out` 或具体日志实现替代 SLF4J。
- 不用反射猜测类插件构造器；使用 `PluginFactory`。
- 不用动态代理或 ThreadLocal 隐式传播 caller；使用 `BoundService` 与 `InvocationContext`。

## 项目适配结论

Reactor 与 SLF4J 保持内核语义，PF4J 只承担它擅长的制品层机制；插件装载、宿主框架和业务模块仍留在 core 边界之外。
