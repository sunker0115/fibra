# Fibra vNext 与 Java Harness 集成架构

日期：2026-09-07

状态：非权威参考场景，Java Harness 尚无可核对源码。本文用于验证通用 Fibra 接入面能否自然承载
一种 Agent Harness，不决定 Fibra 的产品方向、模块边界或发布优先级，也不把草图中的类名、状态名或
包名视为既有实现。

上游权威设计：[`2026-09-07-fibra-vnext-architecture.md`](./2026-09-07-fibra-vnext-architecture.md)。

## 1. 结论

如果未来建设 Java Harness，不应只把 Fibra 当成“第三方 JAR 加载器”。DeepSeek Harness 的核心设计
思想是：
内置能力与外部扩展都进入同一个依赖、作用域和生命周期模型。Java Harness 应据此把 Fibra 用作唯一
运行时组合内核：

1. `agent/llm/tool/skill/session/goal/plan/hooks/...` 中有长期生命周期、服务依赖或资源清理的能力，以
   内置 `PluginDefinition` 参与组合；普通实体、值对象和纯函数不插件化。
2. Java Native JAR、内置能力和 Node sidecar 实例最终都成为 Fibra `PluginInstance`，共享
   ACTIVE/PENDING/FAILED、依赖重载、Scope 所有权和完成边界。
3. Fibra `PluginRegistry` 提供安装、版本、期望状态、制品记录、审计和实际状态投影的通用控制面；
   Harness 只在其上增加权限、租户、运营策略和对外接口，不能再维护第二套 ACTIVE 真源。
4. 工具、模型、skill 和 hook 是 Harness 业务能力，不进入 `fibra-api`。Fibra `ContributionBridge`
   提供通用贡献身份、Scope 注册、撤销、快照与调用适配；Harness 定义具体 contribution kind、命名渲染
   和能力目录。
5. Spring 只负责启动与基础设施装配。任何长期运行资源只能由 Spring 或 Fibra 一方拥有，禁止两个
   容器同时调用其关闭生命周期。

上述结论只说明 Harness 应如何消费 Fibra。反向依赖被禁止：Fibra core、Engine、artifact、config 和
Spring 模块均不得出现 Harness、Agent、Tool、Skill 或本项目包名；Node、JSON-RPC 和 Process 类型只可
存在于 `fibra-runtime-node`。本文出现的业务接口全部由 Harness 自己定义。

## 2. 整体分层

```text
HTTP / CLI / SDK / preset
            |
            v
Harness plugin use case（权限、租户、运营策略）
            |
            v
Fibra PluginRegistry（安装、版本、期望状态、审计、状态投影）
            |
            | EngineCommand / EngineSnapshot
            v
Fibra Engine（唯一运行态写入口、Scope、恢复、ChangeSet）
            |
      +-----+---------------------+
      |                           |
      v                           v
fibra-runtime-java          fibra-runtime-node
ClassLoader + JAR           sidecar + JSON-RPC
      |                           |
      +------------+--------------+
                   v
Fibra ContributionBridge（身份、Scope 注册、撤销、调用边界）
                   |
                   v
Harness contribution adapter（Tool 命名与协议映射）
                   |
                   v
ToolCatalog / ModelCatalog / SkillCatalog / HookRegistry
                   |
                   v
Agent / Session / Workflow 等业务消费者
```

图中上半段是控制面，下半段是贡献面，二者只通过 Engine 的实例身份和 revision 对齐。Agent 只消费
能力目录；能力目录不知道 Java/Node 来源；插件 bridge 不反向调用 Agent；Node 协议和 ClassLoader
不进入 domain。

### 2.1 本期交付边界

Fibra vNext 本期必须形成可运行纵向闭环，而不是只预留接口：

1. Engine、Scope、失败恢复和 ChangeSet 事务；
2. `fibra-runtime-java` 的真实 JAR/ClassLoader 装载、更新和回收；
3. `fibra-runtime-node` 的真实 sidecar/JSON-RPC 启停、调用、失败和回收；
4. `fibra-registry` 的 install/upgrade/enable/disable/uninstall 与 get/list/watch/history；
5. `fibra-bridge` 的贡献身份、Scope 注册、撤销、快照、调用和 adapter SPI；
6. 一个不含 Agent 语义的示例贡献，以及 Harness Tool 贡献的契约测试夹具，证明 Java/Node 来源可进入
   同一个目录。

本期不会在 Fibra 仓库实现尚无源码的 Harness 业务模块，也不会实现 Tool/Skill/Model 的业务 DTO、权限
运营规则或三种外部 Node 格式导入器。它们属于后续 Harness 项目的场景适配；Fibra 本期必须把其所需的
通用接入面做完整，不能只写文档或留空 SPI。

