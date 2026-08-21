# Fibra 内核设计（Cordis core 的 Java 忠实移植）

日期:2026-08-21
状态:已确认方向,待用户复审后进 M1 计划

## 一句话目标

用 **Java 独立零依赖内核**,语义 **1:1** 复刻 Cordis `packages/core`(Context / Service / Fiber / Reflect / Registry / Events),
含**响应式注入 + epoch 重载 + 异步生命周期**。只做**逻辑重载**(代码热替换 HMR 划到内核外的 `fibra-hmr`)。

## 已锁定的五个决策

1. **对标层**:Cordis 内核语义(不含 DSH 具体插件,不含整 harness)。
2. **地基**:独立零依赖内核(不建在 Spring/Solon/Hasor/OSGi 上——它们的应用级 bean 与 context 作用域服务有阻抗)。
3. **重载边界**:只做逻辑重载(配置变/依赖服务变 → dispose+重建子树);字节码热替换出内核,日后 `fibra-hmr`。
4. **并发/异步**:**单线程生命周期调度器**(所有 mount/unmount/reload 在一条专用线程串行,复刻 Cordis 单线程顺序保证)+ **CompletableFuture** 异步生命周期(对应 Cordis 的 Promise/`inertia`)。插件自身业务可另起线程池。
5. **保真度**:全保真 1:1,**语义不退化**。Cordis 的 `ctx.foo`(JS Proxy over 任意运行时名字的字段访问)在 Java 语言层无对应(无 `__getattr__`、`Proxy` 仅接口),这是**唯一物理不可达的语法**;但用**类型安全的等价物**替代,不是缩水而是净增强(编译期类型检查,拼错即编译错,而非 `any` + 运行时炸):
   - **`@Inject` 响应式字段注入**:插件类 `@Inject FooService foo;`,内核在服务可用时注入、服务变更时参与 epoch 重载。恢复字段访问手感 + 类型安全 + 全程响应式(同 Spring `@Autowired` / IntelliJ `@Reference`)。**先运行时反射实现**,APT/native 版留后。
   - **`Ref<T>` 类型安全句柄**:`Ref<FooService> f = ctx.inject(FooService.class)`,`f.get()` 反映当前绑定并驱动重载;`ctx.get(FooService.class)` 命令式取值。
   - **sink 式 effect**:`ctx.effect(sink -> { sink.add(d1); sink.add(d2); })` 覆盖 Cordis generator 增量 yield 语义(见「难点3」,已消除)。

## 工程形态

- 独立仓,坐标 `com.sstlfsj:fibra`(父 pom,packaging=pom,`<version>${revision}</version>`,`<revision>0.1.0-SNAPSHOT</revision>`,flatten-maven-plugin resolveCiFriendliesOnly)。
- Java 21(单线程调度器可用虚拟线程;不强依赖)。零运行时依赖;测试 JUnit5。
- 模块:现只建 `fibra-core`(内核,包 `com.sstlfsj.fibra`,**零运行时依赖**)。未来兄弟模块(要时再加,不重构):
  - `fibra-spring` / `fibra-spring-boot-starter` —— Spring 集成适配(见「Spring 集成友好性」)
  - `fibra-hmr`(代码热替换 ClassLoader 层)/ `fibra-loader`(配置文件装载)/ `fibra-timer` / `fibra-logger`
- 仓库目录 `/Users/sunke/dev/ai-project/fibra`。

### Spring 集成友好性（为什么零依赖 core 是前提而非障碍）
把内核**建在** Spring 上会被 Spring 焊死、丧失独立/多宿主能力(已否决)。保持 core 纯净后,Spring 集成是一个**可选适配模块** `fibra-spring-boot-starter`(与 disruptor-spring-boot 同构):
- 把 root `Context` 与关键内核 bean 装配为 Spring bean(自动装配);
- **服务互桥**:Fibra 服务暴露为 Spring bean;Spring bean 作为 Fibra 服务源(让 Fibra 插件能 `@Inject` Spring 管理的对象);
- `@Inject`(Fibra 自有注解,core 零依赖)与 `@Autowired`(Spring)可共存;适配器负责两套注解/容器的桥接,**core 永不依赖 Spring**;
- 同一 core 可同时支持 Spring / Solon / 纯 Java 等多宿主。

