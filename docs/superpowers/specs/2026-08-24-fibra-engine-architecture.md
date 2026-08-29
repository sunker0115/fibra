# Fibra Engine 最终架构

日期：2026-08-24

状态：架构已确认，作为 loader 重构、`fibra-engine`、Spring 适配、插件 archetype 和发布验收的共同上游权威源

## 1. 目标

Fibra 最终由框架中立内核、机制型 loader、托管 engine 和框架适配四层组成。`fibra-engine` 把插件 `artifact`、配置树、运行实例、期望状态、持续收敛和联合部署事务组成一个长期运行的插件引擎；Spring、Spring Boot、CLI、Web、Solon 或纯 Java 宿主只能适配或消费该能力，不得重新实现运行时协调。

本设计冻结以下原则：

- `fibra-api`、`fibra-core` 和 Cordis 对等语义不因 engine 改变；
- loader 只负责各自资源的读取、校验、计划和事务执行，不负责宿主监听策略；
- 文件 watcher 只产生 dirty signal，不直接调用 loader 变更方法；
- 所有自动变更由一个 level-triggered `ReconcileCoordinator` 串行收敛；
- plugin 与 config 强耦合升级通过显式 deployment package 建立事务边界，不按文件事件时间猜测批次；
- Spring 只管理 engine 的容器生命周期，不成为 engine 的实现依赖；
- 开发阶段直接删除错误 watcher API 和一步式内部实现，不提供兼容转发。

## 2. 最终模块图

```text
fibra-api
  ↑
fibra-core
  ↑
fibra-pf4j-api
  ↑
fibra-loader-pf4j
  ↑
fibra-loader-config
  ↑
fibra-engine
  ↑
fibra-spring
  ↑
fibra-spring-boot-autoconfigure
  ↑
fibra-spring-boot-starter

fibra-plugin-archetype  --只在生成项目中依赖已发布 fibra-pf4j-api，不进入运行时依赖链
```

九个运行时 `artifact` 加一个开发工具 `artifact`，共十个可发布 `artifact`。示例、parity、benchmark 和 verification 模块不发布。

## 3. 原有模块的最终责任

### 3.1 `fibra-api` 与 `fibra-core`

保持当前公开契约和 Cordis 71 用例语义。`fibra-core` 继续只提供 root `Context` 和插件实例生命周期，不知道 PF4J、配置文件、watch service、deployment package、Spring 或宿主进程。

### 3.2 `fibra-pf4j-api`

继续只定义标准插件入口。插件实现编译时只依赖该模块和自己的 contract；PF4J、Fibra API与共享 contract 均由宿主或独立 contract 插件提供，不复制进插件私有 `lib/`。

### 3.3 `fibra-loader-pf4j`

保留标准插件包检查、依赖图、候选 ClassLoader、安装根、mount/unmount 和 `artifact` 崩溃恢复。删除直接执行业务变更的 `FibraPluginWatcher` 公共 API。

一步式 `applyArtifacts` 只作为单资源事务的便捷入口，底层唯一实现拆为：

```text
inspect → plan → prepare → commit → complete
                           ↘ rollback
```

`prepare` 完成全部可失败的读取、摘要、依赖、候选类型和磁盘准备，不关闭旧 ClassLoader；`commit` 只执行已准备的确定性切换；`complete` 在上层 readiness 成功后释放 previous 数据。联合部署期间不得创建独立顶层 journal。

### 3.4 `fibra-loader-config`

保留 YAML 读取、include、patch、限制、entryId 解析、typed config 物化和运行实例 reconcile。删除 `watch(...)` 与 `FibraConfigWatcher` 公共 API。

配置处理拆为：

```text
resolve source tree
  → plan against current or candidate plugin catalog
  → prepare reversible runtime/file changes
  → commit
  → complete or rollback
```

候选配置计划必须能使用 `fibra-loader-pf4j` 提供的只读候选插件目录和目标 `configType`，保证 plugin/config 联合变更在拆除旧运行态前完成类型校验。

