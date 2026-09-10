# Fibra 运行代隔离与能力发布设计

日期：2026-09-10

状态：已确认，尚未实现。本文是
[`2026-09-07-fibra-vnext-architecture.md`](./2026-09-07-fibra-vnext-architecture.md) 的运行代发布细化，
并以这里的模型替换其中 `Engine.runtime()`、共享 `ContributionBridge` 和仅靠 service realm 隔离运行代
的部分。两文冲突时，以本文为准。

## 1. 要解决的问题

当前 vNext 工作树已经能建立候选 core Scope、候选 Java ClassSpace 和 runtime generation，也能在
ChangeSet 失败时保留旧 snapshot。但它还没有形成一个完整的运行代发布边界：

- `ServiceRegistry` 用 `GenerationRealm` 区分服务，`EventBus` 的 contract/hook 表却仍由整个
  `FibraRuntime` 共享；服务隔离不等于事件隔离。
- Node 插件在候选实例启动时直接向全局 `ContributionBridge.registerAll` 注册。候选贡献会在
  ChangeSet 发布前对宿主可见，并可能与旧代同名贡献冲突。
- `FibraEngine.runtime()` 把可变 `FibraRuntime` 交给宿主。宿主可以绕过 Engine command loop 直接
  mount、provide、on 或持有候选/旧代对象，唯一写入口只是约定，不是接口边界。
- `EngineSnapshot` 只发布状态事实，没有发布与该 revision 一致的能力路由。宿主可能看到新 snapshot，
  调用却仍落到旧贡献目录，或者反过来。
- Java/Node runtime participant 的提交、core generation 的切换和贡献目录发布不是同一个可观察切换点。

目标不是给这几处分别加 generation 字段，而是让服务、事件、贡献和调用排空共享同一个运行代所有权。

## 2. 设计假设与硬约束

本设计基于以下已确认方向：

1. Fibra 保留 Cordis 的异步 effect、依赖驱动 Fiber、调用者所有权、事件分派和关闭语义。
2. Engine command loop 与 core lifecycle lane 仍是两层唯一单写者；不为每代创建独立生命周期线程。
3. Java 和 Node 是两个一等 runtime adapter；Engine 不接触 ClassLoader、Process 或 RPC 私有句柄。
4. 宿主只通过一个已发布能力面调用插件，不直接访问 Engine 托管的 `FibraRuntime` 或完整 `Context`。
5. 候选代可以执行内部验证，但在发布前不得被宿主、活动代或其他候选代发现。
6. 发布只承诺 Fibra 内部可控事实的一致切换，不把任意插件外部副作用包装成虚假的 ACID 事务。
7. 71 项 Cordis 原始行为和 44 项 Fibra 额外回归是两组独立验收门禁，不能用新增测试数量抵扣。
8. 事件派发方式是事件契约的一部分；同名事件不能在 `emit/parallel/serial/bail/waterfall` 间随意切换。
9. `PENDING` 是合法 Fiber 状态，是否允许发布由逐 entry 的显式要求决定，不能由 Engine 全局猜测。
10. 外层宿主壳与运行域内 built-in plugin 是两类主体；前者不取得 Context，后者继续使用完整 Cordis
    Service/Event/Effect 语义。

这里的“运行域”指共享一套 Cordis 可见性的插件集合；“运行代”指 Engine 为一次候选变更建立的不可变
运行域版本。一个运行代只有一个运行域，一个 Engine 同时只有一个已发布代、最多一个正在准备的候选代
和一个正在排空的旧代；旧代在切换后不再接收新宿主调用。会发布新代的下一条 ChangeSet 必须等旧代
drain 正常完成或强制关闭结果落地后再开始；期间的 dirty signal 合并为最新目标，避免 ClassSpace、
sidecar 和资源代数无界增长。

## 3. 候选方案比较

