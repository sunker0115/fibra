## Context

完整架构、候选比较、配置数据结构、生命周期流程、公开 API 与验收边界以 [`docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md`](../../../docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md) 为上游权威源。本文件只记录本 change 的实施级决策，不重复完整字段定义。

当前 `fibra-spring-boot-starter` 同时是实现模块和依赖入口。`FibraProperties.watcher` 只完成绑定，没有 watcher bean 或运行时创建；`FibraLifecycle` 构造器接收始终为 null 的 artifact watcher，也没有 config watcher。启动失败发生在 `running=true` 前时，当前实现不关闭已经 load 的 loader、配置 entry 和 root。

Spring Framework 7.0.8 源码确认：refresh 中某个 lifecycle 启动失败会停止已经 running 的 lifecycle，但失败中的 lifecycle 必须自行回滚部分资源。`FibraConfigLoader.watch()` 又会立即启动线程，所以 watcher 不能在普通 bean 创建阶段无序构造。

## Goals / Non-Goals

**Goals:**

- 直接建立 `0.4.0` 唯一 Spring 适配模块与属性边界，不保留 `0.3.1` 兼容代码。
- 让 artifact/config watcher 真正按属性启停，同时保持初始装载、readiness 和关闭时序确定。
- 让默认托管运行时的所有资源只有一个生命周期所有者。
- 对任意启动阶段失败执行可验证的完整反向回滚。
- 保持 Fibra 内核、loader、插件对象和根父 POM的 Spring-free 运行时边界。
- 为后续 Spring Shell CLI 提供稳定通用底座，但不引入任何 CLI 特殊语义。

**Non-Goals:**

- 不实现 Spring Shell 动态命令、Spring AI、Actuator 或 Web 管理端点。
- 不建立每插件 Spring Context，不扫描或自动注入插件对象。
- 不增加旧属性别名、旧类转发、弃用层或 starter 双布局兼容。
- 不改变 loader 事务、标准插件包、配置文件格式或 PF4J 生命周期边界。

## Decisions

### D1：自动配置实现与 starter 依赖入口拆分

新增 `fibra-spring-boot-autoconfigure`，接收当前 starter 的全部 Java 源码、测试、配置处理器和 `AutoConfiguration.imports`。`fibra-spring-boot-starter` 只依赖该模块，主 JAR不包含生产 class 或自动配置注册文件。

原因：符合 Spring Boot 官方和本地 `disruptor-spring-boot` 的模块边界；用户坐标稳定，自动配置实现可独立测试和演进。

### D2：Spring 版本仍由适配实现模块自管

`fibra-spring-boot-autoconfigure` 保存唯一 Spring Boot 4.1.0 版本与 BOM，并覆盖 Reactor 为 Fibra 冻结版本。starter 不重复声明 Spring 版本，宿主自行选择 Web、Shell 等应用 starter。

原因：满足父 POM不引入 Spring BOM/依赖的硬边界，也避免两个 Spring 模块出现两个版本真源。

### D3：配置按四个运行时关注点重建

删除旧扁平字段，采用上游设计第 4 节的 `artifacts/config/startup/shutdown` 不可变数据结构。`staging-root` 迁出通用契约；`requiredEntries` 明确是 entryId；artifact/config watcher 各自拥有 enable 和 debounce。

原因：安装目录、候选输入、配置树、启动门禁和关闭预算是不同责任，单一 watcher 与 plugins 命名无法准确表达真实 API。

### D4：属性在任何 Fibra 资源创建前整体校验

自动配置先验证完整属性图，再创建 root、loader 或 watch resource。路径必须预先存在；starter 不自动创建部署目录。条件必填和范围以设计第 4 节为准。

原因：loader/watcher 构造已经有文件系统和线程资源副作用；晚校验会留下部分资源，自动创建路径又会掩盖部署拼写错误。

### D5：默认运行时是不可拆分的所有权单元

自动配置仅在宿主不存在 Fibra `Context` 时创建完整 root、两个 loader、bridge 和 lifecycle。宿主已有 `Context` 时整体退让，宿主自行装配和关闭，不做逐 bean 拼接。

原因：逐 bean `@ConditionalOnMissingBean` 无法可靠判断资源由 starter 还是宿主关闭，会产生双重 destroy 或漏关。