## 3. 对 Harness 现有目录规划的落位

截图中的七个 Maven 模块可保留，但不能让“分层模块”和“领域包”同时拥有同一职责。

高内聚、低耦合在此场景中的含义是：domain 聚合业务语义，case 聚合用例编排，infrastructure 聚合
外部机制，trigger 聚合入口协议，app 只装配。任何一个 install/activate 流程都不能跨这些层复制状态
机；跨层只能依赖由内层声明的 port。

| Harness 模块 | 允许放置的内容 | 与 Fibra 的关系 |
|---|---|---|
| `deepseek-harness-java-types` | 无行为值对象、ID、序列化 DTO、wire schema | 不依赖 Fibra、Spring、ClassLoader |
| `deepseek-harness-java-domain` | Agent、Tool、Plugin、Session 等领域契约与聚合 | 不依赖 `fibra-engine` 或基础设施；插件聚合只保存权限/策略，不保存实际运行态真源 |
| `deepseek-harness-java-case` | install/activate/disable/uninstall、会话和 Agent 用例 | 依赖 domain port；不直接操作 Context、ClassLoader 或进程 |
| `deepseek-harness-java-infrastructure` | Fibra Registry/Bridge adapter、外部格式导入、数据库、E2B | 可依赖 `fibra-registry`、`fibra-bridge`、两个 runtime 和具体基础设施 |
| `deepseek-harness-java-trigger` | HTTP、CLI、定时触发器、请求/响应映射 | 只调用 case，不直接调用 Engine 或 ToolRegistry |
| `deepseek-harness-java-api` | Java 插件 SDK、能力契约、稳定 ServiceKey | 可依赖 `fibra-api`，不得依赖 core/engine/artifact/Spring |
| `deepseek-harness-java-app` | Spring Boot 入口、内置 catalog、desired graph、Bean 装配 | 唯一 composition root；启动 Engine 并确定关闭顺序 |

`domain` 下的包名是逻辑能力地图，不应机械地变成同等数量的 Maven artifact。只有存在公开契约、依赖
隔离、运行位置或发布节奏边界时才拆 artifact。

### 3.1 能力包的分类

- `agent/llm/tool/skill/session/goal/plan/hooks/guard`：定义业务契约，并由 app 中的内置
  `PluginDefinition` 组合长期运行实现。
- `storage/credentials/sandbox/e2b/coderuntime/lsp/terminal`：domain 定义 port，infrastructure 提供
  adapter；是否成为 Fibra plugin 取决于是否拥有生命周期和可替换依赖。
- `task/jobs/workflow/schedule`：必须分别固定任务定义、后台执行、流程编排和定时触发，不能各自维护
  一套可运行任务状态机。
- `tool`：工具定义、目录和调用契约；`runtime.tool` 只能做本次 Agent 运行的选择、策略与路由。
- `plugin`：插件聚合和状态语义；`plugin.registry/runtime/bridge` 分别负责控制面、运行资源和能力投影。
- `shared`：不允许成为跨层杂物箱；只保留无业务语义、无外部依赖且有多个真实消费者的基础类型。

如果 `shared` 无法用一句业务无关的职责描述，应删除该包并把类型移回唯一所有者；不能为了减少重复
让 domain、infrastructure 和 trigger 通过 shared 形成隐式耦合。

## 4. 内置 Harness 能力如何使用 Fibra

Harness 的静态模块与外部 JAR 使用同一 `PluginDefinition` 契约，区别只在 definition 来源：

- 内置 definition 由 `deepseek-harness-java-app` 直接注册到 built-in catalog，不经过 ClassLoader；
- Java Native definition 由 `fibra-runtime-java` 从已安装 JAR 的显式 entrypoint 得到；
- Node definition 由 `fibra-runtime-node` 从已安装 Node 包和 JSON-RPC 握手结果得到。

Engine 合并 built-in catalog 与 artifact catalog 后解析一棵 desired graph。建议的内置条目示例：

```text
storage -> credentials -> sandbox
contribution-bridge -> tool-catalog -> skill-catalog -> model-catalog
session -> agent -> goal/plan/subagent
plugin-registry -> external plugin instances
```

这只是依赖关系示意，不规定包必须按此顺序启动。真实依赖必须由稳定 `ServiceKey` 声明，不能依赖
Spring Bean 创建顺序或手写启动序号。

以下对象不应插件化：请求 DTO、消息值对象、领域实体、一次性 mapper、无资源的纯策略函数。插件化
标准是“是否需要依赖满足后激活、动态替换、作用域所有权或确定性清理”，不是“是否属于一个包”。

