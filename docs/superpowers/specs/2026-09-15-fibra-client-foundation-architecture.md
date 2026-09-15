# Fibra Client Foundation 最终架构与 P0 验证设计

状态：架构与实施契约已冻结，P0 尚未实施；本文是 Fibra UI/client 底座、跨执行域插件模型和 P0 实施的
权威设计。本文覆盖并替代
`2026-09-07-fibra-vnext-architecture.md` 与
`2026-09-13-fibra-based-agent-product-architecture.md` 中“client adapter、runner 与传输首版归产品仓库”及
“Fibra 底座不再新增能力”的旧结论；旧文档中的冲突表述已删除或整段重写，不存在两套当前结论。
“最终架构”表示内核所有权、状态模型和模块边界冻结，不表示 P0 已覆盖 Fibra 未来全部产品化能力；P0 完成态、
验证夹具和后续 Fibra 能力账本见第 14 节。

## 1. 决策与目标

Fibra 的最终定位不是“只有 Host runtime 和 CLI 的 Java 插件框架”，而是一个跨执行域插件内核：同一个
逻辑插件可以包含 Java、Node、CLI command 和 client facets，由唯一 Engine/Registry 管理同一 desired
target、同一次变更、同一套准入与撤销语义。

因此，框架无关的 client foundation 归 Fibra，而不是由每个上层产品重复实现。Fibra 负责：

- 逻辑插件及其多 facet 安装模型；
- client 执行端协议、身份、能力协商和生命周期；
- Host 侧 client runtime 协调器；
- framework-neutral client runtime、Scope/effect 所有权和 Host 调用边界；
- client facet 的 desired/observed 投影、诊断、重连和迟到回复围栏；
- 至少一个浏览器 ESM 执行端实现及独立发行门禁。

Fibra 不负责：

- Electron、Tauri、浏览器标签页或原生窗口的产品启动策略；
- React、Vue、Svelte、原生 DOM、JavaFX 等具体组件模型；
- 产品布局、主题、路由结构和业务页面；
- Agent、Model、Session、MCP、审批等业务事实与长会话数据面；
- 非可信第三方 JavaScript 的安全沙箱。

“不限制 UI 技术”不表示一个 UI facet 二进制可跨 React、Vue、JavaFX 运行。它表示增加新的窗口壳或
renderer adapter 不需要修改 Fibra Engine、Registry、client protocol 或 framework-neutral runtime。

## 2. 前后端分离的实际边界

前后端分离约束的是依赖、进程、协议、部署与状态所有权，不要求源码必须位于不同仓库。Fibra 采用同仓但
独立发布的多语言模块：Java Host 不依赖 Node/npm 或具体 UI 框架；client 包不能访问 Java 内部对象，只能
通过版本化 wire contract 通信。

必须同时满足以下边界：

1. **发布物分离**：Maven artifacts 与 npm packages 独立构建、独立消费；纯 Java 构建不运行前端工具链。
2. **依赖单向**：具体 renderer 依赖 client core；client core 不依赖 React、Vue、DOM、Electron、Tauri
   或任何产品 DTO。
3. **进程分离**：Host 与 client execution 是独立生命周期；renderer 断线不等于 Host、Session 或 Agent
   退出。
4. **协议分离**：跨线只传版本化、可严格校验的值对象，不传 `Context`、`Scope`、handler、组件对象或
   Host 文件路径。
5. **状态唯一**：Engine/Registry 保存唯一 desired target；client 只保存可丢弃的执行状态和 observed。
6. **控制面与数据面分离**：插件生命周期协议归 Fibra；产品 Session journal、token 流与业务事件不进入
   client foundation。

只要这些边界成立，把 Host/client 基础设施放在同一 Fibra 仓库不会破坏前后端分离；反而能用同一版本和
验收矩阵冻结跨语言契约。

## 3. 开源参照与取舍

| 参照 | 可直接借鉴 | 不照搬 | Fibra 取舍 |
|---|---|---|---|
| DSH/Cordis `0.1.5-rc.2` / `c291e7961` | Host/client runner 分层、Scope/effect 所有权、动态装载、renderer 后置挂载 | DSH client runner 直接依赖 Cordis slot/React 体系，且部分运行由页面主动编排 | 复用生命周期思想，但由 Fibra Engine 主导 client target，renderer 仅作为可选 adapter |
| VS Code extension host/web extensions | 同一扩展系统可有多个执行位置，浏览器能力受限 | 不复制 VS Code 的产品协议、扩展清单或进程拓扑 | runtime、execution target、capability 分轴建模 |
| Grafana app/backend plugins | 一个用户安装单位可包含前端与后端能力 | 不把其插件包或后端启动模型当成 Fibra 事务保证 | 逻辑插件统一管理，多 facet 保留独立物理身份 |
| Eclipse Theia | 前后端插件位置显式、宿主与插件 API 分层 | 不引入其 IDE 专用 widget/layout 体系 | UI composition 留在 renderer adapter |
| OpenAI Codex app-server | 核心工程拥有协议及多语言导出，进程内与外部传输共享语义 | 不引入其 Agent/Session 业务协议 | Fibra protocol 只承载通用 client 生命周期和 Host contribution 调用 |

DSH 的 `packages/extensions/cordis-client-runner/src/client/runtime.ts` 证明浏览器装载、精确运行身份、串行替换
与 effect 级联清理可行；`packages/client/ui-renderer` 同时证明 React renderer 可以是独立包。它不能证明
React 类型适合作为 Fibra 通用 UI 契约，因此 Fibra 不复制 DSH `SlotMap` 中的 `ReactNode` 边界。

