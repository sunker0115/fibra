# Fibra 开源基线与取舍

## 采用

| 项目 | 版本 | 用途 |
|---|---:|---|
| Reactor Core | 3.8.6 | Publisher 协议、Mono/Flux、单线程 Scheduler |
| SLF4J API | 2.0.18 | 日志门面 |
| JUnit | 6.1.3 | 单元测试 |
| Reactor Test | 3.8.6 | StepVerifier 与响应式时序测试 |
| Awaitility | 4.3.0 | 必要时等待最终状态收敛 |

采用这些库是为了复用成熟的异步协议、调度和测试工具；Cordis 特有的 Fibra 状态机、effect 所有权、隔离服务表和事件策略仍由内核实现。

## 参考但不进入 core

| 项目 | 参考点 | 不直接采用的原因 |
|---|---|---|
| PF4J | 插件入口发现、JAR/ClassLoader 生命周期 | 不提供 Cordis 的响应式服务重载和 effect 语义；留给未来 loader 模块 |
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

Reactor 与 SLF4J 的组合比照搬 PF4J/OSGi 更适合当前内核：既保留 Cordis 的异步和资源语义，又把插件装载、宿主框架和业务模块留在 core 边界之外。
