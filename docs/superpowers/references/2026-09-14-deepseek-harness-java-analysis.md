# DeepSeek Harness Java 公开架构分析与 Fibra 最优演进建议

日期：2026-09-14

状态：公开资料分析与外部实现参照，不是新的架构真源或实施计划。Fibra 第 1–10 节与 CLI F1–F4 仍由
[Fibra vNext 架构](../specs/2026-09-07-fibra-vnext-architecture.md)定义；上层 Agent 产品 P0–P8 仍由
[CLI + Desktop Agent 产品架构](../specs/2026-09-13-fibra-based-agent-product-architecture.md)定义。
本报告可以提出改变现有设计的建议，但只有合并进对应权威 spec 并补齐测试后才成为项目契约。

本报告只引用官网、公开仓库与公开成熟项目。

## 1. 结论

DeepSeek Harness Java 证明了 Java 生态能够较完整地承载 Agent Harness：ReAct、工具、会话、审批、插件、
MCP、工作流、子代理、目标、计划、技能和 Web 控制台可以组织成一个可运行产品。它最值得借鉴的不是
“27 个限界上下文”或目录数量，而是四组产品设计：

1. 所有内置、Java、Node、MCP 工具进入一个执行与治理入口；
2. 会话事实、模型上下文、流式协议与 UI 投影分离；
3. Java 插件只依赖轻量契约，通过受控 Context 注册 Tool、Hook、Prompt、事件与 disposer；
4. 用真实 MySQL、商城案例证明“业务系统是事实源，插件是语义和治理适配器”。

公开资料同时明确暴露了生产缺口：运行期审批可能默认未接入、ClassLoader 不是安全沙箱、本地 Shell/文件
边界不足、Schedule/Workflow/后台 Subagent 尚未闭环、数据库迁移和 HTTP/SSE 测试仍需工程化。

综合 DSH Java、原始 DSH/Cordis、Fibra 以及成熟插件宿主实践，最优目标不是三者任选其一，而是分层组合：

```text
Fibra：通用插件内核、唯一 desired/observed 控制面、Scope 所有权、差量更新、调用排空
  ↓
Agent 产品运行时：Model、Session、Agent、Tool、Approval、Credential、MCP、Skill、Workflow
  ↓
CLI / Desktop / SDK：同一事实流、同一管理面、不同交互投影
```

如果比较后发现 Fibra 当前实现偏离这组目标，可以调整；不应因为代码已写完就保留次优设计。当前公开
证据没有支持回退长期 `RuntimeDomain`、配置条目树、实例差量更新、`PublishedRuntime` 或单一 Fibra
manifest。真正需要继续优化的是上层 Agent 产品的调用治理、事件持久化、身份授权、凭据和插件 SDK。

## 2. 公开证据范围

### 2.1 官网

官网材料抓取于 2026-09-14，页面标示的能力与边界以当日内容为准：

