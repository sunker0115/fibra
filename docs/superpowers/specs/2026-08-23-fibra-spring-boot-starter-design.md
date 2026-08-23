# Fibra Spring Boot Starter 设计

日期：2026-08-23
状态：设计草案，作为实现与验收的权威输入
流程：本变更走 superpowers spec + plan，不走 OpenSpec change。

## 1. 目标与边界

`fibra-spring-boot-starter` 是 Fibra 的**可选 Spring 适配模块**，让 Spring Boot 项目以 drop-in 方式使用 Fibra 的动态插件运行时（生命周期、服务、配置装载）。它是业务中立的通用件，DeepSeek Harness 只是第一个消费者。

「Spring 不进 Fibra」的准确含义是**不在 Fibra 内核（`fibra-core`/`fibra-api`）引入 Spring**，不是「Fibra 不能被 Spring 使用」。因此：

- Fibra 内核与 loader 继续只依赖 Reactor Core + SLF4J，可在**不引入 Spring** 的纯 Java 宿主中使用；
- 本模块是**额外**的适配层，让**引入 Spring** 的项目也能用 Fibra；
- 内核中立与本模块并存，不矛盾。

本设计冻结以下边界：

- `fibra-core`/`fibra-api` 不出现 Spring 依赖、类型或注解；
- starter 只做通用宿主适配（自动装配、生命周期协调、readiness 门禁、ServiceKey 桥接），不含任何 DeepSeek/Harness 业务；
- 不复制 gj.spring.pf4j 的每插件 Spring 子容器与 web 插件能力（见 §2）；
- 插件不注册为 Spring Bean，不建每插件 Spring `ApplicationContext`，不对插件 `@ComponentScan` 或读取插件内 `spring.factories`；
- 不捆绑业务库（ModelMapper/EasyExcel/MyBatis/Druid）；v1 不引入 Actuator。

## 2. 开源参照与取舍

对照 gj.spring.pf4j（源码 `/Users/sunke/dev/ai-project/gj.spring.pf4j`）逐类划界：

| gj.spring.pf4j 能力 | 本 starter 结论 |
|---|---|
| 一插件一目录、版本化主 JAR、私有 lib、卸载释放资源 | 已由 Fibra `0.3.0` 制品层覆盖且更严格（事务 + 崩溃恢复），不重做 |
| install ≠ enable 治理 | 由 Fibra「制品 install」与「entry mount」分离直接表达 |
| 插件消费宿主 bean、宿主资源逆序注销 | starter 的 `FibraServiceBridge` + Fibra effect 树覆盖，逆序/可等待/错误边界比 gj 三阶段 registrar 更严 |
| 操作串行化、热更新去抖 | 已由 `0.3.0` `LoaderOperationGate` + `FibraPluginWatcher` 覆盖 |
| 插件事件/生命周期事件 | 由 Fibra `EventBus` + `CoreEvents` 覆盖 |
| 每插件 Spring `ApplicationContext`（插件即 Spring 应用，内部 `@Autowired`/`@ComponentScan`） | **刻意不复制**；Fibra 插件用 `PluginDescriptor` + `ServiceKey`，gj 插件需按 Fibra 模型重写 |
| 插件贡献 MVC/WebFlux 路由、鉴权 SPI、六槽过滤器 | **不在 starter**，未来另建业务层 |
| ModelMapper/EasyExcel/MyBatis/Druid 集成 | **不捆绑**，由具体业务自行引入 |

结论：starter 覆盖「插件生命周期/隔离/资源清理/事务/热更/宿主服务桥接/事件/配置/install≠enable」且更严格；gj 的「插件即 Spring 子应用」与 web/业务集成不在本 starter 目标内。

## 3. 模块位置与依赖

### 3.1 位置：Fibra reactor 内的可选适配模块

- `fibra-spring-boot-starter` 进根 POM `<modules>`，是 Fibra reactor 模块，继承 `com.sstlfsj:fibra` 父 POM（复用 Java 21、插件版本、编码、Enforcer 约定）；
- 它与 5 个中立内核/loader 制品**分类区分**：5 个中立制品保持只依赖 Reactor + SLF4J；starter 是**第 6 个可发布制品，归类为「可选 Spring 适配制品」**，明确携带 Spring，不与「内核/loader 制品」混称。

