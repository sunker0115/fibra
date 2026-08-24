## ADDED Requirements

### Requirement: 默认运行时具有单一所有权

自动配置 SHALL 把 root Context、plugin loader、config loader、service bridge 和 lifecycle 作为一个完整托管单元创建和关闭。宿主已经提供 Fibra `Context` 时，默认托管单元 MUST 整体退让，不得继续补建部分 loader 或 lifecycle。

#### Scenario: 默认自动配置
- **WHEN** 宿主没有定义 Fibra `Context` 且属性合法
- **THEN** 自动配置创建且仅创建一个 root、一个 plugin loader、一个 config loader、一个 service bridge 和一个 lifecycle

#### Scenario: 宿主已有 Context
- **WHEN** 宿主定义任意 Fibra `Context` bean
- **THEN** 默认托管运行时整体不创建，宿主承担 loader、bridge、生命周期和关闭责任

#### Scenario: Spring 销毁资源 bean
- **WHEN** 默认托管运行时随 ApplicationContext 关闭
- **THEN** root 和两个 loader 只由 Fibra lifecycle 按契约关闭，Spring bean destroy method 不再次打乱顺序

### Requirement: 启动顺序确定且 watcher 延迟创建

默认 lifecycle SHALL 按 plugin artifacts load、config initial load、required entry readiness、config watcher、artifact watcher 的顺序启动；任一 watcher 关闭时 MUST NOT 创建对应 watcher。watcher 不得在普通 bean 实例化阶段启动。

#### Scenario: 两个 watcher 都启用
- **WHEN** 初始 artifact/config 装载和 readiness 成功
- **THEN** config watcher 先开始监听，artifact watcher 后开始监听，最后 lifecycle 才报告 running

#### Scenario: 两个 watcher 都关闭
- **WHEN** 两个 watch enabled 均为 false
- **THEN** 初始 artifacts 和 config 仍正常装载，但不创建 watch service、watcher scheduler 或 watcher worker thread

#### Scenario: 初始装载失败
- **WHEN** artifact load 或 config initial load 失败
- **THEN** 两个 watcher 均未创建，应用启动失败并进入反向回滚

### Requirement: Readiness 按 Entry 和总时限门禁

lifecycle SHALL 把 `requiredEntries` 解释为 Fibra entryId，在一个总 `readinessTimeout` 内等待当前 epoch 收敛，并要求每个 entry 最终为 ACTIVE。缺失、稳定非 ACTIVE、业务异常和总超时 MUST 保留不同诊断。

#### Scenario: 所有必需 Entry 已 ACTIVE
- **WHEN** 配置装载产生全部 required entry 且它们在总时限内 ACTIVE
- **THEN** readiness 通过并继续启动 watcher

#### Scenario: Entry 不存在
- **WHEN** required entryId 在初始 reconcile 后不存在
- **THEN** 启动立即失败并指明该 entryId，不等待完整 readiness timeout

#### Scenario: Entry 稳定 PENDING
- **WHEN** required entry 的当前 epoch 已收敛但状态为 PENDING
- **THEN** 启动按非 ACTIVE 状态立即失败并报告 PENDING，不伪装成 timeout

#### Scenario: 多 Entry 总预算
- **WHEN** 多个 required entry 依次或并行收敛
- **THEN** 全部等待共享同一个启动 deadline，总等待时间不得为 entry 数量乘以 timeout

#### Scenario: 插件业务启动失败
- **WHEN** required entry 的 `ready()` 传播业务异常
- **THEN** 应用启动失败，原业务异常保留为 cause，并执行完整反向回滚

### Requirement: 任意启动失败完整反向回滚

默认 lifecycle 的任一启动阶段失败时 SHALL 按已完成阶段反向关闭 artifact watcher、config watcher、config loader、plugin loader 和 root。原启动异常 MUST 保持为主异常，回滚失败按发生顺序作为 suppressed，未完成阶段不得执行虚假关闭。

artifact/config watcher 的构造路径在对象引用返回前失败时 MUST 自行关闭已经分配的 watch service、scheduler 或 worker；lifecycle SHALL 关闭所有已经取得引用的 watcher，包括构造成功但 start 失败的对象。

#### Scenario: artifact watcher 启动失败
- **WHEN** config watcher 已启动而 artifact watcher 构造或 start 失败
- **THEN** 系统先关闭 config watcher，再关闭 config loader、plugin loader 和 root，最终异常以 artifact watcher 失败为主