config loader 额外发布不可变 `sourcePaths()` 快照，包含根配置、最后成功 include 与最后失败 resolve 尝试的路径，供外部托管 source 建立监听。读取快照不得进入 loader 操作门、启动 watcher 或触发 refresh，避免 source 线程与 reconcile/deployment 竞争同一协调锁。

## 4. `fibra-engine`

### 4.1 公共入口

模块公共入口为终止性、`AutoCloseable` 的 `FibraEngine`：

```java
public final class FibraEngine implements AutoCloseable {
    public static Builder builder(Path installedRoot, Path configLocation);

    public void start();
    public void requestReconcile();
    public FibraDeploymentResult applyDeployment(Path packagePath);
    public FibraEngineStatus status();

    public Context root();
    public boolean isRunning();
    public void close();
}
```

builder 使用命名方法配置 artifact/config watch、required entries、readiness、root close 和周期 resync；不提供长位置参数构造器。所有集合在 build 时防御性复制，所有路径归一化并在创建任何 root、loader、watch service 或线程前整体校验。

engine 持久状态目录固定为 `<installedRoot>/.fibra-engine/`，只存放 engine journal、revision 和预检临时数据，不作为插件包参与扫描。`build()` 的固定顺序为整体路径校验、engine 崩溃恢复、root、plugin loader、config loader；恢复必须早于 PF4J manager/ClassLoader 创建，使半提交磁盘图不会被装载。框架适配可在 lifecycle start 前取得 root；watch source 和 reconcile worker 只能在首次 `start()` 初载及 readiness 成功后创建。

### 4.2 所有权

每个 engine 独占一个 root、一个 plugin loader、一个 config loader、两个可选 watch source、一个 reconcile worker 和一个 deployment journal root。托管 engine 不公开真实 loader，所有 artifact/config 变更只能通过 engine API 进入同一协调域；需要 loader 低层 API 的非托管宿主必须自行构造并完整拥有 loader，不得把同一 loader 同时交给 engine。root 只用于显式服务桥接和 Fibra 运行查询，关闭权仍只属于 engine。

正常关闭顺序固定为：

```text
停止接收 reconcile/deployment
  → 关闭 artifact source
  → 关闭 config source
  → 等待或取消尚未开始的 reconcile
  → config loader
  → plugin loader
  → root.closeAsync()
```

### 4.3 Reconcile 模型

artifact/config source 只把同一个 engine key 标记为 dirty。reconcile 不消费事件差量，每次重新读取完整安装目录、候选目录和配置依赖文件并执行幂等收敛。

公共 `desiredRevision` 与 `appliedRevision` 保留为运维摘要，但内部必须分别维护 artifact/config 分量后再组合：

- desired artifact 分量来自本轮唯一一次候选选择所得目标 catalog；desired config 分量来自本轮配置解析目标，解析失败时使用该次文件来源指纹；
- applied artifact 分量只来自当前活动 catalog；applied config 分量只来自最后成功提交的不可变 `FibraConfigSnapshot`，不得重新读取已变化或已损坏的磁盘文件；
- artifact 成功而 config 失败时，applied revision 必须推进为“新 artifact + 旧 config”的真实组合，不能保留整个旧 revision；
- 文件系统不提供 API Server 式原子对象版本，因此 desired revision 只是最近一轮完整观察的运维摘要，不作为跳过 reconcile 或证明原子事务的依据。

来源文件指纹使用 `fibra-source-files-v2` 域、归一化绝对路径、64 位文件长度和完整文件内容计算 SHA-256。内容必须通过固定缓冲区流式读取，不按候选 ZIP 或配置文件大小分配整块堆；读取前后实际字节数与声明长度不一致时本轮失败，由 level-triggered reconcile 重读，不接受部分摘要。