| 方案 | 架构层取舍 | 业务层取舍 | 结论 |
|---|---|---|---|
| 共享 Runtime，所有表增加 generation/realm 过滤 | 改动表面最小，但每个查询、事件 target、订阅和未来能力都必须记得过滤；漏一处即跨代泄漏 | 更新期间容易出现旧服务、新事件、新贡献混搭，问题难诊断 | 拒绝 |
| 每个 generation 创建完整 `FibraRuntime` | 天然隔离，但每代各有 lifecycle lane，破坏 core 单写者；宿主服务、日志和关闭协调重复 | 并发更新时资源和线程倍增，跨代排空与观察更复杂 | 拒绝 |
| 单一 Runtime 内建立私有 `RuntimeDomain`，整代发布 | lifecycle lane 保持唯一；服务、事件、Scope 和贡献以同一 domain 为边界；需要一次明确的 core 重构 | 候选验证、失败保留旧代和一致切换可解释，宿主 API 更窄 | 采用 |

开源实践只作为边界参照，不照搬实现：

- [DeepSeek Cordis 行为证据](../references/2026-09-09-cordis-behavior-evidence.md)表明，它在单个
  Context/Loader 树内提供本设计需要保留的 effect、Fiber 与事件语义；其
  `Entry.update` 会在失败时恢复旧配置/插件，但没有证明任意外部副作用可回滚，也没有提供服务端双代
  原子发布，不能直接当作 generation 事务。当前 DSH `0.1.2-rc.1`（`a66e470204`）的应用启动审计要求
  enabled entry 全部 ACTIVE，而动态插件 runner 又允许合法 PENDING 并返回 `waitingFor`；这证明发布
  要求必须由场景显式表达，不能把某一种 DSH 策略固化为 Fibra 全局规则。
- IntelliJ 通过插件 ClassLoader 和依赖声明隔离类型，并在动态变更前计算目标插件集合；这支持“先验证
  目标图、再切换”，但它不是 Fibra 的持久事务或统一贡献目录。
- PF4J 提供依赖图和 ClassLoader 委派，却以可变 manager 和插件状态机为中心；若沿用该形状，会重新
  引入已决定删除的第二状态机和可变旁路。
- [cordis4j 源码对拍](../references/2026-09-11-cordis4j-design-evidence.md)显示，它的独立可关闭子域
  适合说明所有权边界，但其同步激活、串行顶层卸载、失败不可恢复和局部事件模型与本项目保真目标
  不一致。

因此，推荐方案吸收“独立域”和“目标图预检”，同时保留 Fibra 已选择的单写者、异步 Cordis 语义与
持久 ChangeSet 协调。

## 4. 推荐运行模型

### 4.1 内部对象关系

```text
FibraEngine
  ├─ Engine command loop
  ├─ FibraRuntime
  │    └─ lifecycle lane
  ├─ PublishedRuntime                  外层宿主唯一调用入口，稳定对象
  │    └─ AtomicReference<PublishedState>
  │         └─ PublishedView           同 revision 的状态、贡献、运行域诊断与 Engine 诊断
  ├─ active PublishedGeneration
  │    ├─ RuntimeDomain
  │    │    ├─ root Scope / plugin instances
  │    │    ├─ ServiceRegistry
  │    │    └─ EventBus
  │    ├─ ContributionDirectory
  │    ├─ runtime generation participants
  │    └─ invocation lease gate
  └─ candidate PublishedGeneration     publish 前仅 Engine 可见
```

`RuntimeDomain` 是 `fibra-core` 的托管抽象，不进入 `fibra-api`。由于 Engine 位于另一个 Maven 模块，
core 提供窄的公开管理句柄，使 Engine 能取得 domain root Scope、只读诊断并执行 `closeAsync()`；该句柄
不得由 Engine 返回给宿主。domain 共享 `FibraRuntime` 的 lifecycle lane、序列和日志设施，但拥有自己的
root Scope、实例表、服务注册表、事件 contract/hook 表及关闭状态。关闭 domain root 只关闭该 domain；
只有关闭 FibraRuntime 才关闭全部 domain 和 lifecycle lane。`Context` 必须绑定且只能绑定一个 domain；
service lookup、event target 和插件依赖解析都不得跨 domain。

候选代不再依靠在 service realm 中拼入 generation 字符串获得隔离。realm 只表达同一 domain 内的业务
隔离标签；generation 是更外层的容器边界。删除 `GenerationRealm` 可以避免 ClassLoader 类型和业务
realm 被内部代号耦合。