### 4.1 作用域映射

```text
FibraRuntime root Scope
  ├── 内置能力和外部插件实例
  ├── workspace Scope
  │     └── session Scope
  │           ├── agent Scope
  │           ├── subagent Scope
  │           └── task / terminal / code-runtime Scope
  └── 后台 job Scope
```

关闭 session 必须自动清理其 Agent、工具临时注册、终端、任务与在途资源，但不能关闭全局插件。需要跨
session 生存的 job 必须显式迁移到 job Scope，不能因捕获了 session Context 而偶然延长生命周期。

## 5. 本场景对 Fibra 通用宿主 API 的验证

### 5.1 同时支持内置与制品 definition

`PluginCatalog` 必须能组合程序注册的 built-in definitions 与各 runtime definitions。内置能力不应为了
进入 Fibra 生命周期而先打成 JAR，也不能绕过 Engine 直接调用 `Context.plugin()`。

definition 的公开名称、artifact identity 和 instance id 继续分离：

```text
definitionName = harness.tool-catalog
artifactId     = third-party.git-tools:1.4.0@sha256:...
instanceId     = workspace/acme/plugins/git-tools
```

### 5.2 程序化 desired state

YAML/JSON 文件只是 `DesiredStateRepository` 的一种 adapter。Harness 若选择数据库作为权威 repository，
HTTP 与 preset 必须向它提交程序化意图，不能同时让配置文件成为第二真源，也不能通过修改文件再等待
watcher 来激活插件。

Engine 对宿主提供单一命令入口：

```java
Mono<EngineResult> submit(EngineCommand command);
EngineSnapshot snapshot();
Flux<EngineSnapshot> snapshots();
```

`EngineCommand` 表达 install artifact、upsert/remove desired entry、uninstall artifact 和 apply deployment
等意图，并携带 expected revision 防止丢失更新。它不是公开 `ChangeSet`：ChangeSet 是 Engine 校验意图
后生成的内部执行计划。禁止重新公开低层 `mount/update/unmount`。

文件 watcher、周期 resync、HTTP use case 与 preset loader 都只能产生 EngineCommand 或 dirty signal，
最终进入同一个 command loop。

### 5.3 状态订阅而不是轮询内部对象

Fibra `PluginRegistry` 投影安装记录、期望状态、审计和 `EngineSnapshot`；Harness 插件聚合只关联权限、
租户与运营策略。actual revision、实例状态和失败始终来自 `EngineSnapshot`，任何投影都不能再写回
Engine 当事实。

`snapshots()` 只在 revision 变化时发布不可变快照。Harness 不需要取得可变 loader、遍历 ClassLoader
或持有内部 PluginInstance 才能判断 readiness。

### 5.4 启动前宿主服务

ToolCatalog、CredentialBroker、SandboxProvider 等 Harness 宿主服务必须在动态插件激活前可见。优先
把它们作为 built-in definitions 放进同一 desired graph；确实由 Spring/外部容器拥有的对象通过
`FibraServiceBridge` 在 Engine start 前显式导出。

Scope 始终拥有 service binding 的撤销，但不会猜测 service 对象的关闭方式：Spring 拥有的对象只导出
binding；Fibra 拥有的对象必须显式提供 disposer，并与 binding 一起登记到 Scope。禁止因为对象实现了
`AutoCloseable` 就让两个容器都关闭它。

## 6. Java Native Plugin

### 6.1 单一入口

Java Native 直接使用 Fibra 的唯一标准 manifest：`META-INF/fibra/plugin.yaml`。Harness 不再定义第二个
可执行入口文件。manifest 显式声明：

```yaml
id: git-tools
version: 1.4.0
apiVersion: 1
entrypoint: com.example.git.GitToolsEntrypoint
```

不再同时支持 `META-INF/services` fallback，也不扫描 JAR 猜入口。安装阶段完成 schema、入口类形状、API
版本、依赖、包边界和摘要校验；运行阶段只消费规范化 descriptor。Java runtime id 属于 Fibra
`ArtifactRecord`，权限和业务审计属于 Harness 插件聚合；需要 `process.exec` 等权限时由 Harness 绑定到
artifact digest，不能让插件自行声明后自动获得权限。

Java 插件只依赖 `deepseek-harness-java-api` 与其传递的 `fibra-api`。它不能依赖 artifact、Engine、Spring
或 Harness infrastructure。

### 6.2 插件作者体验

Harness API 提供薄的 `HarnessPluginContext`，底层仍绑定当前 Fibra PluginInstance 的 Scope：