### 3.1 为什么现在下沉到 Fibra

当前 CLI 与计划中的 Desktop 是同一产品的两个入口，不把它们包装成两个独立产品消费者；DOM/React probe
也只是框架无关性的证伪夹具。现在下沉的依据不是消费者数量，而是同一逻辑插件已经需要跨 Host、CLI 与
browser execution 同步安装、启用、升级、停用和卸载。首批真实场景包括 provider 设置页、工具结果
renderer、审批页以及同时贡献 Host handler、CLI command 和 client view 的 full-stack 插件。

如果 client lifecycle 留在产品仓库，上层必须重复实现 package gate、target revision、observed、重连收敛、
drain/stop 和 payload digest 的协议与围栏规则，并承担两套实现随演进漂移的风险。这样即使没有第二个外部
产品，也可能产生“新 UI 调旧 handler”“部分 facet 已升级”“断线被误作停用成功”等一致性裂缝。由 Fibra
拥有这些跨执行域不变量，用户的一次安装或启停才能同时、可观察地改变 Host、CLI 与 Desktop；上层只选择
壳、transport、renderer 和业务数据流。

这项工作必须先于 Agent 产品 P1，因为后续设置页、审批和工具结果展示都会消费同一生命周期；先在产品侧
落一套临时 runner，之后再下沉只会制造第二次协议和状态迁移。它仍是 P0 可证伪验证：若真实纵向闭环表明
browser execution 不需要参与唯一 target/`ChangeSet`，或只能通过 Agent/Desktop 专用分支才能接入，则停止
通用 client foundation，实现退回为 Fibra 仅提供通用外部 execution SPI，Web runtime 留在产品侧。

## 4. 最终模块结构

```text
fibra/
  fibra-api/                       Java 嵌入式生命周期内核
  fibra-artifact/                  逻辑插件包、facet 制品、不可变存储身份
  fibra-engine/                    唯一 desired/observed 与跨 facet ChangeSet
  fibra-registry/                  逻辑插件级管理用例与审计
  fibra-runtime-host/              内建与动态 Java definition 的 Host RuntimeDomain 执行实现
  fibra-runtime-java/              JVM facet 探测、class space 与 PreparedArtifact 实现
  fibra-runtime-node/              Node sidecar execution adapter
  fibra-client-protocol/           Java wire 值对象、codec 与协议状态机
  fibra-runtime-client/            client facet 静态准备与 Host 侧外部 execution 协调器
  client/
    packages/client-api/           TypeScript ClientContext/Scope/effect 契约
    packages/client-runtime/       framework-neutral lifecycle runtime
    packages/client-runtime-web/   浏览器 ESM 装载、digest 与资源解析
    packages/client-react/         P0 参考 renderer adapter，非 core 依赖
  verification/client/             DOM、React、跨语言和真实浏览器验收
```

`fibra-client-protocol` 与 `fibra-runtime-client` 是 Maven 发布物；`client/*` 是 npm 发布物。两组发布物共享
Fibra 产品版本和 wire protocol version，但不存在 Maven 构建对 pnpm 的隐式调用。聚合验证显式组合二者。

模块依赖方向固定为：`fibra-engine` 只拥有并发布 `ArtifactRuntime`、`ExecutionRuntime` 等协调抽象，不依赖
`fibra-runtime-host`、`fibra-runtime-java`、`fibra-runtime-node` 或 `fibra-runtime-client`；这些 runtime 模块
单向依赖 `fibra-engine`，分别实现所需的 `ArtifactRuntime` 和/或 `ExecutionRuntime` SPI，由 CLI、Spring starter
或 verification Host 等 composition root 显式装配。Maven reactor 和架构测试必须同时阻止反向依赖、
runtime 实现模块互相依赖与循环依赖。

上层产品只需要选择并装配：

- Host transport；
- Electron/Tauri/Browser/原生窗口壳；
- renderer adapter 与具体 UI 插件；
- 产品业务 gateway 和业务数据流。

它不再实现自己的 client registry、client engine、生命周期状态机、revision 围栏、重连收敛或 digest
校验。

## 5. 逻辑插件与 facet 模型

用户安装单位是不可变的 `PluginPackage`：

```text
PluginPackage
├── pluginId
├── version
├── packageDigest
└── facets[]
    ├── facetId
    ├── role
    ├── runtimeId
    ├── executionTarget
    ├── payload
    ├── payloadDigest
    ├── dependencies
    └── requiredCapabilities
```

各字段分工如下：

- `pluginId/version`：用户可见逻辑身份和统一管理边界；
- `facetId`：逻辑包内稳定且唯一的 facet 身份；
- `runtimeId`：解释 payload 并产出对应静态执行描述的 runtime，如 `java`、`node`、`client`；
- `executionTarget`：执行位置选择，如 `host`、`client:web`、未来 `client:javafx`；
- `role`：贡献意图，如 `host`、`command`、`client`，不参与 runtime 路由；
- `requiredCapabilities`：选择可执行 client 的能力条件，不表示 UI 框架类型；
- `payloadDigest`：跨进程获取和校验资源的内容身份，不能使用 Host 本地绝对路径。