### 4.2 服务、事件和贡献隔离

- 插件提供的服务只进入所属 `RuntimeDomain.ServiceRegistry`。同名契约在不同代可以并存，在同代同
  realm 内仍执行唯一性和类型一致性检查。
- event contract 与 hook 列表属于 domain。`EventKey` 同时固定稳定名称、listener 类型和 `EventMode`；
  `emit/parallel/serial/bail/waterfall` 派发方法必须与 mode 一致，不一致直接拒绝。事件 source、target
  和 listener 必须来自同一 domain；显式跨域 target 直接拒绝，而不是静默过滤。
- 每个候选代拥有独立 `ContributionDirectory`。Node/Java 插件只向当前 Context 注入的 generation-local
  registrar 注册，不再持有或调用全局 `ContributionBridge`。
- `ContributionId(providerInstanceId, localName)` 只要求同代唯一。宿主可见身份在发布时与 generation
  revision 绑定，因此旧代与候选代不会因相同 id 在准备阶段冲突。
- 宿主提供给插件的稳定服务是显式输入，不是跨代 service lookup。Engine 在创建 domain 时把一份不可变
  host binding snapshot 导入候选代；运行中变更 host binding 必须通过 Engine command 生成新代。
  外部服务对象的生命周期仍由宿主持有，Fibra 只拥有绑定。

### 4.3 两层宿主与能力路径

“宿主”必须区分为两层：

- 外层宿主壳是 Spring、HTTP、CLI、SDK 与管理用例。它只提交 `EngineCommand`、读取 `PublishedView`、
  调用已发布 contribution；它不取得 Engine 托管的 `Context`、`Scope` 或 service/event 注册入口。
- 运行域内建宿主能力是 built-in `PluginDefinition`。Agent Loop、ToolCatalog、Session 服务等只有在确实
  需要依赖激活、动态替换、作用域所有权或确定性清理时才插件化；它们与 Java/Node 插件位于同一
  `RuntimeDomain`，继续通过 Service/Event/Effect 协作。

`ContributionDirectory` 是运行域向外层宿主或跨进程端点投影能力的边界，不替代 domain 内原生
Service/Event。外层调用由 `PublishedRuntime` 在所选 generation 内创建一次调用 Scope，并据此构造
`InvocationContext`；Scope 在返回的 Publisher 成功、失败或取消后关闭。外层只提供
`ContributionKind` 规定的 input，不得传入或持有 core `Context`。需要跨调用生存的 session/job 资源
必须由 domain 内建插件拥有，不能通过延长一次 published 调用 Scope 的寿命实现。

### 4.4 宿主唯一发布面

公开 API 删除 `FibraEngine.runtime()`，替换为稳定、只读的 `PublishedRuntime`：

```java
public record PublishedView(
    String viewRevision,
    String generationRevision,
    EngineSnapshot engine,
    ContributionSnapshot contributions,
    RuntimeDiagnostics diagnostics,
    EngineDiagnostics engineDiagnostics) {}

public interface PublishedRuntime {
    PublishedView current();
    Flux<PublishedView> views();
    <D, I, O> Mono<O> invoke(
        String expectedViewRevision,
        ContributionKind<D, I, O> kind,
        ContributionId id,
        I input);
}

public interface FibraEngine extends AutoCloseable {
    Mono<PublishedView> start();
    Mono<EngineCommandResult> submit(EngineCommand command);
    PublishedRuntime published();
    void close();
}
```

revision 名称不得混用：`viewRevision` 是每次对外可观察状态变化都递增的发布序号；
`generationRevision` 只在整代 ChangeSet 切换时变化；desired source、artifact runtime 与 contribution
directory 各自保留自己的来源 revision，但不能冒充 published view revision。旧的
`EngineSnapshot.revision` 已删除；控制面统一读取 `PublishedView.viewRevision`，整代切换只读取
`EngineSnapshot.generationRevision`。

上面是职责形状，不要求把当前 concrete `FibraEngine` 改成接口。实现阶段可以根据 Java 命名冲突微调
类型名，但以下边界不能变：