incoming 候选目录按插件 ID 分组并选择唯一最高 SemVer；低于已安装版本的候选忽略，同版本同摘要为 no-op，同版本不同摘要拒绝，最高版本的无效候选持续保持失败直到被替换或移除。成功提交不依赖删除 incoming 文件，后续完整重读通过已安装身份判定 no-op；松散目录只允许升级，显式 deployment 才允许受控降级。

同一轮读取可以同时发现 artifact 与 config 变化，但必须作为两个独立事务固定按 artifact、config 顺序尝试，并分别记录失败；不得共享候选 catalog 让两个单独失败的松散变化联合成功。只有带 identity、version、checksums 和 journal 的显式 deployment 才使用联合预检与提交。

规则：

- 一个 engine 同时最多执行一个 reconcile 或 deployment；
- 多个文件事件合并为一次 dirty 状态；
- 执行期间再变 dirty，当前执行结束后至少再 reconcile 一次；
- 周期 resync 修复丢失的文件事件；
- 初载与 readiness 成功、source/coordinator 启动后立即提交一次 dirty，使宿主启动前已经存在的 incoming 候选也进入正常收敛；坏 incoming 进入 `DEGRADED` 和退避重试，不得使已经建立基础运行态的 `start()` 失败；
- artifact/config 失败保留各自最后成功分量，按来源记录结构化活动失败并按有界退避重试；完整回滚的显式 deployment 失败只返回给调用方，不把健康运行态标成 `DEGRADED`；
- 任一 artifact、config 或 deployment 事务报告 `ROLLBACK`，表示旧运行图无法证明完整恢复。Engine 必须设置关闭前不可清除的 mutation block，保留对应阶段失败并进入 `DEGRADED`；本轮立即停止后续参与者变更，公开 reconcile、公开 deployment 和已排队 deployment 的真正执行入口都必须拒绝。root 查询与 `close()` 继续可用；不得用后续成功或定时重试自动解除安全闸；
- 手工 deployment 的 revision 高于松散目录信号，执行期间只累计 dirty，不交叉提交。

### 4.4 双 Source

`ArtifactDirectorySource` 监听候选 ZIP目录，`ConfigFileSource` 监听当前配置快照解析出的根文件和 include 文件集合。两者只依赖 JDK `WatchService`，构造失败必须关闭已分配资源；source 关闭后不得再入队。

source 只是低延迟提示器，周期 resync 才是正确性兜底。`WatchKey.reset()` 失效后必须移除旧注册，使目录或目标文件恢复时可以重新注册；监听循环异常至少通过 SLF4J WARN 可观察，不能静默退出。source 与 `ReconcileCoordinator` 是 engine 内部实现，不进入公共 API签名。底层 loader 不再持有 watch thread、scheduler 或 debounce 状态。命名使用 coordinator 而不是 controller，避免与 Spring MVC/Web controller 混淆。

### 4.5 运行状态与同步命令结果

保留 `NEW/STARTING/RUNNING/DEGRADED/STOPPING/TERMINATED`，因为纯 Java 与 Spring 运维场景需要一个可直接序列化的综合状态。`RUNNING` 与 `DEGRADED` 都表示资源仍由 engine 持有且可自动恢复；状态必须由当前活动失败统一计算，单次成功不得在其他活动失败尚存时无条件改回 `RUNNING`。

同步 deployment 的返回必须与副作用一致。等待线程被中断时，coordinator 在线程安全边界内先尝试移除尚未开始的 operation：移除成功则 operation 永不执行；worker 已取得 operation 则等待真实结果后恢复调用线程中断标志，不能让调用方收到失败后又在后台提交。

### 4.6 运行诊断日志

Fibra 生产代码只依赖 SLF4J API 2.x，不绑定 provider，不直接使用 Logback/Log4j API，不写 `System.out`/`System.err`，也不在库层修改 MDC。内部 logger 字段统一命名为 `LOGGER`，使用 fluent API；异常必须作为 cause 传递，不拼接为文本。事件名和关联字段必须写入消息正文，不依赖 provider 是否渲染 SLF4J 键值对；正文固定为 `event=... key=value`，使 Spring Boot 默认 Logback 与简单 provider 的检索语义一致。