逻辑包根清单固定命名为 `fibra-package.yaml`。`ArtifactId` 继续表示物理 facet payload 身份，但不再是
Registry 的用户级安装单位。现有单 runtime `ArtifactPackage` 被逻辑 `PluginPackage` 替代；只含
`plugin.properties` 的旧包直接拒绝，不保留兼容读取器。

外层 `fibra-package.yaml` 是 `pluginId/version`、facet 列表和 facet 依赖的唯一真源；唯一 desired target 中的
`PluginSelection` 是部署时 package revision 选择的唯一真源。Java/Node 等 runtime-local descriptor 只描述
入口和贡献声明，不得再次声明逻辑 `pluginId/version/requires`，也不得独立求解版本；已经解析的 wiring 由
Engine target compiler 另行交给 runtime，不写回 descriptor。runtime-local descriptor 的文件名由对应
runtime 定义；即使沿用已有 `plugin.yaml` 或 `package.json`，它也不是逻辑包清单，两者职责不能重叠。

对象模型使用完整字段名，`fibra-package.yaml` 使用固定短 key：顶层 `id` 对应 `pluginId`；facet 的 `id`、
`runtime`、`target`、`capabilities` 分别对应 `facetId`、`runtimeId`、`executionTarget`、
`requiredCapabilities`。顶层 `format` 只表示 manifest 格式版本。`packageDigest` 与 `payloadDigest` 均由受控内容
计算，不在 manifest 中自报。

每个 facet 的 `dependencies[]` 使用唯一结构
`FacetDependency(pluginId, facetId)`：来源 facet 由所在数组确定，目标始终是一个逻辑 plugin 的稳定 facet，
不引用物理 `ArtifactId`。同包依赖也完整填写当前 `pluginId`。Engine 的目标编译器在保存前只针对唯一 target
中已经启用的精确 `PluginSelection(pluginId, packageRevision)` 解析，生成
`ResolvedFacetDependency(pluginId, packageRevision, facetId, artifactId)`；同包依赖使用来源 package 的
revision，跨包依赖使用 target 已选中的目标 package revision。编译器校验缺失或未启用的目标、重复边和
依赖环，再把确定的 wiring 交给 runtime。

P0 不定义 `versionConstraint`，也不保留无行为占位字段；不解析版本范围、不自动下载或另选版本、不做多版本
冲突仲裁。只有真实多版本消费者出现后，才单独设计 package 版本兼容策略；该策略不得引入第二个版本选择器，
也不得改变 `PluginSelection` 作为部署选择唯一真源的地位。

持久目标同时包含 package 级选择和实例级配置：

- `PluginSelection(pluginId, packageRevision, enabled)` 是逻辑插件所有 facets 的统一版本选择与启停门；
- `DesiredInputEntry` 不再引用无归属的 `definitionName`，而是引用
  `PluginDefinitionRef(pluginId, definitionId)`；
- package gate 关闭时，其所有 facet executions 与 Host definitions 一起撤销；单个配置实例仍可在统一
  desired graph 内独立 enabled，但不能改变 package 版本或让某个 facet 绕过 package gate；
- 从逻辑 package、selection 和 desired graph 到全部 facet/instance plans 的编译是确定性的，并在目标
  保存前完成。

Host 内建 definitions 也不能保持匿名。现有 `FibraEngine.Builder.catalog(PluginCatalog)` 被
`builtInPackage(BuiltInPluginPackage)` 替代；每个内建包提供稳定 `pluginId/version/packageDigest`、隐式 Host
facet 和带归属的 definitions，作为不可安装、不可升级但可统一启停的固定 package selection 进入同一目标
编译。Registry 对内建包拒绝 install/upgrade/uninstall，只允许 package gate 的 enable/disable；所有现有
直接 catalog 消费者必须迁移。动态 package 与内建 package 的 `pluginId` 不得冲突。

一个安装、升级、停用或卸载操作总是针对逻辑插件，编译成包含全部 facets 的一个 ChangeSet。任何 facet
在保存目标前的静态验证失败都拒绝整个操作，不产生半安装；保存目标后的远端执行失败保留新 desired 并
如实发布 observed，不伪回滚旧目标。

## 6. Engine、artifact runtime 与 execution runtime

现有“制品资源准备”和“插件实例执行”在 Java/Node 中恰好同进程或由 Host 拉起，但外部 client 可以离线、
重连并存在多个 execution。P0 不把这些语义硬塞进单个本地资源状态，而是把 runtime SPI 明确拆成制品面与
执行面。最终契约为：

```text
ArtifactRuntime
  id()
  probe(package facet source)
  inspect(managed facet)
  createUpdate(target managed facets) -> PreparedArtifactUpdate
  snapshot()
  closeAsync()

PreparedArtifactUpdate
  prepareAsync()
  preparedArtifacts()
  affectedFacets()
  adopt()
  closeAsync()

ExecutionRuntime
  id()
  compile(logical target, prepared artifacts) -> ExecutionTargetPlan
  createUpdate(execution target plan) -> ExecutionUpdate
  observations()
  closeAsync()
```

每类 update 都必须先返回并登记可关闭句柄，之后才能执行该 update 所拥有的副作用：
`PreparedArtifactUpdate` 在 prepare I/O 前登记；artifact prepare 完成后，`ExecutionRuntime.compile` 只做
纯编译并产出 plan，随后 `ExecutionUpdate` 在启动进程或发送远端命令前登记。两个 `createUpdate` 自身都不得
执行 I/O。这样既允许 execution plan 依赖已准备制品，又避免取消或失败后失去资源所有权。
`PreparedArtifactUpdate` 的语义固定为：

