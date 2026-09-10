# 插件依赖、装载与更新的源码基线

复核日期：2026-09-09。

## 来源

| 来源 | 复核基线 | 关键位置 |
|---|---|---|
| PF4J | `org.pf4j:pf4j:3.15.0:sources`；SHA-256 `7b8333b0d59a9cbe6bdc31771f7bb250b6d21fe99275539a855135593a311597` | `org/pf4j/DependencyResolver.java`、`org/pf4j/PluginClassLoader.java` |
| IntelliJ Platform | 官方 SDK 文档与 2026-09-09 访问的 `master` 源码；未取得固定提交，不能当作发布版行为承诺 | 下方官方链接 |
| DeepSeek Harness | 原始设计基线 `b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`；当前文章对拍 `a66e4702047846cdaa10c66c9d3df3951f5ea70d` | `vendor/loader/src`、`packages/boot/app-boot`、`packages/client/modules/src`、`packages/client/hmr/src/client/index.ts`、`packages/extensions/cordis-client-runner` |
| cordis4j | `6cfd56e684fb403ded952afc09eddb49a5228494` | 独立的[设计对拍与采用边界](2026-09-11-cordis4j-design-evidence.md)；本文件不再代管其内核语义 |

本地 DeepSeek Harness 是 TypeScript 项目。用户提供的 `deepseek-harness-java` 截图和 JAR/Node Bridge 流程是另一份设计材料，尚无对应 Java 源码可复核，不能混为已实现行为。

