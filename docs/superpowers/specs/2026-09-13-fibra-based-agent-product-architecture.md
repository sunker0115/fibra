# 基于 Fibra 的 CLI + Desktop Agent 产品架构与实施路线

状态：产品 P0–P8 的唯一权威架构与实施顺序；Fibra CLI F1–F4 和产品 P0–P8 均尚未实施。

本文件承接 Fibra vNext 第 1–10 节的完成点 `a83174d`，定义后续独立 Agent 产品的双端架构、统一插件模型、
P0–P8 唯一阶段顺序和验收门禁。Fibra 已完成范围与尚未实施的 CLI F1–F4 仍以
[Fibra vNext 架构](./2026-09-07-fibra-vnext-architecture.md)为准；本文件不把现有封闭 CLI/ZIP 验收写成
F1–F4 已完成，也不把产品侧规划写成 Fibra 当前已经交付的能力。

产品项目名称、Maven/npm 坐标和首个模型 provider 在建仓阶段确定。本文使用“产品”作为占位称呼，
不提前冻结品牌或公开坐标。

## 1. 目标、边界与完成定义

产品同时提供 CLI 与 Desktop 两个正式入口：CLI 面向自动化、服务器、诊断和终端工作流；Desktop 面向
会话、富文本、代码、工具过程、审批、设置和插件管理。两端不是两个产品，也不拥有两套状态。

核心目标只有一个：

> 以 Fibra 作为唯一插件控制面，由同一个 Host 管理 Java、Node、CLI contribution 和 Desktop client
> plugin；用户通过一个插件管理入口即可安装、配置、启停和升级改变能力、命令或页面的插件。

产品完成不能由单元测试全绿代替。只有同时满足以下条件才能宣布完成：

- CLI、Desktop 和外部附着客户端共享同一个 Host、插件目标、调用路由、Session 事实与诊断；
- 用户可以通过 CLI 或 Desktop 管理同一个 Host、CLI、Desktop 或全栈逻辑插件；
- 一个真实模型完成真实工具调用，长会话能够重启恢复、断线补读、取消和排空；
- 显式升级或停用只改变依赖闭包，无关 Java/Node/client 实例、ClassLoader、Node PID、effects、组件和
  在途调用保持；
- Desktop 安装包可以直接启动并打开页面，CLI 发行可以独立运行，两者都不依赖源码仓库；
- 仓库外解压、空 data、空 Maven 仓、隔离前端依赖缓存、可复现制品、公开 API、文档和独立审核全部通过；
- 所有阶段已经提交但没有自动推送，测试与实现位于同一提交。

### 1.1 已有、前置与待实现能力

| 层次 | 状态 | 内容 |
|---|---|---|
| Fibra vNext 第 1–10 节 | 已实现并有发行证据 | RuntimeDomain、Engine、Registry、ArtifactStore、Java/Node runtime、PublishedRuntime、差量更新、调用排空、正式基础插件、封闭 CLI 和发行 ZIP |
| Fibra CLI F1–F4 | 产品建仓前置，尚未实施 | 公开 CLI 组合 API、动态 command contribution、终端租约、历史/补全/高亮、调用级取消、CLI API 与发行冻结 |
| 上层 Agent 产品 P0–P8 | 尚未实施；阶段顺序只由本文定义 | 单 Host 附着、Electron/React Desktop、client runtime adapter、统一逻辑插件包、Model、Agent、Session、MCP、Skill、审批及其它产品插件 |

F4 的空 Maven 仓、仓库外消费者、公开 API 签名和最终发行门禁通过以前，不创建产品代码仓库。现在完成
本设计不等于提前开始 P0 实现。

## 2. 桌面技术路线与开源参照

### 2.1 正式选择：Electron + React

Desktop 正式主路线固定为 Electron + React：

- Electron main process 只负责进程、窗口、受控 preload、更新入口和退出协调；
- renderer 运行极薄 client runtime 与 React UI 插件；
- Java Host 继续拥有唯一 Engine、Registry、目标、调用准入、权限判断和业务事实；
- Node sidecar 仍由 `fibra-runtime-node` 托管，不能被当作浏览器 runtime；
- CLI 继续使用 Fibra CLI SPI，不嵌入 Electron，也不复制 Desktop 状态。

选择 Web 渲染不是为了建立“前端系统”，而是因为 Agent 产品长期需要 Markdown、代码块、语法高亮、
虚拟列表、流式内容、图表、主题、无障碍和复杂布局。Electron 的前置平台成本更高，但 React/CSS 生态能
降低每个后续页面和 renderer 的边际成本。