- prepare 失败或只完成一部分时，仍由同一预登记句柄反向释放；
- `adopt()` 只转移所有权，不执行 I/O；adopt 前关闭释放候选，adopt 后关闭释放旧闭包；
- 未变化资源只是借用，候选失败不能关闭它们；
- 清理失败必须保留失败资源及其真实先决依赖、缓存终态，并阻止下一次不安全更新；
- 单 facet 的 `PreparedArtifact` 与一次执行的 `ExecutionHandle` 不是同一所有者；执行实例和在途调用全部
  清理前，不能 retire 它引用的制品资源。

现有抽象不是并列保留，而按下表硬迁移：

| 现有责任 | 最终责任 |
|---|---|
| `PluginRuntimeAdapter.probe/inspect` | `ArtifactRuntime.probe/inspect`；runtime 只校验 facet payload |
| `PluginRuntimeAdapter.create + RuntimeResourceOwner` | 合并为 Engine 独占的长期 `ArtifactRuntime` 实例 |
| `RuntimeResourceUpdate` | `PreparedArtifactUpdate`；表示一次受影响闭包，不误命名为单个 artifact |
| `RuntimeResources` | `ArtifactResources`；只协调制品准备、采用、保留和 retire，不再拼通用 `PluginCatalog` |
| `RuntimeCatalog` | 拆为 `PreparedArtifact` 静态描述与 Host 专用 `PluginCatalog` |
| `FibraEngine.bind + Bound` | `ExecutionRuntime.compile + ExecutionTargetPlan`，保存前完成纯编译 |
| `FibraEngine.Managed/PluginInstance/Scope` | Host execution 的 `ExecutionHandle`；client execution 不伪装 JVM 实例 |
| `FibraEngine.reconcile` | Engine 调度各 `ExecutionUpdate`，执行实现拥有具体 instance/session 生命周期 |
| `PluginInstanceSnapshot` | 通用 `ExecutionObservation`；JVM domain 细节只作为 Host 诊断子结构 |
| `InstallArtifact/UninstallArtifact/DeploymentArtifact` | `InstallPackage/UninstallPackage/DeploymentPackage` |

`PluginCatalog` 与 `PluginCatalogEntry` 只允许作为 Host 本地执行的内部模型。Engine 通用 target 编译、client
协议和远端 observed 不得依赖 `PluginDefinition`、`PluginInstance` 或 `RuntimeDomain`。

`JavaArtifactRuntime` 只负责动态 JVM facet 的探测、class space、loader 和 `PreparedArtifact`，不拥有
`PluginInstance` 生命周期。`HostExecutionRuntime` 同时消费内建 definitions 与 Java prepared artifacts，统一
在 Host `RuntimeDomain` 中创建和关闭 `PluginInstance`；不再存在第二个 `JavaExecutionRuntime`。Node runtime
的 execution 是 Host 拥有的 sidecar。`ClientArtifactRuntime` 在 Host 侧校验受管 client facet payload 及
digest，产出只包含 entry module、受控资源、execution target 和 capability 条件的
`ClientPreparedArtifact`；它不连接远端、不启动浏览器，也不拥有 session。`ClientExecutionRuntime` 只消费该
静态结果并协调外部注册的 renderer/browser/native client。三个 execution runtime 只依赖 Engine SPI，彼此
不依赖；Engine 只协调统一阶段，不假设所有执行实例都是 JVM `PluginInstance<?>`。

必须保留的 Engine 不变量：

1. prepare 本地制品和协议元数据；
2. 原子保存完整 desired target，并同时保存内容身份 `targetDigest` 与部署代次 `targetRevision`；
3. reconcile 各 execution runtimes；
4. 先关闭受影响贡献准入，再排空已接受调用；
5. 撤销实例、effects 和远端 execution；
6. retire 被替换资源；
7. 失败保留明确阶段、目标保存事实、清理事实和可查询 observed。

远端 client 离线不能阻塞保存前 prepare，也不能阻塞 Host ready。它只影响保存后的 execution observed 和
特定 UI execution 的 ready。

`targetDigest` 是完整 package selections 与 desired graph 的 SHA-256 内容身份，相同 A 内容可复用。
`targetRevision` 是持久目标谱系内严格递增、对调用方不透明的部署代次：连续提交与当前目标相同的内容是
no-op，不推进 revision；A→B→A 的第二个 A 必须获得新 revision。`hostInstanceId` 再隔离不同 Host 进程。
因此任何生命周期回复都不能只凭 digest 对齐，必须同时匹配 target revision、runtime instance 与 operation。

### 6.1 P0 阶段门与失败回退

P0 是验证，不以先拆毁全部外围调用方作为前提，也不能把协议 harness 误作 Engine 可行性证据：

1. **P0-A client 技术栈可行性门**：只新增 protocol/client core/Web loader/DOM 与 React probe，在
   `verification/client/risk-gate` 用无持久状态 harness 验证跨语言 codec、资源摘要、Scope/effect、真实
   浏览器和 A→B→A wire fence。harness 不保存 desired、不提供 Registry，只证明 client stack 可行。
