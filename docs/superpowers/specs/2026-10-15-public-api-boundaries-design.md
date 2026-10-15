# Fibra 整体公开 API 稳定边界

日期：2026-10-15；基线 `e133814`；状态：本轮实现与本机运行/发行资格通过，独立设计/规格/质量/文档审查清零。
本轮工作和停止条件只见[活动计划](../plans/2026-10-15-public-api-boundaries/task_plan.md)。
原生命令、制品摘要、失败记录和平台边界见[资格记录](../references/public-api-boundaries/qualification.md)。

## 1. 目标与唯一 owner

以当前整套公开 API 为对象，按真实使用者、能力、资源 owner 和兼容承诺审定，不把 Java 的 public 关键字
直接等同于产品应支持的接口。保留既有内核、制品/配置、Engine、Bridge、runtime SPI、管理适配、
CLI/Spring、标准能力合同和客户端协议的单向职责；不新增模块、容器、万能 facade 或第二事实源。
各层角色沿[整体架构](2026-10-15-public-api-architecture.md#1-目标受众与唯一体系)，完整审定见
[`public-api-inventory`](../../api/public-api-inventory.md)。

普通 Host 可以直接使用 Engine 的完整部署命令，或使用 Registry 的管理用例；两者共享唯一 Engine lane、
CAS 与生命周期。Registry 不拥有 driver、store 或 target，也不要求所有 Host 迁回其入口。
执行器作者需要的 RuntimeProvider/Driver/Candidate/Generation/Unit 是正式 SPI，不能与 Engine 私有编排聚合混为一谈。

## 2. 核验与路线

Claude 最新建议的三项核心事实成立：完整 Registry 部署缺调用者版本，两个内部类型被公开，普通快照混入高级诊断。
但其“CAS恒等成立”表述不成立：读取最新 view 到 Engine lane 之间仍受 CAS 保护，丢失的是用户编辑窗口。
其“必须由Tela迁回才算已验证”也不成立：已有CLI、仓外普通Host、插件验收与benchmark使用完整Registry部署。

| 路线 | 结果与成本 | 决定 |
|---|---|---|
| 仅写文档区分当前snapshot字段 | 无源码变化，但普通合同仍引用整个候选/退休树，无法缩小稳定承诺 | 拒绝作为最终方案 |
| 修完整部署CAS；普通管理投影加显式高级诊断读取；收窄无消费者内部类型 | 沿同一PublishedView、同一Engine，无新状态/控制入口；需要本批调用方重编 | 采用 |
| 新Host万能SDK/facade或独立SDK/SPI多模块重组 | 重复命令/类型、扩大制品面与迁移，当前无新增owner需求 | 拒绝 |

成熟原则采用同一应用边界的显式版本前置条件，以及API/SPI/实现分层；具体Java装载与模块边界仍以固定源码和
本仓实际消费者证明，不照搬框架容器。原始来源及本轮纠偏见活动计划的findings。

## 3. 完整部署的精确公共合同

唯一请求改为：

```java
public record PluginDeploymentRequest(long expectedRevision,
    List<PluginSelection> selections, DesiredInputGraph graph,
    ConfigContextSnapshot configContext) { }
```

expectedRevision 必填且非负；0表示调用者读取时无durable target。集合防御复制，其余值沿原非空/不可变合同。
删除旧三参数构造，不提供latest、负值哨兵、隐式捕获或重试分支。
`PluginRegistry.deploy` 原样将该值传到 `ApplyDeployment.expectedRevision`，仍经原mutate/engine.submit和审计。
mutate 可以为审计读取当前view，但不得用这个view改写请求的expectedRevision。
revision不符时沿原Engine的 `IllegalStateException("deployment target revision conflict: ...")` 拒绝，
不臆造新异常类型；不保存target，不激活/停止实例，不产生新目录事实。
旧revision提交即使内容相同仍先冲突；同revision同内容沿原no-op语义，返回现有target，审计NOT_APPLICABLE。
请求为冷Mono时，revision固定在请求值，不因延后订阅或重复订阅自动追到最新版本。
成功Apply的审计保存态以该请求expectedRevision与返回targetRevision是否相同判定：相同为NOT_APPLICABLE，
递增为SAVED；不得比较Registry在订阅时独立捕获的before，因为显式expectedRevision可能在排队期间才与Engine当前值相等。
需要覆盖前一命令排队推进到R2、Registry捕获before仍R1、expected=R2同内容随后no-op时审计仍NOT_APPLICABLE。
“失败不改变事实”仅指CAS拒绝；原有prepare失败、已保存后启动失败、UNCONFIRMED与FAIL_STOP语义不变，
不能把所有失败一概改报NOT_SAVED。

增量 install/upgrade/enable/disable/move/remove/upsert 保持订阅时读取当前图并只修改指定部分，保留原CAS窗口。
不引入自动重试、静默重放或caller编辑版本的伪造。增量与完整替换不共用隐式latest语义。

成功、R1→R2→旧R1冲突、负revision、延后订阅/重复提交、无变化、并发抢先保存与审计保存态均须验证；
CAS拒绝不得改变部署事实，其他失败保留原NOT_SAVED/SAVED/UNCONFIRMED语义。

## 4. 普通管理视图与高级诊断

`RegistrySnapshot` 仍是不可变值及既有snapshot/watch/变更返回值，只包含普通管理事实：

```java
public record RegistrySnapshot(String viewRevision,
    EngineState state, DurableTargetState durableState,
    TargetConvergence targetConvergence,
    boolean mutationGateOpen, boolean contributionAdmissionOpen,
    Optional<DeploymentTarget> target,
    Map<String, ExecutionObservation> observed,
    List<PluginAuditDeliveryFailure> auditFailures) { }
```

保留Builder风格与 `target()`、`selections()`、`desiredGraph()`、`observed()`、`auditFailures()` 使用形状。
target中的revision/digest、全部selection/raw desired/config context仍是完整不可变部署事实；无target用Optional.empty，
selections/desiredGraph仍返回空值。observed是当前attempt的按desiredEntryId投影；无current为空，不从target推测ACTIVE。
保留EngineState/DurableTargetState/TargetConvergence和ExecutionObservation这些共同观察词汇，不再复制一套状态枚举。
不包含EngineSnapshot、CurrentAttemptSnapshot、candidate、generation、retirement、cleanup树或可执行句柄。
不保留旧 `engine()`、`engineDiagnostics()`、`current()` 或旧record构造器作为兼容旁路。

普通Host可直接读取durableState/mutationGateOpen决定是否允许管理请求，不能依赖内部attempt结构。
投影只读同一个 `PublishedView`，不另读runtime、不协调、不写缓存。state/target/observed/gates/viewRevision来自该同次捕获。
snapshot/watch/变更结果均用同一投影函数；auditFailures沿原Registry审计投递失败只读副本，不冒充Engine原子事实。

高级诊断通过一个明确读取：

```java
public record RegistryDiagnostics(String viewRevision,
    EngineSnapshot engine, EngineDiagnostics engineDiagnostics) { }
public RegistryDiagnostics PluginRegistry.diagnostics();
```

diagnostics从一次 `engine.published().current()` 构造，不另建lane/store或第二快照owner。
各对象非空，所有嵌套值继续采用原不可变观察合同。单次诊断内部同revision；分别调用snapshot与diagnostics可能跨revision，
不能据此假定原子组合，调用者应比较viewRevision或只用完整诊断值。没有当前消费者要求新增watchDiagnostics或组合facade。
高级诊断是正式、明确支持的接口；字段/语义变动同样走版本与迁移门，不写“无稳定承诺”绕开责任。
健康/目标/聚合观察为普通合同，attempt/current/retirement/失败树为高级诊断合同，二者不维护不同运行事实。

## 5. 实现类型与真正SPI

- `DeploymentCandidate` 类及原public访问器降为包级；仅Engine内部持有并聚合各runtime所有权。
- `JavaFacetDescriptor` record及构造降为包级；YAML是作者合同，解析后的Java对象不是另一个配置入口。
- 同原则收窄同runtime私有的JavaRuntimeDriver、NodeRuntimeDriver、NodeFacetDescriptor、NodeEndpointManifest；
  provider仍通过正式RuntimeDriver接口返回实现，Node文本格式与线协议不变。
- PluginCatalog/PluginCatalogEntry仅互相引用，DesiredBindingException/UnknownRuntimeException没有实际生产抛出点；
  本批收窄可见性，不删除文件或把它们伪称成真实错误合同。
- JavaApiContract/JavaRuntimeProvider是真实Host装配合同，保留公开。provider通过RuntimeDriver接口返回实现，不把Java loader变成公共控制句柄。
- 跨模块/包实际协作所需public与面向用户的支持范围分别记录；不能为隐藏Java可见性而随意重排包或添模块。
  CompiledDeployment及Builder、DeploymentTargetCompiler及三个nested结果型因Engine根包与deployment包协作而保持public，
  清单精确列为内部协作非SDK。所有本批收窄均列破坏性影响，不以一次全仓零引用证明不存在仓外使用者。
- public signature基线继续监控全部字节可见面；它本身不是对每个类型的支持承诺。支持边界以审定清单为准。
  新public类型仍须有真实消费者、owner、失败/关闭及兼容规则，不能只改签名快照便通过资格。

## 6. 稳定基线与重开条件

本轮通过正式门后，以审定清单、公开签名、格式与消费者同批冻结基线。内部重构、实现替换、性能优化都维持支持的API/SPI。
新需求/新场景先证明现有扩展点不足；只有跨产品通用需求或正确性、安全、资源生命周期证据推翻旧合同才重开。
破坏性变更必须先列受众、源码/二进制/格式/语义影响、版本边界和迁移步骤，再同步权威规范、签名、作者示例与仓外消费者。
不承诺永不变更，也不把新场景自动等同于必须新增公共类型。普通管理与高级诊断同样遵守版本演进规则。

本轮是未发布0.5.0-SNAPSHOT的同批合同收口，不保留两套record/解析/管理模式；下游升级须整批重编。
不改变target持久格式、贡献kind/wire、API inventory装载、Store/恢复或原Engine生命周期owner。

## 7. 验收、制品与Tela并行

先独立设计审查清零，再按真实RED/GREEN实施；内部类型约束用架构测试、完整部署用真实Engine/Store负例，
管理/诊断测试同次view投影、watch、失败/UNCERTAIN/关闭和审计边界。全部调用方同批迁移，不注释旧断言。
签名仅接纳本批声明差异；所有模块的分组审定及npm合同均纳入清单，未变更模块也须有保留理由。
正式门包括受影响模块、整仓verify、架构边界、客户端发行和隔离公开制品仓外consumer，不能用旧e133814绿色抵扣。
本轮构建/发布使用 `/private/tmp/fibra-api-boundaries-evidence-20261015` 下独立仓与发行目录。

Tela目前r107的settings固定e133814，不需要整体暂停；本轮不覆盖其公开file仓或消费m2，不修改Tela源码/计划。
未来升级明确影响Bootstrap对RegistrySnapshot.engine/engineDiagnostics的读取和两个测试的三参数请求构造。
新合同及制品资格闭合后再交接适配，不能让Tela跟随工作分支或默认同坐标SNAPSHOT。