#### Scenario: readiness 失败
- **WHEN** artifacts 和 config 已装载但 required entry 不满足
- **THEN** 系统不创建 watcher，依次关闭 config loader、plugin loader 和 root

#### Scenario: Watcher 构造中途失败
- **WHEN** watch service 已创建，但目录注册、scheduler 或 worker 创建在构造返回前失败
- **THEN** watcher 构造路径关闭全部已分配资源，lifecycle 继续关闭其他运行时资源，不遗留 watcher 线程或句柄

#### Scenario: 回滚阶段也失败
- **WHEN** 原启动失败后一个或多个 close 失败
- **THEN** 所有剩余 close 继续执行，回滚失败按发生顺序位于原启动异常的 suppressed

#### Scenario: 失败后 Spring 再次关闭 Context
- **WHEN** lifecycle 已完成启动失败回滚，Spring 随后关闭 ApplicationContext
- **THEN** stop 幂等返回，不重新装载、不重新创建 watcher、不重复关闭造成新异常

### Requirement: 运行期 Watcher 保留底层事务语义

starter SHALL 复用现有 config watcher 和 artifact watcher，不复制文件去抖、版本选择、loader gate 或事务更新算法。运行期 reload/apply 失败保留最后成功运行态并通过底层 SLF4J/failure 机制报告，不自动关闭宿主。

#### Scenario: 配置文件变更
- **WHEN** config watcher 观察到根配置或 include 的有效变化
- **THEN** 它通过现有 config loader refresh 完成 reconcile，不绕过 loader 事务门

#### Scenario: 更高版本 ZIP 到达
- **WHEN** artifact watcher 观察到已安装 ID 的严格更高版本标准 ZIP
- **THEN** 它通过现有单包 `applyArtifacts` 事务升级，不复制安装或回滚算法

#### Scenario: Watcher 与管理操作竞争
- **WHEN** watcher 在另一个 loader 事务活动时触发
- **THEN** 沿用底层 dirty/retry 最终收敛，不把瞬时 busy 记录成最终运行失败

#### Scenario: Watcher 处理非法输入
- **WHEN** 配置刷新或候选 ZIP无效
- **THEN** 保留最后成功运行态并记录失败，lifecycle 继续 running

### Requirement: 正常关闭严格逆序且幂等

默认 lifecycle SHALL 按 artifact watcher、config watcher、config loader、plugin loader、root Context 的顺序关闭。`rootCloseTimeout` 只约束 root async close；任一阶段失败 MUST NOT 跳过后续阶段。`SmartLifecycle.stop(Runnable)` 的 callback MUST 恰好调用一次。

#### Scenario: 正常关闭
- **WHEN** Spring 关闭一个正常 running 的默认运行时
- **THEN** 所有资源按固定顺序关闭，旧插件 ClassLoader和 watcher 线程均可回收，最后 running 为 false

#### Scenario: 中间关闭失败
- **WHEN** watcher 或 loader close 抛出异常
- **THEN** lifecycle 记录失败并继续关闭后续资源，最终仍调用 callback

#### Scenario: root 关闭超时
- **WHEN** `root.closeAsync()` 超过 `rootCloseTimeout`
- **THEN** lifecycle 准确记录 root 关闭超时，不宣称同步 watcher/loader 受该时限强制中断，最终调用 callback

#### Scenario: 重复关闭
- **WHEN** 同步 stop、异步 stop callback 或 context close 重复触发关闭
- **THEN** 实际关闭链最多执行一次，每个调用安全返回，callback 对每次异步 stop 调用各自恰好一次

### Requirement: 宿主服务只通过显式 ServiceKey 桥接

默认运行时 SHALL 提供 `FibraServiceBridge.register(ServiceKey, service)` 把指定宿主对象注册到 root Context。系统 MUST NOT 按 Spring bean 类型自动桥接、把插件对象注册为 Spring bean，或缓存可撤销 provider 实例。

#### Scenario: 显式注册宿主服务
- **WHEN** 宿主用明确 ServiceKey 注册一个 Spring 单例
- **THEN** Fibra 插件按正常 caller/isolate 服务规则解析，返回的 ServiceRegistration 可等待撤销

#### Scenario: 宿主只声明普通 Spring Bean
- **WHEN** 宿主没有调用 bridge register
- **THEN** 该 bean 不会自动出现在 Fibra 服务 registry 中