2. **P0-B1 Engine 核心风险门**：先硬切生产 `DeploymentManifest`/codec/state store、唯一 target、
   `ArtifactRuntime`/`ExecutionRuntime` 和 `FibraEngine` 协调路径。使用最终格式的合成逻辑包/provider fixture，
   由真实文件 store 验证 no-op、A→B→A 新代次、旧格式拒绝和 save-unconfirmed；再把真实
   `ClientArtifactRuntime`、`ClientExecutionRuntime` 与浏览器接到实际 Engine，证明 client 静态准备不依赖
   execution 在线、离线 PENDING、保存前失败不落盘、保存后失败保留 desired、统一准入、
   drain/stop/retire 和 CLI-like/UI 共用 `PublishedRuntime`。不得使用临时 Engine、第二 Registry 或模拟
   target compiler。
3. **P0-B2 管理面与调用方硬切**：在 B1 通过后迁移 Registry/config、CLI、Spring、examples、parity、
   archetype、plugins 和 distribution。按 Maven 模块依赖前沿形成可编译、可测试的分支 checkpoint；不要求
   每个 checkpoint 都让尚未迁移的全 reactor 通过，但任何受影响前沿都必须绿。
4. **P0-B3 最终真实证据**：迁移并验收一个真实 fs provider 家族，即 `fibra-fs` contract 逻辑包与
   `fibra-fs-local` provider 逻辑包，完成精确跨包依赖、CLI/UI 同一真实 contribution、独立发布物消费、
   仓库外运行和真实浏览器验收。shell/subprocess 只有被该纵向场景实际需要时才迁入 P0，不作为证明跨执行域
   模型的附加范围。

阶段 checkpoint 只承担实验定位和 Git 回退，不是发布物。中间可暂存尚未接入的最终类型，也可让尚未进入
迁移前沿的模块暂时不编译；不得增加旧新互转 adapter、双格式读写、第二 Registry/desired state 或运行时
选择分支。P0-A 失败时不修改生产 target；P0-B1 失败时在核心风险处停止；P0-B2/B3 失败时保留失败证据和
最后一个通过的 checkpoint。只有全 reactor、发行隔离和最终真实验收全部通过后才允许合并或发布。

## 7. Client execution 与 observed

以下身份必须独立，不得复用或互相替代：

| 身份 | 作用 |
|---|---|
| `hostInstanceId` | 排除 Host 重启前的连接、回复与调用 |
| `targetDigest` | 标识完整 desired target 内容；A→B→A 可重复 |
| `targetRevision` | 标识本次已保存部署代次；A→B→A 不重复 |
| `viewRevision` | Host 已发布贡献与调用准入快照 |
| `clientExecutionId` | 一次具体 client 连接/执行会话 |
| `runtimeInstanceId` | 一个 facet 在一个 execution 中的本次实例 |
| `lifecycleOperationId` | 一次 prepare/activate/drain/stop 操作 |
| `registrationIdentity` | Host contribution 的精确注册代 |

最终 observed 按逻辑插件、facet 和 execution 分层：

```text
LogicalPluginObserved
└── FacetObserved
    ├── facetId/runtimeId/executionTarget
    ├── aggregateState
    └── executions[]
        ├── clientExecutionId
        ├── runtimeInstanceId
        ├── lifecycleOperationId
        ├── state
        └── failure
```

没有匹配 client 时 facet 为 `PENDING`；所有当前匹配 execution 已对齐时为 `ACTIVE`；存在失败或不同步时
按 execution 如实展示，聚合状态不得覆盖明细。client 连接集合变化只推进 observed/view，不改变
targetRevision。P0 只启动一个真实 web execution，但模型和协议从第一天按 execution 建模，不能保存一个
全局 client 状态。

## 8. Wire protocol

协议采用 request/response/event 统一 envelope，codec 严格拒绝未知字段、重复字段、尾随内容、非法枚举、
缺失的消息专属身份和超限 payload。不得用一个所有字段可空的“万能 identity”，也不得要求握手伪造尚未
分配的 Host/runtime 身份。消息按阶段使用四种严格结构：

| 结构 | 字段 | 使用范围 |
|---|---|---|
| `HelloIdentity` | `clientNonce` | 仅 `client.hello`；由 client 生成，不假设已知 Host/target |
| `SessionFence` | `hostInstanceId + clientExecutionId` | `host.welcome` 后的 session/snapshot/detach |
| `LifecycleFence` | `SessionFence + targetRevision + runtimeInstanceId + lifecycleOperationId` | prepare/activate/drain/stop 及其结果 |
| `CallFence` | `SessionFence + expectedViewRevision + registrationIdentity` | contribution call/result |

v1 wire envelope 固定为 `{protocolVersion,messageId,type,payload}`；消息专属字段只能放在 `payload`，不得摊平到
envelope，也不套用 JSON-RPC。为让 wire 形状本身即可拒绝跨阶段身份，payload 身份键固定为：hello 使用
`identity`，welcome/snapshot/observed/detach 使用 `session`，生命周期命令及结果使用 `lifecycle`，call/result
使用 `call`。编码后的 UTF-8 envelope 上限为 1 MiB；受控 inline bytes 计入该上限，大资源必须走受控 URL，
超限消息按 `MALFORMED_MESSAGE` 拒绝。

`host.welcome` 分配 `clientExecutionId` 并返回完整 `SessionFence`。`targetDigest` 作为 snapshot/target 内容字段，
不代替生命周期 fence。codec 按 message type 精确校验所需结构：hello 携带 lifecycle 字段、生命周期消息
缺 operation、或 session 消息夹带 runtime identity 都必须拒绝。P0 最小消息集：

```text
client.hello
host.welcome
host.snapshot
host.prepare
host.activate
host.drain
host.stop
client.lifecycle-result
client.observed
client.call
host.call-result
client.detach
```