- [首页](https://dsh-java.xiaofuge.cn/index.html)
- [运行时详解](https://dsh-java.xiaofuge.cn/runtime.html)
- [使用教程](https://dsh-java.xiaofuge.cn/docs.html)
- [18 章学习教程](https://dsh-java.xiaofuge.cn/document/index.html)
- [六边形架构](https://dsh-java.xiaofuge.cn/document/ch02-architecture.html)
- [ReAct 循环](https://dsh-java.xiaofuge.cn/document/ch04-react-loop.html)
- [会话与事件溯源](https://dsh-java.xiaofuge.cn/document/ch05-session-event.html)
- [工具与 Hook](https://dsh-java.xiaofuge.cn/document/ch08-tools.html)
- [插件系统](https://dsh-java.xiaofuge.cn/document/ch09-plugin.html)
- [MCP](https://dsh-java.xiaofuge.cn/document/ch10-mcp.html)
- [审批与治理](https://dsh-java.xiaofuge.cn/document/ch11-governance.html)
- [部署与生产化](https://dsh-java.xiaofuge.cn/document/ch12-deployment.html)
- [已知边界](https://dsh-java.xiaofuge.cn/document/ch13-epilogue.html)
- [工程全景](https://dsh-java.xiaofuge.cn/document/ch14-engineering.html)
- [工作流与子代理](https://dsh-java.xiaofuge.cn/document/ch15-workflow-subagent.html)
- [长程能力](https://dsh-java.xiaofuge.cn/document/ch16-platform-capabilities.html)
- [版本演进](https://dsh-java.xiaofuge.cn/document/ch17-release-notes.html)

官网同时存在使用页、学习教程和镜像发布三个版本口径，当前页面涉及 `0.1.5`、`0.1.6` 与 `0.1.6.4`。
因此引用“当前实现”时必须标明页面和版本，不能把不同页面自动视为同一制品。

### 2.2 公开业务源码

| 仓库 | 固定提交 | 用途 |
|---|---|---|
| [dsh-java-mysql](https://github.com/fuzhengwei/dsh-java-mysql/tree/4084ac7eaabd489662d8e6f296e9764737e2cdad) | `4084ac7` | 数据库控制台、MySQL 插件、业务安全边界 |
| [2d-weekend-mall](https://github.com/fuzhengwei/2d-weekend-mall/tree/1d1edb28a2ad97681e104d0d06be44adda98fa24) | `1d1edb2` | 商城应用、客服插件、身份与订单查询 |
| [dsh-java-deploy](https://github.com/fuzhengwei/dsh-java-deploy/tree/d764cb574335fd9b8ccb3a8fac38ae870f50ea0f) | `d764cb5` | Docker Compose、插件构建与安装 |

三个公开仓库没有 `src/test`，也没有独立 Maven CI。它们可以证明接入形态和代码边界，不能单独证明
生产质量或完整行为。

### 2.3 成熟参照

- 原始 DeepSeek Harness/Cordis 固定源码：证明 Scope/effect、Host/client runner、UI renderer/slot 和
  插件组合；固定版本与位置见[架构来源审计](2026-09-13-architecture-source-audit.md)。
- [VS Code Extension Host](https://code.visualstudio.com/api/advanced-topics/extension-host)：证明扩展可按
  local/web/remote 运行位置隔离。
- [Grafana App Plugin](https://grafana.com/developers/plugin-tools/key-concepts/anatomy-of-a-plugin)：证明一个
  逻辑插件可组合前端、扩展点与后端 facet。
- [Eclipse Theia 扩展模型](https://theia-ide.org/docs/extensions/)：证明运行时插件和编译期扩展需要区分。

后三项只作为浮动的设计灵感；Fibra 的唯一控制面、跨 facet `ChangeSet`、准入和排空仍须由本项目自行
定义与验证。

## 3. DSH Java 架构与能力地图

### 3.1 六边形/DDD 分层

| 层 | 官方职责 | 评价 |
|---|---|---|
| App | Spring Boot 启动、装配、Profile、静态控制台 | composition root 清晰 |
| Trigger | REST/SSE、鉴权、DTO 转换 | 协议与业务分离正确，E2E 测试仍缺 |
| API/Case | Facade、CQRS、策略树/责任链 | 适合产品用例；简单 CRUD 不必机械套层 |
| Domain | ReAct、Session、Tool、Task、Approval 等 | 业务地图完整；包数量不等于合理性 |
| Types | Java 插件 SPI | 轻量发布边界值得借鉴 |
| Plugins | Java JAR、Node sidecar、MCP | 多来源统一进入工具链方向正确 |
| Infrastructure | MyBatis、LLM、Shell、Node/MCP adapter | 端口适配合理，隔离与持久化成熟度不一 |

官网将能力组织为 Agent、Session、Tool、Task、Plugin、LLM、Workflow、Subagent、Goal、Plan、Skill、
Schedule、Credentials、Sandbox、Terminal、Storage 等领域。最重要的拆分原则是“变化原因不同才分开”，
不是每个名词都建 Maven module。

### 3.2 ReAct、Inbox 与模型边界

官网说明 ReAct 使用 `kick → turn → step` 三级驱动，单 turn 最多 50 step，输出截断最多受控续写 4 次；
取消采用协作式检查。Inbox 区分当前 step 与下一 turn 输入，避免工具结果被误当作新用户回合。

值得采用：

- Turn、Step、Tool Call、Tool Result 和取消原因都成为显式状态；
- Domain 只依赖模型流端口，不绑定具体 HTTP SDK；
- 工具结果回填后继续当前 turn，不重新消费用户 Inbox；
- model capability 应由 provider adapter 表达。

需要优于公开实现：

- 取消必须贯穿模型请求、工具、子进程、MCP 和 Scope 排空，不能只设置布尔标志；
- 线程池必须有界并归属生命周期；
- token 预算不能长期依赖字符数粗估；
- temperature、tool choice、reasoning、多模态等应能力协商，不能假设所有 OpenAI-compatible 网关一致。

### 3.3 会话事实、上下文与 UI 投影

官网区分 `SessionLog`、持久化事件、`SurfaceProjector`、LLM 上下文和 UI 聊天消息，并用
`SurfaceOp.Append/Replace` 支持压缩摘要遮蔽历史。这是比“保存最终聊天数组”更好的方向：

- 原始事件用于恢复与审计；
- 模型上下文保留 Tool 与系统状态；
- UI 可以隐藏噪声；
- 压缩新增摘要和遮蔽范围，不删除原始事实。

最优实现还需要：

- `(sessionId, seq)` 单调序号、expected revision 和幂等追加；
- 对用户输入、审批、工具副作用、工具结果和 Turn 终态设置 durable barrier；
- 非关键 token chunk 可异步批量写，但 `done` 之前必须知道持久化水位；
- 断线按游标补读，快照用于对账而不是掩盖丢事件；
- 增量投影索引，避免长会话反复全量 O(n²) 派生；
- 模型、Skill、Prompt、Tool schema 版本进入事件上下文，保证历史可解释。

### 3.4 SSE 协议

官网序列为：

```text
meta → (reasoning | chunk)* → (step_break → tool_result)* → finish → done
```

`step_break` 把文本与工具卡片分开，`done` 携带完整投影做最终对账。上层产品应吸收“实时帧 + 对账态”，
但补上 `streamSeq`、`sessionRevision`、`turnId`、`stepId`、断线补读、审批等待、取消和错误分类。CLI、
Desktop 与 SDK 必须消费同一事实协议，而不是各自推断状态。

### 3.5 统一工具执行与 Hook

官网明确所有内置、Java、Node、MCP 工具经过 `ToolRegistry` 与 `ToolCallExecutor`，统一执行：

```text
schema/参数 → PRE Hook → 运行期审批 → execute → POST Hook → ToolResult 事件
```

工具命名空间为内置名、`plugin__<pluginId>__<toolName>`、`mcp__<serverName>__<toolName>`。外部展示可用
前缀，内部授权和路由应使用结构化 `ToolId`，避免字符串拼接成为安全边界。

最优工具链需要：

- 每个调用携带主体、委托范围、session/agent/task Scope、deadline、cancellation 和幂等键；
- PRE policy 与运行期审批 fail-closed；
- 并发资格可按工具和资源键决定，执行限流；
- 并发完成可乱序，但进入模型上下文按原 tool-call slot 确定性提交；
- 未启动调用在取消后记为 skipped，已启动调用必须等待真实终态与资源排空；
- Hook 阻止、审批拒绝、未知工具、超时、取消、执行失败和输出编码失败使用稳定错误码；
- 任何直接工具、Skill、Workflow、Schedule 或 Subagent 路径都不可绕过。

Hook 的顺序还要按能力细分：如果 PRE Hook 能改写参数，应先得到最终参数，再执行权限和审批；不能先批准
旧参数，再运行 Hook 把它改成更危险的动作。POST Hook 或审计写入失败也不能把已经发生的外部副作用伪装成
“未执行”，更不能因此自动重试；结果应区分业务动作结果与观察/审计失败。

### 3.6 提交期与运行期治理

官网第 11 章把提交期 Permission/Approval 与运行期 Approval Gate 分开，这是正确的职责划分；该章同时
描述其对应版本的默认装配可能让运行期 gate 为 allow-all，不能据此推定其他发行版本。最优实现必须在
唯一工具入口做逐次判断：broker 缺失、审批超时、
断线、策略变化或状态不明时默认拒绝。批准应绑定规范化参数摘要、主体、session、policy revision、有效期
和调用次数，不能把“会话曾批准 shell”当永久权限。

### 3.7 Java、Node 与 MCP

Java 插件通过 `META-INF/plugin.yaml` 和 ServiceLoader 注册入口，只依赖轻量 Types；PluginContext 提供
配置、Tool、Prompt、Hook、事件与 disposer。Node 以 sidecar JSON-RPC 运行；MCP 支持 stdio、SSE 与
streamable HTTP，并适配成统一工具。

值得采用的是轻量 SDK 和多来源统一能力面。更优边界是：

- Fibra Java/Node 是制品运行时，统一走 `PluginRuntimeAdapter`；
- MCP 是上层产品连接的外部能力，不成为第三个 Fibra runtime；
- Java plugin 默认视为可信进程内扩展，非可信代码放独立进程/容器/远端 sandbox；
- manifest 是唯一入口真源，兼容格式只在安装期转换；
- configure/register 私下完成，验证通过后原子发布；
- 停用先撤销新准入、等待调用和 Scope，再释放进程/ClassLoader。

### 3.8 Goal、Plan、Skill、Schedule、Workflow 与 Subagent

官网为长程能力提供了清晰业务词汇：Goal 保存长期状态和轮次预算；PlanMode 控制执行前规划；Skill 通过
统一发现与 rank 仲裁；Schedule 表达 AFTER/AT/EVERY；Workflow 通过执行端口运行；Subagent 通过 provider
区分 spawn、fork、进程外 CLI 与 ACP；Credential 使用引用而非在参数中散落明文。

公开文档也诚实说明边界：Schedule 缺真正调度驱动、持久化、幂等和重试；本地 Workflow 不是安全沙箱；
工作流 SSE 主要等待终态；`run_in_background` 仍是预留语义；Fork 上下文继承尚未完全闭环。

正确演进顺序是先稳定单 Agent、Session 事实、Tool 治理与取消，再实现 Goal/Plan/Skill，最后进入
Workflow/Subagent/Schedule。不能以接口、包名或 UI 已存在宣称后台能力完成。

## 4. 公开业务案例分析

### 4.1 MySQL 助手

公开工程把数据库控制台与 Harness 插件拆成两个制品。真实 JDBC 凭据留在控制台，插件通过 HTTP 暴露
连接列表、只读查询、EXPLAIN、性能快照和 SQL 风险审计五个工具。

应借鉴：

- 业务服务是事实源，插件是语义和治理适配器；
- 模型只取得任务所需的最小工具；
- 提示词、工具最小权限和服务端校验形成多层边界；
- 查询结果用 `columns + rows`，避免 JOIN 同名列在 Map 中丢失。

应优化：

- [SQL 只读判定](https://github.com/fuzhengwei/dsh-java-mysql/blob/4084ac7eaabd489662d8e6f296e9764737e2cdad/dsh-java-mysql-app/src/main/java/cn/xiaofuge/dsh/mysql/service/QueryService.java#L223)
  不能只看首关键字，应使用独立只读账号、服务端授权、查询预算和 AST/网关策略；
- [插件端点覆盖](https://github.com/fuzhengwei/dsh-java-mysql/blob/4084ac7eaabd489662d8e6f296e9764737e2cdad/dsh-java-mysql-plugin/src/main/java/cn/xiaofuge/dsh/mysql/plugin/DshMysqlPlugin.java#L71)、
  CredentialRef 和权限来自管理员配置，不由模型参数决定；
- 并发安全声明必须与连接池、事务和下游客户端真实能力一致；
- 凭据密文、密钥、文件权限、容器卷和备份要统一设计。

### 4.2 商城客服

公开工程把商品、购物车、订单和物流事实留在商城服务，客服插件只做查询工具与 Harness 适配。这种边界
比让插件直连业务数据库更优。

应借鉴：

- Tool schema 同时描述参数语义和模型行为预期；
- 工具调用产生结构化审计；
- 插件地址与服务身份通过配置注入；
- 对事实性问题加入“先查工具再回答”的确定性提示。

公开源码也暴露了身份契约不匹配风险：
[插件以服务令牌调用订单接口并传 customerId](https://github.com/fuzhengwei/2d-weekend-mall/blob/1d1edb28a2ad97681e104d0d06be44adda98fa24/mall-agent-plugin/src/main/java/cn/xiaofuge/mall/assistant/QueryOrdersTool.java#L32)，
[认证过滤器的服务令牌分支不设置当前用户](https://github.com/fuzhengwei/2d-weekend-mall/blob/1d1edb28a2ad97681e104d0d06be44adda98fa24/mall-app/src/main/java/cn/xiaofuge/mall/api/AuthenticationFilter.java#L41)，
而[订单 Controller 依赖请求属性中的当前用户](https://github.com/fuzhengwei/2d-weekend-mall/blob/1d1edb28a2ad97681e104d0d06be44adda98fa24/mall-app/src/main/java/cn/xiaofuge/mall/api/MallController.java#L97)。
即使接通参数，服务令牌也不能自动证明它有权代表任意 customerId。最优方案是
由受信调用上下文携带主体和委托范围，在业务服务端做行级授权，向模型返回最小字段；订单中的手机号、
地址等 PII 不应直接拼入 prompt。

### 4.3 部署

公开 Compose 将 Harness、MySQL Studio 和商城放在 bridge 网络，外部端口绑定 loopback，用健康检查与
容器 DNS 协调启动，并说明“容器内 localhost 不是宿主”。这类地址矩阵和真实三服务闭环值得 Fibra 分发
文档借鉴。

部署脚本仍有演示边界：
[插件版本与 sourcePath 硬编码](https://github.com/fuzhengwei/dsh-java-deploy/blob/d764cb574335fd9b8ccb3a8fac38ae870f50ea0f/scripts/install-plugins.sh#L49)、
默认令牌、强制删除旧容器。生产路径应从制品元数据生成
安装请求，校验 digest、API/ABI 与 expected revision，并区分首次演示、无损升级和灾难恢复。

## 5. 最优实现方式横向比较

下表采用定性判断，避免把“不属于 Fibra 底座职责”误写成低分，也避免把尚未实现的 client runtime 算入
Fibra 当前能力。

| 维度 | DSH Java 公开实现 | 原始 DSH/Cordis | Fibra 当前 | 最优目标 |
|---|---|---|---|---|
| 通用运行时边界 | Agent 产品与宿主一体 | 通用插件语义强 | 边界最清晰 | Fibra 通用内核，上层产品独立 |
| 唯一 desired/observed 真源 | 有登记/运行对账，控制面较产品化 | Cordis 组合强 | Engine/PublishedView 最完整 | 单 Engine/Registry，客户端只持投影 |
| 局部动态更新 | 更偏运行管理 | Fiber/effect 语义成熟 | 受影响闭包与排空最完整 | prepare + 受影响闭包切换 + 排空 |
| 资源所有权与清理 | Context/disposer 易用 | Scope/effect 最成熟 | Scope/effect + 调用租约 | Scope/effect 统一所有权，SDK 只做薄封装 |
| Java 插件作者体验 | 轻量 Types 和案例最好 | 非 Java 主场 | 契约强，产品 SDK 尚缺 | typed Harness SDK 封装 Fibra Scope |
| Node 宿主插件 | sidecar/MCP 已形成产品入口 | 生态与组合最强 | Node runtime 已有强契约 | 保留 Fibra runtime，补产品目录和治理 |
| Client/UI 插件 | 以静态 Web 控制台为主 | host/client 插件化最成熟 | 产品 client runtime 尚未实现 | 逻辑插件多 facet、按执行域装载 |
| 工具执行与治理 | 统一执行入口最清晰 | 产品插件组合成熟 | 只有通用调用与排空，不是产品审批 | 上层唯一 Pipeline + Fibra 调用排空 |
| 会话事件与 UI 投影 | 公开模型完整，也披露性能/口径缺口 | 事件与前端插件成熟 | 不属于 Fibra core，产品尚未实现 | 上层 durable journal + 单一增量投影 |
| 安全隔离 | ClassLoader/本地策略，公开承认边界 | 仍需外部隔离 | 资源所有权强，安全沙箱后置 | 权限策略与 OS/容器/远端隔离分层 |
| 部署与上手 | 单 JAR/Web/Compose 最友好 | Node 产品体验成熟 | 离线发行与仓外门禁强 | 单命令本地体验 + 严格 server profile |
| 可验证性 | 教程和案例强，公开案例测试弱 | 固定源码可对拍 | 契约与发行门禁最强 | 固定协议、真实场景、故障与发行门禁 |

### 5.1 架构层比较

- 扩展性：原始 DSH/Cordis 的插件化最彻底；Fibra 在 Java/Node 制品、依赖图和动态一致性更强。最优是
  Fibra 管 Host 事实，产品插件按 host/client facet 扩展，不把 UI 与 Agent 能力硬编码进内核。
- 一致性：Fibra 的长期域、完整目标、PublishedView revision 和调用租约优于全局可变 Registry。应保留，
  并把 Session/Tool 产品事实接到这一准入面。
- 安全：三者都不能只靠进程内边界。[VS Code 运行时安全说明](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)
  也明确本地 Extension Host 默认拥有与宿主相同的 OS 权限；Grafana 的受管后端子进程主要隔离崩溃和
  内存，不自动完成文件/网络授权。最优是把身份/权限/审批与执行隔离分开：前者决定能否做，后者限制
  即使获准或失陷后能做多少。
- 生命周期：Cordis/Fibra 的 Scope/effect 优于多套 `onStop/close/unregister`。产品 SDK 应提升易用性，
  但不增加第二清理协议。
- 复杂度：DSH Java 的 DDD 地图易学，但容易机械分层；Fibra 的运行时契约复杂但有真实一致性收益。最优
  是复杂性只放在需要一致性和所有权的边界，纯 DTO、mapper、无状态策略保持简单。
- UI 扩展：首版受信 React 插件可以与 renderer 同域以降低开发成本；不可信 UI 再使用 iframe、独立
  renderer 或更窄协议。[Theia 扩展文档](https://theia-ide.org/docs/extensions/)当前更推荐 VS Code
  extension 或 build-time Theia extension，而非继续扩张 Theia-specific 前端插件，说明“运行时前端插件”
  不是任何场景下都天然最优。

### 5.2 业务层比较

- 场景贴合：DSH Java 已有 Web 控制台、MySQL 和商城案例，能更快验证产品价值；Fibra 需要上层产品才能
  达到同等用户闭环。
- 运营成本：Fibra 的不可变制品、统一 Registry 和局部更新更适合长期运行；DSH Java 的单体体验更适合
  本地学习与首次部署。最优产品同时提供单命令 standalone 与严格 server profile。
- 演进空间：原始 DSH 的 host/client 全插件组合最强；Fibra 已规划逻辑插件多 facet，可以吸收该方向。
- 治理：DSH Java 的提交期/运行期概念完整；官网第 11 章描述的版本存在默认装配缺口。最优是把运行期
  gate 固定在不可绕过的 Pipeline，并由策略插件扩展。
- 生态：双格式兼容能快速接入，但长期成本高。最优是安装期 importer + 单一 canonical artifact，运行时
  不保留兼容分支。

### 5.3 底层机制逐项对比

两者并不处于同一抽象层。Fibra 是通用插件宿主与动态一致性内核，关心“制品和配置如何安全变成可调用
能力”；DSH Java 是完整 Agent 产品运行时，关心“模型如何经过会话、工具和治理完成任务”。因此，DSH
Java 的 `ReactLoopAgent`、`SessionLog`、`ToolCallExecutor` 不应与 Fibra `RuntimeDomain` 一一替换；更准确
的关系是前者作为产品插件运行在后者之上。

下表中的 DSH Java 结论只表示官网公开的模型与边界，不把教程中的“应当”自动视为所有版本都已实现。

| 底层维度 | DSH Java 公开模型 | Fibra vNext | 哪种更优及采用结论 |
|---|---|---|---|
| 抽象层 | Agent、Session、Tool、LLM、Plugin、MCP 与 Web 组成产品运行时 | 不定义 Agent 业务，只提供 Java/Node 插件、服务、事件、资源和调用底座 | 分层最优：Agent 语义留在产品层，Fibra core 不吸收 27 个业务域 |
| 执行内核 | `kick → turn → step` 驱动 ReAct；工具结果回填当前 turn | 单生命周期写入 lane 驱动插件挂载、服务、事件、effect 与关闭 | 不互斥：ReAct 是业务状态机，Fibra lane 是宿主一致性机制 |
| 状态真源 | `SessionLog` 是会话事实源；插件安装、绑定、目录另有产品状态 | artifact、desired、observed、`PublishedView` 分离且由单 Engine 收敛 | 部署真源采用 Fibra；会话真源采用 DSH Java 的追加事件思路 |
| 运行域 | 以 Spring 应用、领域服务、Registry 和插件运行管理组成单个产品进程 | 一个长期 `RuntimeDomain` 内按实例差量协调，外层只能看不可变发布视图 | 长期运行和多插件局部变化采用 Fibra，避免产品 Registry 成为第二控制面 |
| 依赖与所有权 | Maven/六边形依赖约束宿主；插件通过 SPI、Context 和 Registry 接入 | 配置条目树、制品依赖图、动态 Service 图、Scope 所有权树分开建模 | Fibra 更完整；产品只把业务关系编译为 desired graph，不复建生命周期图 |
| 生命周期 | `configure/onStart/onStop`、Context 注册与 disposer；官网要求停止时逆序清理 | 所有注册归属 Scope，注册和逆操作同 lane；父子资源递归、幂等、可等待关闭 | 以 Fibra Scope 为唯一所有权协议；产品 SDK 可封装回调但不能增加第二套清理语义 |
| 热更新 | 官网定义停止旧实例、关闭旧 ClassLoader、再用新 ClassLoader 启动 | 先 prepare，计算受影响反向闭包；保存完整目标后撤准入、排空、清理并协调新态 | Fibra 更适合生产热更新；不采用无预检、无闭包、无排空的直接重启 |
| 失败与恢复 | 会话可回放，插件有安装/激活/运行状态；公开资料未给出跨插件变更事务保证 | 区分 prepare 失败、目标保存、协调失败和回收失败；重启按已保存目标收敛 | 部署恢复采用 Fibra；会话恢复采用产品事件日志；两类 revision 不混用 |
| 调用准入 | 所有工具进入 Registry 与 `ToolCallExecutor`；官网第 11 章描述的版本默认可能未启用运行期审批 | `PublishedRuntime` 同时检查 expected view revision、注册身份和开放状态，再登记调用租约 | 两层都保留：Fibra 防止旧路由调用，产品 Pipeline 负责身份、权限、审批和审计 |
| 并发与确定性 | 工具可按安全声明并发，ReAct 负责按工具结果继续推理 | Reactor/cancellation、调用 Scope 和受管资源负责取消、清理、排空与终态 | 产品决定业务并发和模型观察顺序；Fibra保证资源终态，不能只取消 `Future` |
| 事件模型 | 会话事件只增不改，`Append/Replace` 投影模型上下文和 UI；公开文档指出长会话全量派生可能为 O(n²) | Runtime 事件表达插件、服务、贡献和诊断，不承载 Agent 会话内容 | 事件域分开：禁止把 token/chunk 下沉 core；产品采用 durable journal 与增量投影 |
| Java 隔离 | 每插件 `URLClassLoader`，SPI 由父加载器共享；官网明确不是安全沙箱 | 每制品独立 ClassSpace，显式依赖委派；依赖变化替换反向闭包并等待旧引用释放 | Fibra 的依赖与回收边界更强；两者对不可信代码都必须升级到进程/容器/远端隔离 |
| Node/MCP | Node 是 sidecar；MCP 统一适配为 Tool | Node 是与 Java 对等的受管 runtime；MCP 不属于 core runtime | Node 生命周期归 Fibra；MCP 是产品外部 provider，经同一 Tool Pipeline 治理 |
| 持久化 | H2/MySQL 保存 Session、事件、任务和插件产品状态 | Engine 保存 canonical target 并发布 observed；Session 存储由产品层选择 | 不合并数据库模型；部署目标与会话事实分别版本化、分别恢复和对账 |
| 插件作者体验 | 轻量 `types`、`PluginContext`、Tool schema 和真实业务案例更直接 | 底层契约严谨，但 Agent 产品 SDK 和目录尚未形成同等体验 | DSH Java 更优；Fibra 上层应提供 typed SDK，内部仍映射到 Scope/Contribution |
| 安全 | 有提交期权限、运行期审批、沙箱档位；官网第 11 章公开其对应版本的运行期装配缺口 | core 只保证路由、资源和关闭边界，不假装提供业务授权 | 产品安全采用 DSH 的统一入口但必须 fail-closed；Fibra 只提供不可绕过的调用承载面 |
| 可验证性 | 教程、Web、MySQL/Mall 案例完整，公开案例缺独立 Maven 测试 | 契约、真实 JAR/Node、发行和故障门禁更严格 | Fibra 门禁作为底线，再补 DSH 类真实业务 E2E，不能用单元测试或演示页面互相替代 |

从底层宿主角度看，Fibra 的优势不是“类更多”，而是把五个容易混淆的事实分开：声明的目标、实际运行
状态、对外发布状态、资源所有权和服务依赖。DSH Java 的优势则是把模型、会话、工具、审批和业务 adapter
串成了用户可体验的完整链路。前者降低长期运行和动态变更风险，后者缩短 Agent 产品落地路径。

#### 5.3.1 同一次工具调用如何穿过两层

```text
用户/Agent
   │  sessionId、主体、委托范围、deadline、cancellation
   ▼
Agent 产品 ToolInvocationPipeline
   参数规范化 → policy → approval → Tool 路由 → 结果/事件
   │
   │  结构化 ContributionId + expected view revision
   ▼
Fibra PublishedRuntime
   校验 revision → 按 registration 取得调用租约 → 二次复核发布状态 → 建 invocation Scope
   │
   ▼
Java handler 或 Node sidecar
   │
   ▼
业务系统再次授权并执行副作用
   │
   └─ 真实终态 → Fibra 排空/清理 → 产品事件持久化 → 模型与 UI 投影
```

这条链不能压成一个万能 Registry：产品层审批解决“这个主体能不能做”，Fibra 准入解决“这个旧路由现在
还能不能调用”，业务系统授权解决“目标数据是否允许该主体访问”。三者失败语义、revision 和责任人都不同。

#### 5.3.2 Fibra 需要调整与不需要调整的底层边界

| 判断 | 内容 |
|---|---|
| 不需要改 core | 不把 `ReactLoopAgent`、`SessionLog`、Tool DTO、审批状态机、MCP 或 Workflow 下沉 Fibra |
| 需要补产品层 | typed Agent 插件 SDK、唯一 Tool Pipeline、durable Session journal、CredentialRef、身份委托与 provider readiness |
| 需要验证接缝 | 产品插件的所有注册是否归 Scope；CLI/Desktop/SDK 是否只经 `PublishedRuntime`；取消是否到达模型、工具、sidecar 和业务请求 |
| 可由测量触发调整 | 全局 view revision 若在真实负载中造成大量无关冲突，再考虑 route/policy revision；没有数据前不削弱准入保证 |
| 未来可能重构 | 若另一方案以更低复杂度证明同等的局部更新、失败可见、调用排空和恢复能力，应修改权威 spec，而不是保护现有类结构 |

#### 5.3.3 Fibra 当前源码完成度

以下状态核对的是 2026-09-14 工作树源码和既有测试，不把权威设计中的未来目标算作已实现，也不表示本次
重新执行了全部测试。

| 能力 | 当前状态 | 源码与测试依据 |
|---|---|---|
| `RuntimeDomain/Scope/Effect` | 已实现 | [`DefaultRuntimeDomain`](../../../fibra-core/src/main/java/com/sstlfsj/fibra/internal/DefaultRuntimeDomain.java)、[`ResourceDrain`](../../../fibra-core/src/main/java/com/sstlfsj/fibra/internal/ResourceDrain.java) 及 [`RuntimeDomainIsolationTest`](../../../fibra-core/src/test/java/com/sstlfsj/fibra/runtime/RuntimeDomainIsolationTest.java) |
| Engine 变更阶段与持久目标 | 已实现 | [`FibraEngine`](../../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java) 先 prepare、保存完整目标、reconcile、retire；[`EnginePersistenceBoundaryTest`](../../../fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EnginePersistenceBoundaryTest.java) 覆盖保存不确定边界 |
| 发布快照、revision、注册身份与调用租约 | 已实现 | [`PublishedRuntime`](../../../fibra-engine/src/main/java/com/sstlfsj/fibra/engine/PublishedRuntime.java)、[`ContributionDirectory`](../../../fibra-bridge/src/main/java/com/sstlfsj/fibra/bridge/ContributionDirectory.java) 与 [`PublishedRuntimeLeaseTest`](../../../fibra-engine/src/test/java/com/sstlfsj/fibra/engine/PublishedRuntimeLeaseTest.java) |
| Java 制品隔离与依赖闭包更新 | 已实现 | [`JavaPluginRuntimeAdapter`](../../../fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapter.java) 计算反向依赖闭包、准备新 loader 并按真实依赖逆序关闭；对应 [`JavaPluginRuntimeAdapterTest`](../../../fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapterTest.java) |
| Node sidecar、协议取消与受管进程终态 | 已实现 | [`NodePluginRuntimeAdapter`](../../../fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodePluginRuntimeAdapter.java)、[`NodeSidecar`](../../../fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeSidecar.java) 与 [`NodeSidecarTest`](../../../fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodeSidecarTest.java) |
| 通用 Tool ABI 与基础工具插件 | 已实现 | [`ToolContributions`](../../../fibra-plugins/fibra-tool-api/src/main/java/com/sstlfsj/fibra/plugins/tool/ToolContributions.java) 定义跨 runtime contribution；已有 fs、search、shell、storage 插件和严格 codec |
| 最小 client runtime、renderer/slot 与全栈探针 | 未实现 | 属于现有产品 P0 控制切片，不是 Session 之后的能力，也不是 Fibra core |
| Agent、LLM、会话日志、审批与凭据 | 未实现 | 当前业务源码没有对应产品模块；分别属于现有产品 P1/P2，不能把 CLI session 或通用 Tool ABI 宣称为 Agent Session/Tool Pipeline |
| MCP、Skill、Goal/Plan、Workflow/Subagent | 未实现 | 属于现有产品 P4–P8，不是当前 Fibra core 能力；完整会话界面属于 P3 |

源码状态因此支持“底层保持、上层借鉴”的路线，但也限制了结论范围：Fibra 当前在动态插件宿主的控制面和
生命周期上更完整，不代表它已经拥有 DSH Java 的 Agent 产品能力。上层产品进入 P0/P1 后应优先用现有
Tool ABI 和 `PublishedRuntime` 做真实 Model → Agent → Tool 纵向切片，而不是重写已经落地的装载、发布和
排空机制；在此之前，Fibra 按权威架构第 11.9 节完成独立的 `0.5.x` 底座打磨。

### 5.4 推荐的最优目标架构

```text
                        canonical profile / data namespace
                                      │
                         Fibra Registry + Engine
                artifact / desired / observed / diagnostics
                                      │
             同一 desired graph 与长期 RuntimeDomain 逻辑归属
              ┌───────────────────────┼──────────────────────┐
              │                       │                      │
      built-in/Java product      Node runtime adapter   client runtime adapter
      plugin instances           → sidecar facet        → renderer facet
      Credential/Session/
      Tool/Approval/Model/Agent
              │                       │                      │
              └──────────── Contribution/Service/Event ──────┘
                                      │
                              PublishedRuntime
                        宿主调用、准入与不可变投影边界
                                      │
                    CLI / Desktop / SDK 协议投影
```

MCP、Skill、Goal、Workflow 与 Subagent 也是上层产品插件或由其管理的外部能力；它们进入同一产品
desired graph 和 RuntimeDomain 语义，不在 `PublishedRuntime` 外形成第二套运行容器。

关键不变量：

1. 一个产品命名空间只有一个 artifact/desired/observed 真源；
2. client runtime 只有执行状态，没有第二 desired state；
3. 所有长期资源归属 Scope，清理和调用排空可验证；
4. 所有工具调用经过同一 Pipeline，身份不是模型输入；
5. Session event log 是产品事实源，UI 只是可重建投影；
6. Java/Node/client facet 共享一个逻辑插件版本和 ChangeSet；
7. 非可信代码不在宿主 ClassLoader 中执行；
8. 兼容格式只在安装期转 canonical artifact；
9. Stub、未配置或握手失败能力不发布为 ready；
10. 任何“完成”都由真实多插件、断线、取消、升级、重启和发行测试证明。
11. 外部副作用没有下游幂等或查询能力时，不承诺 exactly-once；崩溃窗口恢复为“结果未知”，通过查询或
    人工确认收敛，不能盲目重试。

## 6. Fibra 是否应调整

### 6.1 应保持的设计

| 当前设计 | 判断 | 原因 |
|---|---|---|
| 长期 RuntimeDomain | 保持 | 比整代切换或全局 Registry 更好地保留无关实例与调用 |
| 配置条目树与完整目标 | 保持 | 防止 YAML、数据库、watcher、HTTP 各自成为真源 |
| 实例差量更新 | 保持 | 只处理受影响闭包，降低中断与回滚复杂度 |
| PublishedRuntime revision | 保持 | 状态、贡献、诊断和调用准入一致 |
| Java/Node 共用 runtime port | 保持 | Engine 不泄漏 ClassLoader/Process |
| 单一 Fibra manifest | 保持 | 避免入口与身份双真源 |
| Scope/Effect 清理 | 保持 | 优于分散 onStop、close、unregister |
| Agent 业务留在上层产品 | 保持 | 避免通用底座被单一产品模型绑死 |

这些结论来自横向比较，不是因为代码已经实现。若未来出现更强证据，仍可改变，但必须说明如何保持或
替换现有一致性保证。

当前 `PublishedRuntime` 以全局 view revision 和 registration identity 双重准入，可靠性优先，但任何
无关 view 变化都可能使尚未准入的调用 stale。现在不应为减少重试削弱该保证；先记录冲突率和无关更新
比例。只有真实负载证明它成为瓶颈，再评估 route revision + policy revision 的细粒度准入，并保持插件
替换、权限变化和关闭围栏不退化。

### 6.2 应增强的 Fibra/产品接缝

相对代价只表示改动面，不是工期承诺：小为单插件/契约补强，中为跨模块 API 或协议，大为事实模型、
持久化或多执行域变更。

| 引入时点 | 建议 | 落点 | 相对代价 | 验收 |
|---|---|---|---|---|
| Tool 首次进入产品时 | 唯一 ToolInvocationPipeline | 上层 Tool 插件 | 中：产品 API/调用协议 | Java/Node/MCP/内置/Skill 路径全链一致 |
| 高风险 Tool 可用前 | 运行期审批 fail-closed | Approval 插件 | 中：策略与交互协议 | broker 缺失、超时、断线均拒绝副作用 |
| 首个业务 adapter 时 | 可信调用身份与委托范围 | InvocationContext/业务 adapter | 中：身份契约 | customerId/baseUrl/credential 不可由模型伪造 |
| Session 阶段 | durable barrier | SessionStore | 大：事实模型与恢复 | 成功、取消、工具结果、审批在崩溃恢复后可对账 |
| 首个外部 provider 时 | CredentialRef 与结构化脱敏 | Credential 插件 | 中：API 与存储 | 明文不进入 prompt、事件、历史、日志、诊断、UI |
| 能力目录首次发布时 | readiness 决定发布 | Catalog/PublishedRuntime adapter | 小：发布准入 | 未配置或失败 provider 不进入模型 schema |
| Session 接管副作用时 | 外部副作用不确定态 | Session/Tool pipeline | 大：事件与恢复语义 | 崩溃后不盲重试；可查询、幂等或人工确认后收敛 |
| 并发 Tool 出现时 | 并发执行与确定性提交 | Tool pipeline | 中：调度器与事件顺序 | 混合串并行、乱序完成、取消仍按 slot 收敛 |
| 第三方插件开放前 | typed Harness PluginContext | 产品 SDK | 中：公开 API | 注册全部归属 Fibra Scope，无第二清理协议 |
| 第二种模型能力出现时 | provider capability 协商 | Model API | 中：provider SPI | 不支持能力在请求前失败 |
| Desktop 事件闭环时 | 统一事件传输 | 产品 transport | 大：协议与客户端状态 | CLI/Desktop/SDK 共享游标补读与 done 定义 |
| 插件 SDK 发布前 | 兼容测试套件 | 产品 SDK/发行 | 中：夹具与发行门禁 | 安装、配置、启停、升级、重装、泄漏全链 |
| MCP/Subagent/Workflow 引入时 | 外部 provider 启动检查 | 对应产品插件 | 小到中：探针/握手 | 命令、版本、握手、权限在发布能力前验证 |

### 6.3 只在明确生态需求出现时调整

不建议现在承诺 DSH Java 插件二进制兼容。双元数据、生命周期、工具 DTO、Hook、Prompt、事件和配置都需要
适配，直接兼容会形成长期 runtime 分支。

若真实用户证明生态价值，应先区分两种路线：源码迁移后重新编译最简单；保留原二进制则必须提供版本化
SPI adapter/wrapper。安装期 importer 只解决元数据和包格式，不能自动弥合生命周期、DTO、Hook、Prompt、
事件和配置语义。即使 adapter 不进入 Engine 核心分支，它仍是需要独立版本、维护和安全审核的兼容产品。
两条路线都应固定 digest、验证 API/ABI，并转换为 Fibra canonical artifact；运行时只消费 Fibra
descriptor。验收必须跑通公开 MySQL、Mall 和样例插件的安装、授权、工具、事件、配置更新、停用与重装，
不能只验证类能加载。

### 6.4 明确不采用

- 不按 27/28 个 domain 名机械拆 Maven module；
- 不把 Controller、DTO、Factory 层数当作 DDD 质量指标；
- 不恢复 manifest + ServiceLoader 双入口；
- 不采用全局可变 Tool/Hook/Prompt Registry 作为发布真源；
- 不采用无预检、无受影响闭包、无准入排空的直接 `stop → start` 热更新；
- 不把 ClassLoader、路径前缀、提示词或命令黑名单当安全沙箱；
- 不让模型传 base URL、主体 id、凭据或授权范围；
- 不把 Stub、内存 Schedule、预留 background 字段称为完成；
- 不默认关闭 server 认证、开放 Actuator 或在发布物写可用密码；
- 不把 Future 超时/取消等同于远端工作和资源已停止；
- 不将 Goal、Plan、Agent、Session、MCP、Skill、Workflow 下沉 Fibra core。

### 6.5 底座补强候选与上层验证

当前目标是让 Fibra 成为后续产品可以长期依赖的稳定底座，因此不建议立即铺开 DSH Java 的全部 Agent
能力。下面是建议提交权威 spec 评审的补强候选，不创建新的产品阶段编号，也不自动重开已通过的 F1–F4
门禁。候选的采用顺序与版本边界已收敛到
[Fibra vNext 架构第 11.9 节](../specs/2026-09-07-fibra-vnext-architecture.md)；
只有发现实际缺陷或正式纳入权威 spec 的条目，才成为新的阻断条件。

| 候选 | 打磨内容 | 建议验收 |
|---|---|---|
| 生命周期正确性 | Scope/Effect 注册原子性、晚到资源、清理失败、重复关闭、调用中停用 | 故障注入覆盖每个阶段；失败不发布半成品、不提前释放资源、不影响无关实例 |
| 变更与恢复 | prepare、artifact save、target save、reconcile、retire、进程崩溃 | 每个崩溃点重启后都按保存目标收敛；未确认写关闭 mutation gate；诊断能指出阶段 |
| Java 长期运行 | 依赖闭包升级、同版本重装、线程/JDBC/ThreadLocal/日志引用、ClassLoader 回收 | 长循环后 loader、线程、文件句柄和堆占用不持续增长；关闭失败保留可复核证据 |
| Node 长期运行 | 握手、半帧、超时、取消、心跳、异常退出、子进程树、平台差异 | 已接受请求真实终结；受管进程范围静默；失败不会让旧 contribution 保持 ready |
| 调用边界 | revision、registration identity、in-flight lease、invocation Scope、取消 | stale/revoked/cleanup-failed 稳定可区分；无关更新不终止已接受调用 |
| 公共扩展面 | canonical manifest、最小 SPI、typed config、Contribution、错误码 | API 基线和仓外消费者门禁通过；业务 SDK 只封装 Scope，不泄漏 Engine 内部类型 |
| 插件契约套件 | 安装、配置、启停、升级、重装、调用中卸载、资源泄漏 | 第三方 Java/Node 插件可独立运行同一套契约测试并得到结构化报告，不承诺废弃快照兼容 |
| 运维诊断 | Engine phase、target/view revision、affected closure、in-flight、资源和清理失败 | 用户无需调试器即可判断卡在采集、准备、保存、协调、排空还是回收 |
| 安全与供应链 | digest、制品目录边界、依赖图、凭据脱敏、可信/非可信执行策略 | 受信 Java、受管 sidecar、容器/远端三档边界明确；ClassLoader 不被宣传为沙箱 |
| CI 与长稳 | 全量测试、真实 JAR/Node、可复现发行、仓外消费、短超时诊断、压力与资源曲线 | 快速失败能捕获 JVM/进程现场；长稳覆盖重复变更和并发调用；发布物与源码门禁一致 |

现有 CI 已覆盖全量构建、可复现制品和仓外消费者，是良好基础；但
[`run-ci-with-jvm-diagnostics.sh`](../../../scripts/run-ci-with-jvm-diagnostics.sh) 默认 180 秒后才采第一份
诊断，短时卡死或关闭竞态可能在采样前结束。应补短超时专用门禁，而不是只延长全量构建超时。

底座打磨仍需要一个很薄的上层纵向夹具验证扩展面：真实 Model adapter 调用一个 Java Tool 和一个 Node
Tool，经过取消与动态停用后落一条可对账事件。它是底座验收 fixture，不等于提前建设完整 Agent 产品。
只有该夹具不需要绕过 `PublishedRuntime`、不需要取得内部 `Context`、也不要求修改 Engine，才能证明
上层扩展边界已经稳定。

## 7. 推荐演进与验收

本报告不创建新的阶段编号。对应能力进入现有产品 P0–P8 时，应遵守以下依赖：

```text
P0 唯一 Host + CLI/Desktop 控制切片
                     ↓
P1 真实 Model/Agent + Tool Pipeline + 最小审批/身份
   （仅 invocation 内状态，不冒充可恢复 Session）
                     ↓
P2 durable Session journal + checkpoint + 崩溃恢复
                     ↓
P3 Desktop 事件闭环 → P4 MCP → P5 Context/Skill → P6 Goal/Plan
                     ↓
P7 Sandbox/Jobs/Terminal → P8 Workflow/Subagent/外部 adapter
```

最小验收清单：

- 重复启停、同版本重装保持幂等；prepare/保存目标前失败不改变已发布能力；目标已保存后的启动失败按
  observed failure 发布，不伪造旧版本回滚；
- configure/start 失败不发布半成品，清理失败保留诊断并继续释放其他资源；
- 无关 Java/Node/client 实例、PID、ClassLoader、effect 与调用在局部变更中保持；
- 所有工具来源经过同一身份、权限、审批、Hook、执行、事件链；
- 并发工具限流，模型观察顺序确定；
- 取消传播到模型、MCP、子进程和 Scope，并等待真实终态；
- Session 关键事件在 `done` 前越过持久化水位；
- 外部副作用在崩溃窗口恢复为明确不确定态，没有幂等保障时不自动重试；
- CLI/Desktop/SDK 断线后按游标补读并与快照对账；
- PII/secret 不进入 prompt、事件、日志、历史、诊断和 renderer state；
- 当前 JSONL Session provider 验证格式版本、尾部截断恢复、备份和归档；若以后采用关系数据库，再要求
  H2/MySQL 使用版本化 migration，而不是提前改变 P2 的存储选型；
- 真实 MySQL/Mall 类案例通过 HTTP、业务授权、工具、事件、停用和重装全链；
- standalone 一条命令可体验，server profile 默认认证、最小权限和持久卷；
- HTTP/SSE/认证、Maven CI、发行制品和仓外部署都有自动门禁。

## 8. 最终判断

DSH Java 的最佳价值是产品能力地图、教学清晰度、统一工具链和真实业务适配；原始 DSH/Cordis 的最佳
价值是彻底插件化、Scope/effect 与 host/client 组合；Fibra 的最佳价值是 Java 原生制品、唯一控制面、
长期运行域、差量更新、PublishedRuntime 与调用排空。

最优方向是把三者优势组合，而不是维护任一项目的既有形态：

> Fibra 继续负责通用运行时与一致性；上层 Agent 产品吸收 DSH Java 的产品语义和作者体验，并用
> Cordis/Fibra 的生命周期、准入、排空和多执行域机制补足生产边界。

所以“不局限已实现代码”的具体含义不是推倒已验证的不变量，而是每项设计都重新接受横向比较。当前
比较结果支持保留 Fibra 核心，同时显著提高上层产品在工具治理、事件耐久、身份、凭据、插件 SDK 和真实
业务验收方面的标准。未来若新方案能以更低复杂度提供同等或更强的一致性、隔离和可验证性，应更新权威
spec 并迁移；不能用“已经实现”作为拒绝更优方案的理由。