框架运行诊断与插件通过 `LoggerService` 输出的业务日志是两条边界：`DefaultLoggerService` 保持用户的 logger 名、级别和消息原样；框架诊断统一携带 `event`，命名为小写点分 `fibra.<layer>.<subject>.<outcome>`。字段只记录定位所需的 `entryId`、`eventName`、`pluginIds`、`transactionId`、`deploymentId`、stage、revision 和 source 路径，不记录 typed config 值、部署内容、凭据、签名材料或仓库 token。

级别与唯一记录责任固定为：

- `ERROR`：异步失败已被内核吞掉，或不完整 rollback 触发全局 mutation block；同一不完整 rollback 只由 Engine 记录一次；
- `WARN`：库内吞掉但可恢复的失败，例如提交后 cleanup 延期、source 注册失败或监听线程意外退出；会原样抛给调用方的普通校验/提交异常不在底层重复记录；
- `INFO`：Engine 启停、显式 deployment committed、失败阶段恢复等低频边界；普通 reconcile/no-op 不记录 INFO；
- `DEBUG`：source 注册恢复等默认关闭的高频诊断。

相同 source 注册失败在一个持续失败周期只记录首次，恢复后才允许下一周期再次记录。Engine 同一 failure stage 也只在 revision 或错误事实变化时重记，清除真实活动失败时记录一次 recovered，避免周期 resync 和退避重试刷屏。

## 5. Deployment Package

plugin package 是可复用插件 `artifact`；deployment package 是某个宿主环境的一次原子发布，两者不得混为一个格式。

标准结构：

```text
deployment.zip
├── deployment.properties
├── checksums.sha256
├── plugins/
│   └── *.zip
└── config/
    └── fibra.yaml
```

`deployment.properties` 至少包含 deployment id、version、配置根相对路径和按字典序排列的插件相对路径。摘要固定使用 SHA-256；禁止 MD5。ZIP 路径、大小、条目数、重复项、符号链接、未知顶层条目和摘要全部在预检期校验。

deployment 的配置根及全部 include 必须位于包内 `config/`，安装时按相对路径映射到 `configLocation` 所在配置树。事务备份保存在 engine transaction 中，提交和恢复都先在目标配置文件同目录写临时文件，再执行原子 move；配置文件与插件安装根不要求位于同一 FileStore。

### 5.1 唯一总事务

engine 为联合部署创建唯一持久 journal。artifact/config loader 以参与者身份加入：

```text
PREPARING
  → PREPARED
  → COMMITTING
  → VERIFYING readiness
  → COMMITTED
  → complete participants
```

任一步失败按 config 后 artifact 的逆序 rollback，原异常为主异常，恢复异常按发生顺序 suppressed。崩溃恢复只读取 engine journal；参与者不得在同一联合部署中创建互相矛盾的顶层 journal。

新状态在 readiness 成功前不得成为对外 committed revision。恢复无法证明前态或后态完整时拒绝启动并报告 ROLLBACK，不猜测一个插件图继续运行。

durable `COMMITTED` journal 是唯一对外提交点。写入成功前的任何失败都进入逆序 rollback；写入成功后必须发布 applied revision 并返回成功。参与者 `complete`、非权威的 last-deployment receipt 和事务目录删除只负责提交后维护，失败时保留 `COMMITTED` journal 供下次启动验证并清理，同时记录 WARN，不得把已经生效的部署返回成失败。receipt 不参与启动决策，也不替代真实 installed catalog 与配置快照。

## 6. Spring 边界

`fibra-spring` 只提供 `FibraSpringLifecycle` 和 `FibraServiceBridge`。lifecycle 把 Spring `start/stop` 委托给同一个 `FibraEngine`，不复制 load、watch、readiness、rollback 或 close 算法。