- 不返回 `FibraRuntime`、`Context`、`Scope`、裸服务对象、ClassLoader、Process、RPC channel 或
  generation-local registration。
- 宿主消费插件能力只走 `PublishedRuntime` 持有的不可变 contribution 路由；插件间 service/event 只在
  domain 内部。
- `PublishedView` 是同 view revision 的不可变值，同时包含 Engine 状态、贡献目录、运行域诊断和
  Engine 诊断；它不持有 generation 对象或 lease。禁止用分别读取这些事实的 API 制造跨 revision 组合。
- `PublishedRuntime` 是稳定路由器，不是可被长期持有的某代 Context。调用必须携带选取 contribution 时
  观察到的 `expectedViewRevision`；revision 已变化就返回明确的 stale-revision 结果，不能悄悄路由到新代。
- 每次调用取得当前 `PublishedState`，核对 revision，取得 generation lease 后再次读取原子引用。只有
  两次读取仍是同一 state 才能解析贡献并执行；否则释放 lease 后重试或报告 revision 已过期。
- published 调用在目标 generation 内创建临时调用 Scope，并由其 Context 构造正常 `InvocationContext`。
  Publisher 成功、失败或取消后必须等待异步 Scope 清理完成，再释放 generation lease；不能用
  fire-and-forget `doFinally(...subscribe())` 提前结束所有权边界。
- 整代发布通过一次 `AtomicReference` 交换同时切换 generation、EngineSnapshot、贡献路由和诊断。
  随后立即关闭旧代准入，再发出新 `PublishedView`；不得先发状态再切能力或先发 view 后关闭旧代准入。
- 旧代进入 `DRAINING` 后拒绝新 lease，已经取得 lease 的调用继续完成。全部调用排空后，才撤销贡献、
  dispose domain Scope、关闭 sidecar/ClassSpace 并 retire artifact。
- 调用在取 lease 与发布竞态时，要么在线性化点前进入旧代并被计入排空，要么重试并进入新代；不能在
  未计数的旧代上执行。
- active domain 内由 Cordis 服务变化触发的实例状态或 contribution 变化不伪装成新 generation。它们在
  同一 generationRevision 下生成新的不可变 viewRevision；expected view revision 仍保证目录选择与
  调用不撕裂。candidate domain 的同类变化只更新私有状态，绝不触发对外 view。

纯 core 嵌入仍可直接创建 `FibraRuntime` 并使用完整 Context；一旦由 Engine 托管，就只能通过
`PublishedRuntime` 和 `EngineCommand`。这不是兼容层，而是两个明确使用模式。

### 4.5 发布要求与发布顺序

实例状态、发布要求和 Engine 健康度是三个不同事实：

- `PluginInstanceState` 继续表达 `PENDING/STARTING/ACTIVE/FAILED/STOPPING/DISPOSED`。
- 编译后的每个 enabled `DesiredEntry` 必须显式携带 `PublicationRequirement`：`ACTIVE_REQUIRED` 要求
  候选实例 ACTIVE；`PENDING_ALLOWED` 接受稳定 PENDING 并在诊断中列出 `waitingFor`。配置 adapter 可以
  提供场景默认值，但进入 `DesiredGraph` 后不得缺省或靠 Engine 猜测。
- 候选实例 FAILED 默认拒绝整代发布。活动代后来出现可恢复实例失败时，Engine 发布降级诊断并交给恢复
  策略；不能仅因单实例失败就把可继续服务和可继续变更的 Engine 等同于不可用。Engine 生命周期、
  published generation 健康度和 mutation gate 分别投影。

统一 ChangeSet 的内存与持久阶段固定为：

```text
observe source
  -> validate artifact / desired graph / host bindings
  -> prepare runtime participants
  -> create private RuntimeDomain
  -> mount and settle candidate instances
  -> verify publication requirements and candidate contributions
  -> write durable PREPARED / COMMITTING journal
  -> commit compensatable participants
  -> write durable COMMITTED decision
  -> atomically publish PublishedGeneration
  -> close old generation admission
  -> emit PublishedView revision
  -> drain, dispose and retire old generation
```

发布对象必须一次性携带 core domain、不可变贡献路由、runtime generation snapshots、artifact facts、
desired facts 和诊断投影。禁止分别交换 service view、event view 和 contribution view。