### 3.2 内核中立如何保持

- `fibra-core`/`fibra-api`/`fibra-loader-*` 依赖图不变，不新增 Spring；
- starter 依赖：reactor 内 `com.sstlfsj:fibra-loader-pf4j`（直接用 `FibraPluginLoader`）、`com.sstlfsj:fibra-loader-config`（直接用 `FibraConfigLoader`，传递带入 `fibra-core`/`fibra-api` 与 Jackson 3.x、slf4j-api）、Spring Boot；
- **父 POM 保持 Spring-free**：Spring 版本不进父 POM `properties`。starter 模块在**自己的 `dependencyManagement`** 中 `import` `spring-boot-dependencies` BOM，并在**自己的模块**定义 `spring-boot.version`。内核架构 §2「第三方版本集中在父 POM」对本适配模块记为**显式例外**（Spring 版本自管，避免父 POM 触碰 Spring）。

### 3.3 Reactor 版本对齐

Fibra 钉 `reactor-core 3.8.6`。Spring Boot BOM 管的 Reactor 版本可能不同，starter 必须在自己的 `dependencyManagement` **显式覆盖 `io.projectreactor:reactor-core:3.8.6`**，使 reactor 构建与下游解析都对齐 Fibra 版本。Boot BOM 实际 Reactor 版本与偏差在计划阶段用依赖树验证。

## 4. 组件

全部自动装配 bean 使用 `@ConditionalOnMissingBean`，宿主可覆盖任一。

### 4.1 `FibraAutoConfiguration`

`@AutoConfiguration`，经 Boot 自动配置注册文件登记（Boot 4 精确文件名在计划阶段核实）。构建：root `Context`（`FibraRuntime.create()`）、`FibraPluginLoader`（以 `FibraProperties.pluginsRoot` 构造）、`FibraConfigLoader`、可选 `FibraPluginWatcher`（`fibra.watcher.enabled=true` 时）、`FibraLifecycle`（4.3）、`FibraServiceBridge`（4.4）。

### 4.2 `FibraProperties`

`@ConfigurationProperties("fibra")`：`plugins-root`、`staging-root`、`config-location`、`startup-required-plugins`（readiness 必需插件）、`watcher.enabled`（默认 false）、`watcher.debounce`、`shutdown-timeout`。

### 4.3 `FibraLifecycle implements SmartLifecycle`

`DEFAULT_PHASE`，位于 Boot WebServer graceful shutdown 之后。

- `start()`：`loadArtifacts()` → `FibraConfigLoader` reconcile 配置树 → **readiness 门禁**：逐个等待 `startup-required-plugins` 收敛并检查 `state()==ACTIVE`；任一 `FAILED`/`PENDING` 则抛异常令 Boot 启动失败，报出失败的 `entryId`/`ServiceKey`。声明为运行期可选的插件保持 `PENDING`，不计入 readiness；
- `stop(Runnable callback)`：按集成架构 §4.4 顺序——停 `FibraPluginWatcher` 并等在途 → `FibraPluginLoader.close()`（dependent-first dispose、PF4J stop/unload、关插件 ClassLoader）→ root `Context.closeAsync()` 完成并关 lifecycle Scheduler → 完成后才调 `callback`；带 `shutdown-timeout`；
- `@PreDestroy`：幂等兜底，调用同一关闭入口，只在异常启动或 stop 超时后生效，不复制关闭逻辑。

### 4.4 `FibraServiceBridge`

把宿主 Spring 单例暴露给插件的通用机制，不做按类型自动映射：

```java
public final class FibraServiceBridge {
    <T> ServiceRegistration<T> register(ServiceKey<T> key, T service);
}
```

注册落到 root `Context.provide`，归 Fibra effect 所有，返回可等待撤销的 `ServiceRegistration`；starter 在关闭链等待全部桥接撤销完成。宿主用它显式桥接自己的 bean（**机制**在 starter，桥哪个 bean 是宿主**用法**）；插件经 `Context.get`/`BoundService.invoke` 获取，禁止 `@Autowired`、静态 `ApplicationContext`、`Context`-as-locator、在 Bean/静态缓存/ThreadLocal 长期持有插件对象或 `Class<?>`。