这 12 种消息的 v1 payload 不是只含 fence 的占位结构，而是能独立跑通 P0 的最小正式 schema：

| 消息 | `payload` 的完整责任 |
|---|---|
| `client.hello` | `identity + executionTarget + capabilities` |
| `host.welcome` | Host 分配的 `session` |
| `host.snapshot` | `session + targetRevision + targetDigest + viewRevision + assignments + contributions` |
| `host.prepare/activate/drain/stop` | 本次命令的 `lifecycle`；prepare 所需静态描述已由同一 target 的 snapshot 给出 |
| `client.lifecycle-result` | `lifecycle + outcome`；outcome 是 applied，或带结构化 failure 的 failed |
| `client.observed` | `session + executions[]`；每项含 target/runtime/operation、状态及仅失败态存在的 failure |
| `client.call` | `call + contributionKind + contributionId + input` |
| `host.call-result` | `call + contributionKind + contributionId + outcome`；outcome 是 value 或结构化 failure |
| `client.detach` | 本次断开的 `session` |

`viewRevision` 是包含初始值 `"0"` 在内的不透明非空字符串，`registrationIdentity` 是正整数，必须直接映射
`PublishedRuntime.invoke(String, long, ...)`，不得在 protocol/runtime 间另做类型转换。`contributionId` 对应
`ContributionId(providerInstanceId, localName)` 的两个字段，不使用不可逆或有歧义的拼接字符串。调用输入和
成功结果使用 Fibra 的递归不可变 literal value，只允许 JSON 的 null、boolean、number、string、list 和
string-keyed object，不传 Java/JavaScript 对象。

snapshot 的每个 assignment 至少固定 `pluginId/facetId/runtimeInstanceId/executionTarget/entryModule/
payloadDigest/requiredCapabilities/resources`。每个资源包含逻辑 `path`、SHA-256 `digest`，以及严格二选一的
受控 URL 或 base64 inline bytes；不得携带 Host 本地路径。snapshot 的 contribution 项包含
`contributionKind`、结构化 `contributionId` 和 `registrationIdentity`，client 只能据此构造对应
`CallFence`。`targetDigest`、`payloadDigest` 与资源 digest 均使用 64 位小写十六进制 SHA-256；
`targetDigest` 只属于 snapshot 内容，不能进入或代替 lifecycle fence。

跨线 failure 固定为稳定 `code/message` 和受限的字符串 diagnostics；owner/facet/execution/operation 由其
所在 assignment、call 或 lifecycle/observed 项提供，不复制一套可空身份。`client.observed` 的每个 execution
项使用 `targetRevision + runtimeInstanceId + lifecycleOperationId`；Host 以 session 和 snapshot assignment
映射回逻辑 facet。`FAILED` 必须携带 failure，非失败态禁止夹带 failure。

`hello` 声明 protocol version、execution target 与 capabilities；Host 选择精确支持版本，不做降级兼容。
不匹配直接拒绝并保留诊断。每条 Host 生命周期命令携带完整围栏身份；client 只有在所有身份与当前待处理
操作匹配时才应用或确认；成功确认后该 pending operation 必须被消费，重复确认也返回 `STALE_OPERATION`。
A→B→A 中的迟到 A 回复不能确认新的 A。

snapshot 和资源定位只携带内容摘要及受控 URL/bytes，不携带 Host 本地路径。调用必须携带
`hostInstanceId + expectedViewRevision + registrationIdentity + contribution kind/id`，最终仍由
`PublishedRuntime.invoke` 准入。client 不得按名称重试到新 handler，也不得重放可能已有副作用的调用。

传输是协议的 adapter。P0 正式发布 `ClientTransport` SPI；内存 transport 只存在于测试源码，真实 Web
carrier 只用于 verification 浏览器闭环，二者都不是生产 adapter。第一个上层产品确定连接方式后、进入生产
装配或发布前，必须按第 14.2 节在 Fibra 内另立规格并发布所选 Electron IPC、WebSocket、MessagePort 或其它
正式 adapter；任何 transport 都不得改变协议状态机。

## 9. Framework-neutral client runtime

client core 只提供：

- 不可变 `ClientContext`；
- 层次化 `ClientScope`；
- owner-bound effect/disposable；
- client-local service/contribution 注册；
- lifecycle command 串行化和幂等围栏；
- `host.call`；
- observed 与结构化错误；
- 资源装载接口。

Web runtime 只增加浏览器 ESM 装载、digest 校验、object URL/受控资源 URL 与页面级清理。它不创建 React
root，不定义 route/slot/component，不读取 Electron API，也不保存 desired target。

renderer adapter 在 client-local service/contribution 上定义自己的组件契约：React adapter 可以定义
React slots，Vue adapter 可以定义 Vue components，原生 DOM adapter 可以定义 element mount factory。
这些对象只在对应 execution 进程内流动，不进入 wire protocol。

P0 同时实现纯 DOM 与 React 两个独立 probe。任何 core 包出现 React/DOM/Electron 导入，或增加 Vue 需要
修改 Engine/wire schema，都判定框架无关目标失败。

## 10. 错误与关闭语义

错误按阶段和所有者分类：package validate、artifact prepare、protocol handshake、resource fetch、module
evaluate、activate、call admission、drain、stop、dispose、transport。跨线错误只传稳定 code、message、
owner/facet/execution/operation 身份和可选受限诊断；不传任意异常对象或秘密。