发布线性化不依赖时序运气：调用只有在“取得 lease 后复核 current state 仍相同”时才开始执行；发布交换
current state 后立即关闭旧代准入。已通过复核的旧代调用在线性化点前成立并计入 drain；持有旧引用但尚未
通过复核的调用必须释放并重试。generation 身份禁止复用，避免原子引用出现 ABA 误判。

失败处理：

- durable `COMMITTED` 之前失败：回滚已提交 participant、关闭候选 domain，旧代继续服务。
- durable `COMMITTED` 之后、内存发布之前崩溃：重启时以 journal 决定重建已提交代；不能根据目录现状
  猜测，也不能发布旧 desired state。
- 内存发布之后 cleanup/retire 失败：新代保持已发布，记录退役失败并在下次恢复继续；不得把外部路由
  切回已经进入排空的旧代。
- 任一 rollback 不能证明恢复完整，或 journal 位于结果不确定的 `COMMITTING`：关闭 mutation gate，
  保持可诊断只读状态，等待运维修复或进程关闭。

## 5. 事务明确不承诺什么

Fibra 的 ChangeSet 是“持久决策 + 可补偿参与者 + 单点能力发布”的事务编排，不是对任意插件代码的
分布式 ACID 包装。必须在 API 文档、异常和测试中明确以下边界：

1. 候选插件 `start` 期间已经发出的网络请求、消息、邮件、第三方 API 调用或对外部数据库的写入，
   Fibra 无法自动撤销。候选失败只保证其在 Fibra 内的服务、事件和贡献从未发布，并执行已登记 disposer。
2. 插件绕过 Fibra Scope 创建且未登记的线程、文件、进程或订阅不受回滚和关闭保证；这是插件缺陷。
3. 已进入旧代的在途调用不会因发布新代被回滚。它们按旧代语义完成；调用结果与外部副作用仍有效。
4. 一次调用内部跨多个远端系统的原子性不属于 Engine transaction。需要该保证的插件必须自行使用
   幂等键、outbox、数据库事务或远端补偿协议。
5. ClassLoader 隔离不是安全沙箱；Node sidecar 进程隔离也不自动提供文件、网络、凭据或系统调用权限
   控制。权限由宿主提供的显式 port 和操作系统隔离负责。
6. snapshot 发布只能保证本进程内新调用路由一致，不能原子保证其他进程、浏览器或缓存消费者已经观察
   到新 revision。
7. 进程在 participant commit 与 durable `COMMITTED` 之间崩溃时，若参与者无法凭幂等事务 id 查询
   结果，系统必须停写；不能承诺自动判断或无损恢复。

需要强事务的制品、desired repository 和 audit repository 必须实现幂等 prepare/commit/rollback 与
expected revision；普通插件业务副作用不加入该参与者协议，避免把无法验证的补偿接口伪装成保证。

## 6. 实现落点与职责

| 模块 | 最终职责 | 明确不做 |
|---|---|---|
| `fibra-api` | 保留纯 core 的 Context/Scope；`EventKey` 固定 EventMode；补充 domain 身份不泄漏的契约测试 | 不把 Engine、generation 或贡献业务类型放入 core API |
| `fibra-core` | 引入受限托管句柄 `RuntimeDomain`；实例、service、event、Scope 按 domain 所有；校验事件 mode；删除 `GenerationRealm` 用法 | 不把句柄放入 `fibra-api` 或交给宿主，不为每代创建线程，不用全局表加可选过滤兜底 |
| `fibra-bridge` | 拆为 generation-local registrar/directory、不可变 route table 与 snapshot | 不成为 published generation owner，不依赖 Engine，不保留全局可变目录 |
| `fibra-config` | `DesiredEntry`/`DesiredGraph` 固定逐 entry PublicationRequirement | 不从 Fiber 当前状态反推发布策略 |
| `fibra-engine` | 单向依赖通用 Bridge；`Generation` 聚合 domain、目录、runtime participants 与 lease gate；发布原子 PublishedState；删除 `runtime()`；加入 host binding snapshot 与只读诊断 | 不解释业务 contribution，不公开 prepare/commit/rollback 句柄，不增加第二 command loop |
| `fibra-runtime-java` | 候选 catalog 只用于候选 domain；旧 ClassSpace 在调用排空后关闭 | 不把 ClassLoader 暴露给 Engine snapshot 或宿主 |
| `fibra-runtime-node` | 从 candidate Context 获取 registrar；sidecar contribution 在私有目录登记；排空后 stop/close | 不注入全局 `ContributionBridge`，不宣称 sidecar 是安全沙箱 |
| `fibra-registry` | 查询和 watch 只投影 Engine/Published snapshot；写入仍翻译为 EngineCommand | 不成为第二个 generation owner |
| `fibra-spring` / starter | 宿主服务作为显式 host bindings；业务调用注入 `PublishedRuntime` | 不再通过 `engine.runtime().rootScope()` 绕过 Engine |
| 示例与文档 | Java/Spring host 全部改用 `engine.published()`；说明事务边界和 revision | 不保留旧 runtime 访问示例或兼容转发 |