## 类映射(Cordis core → Fibra，均在 fibra-core）

真源:本机 `/Users/sunke/dev/ai-project/cordis/packages/core/src/*.ts`(context/service/fiber/reflect/registry/events)。

| Cordis 文件 | Fibra 类 | 职责与要点 |
|---|---|---|
| `context.ts` | `Context` | 服务容器 + 作用域。API:`extend(meta)` / `isolate(name)` / `intercept(name,cfg)` / `get(name)` / `set(name,v)` / `provide(name,v)` / `inject(...)` / `effect(...)` / `on(...)` / `plugin(...)`。原型链作用域(`Object.create` 链)→ **父引用链 scope 对象 + 隔离键 `Map<String,Object>`**。 |
| `service.ts` | `Service<T>`(abstract) | 构造即 `reflect.provide(name,this)` 自注册;`filter(ctx)` 按隔离键判可见。callable service(cordis `createCallable`)→ 可选,内核先不做 invokable service。 |
| `fiber.ts` | `Fiber` + `FiberState`(PENDING/LOADING/ACTIVE/FAILED/DISPOSED/UNLOADING) | **生命周期状态机 + effect 系统 + 响应式重载**。见下「难点2」。 |
| `reflect.ts` | `ReflectService` | 服务 store(按隔离键 symbol)+ 沿 fiber 链解析(`_getImpl` / get 陷阱逻辑)+ `provide`/`notify` **响应式重绑**。见「难点1、2」。 |
| `registry.ts` | `RegistryService` | 插件注册表:`Plugin.Runtime`(一个插件定义)+ 其 `fibers`(该定义的多个实例);`plugin()` 注册、`inject()` 声明依赖。 |
| `events.ts` | `EventsService` | 事件总线:`on/once/emit/parallel/serial/bail/waterfall`;内部事件 `internal/plugin`、`internal/service`、`internal/status`、`internal/get`、`internal/set`、`internal/update`。 |
| `utils.ts`(DisposableList 等) | `Disposable` / `Effect` / `DisposableList` | disposer 原语。逆序(LIFO)dispose;fiber 父子级 teardown。销毁树语义参考 IntelliJ `Disposer`(本机 `~/dev/ref/ij-disposer/`)。 |
| —（新增） | `LifecycleDispatcher` | 单线程 executor,串行所有生命周期变更;`CompletableFuture` 表达 `inertia`/async ready/dispose。 |
| —（新增） | `Plugin<C>` | 插件契约:函数式 `apply(Context ctx, C config)`,或抽象类带 `inject` 声明 + 生命周期钩子(对应 cordis `runtime.callback` + class-plugin 的 `[symbols.init]`）。 |
| —（强化） | `@Inject` + `InjectProcessor` | 响应式字段注入:插件类 `@Inject FooService foo;`。**先运行时反射**——扫描字段、把声明转成 fiber `inject`,服务可用时 `setField`、变更时随 epoch 重载。core 自有注解、零依赖;`fibra-spring` 再桥接 `@Autowired`。 |
| —（强化） | `Ref<T>` | 类型安全注入句柄:`Ref<Foo> f = ctx.inject(Foo.class)`;`f.get()` 反映当前绑定并驱动重载。命令式访问替代 `ctx.foo`。 |
| —（强化） | `DisposableSink` | sink 式 effect:`ctx.effect(sink -> { sink.add(d1); sink.add(d2); })` 覆盖 cordis generator 增量 yield 语义(见「难点3」)。 |

### 内核对外 API 面（1:1 的"能做什么"，语法 Java 化）
- 作用域:`ctx.extend()`、`ctx.isolate(name)`、`ctx.intercept(name,cfg)`
- 服务:`ctx.provide(name,value)`→返回 disposer;`ctx.get(name[,strict])`;`ctx.set(name,value)`;`Service` 基类自注册
- 注入:`@Inject FooService foo`(响应式字段注入)/ `Ref<Foo> f = ctx.inject(Foo.class)`(类型安全句柄)/ 插件声明 `inject`;**服务到位才激活、服务变了 dispose+重建**;`ctx.get(Foo.class)` 命令式取值
- 副作用:`ctx.effect(Supplier<Disposable>)`(单 disposer)/ `ctx.effect(Consumer<DisposableSink>)`(sink 增量收集)/ 异步 `Supplier<CompletionStage<Disposable>>` 变体→均返回可弃置句柄
- 事件:`ctx.on(event,handler)`→返回 disposer;`ctx.emit/parallel/bail/waterfall`
- 插件:`ctx.plugin(plugin, config)`→返回 `Fiber`;`fiber.update(config)` / `fiber.dispose()` / `fiber.await()`

## 三处难点（诚实标注可保真度）

### 难点1：`ctx.foo` 透明属性访问 —— 唯一物理不可达的语法，用更强的类型安全等价物替代（非退化）
Cordis 靠 JS `Proxy`（`ReflectService.handler`）拦截**任意运行时名字**的字段 get/set/has,路由到 fiber 链解析。
**Java 语言层无对应**(无 `__getattr__`;`Proxy` 仅接口方法)。→ 用类型安全等价物替代,语义(作用域解析、未注入即报错、隔离键匹配)全保,且**净增强**(编译期类型检查):
- `@Inject FooService foo`(响应式字段注入,先运行时反射)——恢复字段访问手感 + 类型安全 + 响应式;
- `Ref<Foo> f = ctx.inject(Foo.class)` / `ctx.get(Foo.class)` —— 命令式类型安全访问。

对比:Cordis `ctx.foo` 返回 `any`、拼错 `ctx.fooo` 运行时才炸;Java 版拼错即**编译错**。仅"点号取任意运行时名"这一 JS 动态语法不可搬,能力不缩水。

### 难点2：响应式注入 + epoch 重载 —— Cordis 最独有、可 1:1 但是主要工作量
Fiber 用"被注入服务的 provider-fiber uid"拼出 **epoch** 字符串(`_refresh`);epoch 变化 → `_setEpoch` 触发:
- 变为可用（`INACTIVE`→有值）:`_reload()` 跑插件回调、收集 effect,进 ACTIVE;
- 变为不可用:`_unload()` 逆序跑 disposer、teardown,回 PENDING。
`inertia`（一个 CompletableFuture）**串行化** reload/unload,防并发交叠;并处理"卸载中又变可用→卸完再装"的翻转。
`ReflectService.provide` 的 disposer 会 `notify([name])` → 扫描所有 fiber,凡 `inject` 该服务者 `_checkImpl`+`_refresh`,
并 `await` 它们 teardown 完成。**这套"服务变了→自动 dispose+重建子树"就是 IDE 内核都没完全做到的部分。**
Java 1:1 实现要点:Fiber 状态机 + epoch 身份追踪 + inertia(CompletableFuture 串行,跑在单线程调度器上)+ registry 全局 notify。

### 难点3：flexible effect 协议 —— 已用 sink 消除
Cordis `effect` 收集器接受四态 disposer:同步函数 / Promise / Iterable / **AsyncIterable(generator)**(generator 可**增量 yield** 多个 disposer)。
Java 用 **sink 式 effect** `ctx.effect(Consumer<DisposableSink>)` 覆盖增量 yield 语义(effect 体内 `sink.add(d)` 逐个推送),
外加 `Supplier<Disposable>`(单)、`Supplier<CompletionStage<Disposable>>`(异步)两个便捷重载。**逆序 teardown、异步 disposer、增量收集全部 1:1**,不再是变形。

### 其余全部可 1:1
effect 逆序 dispose、fiber 父子级 teardown(孩子先于父,参考 IntelliJ Disposer)、异步生命周期(`await`/`inertia`→CompletableFuture)、
isolate/intercept 作用域(原型链→父引用链)、单个 disposer 抛异常被隔离记录不中断兄弟(参考 Cordis 与 IntelliJ 都如此)。

## 并发模型细节

- 一条 `LifecycleDispatcher` 单线程(`Executors.newSingleThreadExecutor`,可选虚拟线程)串行执行:plugin 挂载/卸载、fiber reload/unload、provide/revoke 的 notify。→ 复刻 Cordis 单线程事件循环的顺序保证,最好推理。
- 异步:reload/unload/provide-disposer 返回 `CompletableFuture<Void>`;`fiber.await()` = 等 inertia 链 settle。
- 插件业务代码可自起线程/线程池;内核只保证**生命周期操作**串行。
- 对照数据点:IntelliJ Disposer 选了"全局锁 + 锁内收集/锁外执行"而非单线程——两条都被验证;本内核取单线程(更贴 Cordis、更易推理)。

## 里程碑（太大，拆 4 段，各自 spec→plan→实现；全部落在 fibra-core）

- **M0 脚手架**:父 pom(${revision}+flatten)+ `fibra-core` 模块 pom + 空包结构 + 一个 `mvn test` 能过的占位。
- **M1 effect/dispose 骨架**:`Disposable` / `Effect` / `DisposableList`(LIFO 逆序 dispose) + `Context.effect()`(含 `Supplier<Disposable>` 与 **sink 式** `Consumer<DisposableSink>` 两形态) + fiber 父子级逆序 teardown(参考 Disposer 语义)。**验收**:effect 逆序回收、sink 多 disposer 增量收集且逆序回收、孩子先于父、单 disposer 抛异常不中断兄弟。
- **M2 Context 作用域 + 服务注册**:`extend/isolate/intercept` + `Service` 基类 + `provide/get/set` + 隔离键解析(**非响应式**,同步)。**验收**:带隔离的 provide/get;未注入即报错;重复 provide 报错。
- **M3 Fiber 生命周期 + 响应式注入（心脏）**:`Fiber` 状态机 + `inject` + epoch + `reload/unload/update/await` + `notify/_refresh` + `LifecycleDispatcher` + **`@Inject` 响应式字段注入(运行时反射)** + **`Ref<T>` 句柄**。**验收**:服务到位→插件激活且 `@Inject` 字段被注入;服务撤销→插件 dispose+重建、字段回收;`Ref.get()` 反映当前绑定;翻转(卸载中又可用)正确收敛;配置 update→重启。
- **M4 Registry/Events/插件人机面**:`plugin()` + `Plugin` 契约 + 事件(on/emit/waterfall/bail) + 配置解析。**验收**:端到端对齐 cordis `packages/core/tests` 的关键场景(插件加载/卸载/依赖激活/嵌套 dispose)。

> 每个里程碑用 TDD;M4 尽量把 cordis 的核心测试用例翻译成 JUnit 作为"1:1"回归基准。

## 参考真源（本机）

- Cordis 内核:`/Users/sunke/dev/ai-project/cordis/packages/core/src/*.ts`(+ `packages/core/tests` 作 M4 基准)
- IntelliJ 销毁树蓝本:`~/dev/ref/ij-disposer/`（Disposer/ObjectTree/ObjectNode/Disposable/CheckedDisposable）
- VSCode 扩展模型(effect/激活参考):`~/dev/ref/vscode/vscode.d.ts`
- DSH（内核用法参考,非本期实现）:`/Users/sunke/dev/ai-project/deepseek-harness`

## 不做（YAGNI，划出内核）

- 字节码热替换(HMR)/ per-plugin ClassLoader → 未来 `fibra-hmr`
- 配置文件驱动装载 → 未来 `fibra-loader`
- timer / console-logger / group → 未来对应模块
- callable(invokable)service、Standard-Schema 配置校验 → 内核先留最小口子,不实现完整校验
