# 插件依赖、装载与更新的源码基线

复核日期：2026-09-11。

## 来源

| 来源 | 复核基线 | 关键位置 |
|---|---|---|
| PF4J | `org.pf4j:pf4j:3.15.0:sources`；SHA-256 `7b8333b0d59a9cbe6bdc31771f7bb250b6d21fe99275539a855135593a311597` | `org/pf4j/DependencyResolver.java`、`org/pf4j/PluginClassLoader.java` |
| IntelliJ Platform | 官方 SDK 文档与 2026-09-09 访问的 `master` 源码；未取得固定提交，不能当作发布版行为承诺 | 下方官方链接 |
| DeepSeek Harness | 原始设计基线 `b0a7d2ce3b4c19d7452e364b2d7acbfa87e707ed`；文章对拍 `a66e4702047846cdaa10c66c9d3df3951f5ea70d`；进程单元复核 `c291e7961a515f6d7af9304e7fd1d257929aef26` | 原有插件路径及 `packages/subprocess` |
| OpenAI Codex | `b9852fe6f7c73c98277da38cccb238083c843d4e` | `codex-rs/utils/pty/src/process_group.rs`、`codex-rs/utils/pty/src/win/job.rs`、`codex-rs/core/src/exec.rs` |
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

Fibra 采用每制品加载器、显式依赖和共享类型单一归属。Java 类沿依赖图委派；父优先前缀先查询 parent，
parent 缺类时继续查询本制品和声明依赖，不能把前缀误作宿主导出白名单。资源采用本地、依赖图、宿主顺序，
枚举去重。递归资源查找是 Fibra 为同一依赖图确定的契约，并非原样复制 PF4J。宿主实际导出的类仍由
parent 唯一定义；ClassLoader 是类型隔离机制，不是安全沙箱。

## DeepSeek Harness 的两种依赖

固定提交下，`vendor/cordis/src/registry.ts` 的 `Inject.resolve` 归一化服务依赖；`fiber.ts` 和 `reflect.ts` 驱动服务变更后的生命周期收敛。JavaScript 模块 import 与 Cordis 服务注入承担不同职责。

前端 `packages/client/modules/src/index.ts` 的 `orderByModuleGraph` 按 `external` 边排序，拒绝自依赖和环；模块请求与提供方检查由组合流程完成。`dsh.client.inject` 的包名列表是信息字段，不控制服务激活顺序。浏览器模块物化由 `src/client/system.ts` 负责，实例启动仍由浏览器 Cordis 服务依赖决定。

`vendor/loader/src/config/entry.ts` 对替换入口先 import，应用失败则恢复旧配置/插件；`packages/boot/app-boot/tests/config-reload.spec.ts` 覆盖失败后恢复、下一次有效修改和树级回滚。它会在部分路径重建旧实例，因此不能描述为所有失败都保留旧对象身份。

浏览器插件与 Node sidecar 分属不同运行位置。Host 发布客户端图，浏览器运行自己的插件树，HMR 负责旧实例和样式清理。Fibra 本期的 Java/Node 运行时不能据此宣称已实现浏览器插件平台。

### 差量更新与清理顺序的直接采用边界

固定 `a66e470204` 的 `Entry.update()` 使用 `deepEqual` 计算条目字段变化：无变化且非强制时立即返回；
普通配置变化经 `_patchContext()` 调用原 Fiber 的 `update()`；`name/inject/group` 变化才替换 Fiber，
其中新入口 import 在旧实例 dispose 之前完成。Fibra 采用相同的职责划分：Engine 比较声明和实际
definition 身份，仅绑定变更输入，普通配置更新保留实例身份；Java 契约类被重新装载或有效隔离策略
改变时重挂受影响实例。制品的资源影响范围由 adapter 计算，不由 Engine 重复实现装载依赖图。

`Fiber._refresh()` 根据实际注入服务的 provider uid 派生 epoch，`_setEpoch()` 在已有 inertia 未完成时
只更新目标；`_reload/_unload` 再驱动后续收敛。这支持由 core 管理消费者联动，而不是 loader 对所有
消费者再次执行配置替换。`Fiber.parent` 和父 Fiber 的 effect 已表达动态子插件的真实归属，不能用
配置 ID、Scope 名称或字符串前缀反推父实例。

同一源码的 `_unload()` 使用 `Promise.all`，逐项捕获普通 effect 清理错误；effect 内部的多个 disposer
才是逆序串行。这不保证某个贡献的排空先于其他 effect 的资源销毁，也不能把 dispose 正常完成等同于
资源都已清理。Fibra 的宿主调用和可卸载 Java 资源因此还需要清理前的受管排空边界及可观测清理结果，
同时保留普通 effects 的并发与错误隔离语义。这部分属于本项目边界要求，不冒称 DSH 已提供相同保证。