```java
public final class GitToolsPlugin implements HarnessPlugin<GitToolsConfig> {
    @Override
    public Mono<Void> start(HarnessPluginContext context, GitToolsConfig config) {
        context.tools().register(ToolDefinition.builder()
            .name("search")
            .description("搜索仓库")
            .parameters(SearchArgs.class)
            .handler(this::search)
            .build());
        return Mono.empty();
    }
}
```

`register()` 自动归属当前 Scope，插件不需要把 registration 包进 `Mono.just(...)`。需要关闭的客户端、
线程池或订阅通过 `context.effects().own(disposable)` 登记。API 不提供第二套 `onStop()`；Scope dispose
就是唯一清理协议。

工具内部身份使用 `ToolId(pluginId, localName)`；只在发送给模型或兼容外部协议时渲染为
`plugin__<pluginId>__<toolName>`。pluginId 和 toolName 必须在安装时规范化并校验，不能在运行时拼接任意
字符串。

### 6.3 ClassLoader 边界

Java Native 是可信进程内扩展。ClassLoader 隔离不等于安全沙箱：

- `fibra-api` 与 Harness plugin API 固定父加载；插件私有依赖由插件 ClassLoader 加载；
- Harness 内部实现包不对插件导出；
- `fibra-runtime-java` 使用自己的 JavaArtifactGraph 与依赖感知 JavaClassSpace，不引入第三方插件 manager；
- 停用先撤销能力并排空调用，再 dispose Scope，最后关闭并验证 ClassLoader 可回收；
- 非可信扩展只能进入 sidecar/sandbox，不允许依靠 ClassLoader 获得安全性。

## 7. Node Bridge Plugin

`.codex-plugin/plugin.json`、第三方 `package.json` 字段和 `cordis.yml` 是 Harness 可选的安装导入格式。
installer 在安装前把它们转换为带唯一 `fibra-plugin.yaml` 的 Fibra 原生 Node 包，固定 canonical
entrypoint、runtime、版本和摘要；权限仍由 Harness 绑定到 artifact digest。运行时不保留这些格式的
兼容分支。

每个 Node 包由 `fibra-runtime-node` 适配成普通 Fibra PluginInstance：

1. start effect 通过参数数组启动 sidecar，不经过 shell；
2. canonical entrypoint 必须位于不可变安装目录内，并限制为允许的扩展名；
3. JSON-RPC 握手校验协议版本、插件 identity、能力和权限；
4. 把远程 endpoint 发布为通用 contribution，Harness adapter 再生成 ToolCatalog registration；两次
   registration 与进程会话归属同一 Scope；
5. 进程异常退出时先撤销能力，再使实例进入可诊断 FAILED，并按 Engine 策略重试；
6. dispose 停止接收新调用、等待或取消在途调用、关闭 RPC、终止完整进程树并清理临时资源。

凭据只能通过受限 `CredentialBroker` 按声明权限取得，不能复制宿主全部环境变量。stdout 只承载有边界的
RPC framing，stderr 独立进入结构化日志；消息大小、调用超时、取消、心跳和进程退出必须有契约测试。

## 8. 能力目录与 Agent 的关系

插件 bridge 负责把不同来源转换成相同的 `ToolDefinition/ModelProvider/SkillDefinition/Hook`。一次能力
发布必须在 lifecycle lane 内完成“校验 + 唯一身份 + 注册 + Scope 归属”，不能先启动插件再异步补
ToolRegistry。

SystemPrompt 不保存另一份可变工具说明。Agent 每轮从同一 `ToolCatalogSnapshot` 生成模型可见工具描述，
工具选择和真实调用也使用该 snapshot 的 revision，从而避免“提示词里有工具但 registry 已撤销”的
裂脑状态。

Agent 只依赖 Harness capability API，不依赖 PluginRegistry、JavaPluginLoader 或 Node process manager。
调用路径固定为：

```text
Agent -> ToolCatalog snapshot -> ToolInvoker
                              -> Java handler
                              -> Node JSON-RPC proxy
```

调用过程继续携带 Fibra `InvocationContext/ServiceRef`，使插件为调用方创建的资源归属于正确的 session、
agent 或 task Scope。

## 9. 安装、激活与卸载协议

### 9.1 安装

```text
HTTP / preset
  -> Harness install use case
  -> PluginRegistry.install(runtimeId, candidate, expectedRevision)
  -> 对应 runtime inspect + validate
  -> fibra-artifact stage + commit immutable artifact
  -> Registry 投影 artifact/desired/observed 三类事实
```