停用/升级顺序固定为：

1. Host 撤销受影响 action 的新准入；
2. 等待已经接受的 Host invocation；
3. 向匹配 client executions 发送 drain；
4. 等待 client-local action/effect 排空；
5. 发送 stop，client 按反向所有权关闭 scopes、listeners、timers、services 和 UI contributions；
6. 收到匹配 operation 的确认后 retire 旧资源；
7. 某 execution 超时或失败时保留失败 observed，并按公开截止进入有界强制断开；不把断开冒充成功清理。

关闭期间控制通道保留到 stop/dispose 结果已经收到或公开截止耗尽。renderer 刷新、transport 断开、窗口
退出和 Host 退出是不同事件；断线不修改 desired，也不默认取消产品业务任务。

## 11. P0 纵向验证

P0 不是完整产品 UI，而是可证伪的架构验证，必须形成真实运行闭环：

1. 一个 canonical 动态逻辑包包含 `host-java`、`command`、`client` 三个 facets，并显式依赖 target 已选中的
   真实 `fibra-fs-local` provider facet；target 同时选择 `fibra-fs` contract 逻辑包，provider 对 contract
   facet 的依赖也解析到精确 package revision；
2. 目标编译器把同包与上述跨包引用解析到精确 package revision；缺失、未启用、重复边或依赖环均在保存
   target 前拒绝；
3. Registry 一次安装/启用，Engine 只保存一个 target revision，并可核对对应 target digest；
4. 正式 `ClientArtifactRuntime` 在没有 execution 连接时仍能准备 client facet；非法 entry module、资源或
   digest 在保存 target 前失败，verification carrier 不生成或修补静态描述；
5. client 未连接时 Host 与 CLI 正常 ready，client facet 明确为 PENDING；
6. 真实浏览器 execution 握手后按 snapshot 加载 ESM，回报 ACTIVE；
7. 纯 DOM probe 与 React probe 通过同一个 client core 和 wire protocol 装载；
8. 页面与 CLI 都经同一 `PublishedRuntime` 调用一个真实 Fibra fs contribution；
9. disable/upgrade 只改变依赖闭包，先拒绝旧 action、排空在途调用，再撤销 UI/effects；
10. 刷新和重连按 Host 当前 target 收敛，不读取 client 自己保存的版本；
11. Host 重启、client 重连、A→B→A 和迟到 ack 均由精确身份围栏拒绝；
12. headless Maven 构建不需要 Node，npm 构建不读取 Maven reactor classpath；
13. Maven/npm 独立消费者、仓库外运行和真实浏览器验收通过；
14. UI 模拟测试不能替代最终真实浏览器证据。

P0 不包含完整 Electron 产品、Session、Agent、复杂路由/slot、主题、远程市场、多窗口一致性、非可信插件
沙箱、依赖版本范围求解、自动选版或多版本冲突仲裁。其中产品壳、业务页面、Agent/Session、市场服务与运营、
面向恶意 JavaScript 的具体隔离容器永不进入 Fibra core；属于 Fibra 的通用平台增强只能按第 14 节触发条件在
本底座通过后另立计划，不能反向膨胀当前 P0。

## 12. 可证伪条件与停止条件

出现任一情况必须停止实现并回到架构决策：

- client 需要第二份 Registry、profile、desired target 或版本选择器；
- 新 renderer adapter 需要修改 Engine 或 lifecycle wire schema；
- browser 资源必须依赖 Host 本地绝对路径；
- 远端 client 被迫伪装成一个本地 JVM `PluginInstance`，且无法表达按 execution 的 observed；
- client 离线阻塞 Host ready 或目标保存；
- 旧 action 可以按名字落到新 handler；
- 正常关闭在 client scope/effect 清理完成前关闭控制通道；
- 纯 Java 消费者被迫安装或执行 npm 工具链；
- 为保留旧单 facet 包格式而引入兼容分支、双写状态或旁路管理 API；
- package 安装已经逻辑化，但 desired/config 或 Registry 仍能按无归属 definition 绕过 package gate；
- 把内容 `targetDigest` 当作部署 `targetRevision`，导致 A→B→A 的第二个 A 复用代次；
- browser execution 只有引入 Agent/Desktop 专用 target、状态或生命周期分支才能接入；
- 同一问题连续两次验证失败且没有新的根因证据。

## 13. 对上层产品路线的影响

产品仍采用独立仓库，CLI 与 Desktop 仍是同一个产品 Host 的同级客户端；变化仅在于它们消费 Fibra 已实现
的 client foundation，而不再自建底层。

产品 P0 从“发明 adapter、runner、协议和多 facet 管理”调整为“装配 Fibra client foundation、选择真实
transport 与 renderer、验证唯一 Host 双端控制”。React + Electron 可以继续作为首个产品组合，但不是
Fibra 的隐式依赖或唯一 UI 路线。

## 14. P0 完成态与 Fibra 后续能力账本

本节是 Fibra 自身的成熟度边界，不是上层产品路线。P0 通过表示跨执行域内核已经完成一次可发布硬切，
不是“先做临时代码，验证后再重写”；也不表示通用 UI 插件平台的所有能力已经完成。

### 14.1 P0 产物分层