### D6：单一 lifecycle 延迟创建两个 watcher

一个内部 `FibraLifecycle` 负责 load、config reconcile、readiness、watcher 创建、失败回滚和关闭。watcher 不作为普通 Spring bean：config watcher 创建即启动，必须晚于初始 load 和 readiness。

原因：所有 Fibra 资源只有一个所有者；正常顺序和部分失败回滚不跨 bean 分散。

### D7：Readiness 使用一个总 deadline

配置 reconcile 后解析全部 required entry，等待各自当前 epoch 收敛，并在一个总 `readinessTimeout` 内完成；完成后逐项要求 ACTIVE。缺失、PENDING 和业务失败分别准确报告。

原因：逐 entry 重置 timeout 会让总启动时间随 entry 数量线性膨胀；`ready()` 对稳定 PENDING 正常完成，不能把其当作 ACTIVE。

### D8：启动失败由当前 lifecycle 自行反向回滚

start 任一步失败按实际完成阶段关闭 artifact watcher、config watcher、config loader、plugin loader 和 root。原异常为主异常，回滚失败按顺序作为 suppressed；失败实例进入终止态。

两个底层 watcher 的构造路径还必须在对象引用返回前失败时自行释放已经创建的 watch service、scheduler 或 worker；lifecycle 只负责关闭已经取得引用的对象。

原因：Spring 只能停止已经 running 的 bean，不能替尚未 running 的失败 bean判断内部完成了哪些阶段；Java 外层也无法关闭一个构造尚未返回的对象。

### D9：关闭属性只承诺可实现的范围

删除含糊 `shutdown-timeout`，使用 `shutdown.root-close-timeout` 只约束 `root.closeAsync()`。同步 watcher 和 loader close 按固定顺序完整执行；任一失败不阻断后续关闭，callback 必须调用。

原因：JDK 无法安全强制中断任意同步 close，声明“整体关闭超时”会形成无法兑现的 API。

### D10：缩小公开 API 冻结面

autoconfigure 只冻结自动配置类名、不可变 properties 和 `FibraServiceBridge`；lifecycle、bean 方法和 watcher 引用是内部实现。starter 只冻结依赖入口制品规则。

原因：Spring Boot 官方把自动配置类名作为入口，内部 bean 方法不应成为用户调用 API；当前 javap 基线冻结过多实现细节。

## Risks / Trade-offs

- [宿主不能只替换一个默认 loader bean] → 需要深度定制时整体排除自动配置并手工装配，换取清晰资源所有权。
- [starter 不直接选择 `spring-boot-starter`] → Web、Shell、batch 等宿主本来就必须选择自己的应用形态，Fibra 只传递自动配置所需 Spring 类型。
- [不可变配置是破坏性 API] → 项目仍在 pre-1.0 开发阶段，直接删除错误模型，不支付兼容成本。
- [watcher 不是可注入 bean] → 避免 config watcher 提前启动；运行失败继续由底层日志/failure sink 报告，本 change 不扩大运维 SPI。
- [路径必须预建增加部署步骤] → 换取拼写错误 fail-fast 和与底层 loader 一致的明确目录所有权。
- [同步 close 无统一强制超时] → 属性改名准确限定 root async close，不发布无法兑现的保证。

## Migration Plan

1. proposal 和实施计划通过后，把 revision 切换为 `0.4.0-SNAPSHOT`，保留 `v0.3.1`。
2. 以测试先冻结新模块制品、不可变属性和完整 fail-fast 校验。
3. 以失败测试冻结启动、总 readiness deadline、watcher 时序和逐阶段回滚。
4. 移动自动配置实现到新模块，把 starter 清空为依赖入口。
5. 迁移 Web 示例 staging 属性和新 `fibra.*` 配置，增加两类 watcher 真实黑盒。
6. 更新签名、发布、README、API、release、架构文档和七制品可复现构建。
7. 完整验证后归档 change，三个稳定规格进入 `openspec/specs/`。

实现前可通过普通 Git revert 回退开发提交；本变更没有生产数据迁移。运行期插件事务回滚继续由现有 loader journal 负责。

## Open Questions

无。完整字段、模块、所有权、时序、失败和公开 API 均已由上游设计冻结。