实现已删除 `GenerationRealm`、`engine.runtime()` 和全局 Bridge；RuntimeDomain、代内目录与
`PublishedRuntime` 是唯一现行路径，不保留兼容转发。

`PublishedRuntime/PublishedView` 放在 `fibra-engine`，generation-local registrar/directory/snapshot 留在
`fibra-bridge`，依赖方向固定为 `fibra-engine -> fibra-bridge`。若反向依赖，Bridge 会知道 Engine 状态且
Engine 又必须消费目录，形成环；新增 published 模块也会迫使 `FibraEngine` 反向依赖它或搬走 Engine
类型。单向依赖是满足唯一发布所有者且不增加空壳模块的最小方案。

## 7. 验收门禁

### 7.1 运行代与发布门禁

以下均需确定性测试，不用 sleep 或概率压测：

1. 候选服务在发布前不能被宿主或旧代发现；同名服务可在旧代与候选代同时存在。
2. 候选 listener 不接收旧代/宿主事件，旧代 listener 不接收候选事件；跨域 EventTarget 被拒绝；错误
   EventMode 派发也被拒绝且诊断列出声明 mode。
3. 候选贡献不进入 published snapshot，不与旧代相同 `ContributionId` 冲突。
4. `ACTIVE_REQUIRED` entry、候选 FAILED、传递服务依赖或贡献校验失败时，旧 view 与旧调用路由保持同一
   revision；`PENDING_ALLOWED` 可发布并准确列出 `waitingFor`。
5. 单次发布同时切换 snapshot、service/event 所属 domain 和 contribution directory，不出现混合代。
6. lease 与 publish 的每个交错点只有“二次复核成功并计入旧代”或“释放后进入新代/报告 revision 过期”
   两种结果；没有未计数旧代调用，也没有发布后才开始执行的旧代调用。
7. 旧代拒绝新调用但等待在途 Publisher 终止；随后按贡献撤销、Scope dispose、sidecar/ClassSpace close、
   artifact retire 的顺序完成。
8. 旧代调用失败、取消和永不完成时的 drain/强制关闭策略均有明确结果和诊断，不永久卡住 Engine close。
9. Java-only、Node-only 及 Java+Node 联合 ChangeSet 都通过同一发布协议；任一 runtime prepare/commit
   失败都不能产生部分可见代。
10. 删除 `FibraEngine.runtime()` 后，公开签名、Spring 自动配置、示例和全仓调用点无旁路残留。
11. `COMMITTING` 崩溃、durable `COMMITTED` 后崩溃、retire 失败三种恢复路径使用真实 journal/目录验证。
12. 单个 `PublishedView` 中 Engine 状态、贡献与诊断 revision 永远一致；基于旧 view 的调用收到明确的
    stale-revision 结果，不会悄悄落到另一代。
13. active domain 内 PENDING 恢复、provider 消失和 contribution 增删在相同 generationRevision 下发布
    新 viewRevision；candidate domain 的变化不对外发 view。
14. 外层调用自动创建并关闭 generation-local 调用 Scope；成功、失败、取消和 stale-revision 都不泄漏
    调用者资源，宿主从未取得 core Context。