| 层级 | P0 完成时包含 | 验证后处置 |
|---|---|---|
| 永久内核不变量 | 唯一 Engine/Registry/desired target、逻辑 `PluginPackage` 多 facet、artifact/execution 双 SPI、严格 revision/identity fence、per-execution observed、统一准入与 drain/stop/retire、Maven/npm 发布边界和 runtime 单向依赖 | 永久保留；新增能力只能扩展，不能建立第二控制面或恢复旧单 facet 模型 |
| 最小正式实现 | `fibra-package.yaml`、精确 package revision 依赖编译、protocol v1 最小消息集、Host/Java/Node runtimes、`ClientArtifactRuntime`/`ClientExecutionRuntime`、Web ESM loader、真实 fs contract/provider 迁移和独立消费者发行 | 继续作为后续版本基础；能力面可以增加，身份、所有权和保存语义不重写 |
| 参考 adapter | `client-react` 及其最小 renderer 接口 | 作为可选发布物保留，但不成为 core 依赖；API 在出现真实消费者并单独冻结前不承诺覆盖完整 route/slot/layout/theme |
| 仅验证夹具 | P0-A protocol harness、DOM/React probe plugins、synthetic package/provider、`EngineGateHost`、`ClientP0Host`、CLI-like caller、verification Web carrier 与仅测试使用的 in-memory transport | 只保留在 verification/test scope，不得进入生产 composition root，不得被上层产品当成正式 transport 或插件 SDK |

P0 中只启动一个真实 web execution 是验收规模限制，不是模型退化：per-execution 身份与 observed 已是永久
模型。P0 只支持精确选择依赖也是正式的首版行为，不是待替换的临时 solver；未来版本范围能力必须继续以唯一
`PluginSelection` 为部署选择真源。

### 14.2 P0 后仍属于 Fibra 的能力

以下项目不属于当前 P0，但已经明确归 Fibra。只有命中“启动条件”才另立规格和验收，不得凭未来想象预建：

| 能力 | P0 结束时的边界 | 启动条件 | 不得改变的内核约束 |
|---|---|---|---|
| 正式 client transport adapters | 发布 `ClientTransport` SPI；内存 transport 和 verification carrier 只负责测试闭环 | 第一个上层产品确定实际连接方式后、进入生产装配或发布前，把所选 WebSocket、MessagePort 或 Electron IPC adapter 做成 Fibra 正式发布物，使上层只选择和装配 | transport 只搬运 protocol envelope，不拥有 desired、revision 或生命周期状态机 |
| 协议演进与版本偏差 | protocol v1 精确匹配，不降级兼容 | 需要发布第二个协议版本，或 Host/client 允许独立升级之前 | 版本协商不能放宽消息严格校验，不能按名称重放调用，也不能削弱身份 fence |
| 多 execution 实证 | 数据模型按 execution，P0 只验一个真实 web execution | 宣称支持多窗口、多浏览器或同一 Host 多 client 之前 | execution 独立 observed；任一断开不修改 desired，也不污染其它 execution |
| 插件制作与发布工具链 | manifest、archetype 和独立消费者门禁可用，但不是完整第三方开发体验 | 对外承诺第三方 client/full-stack 插件开发之前 | 工具只生成和验证唯一逻辑包格式，不引入私有包格式或旁路安装入口 |
| client 资源缓存与运维 | 支持受控 bytes/URL、digest 校验和失败 observed | 出现大 payload、离线缓存、远程资源分发或生产 SLA 之前 | cache 不能成为版本真源；清理失败和 orphan execution 必须可诊断、可回收 |
| 包信任与能力授权协议 | P0 只支持受信插件；capability 是 execution 匹配条件，不是安全授权 | 接受非本地受信来源的 package，或 Host contribution 需要权限控制之前 | Fibra 可定义签名/信任元数据、调用授权、吊销和配额；digest 不能冒充代码信任，具体恶意 JavaScript 沙箱不进入 core |
| 依赖版本与远程包源协议 | 只解析 target 已选中的精确 package revision，不自动下载或选版 | 出现真实多版本消费者或需要接入远程 registry 之后 | 唯一 `PluginSelection` 仍是部署选择真源，不建立第二 solver/installed database；Fibra 只定义通用包源/获取边界，不实现 marketplace 业务 |
| 更多 execution target 与 renderer adapter | 正式实现 `client:web`，React 只是参考 adapter | 出现 JavaFX、原生 UI、Vue/Svelte 等真实消费者之后 | 新 adapter/target 不得要求修改 Engine 核心状态模型或把框架对象送上 wire |
| 通用 UI composition 包 | core 只提供 client-local service/contribution；不定义复杂 route/slot/layout/theme | 至少两个无业务关系的 UI 插件证明相同组合语义可复用之后 | 只能作为可选 client 包，不能进入 framework-neutral core 或 Host protocol |

### 14.3 永不计入 Fibra 欠账

以下内容不因 P0 完成而成为 Fibra 待办：Electron/Tauri 应用启动与窗口策略、产品路由和页面、产品主题、
Agent/Model/Session/MCP/审批业务、Session journal、产品 gateway、token/event 数据流、具体业务插件、
marketplace 服务/目录/运营，以及面向恶意 JavaScript 的具体浏览器沙箱或隔离容器。它们由上层产品或独立
安全组件消费 Fibra 发布物实现；Fibra 只保证其底层插件生命周期、调用准入和跨执行域一致性。

Task 13 完成后应把第 14.1 节从“计划完成态”回填为实际制品和测试证据。第 14.2 节保持 backlog；任何一项
进入实施都必须新增独立规格、计划、真实消费者和停止条件，不能直接追加到已完成 P0。