安装不执行插件代码。安装目录按 pluginId/version/digest 隔离且不可原地修改；升级产生新候选 revision。
Java JAR 与 Node 包都通过同一个 Registry/Engine 命令域安装，区别只在 runtime adapter；不得让 Harness
另建 Node artifact store 或安装状态机。

### 9.2 激活

```text
Activate use case
  -> PluginRegistry.enable / upgrade
  -> EngineCommand.UpsertDesiredEntry
  -> 按显式 runtimeId resolve definition
  -> prepare ClassLoader 或 sidecar
  -> start Fibra PluginInstance
  -> capability registrations settled
  -> publish EngineSnapshot(ACTIVE)
```

任何一步失败都撤销本次产生的注册和运行资源。FAILED 保存 phase、revision、cause 和 desired state，不能
成为永久终态；修正制品、配置或依赖后仍可重新收敛。

### 9.3 disable 与 uninstall

`PluginRegistry.disable` 先修改 desired state；Engine 收敛时先从能力目录撤销入口，拒绝新调用，再排空
在途调用、dispose Scope、停止 sidecar/关闭 ClassLoader，最后发布 observed snapshot。uninstall 只能
作用于已经不被 desired graph 引用且没有活动实例的制品；文件删除应使用可恢复的 quarantine/retire
流程。

## 10. Spring Boot 集成

`deepseek-harness-java-app` 是唯一 composition root：

1. Spring 创建数据库、凭据、安全策略等基础设施对象；
2. Harness 提供 `FibraEngineCustomizer`，注册 built-in definitions、Node runtime 与 contribution
   adapters；外部服务只导出 binding，明确由
   Spring 保留对象关闭权；
3. starter 创建并启动唯一 Engine；
4. trigger/case 只取得 Harness port，不直接注入低层 loader；
5. 关闭时先停止入口流量和 Agent，再关闭 Engine，最后由 Spring 关闭其拥有的基础设施。

Spring stereotype 扫描不能发现动态插件，动态插件也不能向 Spring ApplicationContext 注入 Bean。需要
Bean 的插件只能通过稳定 ServiceKey 消费显式导出的宿主能力。

## 11. Harness 场景验收

- 不打 JAR 即可注册和组合内置 PluginDefinition；内置与外部实例具有相同状态和 Scope 语义。
- Harness 常规用例只持有 PluginRegistry 和自己的 capability API；高级运维才读取 EngineSnapshot，均不
  接触 loader、Process 或 RPC channel。
- install/activate/disable/uninstall 均可从程序化命令完成，不依赖编辑文件或等待 watcher。
- 一个 session Scope 关闭后，其 Agent、临时工具、终端和任务资源全部释放，全局插件保持运行。
- Java 插件项目不依赖 artifact/Spring/Engine；只使用 Fibra manifest 和一个 entrypoint。
- Java 和 Node 使用同一个 Registry API 完成 install/upgrade/enable/disable/uninstall。
- Node sidecar 退出、超时、协议错误和部分注册失败不会留下可调用工具或孤儿进程。
- ToolCatalog 与模型提示词使用同一 snapshot revision。
- Spring 与 Fibra 的 binding/resource 所有权逐项显式，关闭测试能证明每个资源只关闭一次。
- PluginRegistry 分开展示 artifact、desired 与 observed，observed 唯一来自 EngineSnapshot。

这些测试放在 Harness 项目或独立集成验证中，不进入 Fibra parity test。Fibra 自身还必须以纯 Java
嵌入、Spring Boot、测试临时作用域等非 Harness 场景验证同一 API，防止为了本场景形成特殊分支。

## 12. 明确禁止

- 仅把 Fibra 用于加载第三方 JAR，而由 Spring 手工管理全部内置 Harness 生命周期；
- Harness `onStart/onStop` 与 Fibra effect 并存；
- HTTP Controller 直接调用 loader、Context 或 ClassLoader；
- Java Native 与 Node runtime 各自维护 ToolRegistry、状态机和重试器；
- Fibra manifest、Harness manifest、ServiceLoader 和 classpath scan 多种入口并存；
- 把 ClassLoader 描述为安全沙箱；
- 根据文件名、Bean 名、实现类名或运行时反射猜 service/tool identity；
- 为截图中的每个 domain 包发布一个 Maven artifact；
- 为未来 Harness 需求把 Agent、Tool、Skill 或 JSON-RPC 类型下沉进 `fibra-api`。
- 在 Fibra core/Engine 中新增 `runtimeType` 分支，或让 `PluginRuntimeAdapter` 演变成包含业务能力的万能
  driver SPI。
- 在 `app` 或 `shared` 中实现 install/activate、工具注册、状态投影等本应属于 case/domain 的逻辑。