15. `PublishedView` 的诊断投影至少覆盖 desired graph/source revision、实例 waitingFor/provider identity、
    服务 provider owner、事件 mode/listener owner/order、贡献 generation、current/candidate/draining
    generation、transaction journal 和 mutation gate。

### 7.2 Cordis 原始行为账本

固定来源仍为 Cordis 提交 `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的
`packages/core/tests`。12 个 spec、71 个原始 `it` 必须逐项记录“源断言、vNext 落点、目标测试、执行
证据”。每个 Java 测试方法的完整清单见
[行为验收账本](../references/2026-09-11-behavior-verification-ledger.md)：

| spec | 数量 | 完成要求 |
|---|---:|---|
| associate | 5 | 逐项验证，不以同类新测试替代 |
| decorator | 1 | 逐项验证 |
| dispose | 13 | 覆盖资源所有权与 Java `Publisher` 的等价表达 |
| events | 7 | 在 RuntimeDomain 下通过，并覆盖跨域隔离 |
| fiber | 8 | 逐项验证 |
| invoke | 2 | 逐项验证，并覆盖 published lease 的调用者所有权 |
| isolate | 3 | 逐项验证；业务 realm 与 generation domain 分开验证 |
| logger | 9 | 逐项验证 |
| plugin | 10 | 逐项验证 |
| reflect | 4 | 逐项验证 |
| service | 5 | 逐项验证，并覆盖同代冲突与跨代并存 |
| shadow | 4 | 逐项验证 |
| 合计 | 71 | 71 项都有一对一记录和执行证据 |

### 7.3 Fibra 额外回归账本

当前来源为 `fibra-parity-tests/.../migration`，共 11 个类、44 项 `@Test`：

| 当前测试类 | 数量 |
|---|---:|
| `AnnotationInjectionParityTest` | 2 |
| `ContextPropertyParityTest` | 2 |
| `CoreEventsParityTest` | 4 |
| `EffectParityTest` | 5 |
| `EventParityTest` | 6 |
| `FibraDisposalParityTest` | 2 |
| `FibraInertiaParityTest` | 2 |
| `LoggerParityTest` | 5 |
| `PluginAndInvocationParityTest` | 5 |
| `ReadmeExampleTest` | 1 |
| `ServiceFibraParityTest` | 10 |
| 合计 | 44 |

每项都必须保留明确的行为断言；新 API 采用等价表达，不恢复废弃入口。禁止 `@Disabled`、删断言或
改成只验证“不抛异常”。

### 7.4 项目交付门禁

运行代测试、71 项原始账本和 44 项额外账本全部完成后，还必须同时满足：

- 模块依赖边界测试与公开 API 签名基线通过；全仓 grep 无 PF4J、旧 loader、`engine.runtime()` 和全局
  Bridge 写入口残留。
- artifact/config 联合部署、expected revision 冲突、rollback、mutation gate 与崩溃恢复通过真实文件
  测试。
- Java 真实 JAR 依赖图、资源委派、ClassLoader close-and-collect；Node 握手、超时、取消、心跳、stderr、
  进程树终止均有集成测试。
- Spring Boot 自动装配和 Java/Node 组合场景通过；示例只能使用发布 API。
- 全量 `mvn verify`、隔离外部消费者、可重现发布、API 签名和 JMH 编译门禁全部通过。
- 完成性结论必须同时依据行为、架构与分发门禁，不得只凭构建通过，也不得作超出第 5 节的事务承诺。

## 8. 已确认决策

以下绑定选择已经确认，不再并行维护替代实现：

1. Engine 内采用“共享 lifecycle lane、每代独立 RuntimeDomain”，不采用每代完整 Runtime，也不采用
   全局表 generation 过滤。
2. 托管宿主删除 `engine.runtime()`，只保留稳定 `PublishedRuntime`；外层能力通过带 expected revision 的
   contribution 调用，domain 内 built-in plugin 继续使用原生 service/event。
3. ChangeSet 只承诺 Fibra 内部可见性、持久决策和可补偿资源的一致性；任意插件外部副作用遵循第 5 节，
   不宣称可自动回滚。