Swing/JavaFX 能在单 JVM 内直接做插件 UI，但富文本、复杂布局、流式渲染和长期产品迭代成本更高，因此
不作为正式主路线，也不同时建设。若未来出现第二个明确的纯 Java Desktop 消费者，可在不改变唯一控制面
的前提下另做 client adapter；本路线不预建 Swing、JavaFX 或 JCEF 混合层。

### 2.2 开源参照与取舍

DSH/Cordis 使用固定源码作为契约参照；VS Code、Grafana 与 Eclipse Theia 使用浮动官方文档，只是截至
2026-09-13 的非契约设计灵感。版本、提交、源码位置及证据等级见
[后续架构真源与外部参考审计](../references/2026-09-13-architecture-source-audit.md)。

| 参照 | 外部事实 | 证据等级 | Fibra/产品自定推导 |
|---|---|---|---|
| DSH/Cordis `0.1.5-rc.2` 固定源码 | Host/client runner、renderer、slot、layout、页面和业务能力可由插件组合，Scope/effect 负责所有权清理 | 固定提交直接证明所列行为 | renderer/layout/feature 全插件化，并继续使用 Fibra 自身准入与排空 |
| [VS Code Extension Host](https://code.visualstudio.com/api/advanced-topics/extension-host) 与 [Web Extensions](https://code.visualstudio.com/api/extension-guides/web-extensions) | 官方文档描述 local/web/remote extension host，以及 `main`/`browser` 入口和受限浏览器环境 | 浮动链接，非契约灵感 | 唯一管理面、多个执行域、逻辑插件多 facet、manifest 执行位置均为本项目契约，不声称 VS Code 提供这些组合保证 |
| [Grafana App Plugin](https://grafana.com/developers/plugin-tools/key-concepts/anatomy-of-a-plugin) 与 [Backend Plugin](https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins) | 官方文档描述一个 App Plugin 可包含页面/UI extension/backend，后端由服务端启动并经协议调用 | 浮动链接，非契约灵感 | 一个逻辑安装单位跨 facet 进入同一 `ChangeSet`、由 Host 协调准入与排空，是本项目契约 |
| [Eclipse Theia 扩展模型](https://theia-ide.org/docs/extensions/) | 官方文档区分运行时插件与编译期扩展，并描述前后端插件运行位置 | 浮动链接，非契约灵感 | client execution session、desired/observed 分离和重连协调是本项目契约 |

因此，唯一管理面、跨 facet `ChangeSet`、desired/observed 协调、同一目标与排空语义均由 Fibra/产品
自行定义和验收；不得把外部项目的局部模式写成它们已经直接证明这些组合保证。

## 3. 系统结构与核心不变量

```text
                            一个产品命名空间
                    profile + canonical data directory
                                   │
                                   ▼
                  唯一 Fibra PluginRegistry / Engine
                  desired target / ArtifactStore / diagnostics
                                   │
                 ┌─────────────────┼─────────────────┐
                 │                 │                 │
                 ▼                 ▼                 ▼
          Java RuntimeDomain   Node sidecars   client runtime adapter
          host/command plugins host plugins            │
                 │                 │                   ▼
                 └──────── PublishedRuntime ─── Electron renderer
                                   │             React UI plugins
                                   │
                            product SessionStore
                                   ▲
                     ┌─────────────┴─────────────┐
                     │                           │
                 CLI client                Desktop client
```

“一个控制面”不等于“所有代码在一个进程或一种语言中运行”。Java、Node 和 renderer 是不同执行域；版本
选择、desired state、插件管理、调用准入和事实保存只有一个所有者。

以下不变量不可放宽：

1. 唯一性以同一产品、profile 和 canonical data directory 组成的命名空间为边界；不同命名空间可以拥有
   独立 Host。
2. 只有持有该命名空间启动所有权的进程可以打开 Engine 和持久数据；CLI 与 Desktop 后启动者必须附着，
   不能创建第二个 Registry。
3. Java、Node 和 client plugin instance 全部进入同一个 desired graph 和 ChangeSet；执行位置不产生第二
   profile、目标或版本选择器。
4. CLI 与 Desktop 的管理操作调用同一组 Registry 用例；Desktop 插件页不是 client plugin 管理器。
5. SessionStore 是产品 Host 插件拥有的唯一会话事实源，不属于 Fibra core；renderer 只保存可重建投影。
6. client runner 只有执行状态，没有 desired state；离线、失败和重连只改变 observed/diagnostics。
7. 一个 ChangeSet 统一协调所有 facet，但不承诺 Java、Node、浏览器和外部系统组成分布式 ACID 事务。
8. 调用使用选择贡献时的 view revision 与贡献注册身份；过期调用明确失败，不转向同名新 handler。
9. 插件断线、窗口关闭、客户端退出和 Host 退出是不同事件；断线不默认取消 Agent turn。
10. 清理期间保留控制通道，直到停止准入、调用排空、effect/slot 撤销和执行端确认完成。

## 4. 组件职责

| 组件 | 拥有 | 不拥有 |
|---|---|---|
| Fibra Engine/Registry | 目标、制品、依赖图、生命周期、运行事实、诊断、调用准入 | Agent/Session 业务语义、窗口和 React 状态 |
| 产品 Host | Engine 装配、SessionStore、Agent/Model/Tool/MCP、gateway、Host 生命周期 | 第二插件容器、静态工具目录 |
| 产品 CLI | bootstrap 参数、一次性命令、REPL、终端 renderer、Host 附着 | 独立 Engine 状态、Session 副本、Desktop 状态 |
| Electron main | Host 发现/启动、窗口、preload、操作系统集成、退出协调 | 插件版本选择、业务权限、Session 数据 |
| 固定 bootstrap/preload | 最小控制握手、执行端注册、加载/失败画面、受限桥 | 产品页面、插件管理状态、业务命令 |
| client runtime adapter | 把同一 Engine 生命周期映射到指定 renderer 执行端 | 独立 ChangeSet、版本解析和目标保存 |
| 浏览器 client runner | 执行下发的 client snapshot、维护 Scope/effect/slot、回报 observed | Registry、ArtifactStore、安装、权限判断、Session 事实 |
| React UI 插件 | route、slot、component、action、可重建视图状态 | 任意 DOM 修改权、长期凭据、Host 私有对象 |

最小控制握手和 stop/dispose 通道属于 bootstrap，因为插件尚未激活或已经失败时仍需装载和清理。业务
connection、renderer、layout、页面和插件管理 UI 仍是插件。该例外只为打破启动与关闭闭环，不能扩展为
第二套 UI 框架。

## 5. CLI、Desktop 与唯一 Host 生命周期

### 5.1 启动和附着

同一命名空间使用一个受保护的 endpoint record 和操作系统级排他锁。record 至少关联 Host instance
identity、PID/进程身份、端点和协议版本；路径只是本机发现信息，不进入插件制品或部署身份。

| 入口 | 无 Host 时 | 已有 Host 时 | 退出行为 |
|---|---|---|---|
| CLI 一次性命令 | 取得启动权，拉起 Host 并持有临时 lease | 附着并执行，不默认取得 lifetime lease | 释放会话和临时 lease；没有其它 lease 时 Host 排空退出 |
| CLI REPL | 取得启动权，拉起 Host 并持有 REPL lifetime lease | 附着并持有 REPL lease | 释放自己的 lease；是否退出 Host 由剩余 lease 决定 |
| Desktop | 立即显示固定 loading window，拉起或附着 Host，并持有 Desktop lifetime lease | 直接附着并取得 Desktop lease | 先 detach client，再释放 lease；没有其它 lease 时协调 Host 退出 |
| headless/server | 拉起 Host，由 Host 自持 daemon lifetime lease 并公开受控 gateway | 附着或明确拒绝重复 daemon | 只接受显式管理关闭/信号 |

锁竞争失败者等待有效 endpoint 并附着。发现陈旧 record 时必须同时核对进程身份和 Host instance identity，
确认原所有者已经不存在后才能恢复；不能只看 PID 或直接覆盖锁文件。

排他锁和 Engine 始终由独立产品 Host 进程持有，CLI/Electron 只是启动者与客户端。Host 维护进程内 lifetime
lease 集合：REPL、Desktop 和 headless 可以持有 lease，一次性附着调用不延长既有 Host 寿命；启动临时
Host 的一次性 CLI 在命令期间持有临时 lease。释放最后一个 lease 时，Host 先等待已接受工作排空再退出。
因此 CLI 先启动、Desktop 后附着时，Desktop lease 会使 REPL 退出后 Host 继续运行，不需要迁移 Engine
所有权，也不能由某个客户端单方面关闭仍被其它 lease 使用的 Host。

临时、REPL 和 Desktop lease 必须绑定持有者进程身份与已认证控制会话；Host 确认持有者进程死亡或控制
会话失效后自动撤销，不能依赖客户端 finally 才释放。Desktop lease 归 Electron main process，不因单个
renderer 崩溃或重载而释放；main process 异常退出才触发回收。headless lease 由 Host 自持，直到显式关闭
或 Host 进程终止。自动回收仍须经过正常排空，不能把失联等同于立即强杀受管任务。

### 5.2 Host ready 与 UI ready

Desktop 启动后可以立即打开固定 loading 页面，但产品页面只能在两级就绪完成后显示：

- Host ready：Engine 已恢复保存目标或完成空目标初始化，gateway 已监听，当前 PublishedView 可读取；
- UI ready：renderer 执行端已经注册，首屏所需 client plugins 已按当前目标激活并回报成功。

浏览器尚未连接时，client entries 以明确的 `PENDING_ALLOWED` 存在于同一目标。Host ready 不能等待一个尚未
创建的 renderer，否则形成启动死锁；UI ready 也不能因为 Host 已就绪就忽略失败的 renderer/layout 插件。

### 5.3 关闭

关闭页面先禁止新 UI action，再由唯一 Engine 停止相关调用准入并排空在途调用。控制连接必须保持到
client 收到 stop/dispose、撤销 effects、routes、slots、listeners 和 timers，并回报完成后才能关闭。

Host 退出继续停止 Agent turns、Node sidecars、subprocess 和其它受管资源，提交必须持久化的 Session 事实，
最后释放 endpoint record 和锁。只有公开关闭截止耗尽且诊断已经保存时，owner 才能强制终止残留进程。
窗口关闭不能直接杀 JVM；client detach 不能冒充 Host 已退出。

首版只支持一个产品窗口和一个 renderer 执行端。多窗口会引入“每窗口实例”与“每执行端实例”的归属选择，
必须由真实场景另行设计，不能用一个全局 observed 值覆盖多个客户端。

## 6. 统一插件模型与管理

### 6.1 一个逻辑安装单位，多种 facet

用户看到的是一个 `pluginId@version` 逻辑插件包；物理执行仍复用 Fibra 的不可变 ArtifactPackage 和 runtime
adapter。逻辑 manifest 只组合物理 facet，不替代 ArtifactStore 的身份：

| facet role | 物理 runtime | 典型贡献 |
|---|---|---|
| host-java | Java | Model、Agent、Session、Tool、MCP、policy、gateway provider |
| host-node | Node sidecar | Node provider、外部协议 adapter、受管进程能力 |
| command | Java 或 Node | CLI command descriptor、补全来源、TUI action；command 不是独立 runtime |
| client-web | Electron renderer | route、slot、component、theme、tool renderer、设置页 |

每个 facet 保留独立物理 ArtifactId、runtime、entrypoint 和 content digest；逻辑 manifest 保存
`pluginId/version`、facet 引用、依赖、兼容范围和权限声明。只需要一种形态的插件只声明对应 facet，不能为
凑结构创建空 artifact。

Fibra DeploymentManifest 中的稳定 group identity 表示逻辑插件，完整 artifact 集合和实例声明仍是唯一
持久目标。产品不能另建 installed-plugins 数据库；管理页从同一目标和 PublishedView 聚合逻辑状态。

### 6.2 支持的插件形态

| 形态 | 示例 | 一次安装后的效果 |
|---|---|---|
| Host-only | 模型 provider、MCP、storage provider | 增加后端能力和 contribution |
| CLI-only | 诊断命令、TUI renderer | 增加命令、补全或终端视图 |
| Desktop-only | 设置页、主题、工具结果 renderer | 增加或替换页面、面板、slot 内容 |
| Full-stack | GitHub、数据库、Agent workflow | 同时增加 Host 能力、CLI 命令和 Desktop 页面 |

“可以装任何插件”表示可以安装所有符合 manifest、API 版本、runtime、digest、权限和依赖约束的插件形态，
不表示执行任意裸 JAR、npm 包或远程网页脚本。

### 6.3 唯一管理入口

CLI 的 `plugins install/enable/disable/upgrade/configure/inspect` 与 Desktop 插件管理页调用同一个 Host
management contribution：

```text
CLI command ─────────┐
                     ├──> Host PluginRegistry use case ──> one ChangeSet
Desktop action ──────┘
```

一次操作按以下顺序推进：

1. 读取完整逻辑包，验证全部 facet、依赖、兼容性、权限声明和 digest；
2. 将全部物理制品保存到同一个 ArtifactStore，任何 facet 失败都不能形成可启用的半安装；
3. 将所有实例编译进一个 desired graph 和 ChangeSet，完成 Java、Node、client facet 的预检与资源准备，
   但不在目标保存前启动新实例或拆除旧实例；
4. 原子保存一个完整目标并产生一个 target revision；
5. 保存成功后由 Engine 调用 Java、Node、client adapter reconcile；升级或停用在此阶段先封闭受影响准入、
   排空旧调用、撤销 effects，再按依赖闭包替换或释放实例；
6. 发布实际运行事实，分别显示 ACTIVE、PENDING、FAILED 和等待的执行端。保存后协调失败如实保留新目标和
   实际 observed，不自动反写旧目标。

管理页面本身也是 client plugin。固定 bootstrap 只保留最小故障诊断和重新连接入口；管理页损坏时，用户
通过产品 CLI 调用同一个 Host 修复同一目标，不使用前端恢复数据库或旁路配置。

## 7. Revision、执行端会话与调用围栏

不同身份不能压成一个模糊 revision：

| 身份 | 含义 | 生命周期/用途 |
|---|---|---|
| namespace identity | 产品、profile、canonical data directory | 选择唯一 Host 和持久数据 |
| hostInstanceId | 本次 Host 进程的随机身份 | 防止 Host 重启后旧 client 回报或调用污染新进程 |
| targetRevision | 完整 desired target 的内容摘要 | 标识声明的插件、版本、实例和配置目标 |
| viewRevision | 当前 Host 已发布运行事实的 revision | 命令树、贡献选择、调用准入和诊断自洽性 |
| clientExecutionId | renderer 本次受管连接身份 | 归属 client observed、生命周期确认和断线清理 |
| runtimeInstanceId | Engine 分配的本次 client plugin 实例身份 | 区分同目标下依赖重建或实例替换 |
| lifecycleOperationId | Engine 分配的单次 prepare/activate/drain/stop 身份 | 防止 A→B→A 或迟到回复确认错误操作 |
| session sequence | 单 Session 内单调事件序号 | 事件补读、幂等投影和 checkpoint |

`ClientDeploymentSnapshot` 至少携带 hostInstanceId、clientExecutionId、targetRevision、runtimeInstanceId、
client artifact identity/digest、依赖顺序、配置和资源位置。每条生命周期命令另带
lifecycleOperationId；client 回报必须匹配对应 Host、连接、实例和仍在等待的操作。重连、实例重建或新操作
产生新身份，迟到旧回复直接丢弃并记录诊断。这些身份只是跨进程围栏，不是新的 desired revision 或控制面。

每次 action/call 使用选择贡献时的 expected viewRevision 和贡献注册身份。准入失败返回明确 stale view、
revoked contribution 或 wrong host instance，不自动切换到同名新 handler，也不自动重放可能已经产生副作用
的调用。

CLI 的一次解析、help 和补全固定使用捕获的 Fibra command descriptor、贡献注册身份与 viewRevision。
这些不可变事实共同定义命令代；Picocli `CommandSpec` 只是从该代派生、受单次操作或 CLI lane 约束的
可变解析对象，不是并发只读快照，也不能充当代身份。解析尚未完成准入时不持有 route 租约；只有执行
边界以捕获身份/revision 通过 `PublishedRuntime` 准入的 invocation 才取得租约并参与排空。若准入前
revision 已变化或贡献已撤销，返回 stale/revoked，不调用旧 handler，也不转向同名新 handler。

“两端共享一个 PublishedView”表示一次命令、一次渲染投影或一次调用各自在一个不可变快照内自洽，不表示
所有窗口和 CLI 在同一时刻形成同步屏障。view 变化通过订阅通知，客户端按最新可用事实重建投影。

离线 client 不改变 targetRevision；对应 facet 保持 PENDING 并公开等待原因。重连后 Engine 按保存目标
重新下发，客户端不能选择缓存中的旧版本。目标已保存、Host 能力已 ACTIVE、某个 client 已对齐是三个不同
事实，管理 UI 必须分别显示。

## 8. React client runtime 与 UI 插件

### 8.1 薄 client runner

client runner 只做以下工作：

- 建立和维持 bootstrap 控制通道，执行 Host 下发的 prepare/activate/drain/stop；
- 校验 content digest，按确定顺序加载受管 ESM；
- 创建页面内 Scope，提供 services、effects、routes、slots 和受控 `host.call`；
- 差量卸载插件并回报 observed target、错误和 disposal 结果；
- 按 Session cursor 消费事件，重连后从已确认 sequence 补读。

它不扫描插件目录、不安装或选择版本、不解析产品 profile、不保存 desired state、不执行 Maven/npm 解析、
不决定权限、不直接运行 Shell/fs/Model，也不成为 Session 事实源。不得在产品仓库出现 ClientRegistry、
ClientEngine 或第二份插件配置。

### 8.2 UI 插件层次

| 层次 | 插件职责 |
|---|---|
| renderer | 创建 React root、错误边界和 SlotRegistry |
| layout/theme | 声明主框架、导航、面板布局、主题变量和响应式规则 |
| connection/session projection | 将受控 Host action 和 Session cursor 投影为 client services |
| feature | conversation、settings、plugin manager、approval、jobs、tool view 等页面和 slot 内容 |

renderer 提供 `UiSlotRegistry`；feature plugin 只能通过 owner-bound disposable 注册 route、slot、component、
action 和 renderer。停用时注册项随 Scope 撤销。插件不能直接持有全局 React root、修改其它插件 DOM、绕过
effect 注册永久 listener/timer，或把私有模块对象泄漏给另一个插件。

React、React DOM 和产品 client API 由 renderer 父环境提供，插件以受控 peer/provided 依赖消费，不能各自
打包 React 副本。UI API 冻结 descriptor、props、disposal 和错误边界，不把 Electron、gateway 私有对象
或 Host DTO 任意暴露给插件。

### 8.3 安全边界

Electron renderer 开启 context isolation 与 sandbox，关闭 Node integration，只加载发行包或已验证受管
制品。preload 只暴露窄、逐方法校验的桥，并验证 IPC sender。长期凭据不进入 URL、localStorage、日志或
普通 UI 插件；权限在 Host action/call 准入时重新判断。

首版只支持受信插件。Scope、slot、content digest 和 context isolation 是生命周期与进程权限边界，不是
同页恶意 JavaScript 的安全沙箱。非可信第三方插件需要 iframe/独立 renderer、能力 token、供应链签名和
更强隔离，留到出现真实市场需求后单独设计。

## 9. Agent、Session 与长会话数据流

Model、Agent、Session、Approval、MCP、Context、Skill、Goal、Todo 和 Workflow 都是上层产品插件，不进入
Fibra core。它们继续经 RuntimeDomain service/event/effect 组合，并经 PublishedRuntime 向 CLI、Desktop
和外部 adapter 发布宿主可见 contribution。

### 9.1 统一调用路径

```text
CLI command / Desktop action
          │
          ▼
PublishedRuntime.invoke(expectedViewRevision, contributionIdentity, input)
          │
          ▼
Agent / Tool / MCP contribution
          │
          ▼
Session journal append ──> projection ──> CLI/Desktop cursor consumer
```

直接工具、Agent 工具和 MCP 工具必须传递同类 cancellation、deadline、调用 Scope、权限结果和稳定失败码。
客户端取消只表示 Host 已接受取消请求；远端 provider 是否停止由实际终态确认，不能提前显示成功取消。

### 9.2 Session journal 与游标

Session journal 至少记录 `sessionId/turnId/stepId/sequence/correlationId/type/terminal/payloadRef`。事件只追加，
同一 Session 内 sequence 单调且终态唯一；大 token、工具原始结果和附件超过限制时外置，journal 保留摘要
和稳定引用。

- `agent.start` 快速返回稳定 turn handle，实际 turn 是插件 Scope 拥有的有限受管任务；
- `agent.events.read(afterSequence, limit, waitTimeout)` 提供有界批量读取/长轮询；
- gateway 可以用 SSE/WebSocket 推送“有新数据”或批次，但 journal 才是事实源；
- client 断线不取消 turn，重连按 sequence 补读；超过保留范围返回明确 checkpoint/resync；
- 已提交工具副作用不重放，未完成步骤由 Session 恢复规则进入中断或显式续跑；
- 一个永不结束的 contribution invocation 不能代表整段 Session，否则插件升级无法排空。

React 侧不能按每个 token 无限制同步重渲染。connection/projection plugin 对事件做有界合批，conversation
使用虚拟列表，Markdown/代码渲染遵守单批大小和帧预算；慢消费者从 journal 补读，不能依赖无界内存队列。

只有第二个与 Agent/Session 无关的真实消费者证明游标协议无法满足吞吐或延迟时，才评估向 Fibra 下沉
通用 streaming contribution。届时必须同时定义 codec、背压、取消、终态和 Java/Node/client 排空。

## 10. 项目组织与发行

产品使用独立源码仓库、版本和发布周期，只消费 F4 后发布的 Fibra 制品：

```text
product-parent/
  product-api/                 Model、Agent、Session 等宿主可见 API
  product-host/                Fibra 装配、唯一 Host、gateway、client adapter
  product-cli/                 Fibra CLI SPI 消费者与产品命令
  product-desktop/             Electron main/preload/bootstrap/renderer
  product-client-api/          React route/slot/component/action 契约
  product-plugins/             host、command、client 与 full-stack 插件
  product-distribution/        CLI ZIP 与平台 Desktop 安装包
  product-acceptance/          仓库外双端、真实模型、重启和信号门禁
```

目录表示交付职责，不要求 P0 一次创建所有空模块；对应能力进入阶段时再创建。产品模块不能依赖 Fibra
reactor、源码目录、包私有类型、绝对路径或历史 Maven 缓存。

CLI ZIP 与 Desktop 安装包是同一产品版本下的不同物理发行：

- 共享逻辑插件版本、默认 profile、目标格式和产品 API；
- CLI ZIP 保持可脚本化，不因 Desktop 改变 stdout/stderr/退出码；
- Desktop 安装包包含 Electron 静态资源、兼容 JRE、产品 Host、默认插件和目标平台受管工具；
- Java/Node/client 插件保持独立制品，不打入 Host 主 JAR；
- 两种发行均不预置用户 data，不包含源码仓库绝对路径；
- 平台安装包分别构建和验收，不用 macOS 成功代替 Windows/Linux 结论。

## 11. 分阶段实施路线

### 11.1 前置阶段：完成 Fibra CLI F1–F4

四阶段均尚未实施。现有 Fibra 固定管理命令、REPL 和 ZIP 验收属于 vNext 第 1–10 节已完成范围，不能
抵扣任一阶段退出条件。F1–F4 的编号、交付、契约测试、退出条件和提交边界只以
[Fibra vNext 第 11 节](./2026-09-07-fibra-vnext-architecture.md)
为准；本产品真源只把 F4 通过作为进入 P0 的前置条件，不复制第二套 F 阶段表。

### 11.2 产品阶段

Desktop 控制切片必须放在 P0，而不是等到 Agent/Session 已复杂后才验证。

| 阶段 | 最小交付 | 关键真实验收 |
|---|---|---|
| P0 产品骨架与双端控制切片 | 独立仓库；极薄 CLI；Electron/React bootstrap；唯一 Host；gateway/client adapter；renderer/slot；一个诊断 client plugin 和一个 full-stack probe plugin | 从空 Maven 仓只消费发布 Fibra；仓库外启动空 data Desktop；CLI 附着同一 Host；一次插件管理同时改变命令与页面；从页面和 CLI 调用真实 Fibra fs/shell 工具；窗口关闭、detach、SIGTERM 无残留 |
| P1 最小真实 Agent | 一个真实 Model provider、invocation 内单 Agent loop、Tool bridge、最小审批；CLI/Desktop 共享单次运行 | 真实模型选择并调用正式 fs 或 shell 工具；双端走同一 Host；取消和 SIGTERM 无 effect/进程泄漏；不冒充 Session 恢复 |
| P2 Session 与重启恢复 | Session API、追加 journal、JSONL provider、projection、受管 turn、checkpoint/fork | 完成真实模型工具调用后重启恢复；写入前、工具已提交后、终态前三个故障点不产生半完成或伪成功事实 |
| P3 完整 Desktop 插件与事件流 | conversation、settings、plugin manager、approval、tool renderer 插件；有界游标、断线补读、虚拟列表 | 页面显示真实模型与工具事件；断线从 sequence 补读；慢消费者 checkpoint/resync；全栈插件升级/停用只更新依赖闭包，旧 action 被拒绝 |
| P4 MCP | stdio/Streamable HTTP client、工具同步、撤销和有限重连 | 真实 MCP server 发现、调用、取消；停用后 route 排空和子进程清理；双端工具目录一致 |
| P5 Context 与 Skill | instructions、文件引用、时间、按需附件/spill | profile/realm 隔离；上下文来源可解释；敏感值不落 Session、history 或诊断；显式升级不改旧事实 |
| P6 Goal、Todo、Plan、Compaction | 基于 Session 事实的长任务插件和 token 投影 | 跨重启恢复未完成目标；压缩不丢未完成调用或终态；Plan 切换不重启无关插件 |
| P7 Sandbox、Jobs、Terminal/PTY | policy/provider、后台任务、owner 权限、按需 PTY | 报告实际平台隔离强度；任务/PTY 取消、停用和 SIGTERM 排空；内存 jobs 不冒充持久任务 |
| P8 Subagent、Workflow 与外部 adapter | 受管子 Agent、DAG、按真实需求加入 API/SDK/ACP | 父取消排空所有子资源；provider 局部升级；仓库外消费者只依赖产品发布物完成真实调用 |

依赖主线：

```text
F1 -> F2 -> F3 -> F4
                       \
                        P0 双端控制 -> P1 Agent -> P2 Session -> P3 Desktop/事件闭环
                                                \              \
                                                 P4 MCP -> P5 Context/Skill -> P6 长任务
                                                                  \
                                                                   P7 Jobs/Terminal -> P8 Workflow
```

每阶段只实现本阶段退出条件需要的最小完整纵切，不预建后续空接口。发现公共契约分歧、同一问题连续两次
验证失败或需要改变既有 Fibra 不变量时停止，由设计决策先行，不能用兼容层或旁路状态继续堆实现。

## 12. 验收矩阵与交付留痕

### 12.1 双端与统一控制

- Desktop 双击后立即出现 loading window；Host ready 后注册执行端并装载首屏插件，只有 UI ready 后才自动
  进入插件化页面；
- 同一 data/profile 只存在一个 Host，CLI 后启动时附着；并发竞争不能产生第二 Engine；
- CLI、REPL、Electron renderer 或 main process 异常退出后，对应 lease 按所有权规则回收；最后一个 lease
  消失时 Host 完成排空退出，不留下孤儿 Host，也不因 renderer 单独重载误关 Host；
- CLI 与 Desktop 的插件列表、target revision、运行事实和诊断来自同一 Host；
- 一个 full-stack probe plugin 经一次 install/enable 同时出现真实 Host contribution、CLI 命令和 React 页面；
- 显式 disable/upgrade 后三类 facet 一起变化，无关实例、ClassLoader、Node PID、components、effects 和
  在途调用保持；
- client 离线时 Host 保存目标且显示 PENDING，重连按原目标收敛，不选择目录中新版本；
- Host 重启后旧 client 回复和旧 view action 被围栏拒绝。

### 12.2 真实 Agent 与长会话

- CLI 与 Desktop 都能启动同一种真实 Agent run；
- 真实模型完成文件或 Shell 工具调用，工具结果写入唯一 Session journal；
- Desktop 断线重连按 sequence 补读，CLI 能读取同一会话事实；
- 慢消费者、重复批次、终态竞态、显式取消、插件升级和 Host SIGTERM 均有真实门禁；
- 重启不重放已经提交的工具副作用，未完成步骤显示明确中断或恢复状态。

### 12.3 发行与隔离

- 每阶段实现、测试和真实验收夹具在同一提交；提交前运行对应离线定向测试；
- P0 和最终发布从空 Maven 本地仓取得 Fibra 正式发布物，再独立构建产品；
- 前端依赖使用锁文件，并在隔离 package-manager store/cache 中重建，不能读取产品仓库外 link 或全局包；
- 每阶段在仓库外目录解压当期发行并运行新增能力，不能只使用 reactor classpath；
- 最终执行全仓验证、公开 API 签名、可复现制品、CLI ZIP、Desktop 安装包、空仓构建和独立审核；
- 不 amend、不 rebase、不改写已有历史、不自动 push；阶段没有实际变更时只记录已有提交与验证证据。

验收账本逐项记录测试类、命令、制品路径、平台、结果和未实测边界。macOS 通过不能写成 Windows/Linux
也已通过；模拟浏览器测试不能替代最终 Electron 安装包真实启动。

## 13. 明确后置与禁止事项

以下内容不进入首个产品闭环：

- 远程插件市场、自动下载源、在线更新协议和非可信插件沙箱；
- Swing、JavaFX、JCEF、多窗口、移动端和浏览器 SaaS 多租户；
- 第二种模型协议、完整 MCP resources/prompts 承诺；
- Web、LSP、Webhook、Schedule 等没有真实消费者的垂直插件；
- 通用高吞吐 Fibra streaming API；先用 Session journal 与有界游标验证；
- 多 Agent/DAG、PTY 持久化、复杂跨平台 sandbox、完整 ACP/SDK；
- 不改变控制面边界的高级动画、主题市场、可视化编辑器和大规模 UI 优化。

明确禁止：

- 为 Desktop 建立 ClientRegistry、ClientEngine、前端 profile 或 installed-plugins 数据库；
- CLI 与 Desktop 各自打开同一 data 目录或保存不同插件目标；
- 让 renderer 扫描目录、选择版本、写 desired state 或直接访问 Host 私有对象；
- 将 command contribution 当成第四种 runtime；
- 用 targetRevision 代替调用所需的 viewRevision，或用 viewRevision 代替 Session sequence；
- 把 PENDING client 静默从目标删除，或把 Host ready 冒充 UI ready；
- 正常关闭路径在排空完成前关闭控制连接、卸载 Scope、释放 ClassLoader 或强杀 Host；只有第 5.3 节定义的
  公开截止耗尽且诊断已保存后，owner 才能执行有界强制终止；
- 把受信 UI 插件的约定式隔离描述为恶意 JavaScript 安全沙箱；
- 因为使用 Electron 就接受第二控制面、两套配置或不可追踪的前端热更新。

下一次实施仍从 Fibra F1 开始。F4 完成并提交以前，只维护本设计和既有 Fibra 证据，不创建产品源码；
F4 门禁通过后，以 P0 的双端控制切片作为新项目第一个可独立验证的提交阶段。