用户提供的文章
[《拆解 dsh：这套插件设计该如何借鉴》](https://mp.weixin.qq.com/s/CCAMmQHYQ8I1Kxq27Du2Gw)
以 `@deepseek-ai/dsh 0.1.2-rc.1` 为基线。本轮已使用对应提交 `a66e470204` 复核文章影响当前设计的
发布策略、事件模式和动态插件边界；文章中的统计与判断不是 Fibra 的实现承诺。

## IDEA 与 PF4J

IDEA 要求构建依赖和运行时插件声明同时存在；把另一个插件作为普通库重复打包会制造两份类型。每个插件有自己的加载器，声明的插件依赖参与类加载委派。可选依赖通过单独配置片段隔离相关功能。[插件依赖](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)、[类加载](https://plugins.jetbrains.com/docs/intellij/plugin-class-loaders.html)。

IDEA 的模块化插件进一步拆分共享、前端、后端模块，并按模块依赖配置加载器与装载条件；该文档明确标记为实验性。[模块化插件](https://plugins.jetbrains.com/docs/intellij/modular-plugins.html)。

`DynamicPlugins` 的装载、卸载路径先调用 `computeNewPluginsState`，校验目标集合，再交给 `DynamicPluginsSupport` 重配置。这支持“先验证目标图”的原则，但不证明 IDEA 提供 Fibra 的持久事务或新旧代并存保证。[官方源码](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/ide/plugins/DynamicPlugins.kt)。

PF4J 的 `DependencyResolver.resolve` 计算依赖图、拓扑序、缺失依赖与版本冲突。`PluginClassLoader.loadClassFromDependencies` 调用依赖加载器的 `loadClass`，因而可以继续沿传递依赖查找。其资源实现查询直接依赖的 `findResource/findResources`，不能声称与递归类委派完全相同。

Fibra 采用每制品加载器、显式依赖和共享类型单一归属。Java 类沿依赖图委派；资源采用本地、依赖图、宿主顺序，枚举去重。递归资源查找是 Fibra 为同一依赖图确定的契约，并非原样复制 PF4J。宿主导出的类仍由 parent 唯一定义；ClassLoader 是类型隔离机制，不是安全沙箱。

## DeepSeek Harness 的两种依赖

固定提交下，`vendor/cordis/src/registry.ts` 的 `Inject.resolve` 归一化服务依赖；`fiber.ts` 和 `reflect.ts` 驱动服务变更后的生命周期收敛。JavaScript 模块 import 与 Cordis 服务注入承担不同职责。

前端 `packages/client/modules/src/index.ts` 的 `orderByModuleGraph` 按 `external` 边排序，拒绝自依赖和环；模块请求与提供方检查由组合流程完成。`dsh.client.inject` 的包名列表是信息字段，不控制服务激活顺序。浏览器模块物化由 `src/client/system.ts` 负责，实例启动仍由浏览器 Cordis 服务依赖决定。

`vendor/loader/src/config/entry.ts` 对替换入口先 import，应用失败则恢复旧配置/插件；`packages/boot/app-boot/tests/config-reload.spec.ts` 覆盖失败后恢复、下一次有效修改和树级回滚。它会在部分路径重建旧实例，因此不能描述为所有失败都保留旧对象身份。

浏览器插件与 Node sidecar 分属不同运行位置。Host 发布客户端图，浏览器运行自己的插件树，HMR 负责旧实例和样式清理。Fibra 本期的 Java/Node 运行时不能据此宣称已实现浏览器插件平台。

### 当前发布策略对拍

`a66e470204` 的 `packages/boot/app-boot/src/index.ts#assertEntriesActivated` 在应用启动审计中拒绝 enabled
但仍为 `PENDING` 的 entry，并从其 Fiber 依赖中报告缺失服务；对应测试位于
`packages/boot/app-boot/tests/app-boot.spec.ts`。同一提交的
`packages/extensions/cordis-client-runner/src/client/runtime.ts` 则把 settled、非 ACTIVE 的动态插件视为
合法等待，返回 `waitingFor`；行为测试位于 `tests/plugin.client.spec.ts`。

这两种策略服务于不同宿主场景，不能把 `PENDING` 固化为全局成功或全局失败。Fibra 因此把
`PublicationRequirement` 放在逐 entry 配置上，并在 `RuntimeDiagnostics` 中同时公开状态、
`waitingFor` 和 publication impact。

## Fibra 的落点与代价

| 场景 | 当前方案 | 架构与使用取舍 |
|---|---|---|
| 共享 Java API | contract-only JAR；消费者使用 `provided` 构建依赖并声明 manifest `requires` | 契约类型归属清楚，无需虚构运行入口；需要同时理解构建和装载依赖 |
| 服务实现可替换 | `PluginDefinition.require/provide` 与 realm | 依赖服务契约，不绑定某个实现制品；装载图不能替代服务就绪检查 |
| 升级/卸载 | 完整候选 Java ClassSpace 与 Engine generation，验证后发布 | 一致性简单、失败可保留旧代；代价是重建范围较大和准备期资源重叠 |
| 单独停用 provider | Engine 拒绝使仍启用的 consumer 无法 ACTIVE 的目标图 | 不暗改用户的 desired graph；宿主可显式提交整组启停意图 |
| 前后端共同交付 | 后续独立 Web 集成消费版本化图 | 浏览器实际状态必须独立报告，不能与服务端提交视为一个原子事务 |

真实示例见 [plugin-dependency](../../../fibra-example/plugin-dependency/README.md)。`JavaArtifactGraphTest` 检查图约束，`PluginClassLoaderTest` 检查真实 JAR 委派，`PluginDependencyScenarioIT` 检查共享契约、升级、拒绝停用后的继续操作。版本匹配仅证明声明兼容，不证明二进制或业务行为兼容。

## 补充设计参照

Spring Plugin、gj.spring.pf4j、Spring AI、Google ADK Java、Embabel Agent、OSGi Declarative Services、
IntelliJ Disposer 与 Netty EventLoop 只提供局部设计参照，不构成 Fibra 的依赖或产品方向。当前采用的
原则是：动态制品与宿主 classpath 策略注册分离、ClassLoader 资源不得跨 reload 泄漏、Spring 只做
外层适配、资源树必须可撤销、生命周期只有一个异步模型。