## 5. 控制流

- 启动：Boot refresh → `FibraAutoConfiguration` 建 bean → `SmartLifecycle.start()` 于 refresh 后运行 → load + reconcile → readiness 门禁 → 应用就绪；
- 运行：宿主/其它 bean 经 `FibraServiceBridge` 暴露服务；插件经 Fibra 契约取用；
- 关闭：`SmartLifecycle.stop()` 执行 §4.3 有序拆除。

## 6. 错误边界

- 必需插件 `FAILED`/`PENDING` → `start()` 抛异常 → Boot 启动失败并报出失败插件/`ServiceKey`；
- 从 Reactor non-blocking 线程或 Fibra lifecycle 回调反向调 loader 管理 API → `FibraPluginLoaderBusyException`（`0.3.0` 已有），starter 不吞不改写；
- `closeAsync` 超 `shutdown-timeout` → 记录并走 `@PreDestroy` 幂等兜底，不静默；
- starter 不引入 Reactor 全局 Hook，不要求 core 启用。

## 7. 测试

- 单元（`ApplicationContextRunner`）：auto-config 装配与 `@ConditionalOnMissingBean` 覆盖、`FibraProperties` 绑定、`SmartLifecycle` 顺序、readiness 门禁（缺必需插件→启动失败并指名）、`FibraServiceBridge` register + 关闭可等待撤销、依赖树断言 Reactor 解析为 3.8.6；
- 黑盒（集成架构 §4.5 硬性要求）：Spring Boot 可执行（fat）JAR + 外部多插件目录 + reload/unload + Metaspace/ClassLoader 回收；验证插件能读宿主 Fibra 契约、插件 JAR 不含宿主 API/Spring 副本、unload 后 Fibra/loader/宿主/缓存/ThreadLocal 均不再持有插件实例或类、新版本以新 ClassLoader 身份重注册成功；
- 时序测试禁止 `Thread.sleep`，收敛用 Awaitility，在途边界用 Reactor 测试工具。

## 8. 发布与文档影响

- **release.md**：从「5 个生产制品」改为「**5 个中立内核/loader 制品 + 1 个可选 Spring 适配制品（`fibra-spring-boot-starter`）**」；明确 5 个中立制品仍只依赖 Reactor+SLF4J，父 POM 保持 Spring-free，starter 自管 Spring BOM；
- 新增 `docs/api/fibra-spring-boot-starter-public-signatures.txt` 纳入 `ApiSignatureBaselineTest`；`ReleaseArtifactBaselineTest` 覆盖新模块；可复现构建集 +1；
- **集成架构** `2026-08-22-...-integration-architecture.md` §2：新增 `fibra-spring-boot-starter` 为 Fibra 可选适配模块，依赖方向 `harness-spring-boot -> harness-runtime + fibra-spring-boot-starter`；澄清 Spring 通用宿主适配下沉为 Fibra 可选件；
- **开源基线**：澄清「Spring 不进 Fibra」= 不进内核 `fibra-core`/`fibra-api`；Spring 适配是 Fibra 的可选模块（更新第 43/70/72 行相关表述）；
- **内核架构** §2：记录 starter 的 Spring 版本自管为「版本集中父 POM」的显式例外；
- **远程发布配置**：仓库当前无 `distributionManagement`/远程仓库，实际 deploy 暂只能本地 install/verify。「补远程发布配置」作为独立流程事项，不阻塞本 starter。

## 9. 待确认项

1. **Spring Boot 大版本**：按当前稳定版 `4.1.0`（Spring Framework 7，Java 21 支持）钉。若下游宿主须留在 Spring Boot 3.x LTS，需改版本并相应调整自动配置注册机制。审阅时确认。

## 10. 明确非目标

- 内核 `fibra-core`/`fibra-api` 不引入 Spring；
- 不建每插件 Spring `ApplicationContext`，不对插件 `@ComponentScan`/读 `spring.factories`；
- 不提供插件贡献 web 路由/鉴权/过滤器（未来另建业务层）；
- 不捆绑业务库（ModelMapper/EasyExcel/MyBatis/Druid）；
- v1 不引入 Actuator；
- 不把插件对象或 `Class<?>` 放入 Spring Bean、静态缓存或 ThreadLocal。