`fibra-spring-boot-autoconfigure` 只负责不可变属性、完整校验、条件退让、engine builder 映射和配置元数据，并只暴露 engine、root 与 bridge，不把 engine 内部 loader 注册成 Spring bean。starter 是无生产 class 的依赖入口。

## 7. 插件 Archetype

`fibra-plugin-archetype` 使用 Maven `maven-archetype` packaging，生成一个不继承 Fibra 父 POM的独立多模块插件项目：

```text
generated-plugin/
├── pom.xml
├── plugin-api/
├── plugin-impl/
├── config/
├── deployment/
└── README.md
```

生成项目必须直接执行 `mvn verify`，产出标准 plugin ZIP 和包含该插件的 deployment ZIP。模板使用 Maven Assembly，不手写 ZIP组件；依赖版本集中在生成项目根 properties/dependencyManagement，内部模块使用 `${project.version}`。

archetype 自身使用 Maven 官方 `archetype:integration-test` 在构建期生成并验证项目；随后同一模块的 Failsafe 集成测试由真实 `fibra-engine` 装载生成的 deployment。仓库外分发脚本另行验证十个发布 `artifact`、九个运行时 `artifact` 以及 archetype 生成项目的独立坐标消费；仓外 archetype smoke 不复制模块内的细节断言。

## 8. 测试与发布

- Cordis 71 用例和 core API 基线保持通过；
- 两个 loader 分别覆盖单参与者事务、崩溃恢复和候选视图；
- engine 覆盖事件去重、执行期 dirty、resync、失败退避、联合事务、readiness、关闭和 ClassLoader 回收；
- parity 增加生产日志架构门禁，禁止标准输出、具体日志后端 API、非 `LOGGER` 字段、依赖 provider 渲染的键值字段和消息正文缺失稳定 `event` 的框架诊断；
- Spring 只验证委托、属性、自动配置和所有权；
- 纯 Java example 改用 `FibraEngine`，Spring example 只引入 starter；
- `verification/distribution` 验证 core、插件图、engine、Spring Boot 和 archetype 五种外部消费边界；目录职责与完整断言以[示例与分发验收设计](2026-08-25-fibra-examples-and-distribution-verification-design.md)为准；
- 十个可发布 `artifact` 生成主 `artifact`、发布 POM及项目要求的辅助 `artifact`，并进入可复现构建；
- archetype 生成项目不得引用 reactor、`${revision}`、`target/classes` 或 Fibra 父 POM。

## 9. 成熟实现参照与本项目取舍

- Kubernetes `controller-runtime` 与 `client-go` 采用 level-triggered reconcile、dirty/processing 去重和失败重入队。Fibra 保留“事件只提示、完整状态收敛”的语义，但单进程单 engine 不引入通用资源对象、持久工作队列或分布式 controller 框架。
- Kubernetes `observedGeneration` 依赖 API Server 提供的原子对象版本。Fibra 的多文件来源没有同等快照边界，因此 revision 只用于运维观察，不参与是否执行 reconcile 的正确性判断。
- Apache Felix FileInstall 使用初次扫描、周期全量扫描和可持续运行的目录循环。Fibra同样让启动后的首次 dirty 与周期 resync 承担正确性，`WatchService` 只降低延迟。
- JDK `Future` 只保证成功取消的未开始任务不执行。Fibra只移除仍在队列中的 operation；已由 worker 取得的 deployment 不以线程中断破坏事务边界。

## 10. 范围外

- Spring Shell 命令注册、Spring AI 和具体 agent 协议；
- 远程插件市场、自动下载依赖、签名信任策略和沙箱；
- 每插件 Spring Context 或插件 bean 自动注入；
- 根据时间接近程度把两个松散文件事件猜成同一 deployment；
- 对旧 watcher API、旧 Spring 属性或错误模块边界提供兼容代码。

## 11. Open Questions

无。模块名称、依赖方向、watch source、reconcile、联合事务、Spring 接缝、archetype、测试和发布边界均已确定。
