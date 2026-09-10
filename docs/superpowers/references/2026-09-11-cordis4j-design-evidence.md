# cordis4j 设计对拍与采用边界

复核日期：2026-09-11。

本文只记录 `cordis4j` 对 Fibra vNext 架构取舍的源码证据。Fibra 的最终决定仍在
[vNext 架构](../specs/2026-09-07-fibra-vnext-architecture.md)；Cordis 上游行为真源和 71 项门禁见
[Cordis 行为证据](2026-09-09-cordis-behavior-evidence.md)。不能用 `cordis4j` 自己声明的“语义保真”替代
对 Cordis TypeScript 源码与测试的核对。

## 1. 固定真源

- 仓库：`1na-ko/cordis4j`。
- 提交：`6cfd56e684fb403ded952afc09eddb49a5228494`。
- 设计契约：`docs/design-contract.md`，v2.13 frozen。
- 主要复核范围：`cordis4j-core/src`、`cordis4j-loader/src`、`cordis4j-hmr/src`、
  `cordis4j-spring/src`、`cordis4j-inject-processor/src`、`cordis4j-timer/src`。
- 本地工作树只有未跟踪的 `.codegraph/`，不参与判断。

设计契约 v2.13 已明确列出相对 Cordis 上游的激活时序、卸载执行模型、失败恢复与事件可见性差异；
本轮同时读取对应实现和测试，不只引用 README 或设计契约的自述。

## 2. 内核行为对拍

| 维度 | cordis4j 源码证据 | 可观察边界 | Fibra vNext 结论 |
|---|---|---|---|
| 生命周期并发 | `ContextImpl`、`FiberRegistry`、设计契约 D19 | 多个内部 monitor 保护注册表，用户代码在锁外执行；同步 `plugin()` 在返回前完成 satisfied Fiber 激活 | 不复制锁模型；所有状态提交收敛到唯一 lifecycle lane |
| 激活时序 | `ContextImpl.plugin`、设计契约偏差 11、`UpstreamDriftParityTest` | satisfied `plugin()` 同步执行到落地；上游 Cordis 至少让出一个 microtask | 保留 Cordis/Fibra 的异步 tick 边界 |
| effect 清理 | `EffectScopeImpl.track/dispose`、`SimpleLifecycle.revert`、设计契约偏差 12 | 整个 effect domain 使用一个栈，全域严格串行 LIFO；错误聚合 | 只保留单个 effect 内 LIFO；顶层 effects 并发启动并 all-settled |
| 失败恢复 | `Fiber.failed`、`FiberRegistry`、设计契约 D14 与偏差 13 | 激活失败后同一 Fiber 不再重试；更新通过销毁并重新声明或 Loader 换 Fiber 表达 | 保留 `update/restart` 清错并让同一实例重新收敛 |
| 事件可见性 | `ContextImpl` 为每个 Context 创建 `EventBus(parent)`；`EventBus`、设计契约 D3/D22 与偏差 14 | 同步派发，当前 Context 到父链；支持 emit/bail/waterfall，但没有 Cordis 的共享 hook 表与 parallel/serial 完成边界 | 保留代内共享事件表和完整异步模式；`EventMode` 进入 `EventKey` 契约 |
| 服务身份 | `ServiceKey`、`ContextImpl.checkAccess/isolate`、设计契约 D5 | key 是 `Class + qualifier`，qualifier 同时承担 realm 投影 | 保留稳定字符串名与 Java 类型检查；realm 是独立维度，支持跨 ClassLoader 契约 |
| 调用者所有权 | `Context.get/find` 直接返回服务值；公开 API 没有服务调用 envelope | 服务方法拿不到独立的调用者 Scope，注册副作用默认属于当前 ambient/fiber domain | 保留 `ServiceRef + InvocationContext`，调用时显式携带调用者所有权 |
| 子作用域 | `Context.fork/isolate/withBaseUrl`、`EffectScopeImpl` | 子 Context 自身可关闭并作为父 scope 的 effect 登记 | 吸收独立可关闭 `Scope`，但把不可变 Context 视图与资源所有权分开 |
| 关闭竞态 | `ContextImpl.track` 及 plugin/pluginAsync/inject/spawn 的 takeover；设计契约 D29 | 只为四种 lifecycle-bearing API 接管竞态；provide/intercept/listener/child context 等 state-only 产物明确不接管 | 不逐 API 打补丁；在单写者内把状态产生与逆操作登记做成不可分割提交 |

## 3. Loader、HMR 与集成对拍

| 维度 | cordis4j 源码证据 | 适用边界 | Fibra vNext 结论 |
|---|---|---|---|
| 配置组合 | `ComponentSpec`、`Loader.reconcileTree`、`cordis4j-loader` | typed entry/group/isolate/include 先展开，再进行 id-keyed reconcile；格式解析与组件解析可分离 | 吸收不可变 desired model、显式入口和解析边界，不把 include/baseUrl/表达式执行放进 core |
| Loader 事务 | `Loader.reconcile`、`LoaderRollbackTest` | 对 entry/Fiber 集合执行补偿式 reconcile；失败时重建旧集合，不保证旧对象身份，也不覆盖任意外部副作用 | Fibra ChangeSet 只承诺内部可见性、持久决策和已登记资源补偿，明确非承诺边界 |
| Java HMR | `BytecodePluginLoader`、`HotReloadingLoader`、`PluginClassRegistry` | 每 JAR 一个 parent-first `URLClassLoader`；默认扫描 JAR 全部顶层 class 猜唯一 Plugin；按 entry 替换并 close-and-collect | 吸收 ClassLoader 可回收测试；拒绝扫描猜入口，使用显式 manifest、依赖图、候选 ClassSpace 和 artifact 事务 |
| Spring | `CordisServiceRegistrar`、`CordisService` | Bean 初始化后按 ultimate target class 导出，容器停止时逆序撤销 | 吸收自动导出体验；Fibra 必须显式给出稳定 key/type，并落到同一 host binding/Scope 协议 |
| 编译期注入 | `CordisInjectProcessor` | 生成直接字段赋值，避免运行时反射猜入口 | 吸收编译期校验思路，注入仍进入 Fibra typed service 生命周期 |
| Timer | `Timers` | 每个 timer 是一个 `Context.spawn` 虚拟线程，内部 `Thread.sleep` | 不把它作为通用内核能力；真实定时需求应复用统一 scheduler |

`HotReloadingLoader` 的“事务”是配置级 entry/Fiber 更换。复核范围内没有把服务表、事件表、贡献目录、
诊断和宿主路由作为一个 generation 原子发布的公共模型，也没有 `PublishedRuntime` 的 expected revision
调用与旧代 lease 排空。因此不能把它直接等同于 Fibra 的运行代事务。

## 4. 采用与拒绝摘要

采用：独立 Scope、`find/require` 分离、不可变配置组合、显式插件入口、编译期注入、Spring 自动导出
思路，以及 ClassLoader close-and-collect 门禁。

拒绝：同步 core 与额外 async API 的双模型、全域串行卸载、失败 Fiber 终态、父链局部事件总线、
`Class + qualifier` 跨动态插件身份、`Service.start/stop` 第二生命周期、扫描 JAR 猜入口、逐 JAR HMR
充当生产制品层，以及每 timer 一个休眠虚拟线程。

这不是对两个项目作通用排名。cordis4j 更适合零依赖、同步 JVM 组合和轻量 HMR；Fibra vNext 的选择由
异步 Cordis 保真、Java/Node 双运行时、持久事务、代际隔离和托管宿主原子发布共同决定。