不能据此推断 DSH 没有依赖清理等待：`ReflectService.provide()` 的 disposer 先删除公开服务，通知实际
消费者并 `await Promise.allSettled(fibers.map(fiber => fiber.await()))`，最后才删除 provider 自身的
旧服务快照。该等待只覆盖这个服务 disposer，不能约束并行执行的其他 provider effects，也不要求
消费者清理全部成功。Fibra 采用“撤销服务、由内核等待真实消费者”的逻辑；只等待旧 activation 清理，
不把消费者重新激活当作释放旧资源的先决条件。

源码：[Entry 更新](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/loader/src/config/entry.ts)、
[Fiber 生命周期和资源归属](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/cordis/src/fiber.ts)、
[服务撤销与消费者等待](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/cordis/src/reflect.ts#L277-L335)。
新 Engine 已按上述差量职责重写；清理前排空、跨 Java/Node 局部更新和主动停用已有独立门禁。
这只证明框架行为边界，仍不能替代第 10.1 节真实多插件应用与最终分发验收。

### 资源、调用与保存问题的采用边界

下表均以 `a66e4702047846cdaa10c66c9d3df3951f5ea70d` 为准。实现优先采用已有机制；新增保证必须
对应本项目实际宿主边界，不能因为新增状态或测试更多就宣称整体优于 DSH。

| 问题 | DSH 实际处理 | Fibra 落点 |
|---|---|---|
| effect 设置期间重入关闭、异步迟到的 disposer | `Fiber.effect()` 先登记 wrapper，设置屏障等待完整收集；`effectInertia/runDisposable` 复用在途清理 | 沿用现有资源所有权和一次清理终态，不另造一棵清理树 |
| 配置修改失败 | `Entry.update()` 尝试恢复旧配置或重启旧插件；恢复也可能失败 | 预绑定失败不改运行态；完整目标保存后报告实际收敛结果，不自动反写目标。这是持久目标语义差异，不是 DSH 漏做回滚 |
| 工具卸载与正在执行的调用 | 工具注册由 `ScopedLayers.effect()` 撤销；已进入 body 的调用持有局部 tool 引用，但插件卸载不等待它 | 受管宿主调用登记到贡献条目，停止准入并等待调用及调用方清理后才释放资源。只增强受管入口，不承诺追踪任意线程和裸服务调用 |
| 调用取消 | 等待 `tool.execute()` 实际结算后返回取消结果，不能硬杀同进程代码 | 同样协作式等待；不能用取消订阅冒充插件代码已经停止 |
| 清理失败 | Fiber 普通 disposer 错误记录日志后继续，不提供失败资源保留契约 | 普通清理仍隔离错误；受管卸载另行记录真实失败，保留尚被依赖的资源。排空失败必须明确结束，不无限等待一个已知失败 |
| 半写入、并发去重与失败清理 | 附件库独占临时文件、校验及同步后无覆盖发布；冲突读回校验，失败只删自己的临时文件 | 制品采用完整暂存后发布、已有对象校验复用、仅清自有暂存。目录不能照搬文件 hard-link；不增加共享对象回滚、引用计数或通用 GC |
| 摘要与 metadata 不一致 | 附件发布和读取都校验内容摘要及引用字段 | 用同一 revision 公式重验制品身份和内容，不只检查路径中的摘要形状 |
| 并发保存、关闭与持久性 | settings-file 串行队列及完整文件锁，停止等待队列；附件库同步文件和目录链 | Store 操作串行且关闭后旧句柄不可再写；持久确认不能仅依赖 rename 或“目标已存在” |
| 宿主资源关闭 | CLI 单一 shutdown 协调器关闭 root fiber，host-half 启动失败立即释放新 fiber | Engine 拥有其内部资源，Spring 只调用该关闭入口；DSH 无 Spring DI 对照，不能从它推导 Bean 销毁细节 |

工具证据：[注册与调用](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/core/tools/src/index.ts#L1028-L1052)、
[调用执行与协作取消](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/core/tools/src/index.ts#L1333-L1550)、
[注册所有权](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/core/scope/src/store.ts#L226-L265)。

存储证据：[完整发布与完整性校验](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/attachment/attachment-local/src/store.ts#L145-L305)、
[settings-file 操作队列与锁](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/settings/settings-file/src/index.ts#L193-L269)。
`util/atomic-write` 的 `writeFileAtomic()` 明确不负责 fsync；`vendor/include` 的写队列和临时文件 rename
也不能直接证明断电持久性。DSH Loader 使用 Node import，包安装由外部 npm/pnpm 处理，不存在对应
Fibra 的 Java 制品仓库与 ClassLoader 协议。[原子写工具的职责](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/util/atomic-write/src/index.ts#L62-L93)。

宿主证据：[单一退出协调器](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/apps/cli/src/process-shutdown.ts#L22-L76)、
[启动失败的资源归还](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/extensions/cordis-host-runner/src/lifecycle.ts#L22-L45)。

### 配置组合的直接采用边界

固定 `a66e470204` 的 `vendor/include/src/index.ts#applyEntryPatches` 复制输入，建立当前文档的
条目 ID 索引，再按顺序浅覆盖或追加；索引递归 group，但不跨 include。名称字段只作匹配保护，
缺失目标或保护不符产生告警并继续，显式插入的条目可被后续补丁命中。Fibra 采用这一模型，
用已有声明字段 `plugin/entries/enabled` 表达 DSH 的 `name/config/disabled`，不维持另一套补丁协议。
未知补丁字段、ID/插入列表控制形状、最终条目结构和重复 ID 仍按 Fibra 输入边界拒绝，而不是
透传任意未知属性。目标未匹配或名称保护失败时，未应用的覆盖值不做额外声明校验，与 DSH 一致。

特别是普通 group 子列表整体覆盖不会把新后代加入当前补丁索引，不能每一步重新递归查找最新树。
DSH 的 `packages/boot/app-boot/tests/config-dump.spec.ts` 明确验证这一点，以保证离线配置合成与
实际启动的单次补丁应用结果相同。Fibra 沿用此顺序语义；位置调整由已有树编辑接口承担。

`packages/boot/app-boot/tests/config-reload.spec.ts` 验证无效文件刷新保留上次有效树、后续有效修改
仍可应用。Fibra 的对应门禁使用真实 include 文件，覆盖语法损坏、空文件和非数组根，验证目标、
实例身份及 effects 不变，再验证修正后原实例更新。Jackson 将空文件归为解析错误，DSH 的 js-yaml
将其归为后续校验错误；两者都明确拒绝该输入，不为统一错误阶段改写底层解析器。

源码：[条目补丁](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/vendor/include/src/index.ts#L58-L127)、
[单次索引门禁](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/boot/app-boot/tests/config-dump.spec.ts#L109-L140)、
[配置刷新场景](https://github.com/deepseek-ai/deepseek-harness/blob/a66e4702047846cdaa10c66c9d3df3951f5ea70d/packages/boot/app-boot/tests/config-reload.spec.ts)。

### 配置条目隔离与分组继承

`a66e470204` 的 `vendor/loader/src/config/isolate.ts` 将 `true` 解析为条目持有的 `LocalRealm`，
字符串标签解析为共享的 `GlobalRealm`；每个 realm 内又按服务名称取得稳定 symbol。
`loader/patch-context` 从父条目的 isolate/intercept 视图继承，再应用当前条目的覆盖。
因此，在 group 上声明 `true` 时，未覆盖该服务隔离策略的组内插件共享同一个范围；不同 group
各自声明 `true` 时不能互相看见服务。不同插件条目各自声明 `true` 时同样不能误共享。

Fibra 的 `DesiredInputGraph` 保留插件、group 和 include 节点，`effective` 派生结果保留最近声明者的
完整 identity。`FibraEngine.context` 将 `true` 映射为声明节点的局部身份，并按服务名称派生全部策略。
`DesiredRealmIsolationTest.groupLocalRealmsAreSharedWithinEachGroupAndDistinctAcrossGroups`
通过真实 YAML 编译和 Engine 启动验证两个组各含 provider/consumer、各自读取本组服务而不冲突。
同类测试同时覆盖命名 realm 跨组共享、后代恢复默认 realm，以及删除配置源文件后从清单重建 include
命名空间和局部隔离归属。这里验证的是配置树与装配链，不代表局部运行资源更新及全部 DSH 门禁已完成。

仅在挂载叶子时把 `true` 换成新对象不构成修复：那会让同组的 provider/consumer 也处于不同范围。
该行为门禁同时要求保留策略所属条目的身份与父子继承，不能只证明“两个 provider 不再重复注册”。

## 进程单元与回收

JDK `ProcessHandle.children()` / `descendants()` 返回的是调用时快照，进程状态又会异步变化，因此它适合
诊断而不是所有权。NuProcess 的 POSIX `destroy` 对单个 PID 调 `kill`，Windows 路径对单个 handle 调
`TerminateProcess`，也不提供完整进程单元。`tree-kill` 在 Linux/macOS 通过 `ps`/`pgrep` 递归发现后代，
仍有父进程先退出导致链路丢失的窗口。[JDK ProcessHandle](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html)、[NuProcess POSIX](https://github.com/brettwooldridge/NuProcess/blob/master/src/main/java/com/zaxxer/nuprocess/internal/BasePosixProcess.java)、[NuProcess Windows](https://github.com/brettwooldridge/NuProcess/blob/master/src/main/java/com/zaxxer/nuprocess/windows/WindowsProcess.java)、[tree-kill](https://github.com/pkrumins/node-tree-kill)

Codex 在 POSIX 创建独立进程组，以 `SIGTERM`、等待、`SIGKILL` 作用于同一 PGID；Windows 使用 Job
Object，并在需要消除启动竞态的路径中先挂起创建、加入 Job、再恢复。DSH 把同一思想抽成
provider-managed range，同时公开 systemd scope、Windows Job 与较弱 PGID/taskkill fallback 的能力边界。
[Codex process group](https://github.com/openai/codex/blob/b9852fe6f7c73c98277da38cccb238083c843d4e/codex-rs/utils/pty/src/process_group.rs)、[Codex Windows Job](https://github.com/openai/codex/blob/b9852fe6f7c73c98277da38cccb238083c843d4e/codex-rs/utils/pty/src/win/job.rs)、[DSH subprocess](https://github.com/deepseek-ai/deepseek-harness/tree/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/subprocess)

Fibra 采用相同的所有权模型，但不直接依赖 Codex 的 Rust workspace，也不把 DSH 的 RC、Node/Cordis
组合引入 Java 核心。`fibra-runtime-node` 内部以 `NodeProcessUnit` 隔离协议和进程生命周期：监督器是
RuntimeDomain 等待的 participant，payload 在 POSIX 独立进程组内运行；Windows 明确使用较弱的系统树
终止后端。只有受管范围静默才完成 retire，主动逃离该范围的非可信代码必须进入更强的外部 sandbox。

### 当前发布策略对拍

`a66e470204` 的 `packages/boot/app-boot/src/index.ts#assertEntriesActivated` 在应用启动审计中拒绝 enabled
但仍为 `PENDING` 的 entry，并从其 Fiber 依赖中报告缺失服务；对应测试位于
`packages/boot/app-boot/tests/app-boot.spec.ts`。同一提交的
`packages/extensions/cordis-client-runner/src/client/runtime.ts` 则把 settled、非 ACTIVE 的动态插件视为
合法等待，返回 `waitingFor`；行为测试位于 `tests/plugin.client.spec.ts`。

这两种策略服务于不同宿主场景，不能把 `PENDING` 固化为全局成功或全局失败。Fibra 因此把
`PublicationRequirement` 放在逐 entry 配置上，由 `EngineSnapshot.instances` 投影声明要求和是否达成；
`RuntimeDiagnostics` 公开整个域的状态和 `waitingFor`，不为未声明的动态子插件虚构发布策略。

## Fibra 的落点与代价

| 场景 | 最终方案 | 架构与使用取舍 |
|---|---|---|
| 共享 Java API | contract-only JAR；消费者使用 `provided` 构建依赖并声明 manifest `requires` | 契约类型归属清楚，无需虚构运行入口；需要同时理解构建和装载依赖 |
| 服务实现可替换 | `PluginDefinition.require/provide` 与 realm | 依赖服务契约，不绑定某个实现制品；装载图不能替代服务就绪检查 |
| 升级/卸载 | 长期 RuntimeDomain；Java 按变化制品的旧、新反向依赖闭包替换资源 | 保留无关实例和装载器；预绑定只暂存受影响新资源，运行更新不承诺整批回滚 |
| 单独停用 provider | 内核使实际消费者清理旧快照并进入 PENDING，Engine 报告目标是否达成 | 不暗改 desired graph；逐 entry 要求区分合法等待与未达成，仍发布实际运行事实 |
| 前后端共同交付 | 后续独立 Web 集成消费版本化图 | 浏览器实际状态必须独立报告，不能与服务端提交视为一个原子事务 |

源码入口见 [plugin-dependency](../../../fibra-example/plugin-dependency/README.md)、`JavaArtifactGraphTest`、
`PluginClassLoaderTest` 与 `PluginDependencyScenarioIT`。图约束、真实 JAR 委派及旧整代示例不能单独
证明最终差量更新已实现；验收还必须验证无关实例/装载器保持身份、provider 停用后的真实 PENDING
以及局部失败结果。版本匹配仅证明声明兼容，不证明二进制或业务行为兼容。

## 补充设计参照

Spring Plugin、gj.spring.pf4j、Spring AI、Google ADK Java、Embabel Agent、OSGi Declarative Services、
IntelliJ Disposer 与 Netty EventLoop 只提供局部设计参照，不构成 Fibra 的依赖或产品方向。当前采用的
原则是：动态制品与宿主 classpath 策略注册分离、ClassLoader 资源不得跨 reload 泄漏、Spring 只做
外层适配、资源树必须可撤销、生命周期只有一个异步模型。
