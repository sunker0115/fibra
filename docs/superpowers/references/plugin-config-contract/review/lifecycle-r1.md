# 通用配置合同候选：生命周期独立审查

2026-10-11。审查文件：`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`。

**精确 SHA-256：`5e3b1e5eab80bbf5371b9a06165f152763a7d1f7cf961ed991f683dd46fb32ca`**。

读取前后 hash 相同。本轮只读设计及相关源码；未修改仓库、未实现、未运行测试。Nowledge targeted search 无相关结果；使用当前源码而非旧历史补语义。

## 结论

**B0 / I2 / M1。尚不能关闭设计审查门。** 共同预检位置、候选 revision、不执行插件代码、raw/effective 延期、保存/恢复分类、builtin 派生身份及异常 view 安全边界的方向成立。两个重要缺口是固定资源字节与精确 revision 的绑定协议、共享读取 attempt 的公开失败投影。

## I1：固定资源读只有 record/root 定位，尚未冻结“这些字节属于该 revision”的可证伪协议

**设计定位：§3 第 38 行；§6 第 162–170 行；CC02 第 250 行。**

设计说 fixed resource 绑定 exact record/content identity，实际条款规定 inert creation、固定 filename、regular/symlink、read/close。它没有说明什么时候、以哪些已核字节确认返回配置资源仍属于 record.packageRevision。

现存源码：`PluginPackageStore.java:250–284` 的 find/readStoredRecord 会完整读取 package 并核摘要，随后返回含 location 的 record；`PluginPackageRecord.java:17–29,88–109` 本身只是公开可构造的 metadata，既不封装不可变打开句柄，也不是 Store 签发的权限/token。新的 resource 在稍后 read 时重开 pathname，可能已越过多个 runtime 名称 query 的异步等待。当前 `PluginPackage.java`/record 不携带该固定文件的冻结字节或其被 package revision 验证的摘要。

**最近反例：** 先 `packageStore.find(R1)` 获取 record，阻塞名称查询，改同 root 的 fibra-config.json（不改 record/packageRevision），再释放读。只按设计 §6 的现有列举检查实现，会把 R2 规则作为 R1 source 返回/用于 Apply，并仍可通过 CC02“正式构建时改文件使digest变化”。这不是要求抵御同 JVM 恶意插件或所有文件系统 ABA 竞态；它是公开惰性句柄最简单的 stale-record/变更窗口，应该有明确承诺或明确不承诺。

**建议收敛：**

- 明确 `record` 是输入身份声明，不是“已永久验证字节”的证明。
- 选择并冻结一种小型绑定方式，例如 Store 在既有已核包 snapshot 中带出固定资源的不可变内容摘要/缺失事实，resource 对本次实际读取字节匹配该事实；这只是相同 revision 的可重建内容证据，不新增 selection/schema revision。也可采用同一受管读取操作内完成原完整 package 验证与冻结 schema bytes 的方式，但需说明哪些字节被验证、相关读取资源归谁及失败保留，不能只在 read 之前再 find 一次就宣称无窗口。
- 声明正常管理不修改 installed content、外部篡改不属于正常更新；精确查询不声称提供全目录文件系统原子快照或抵御恶意 ABA。若当前版本只依赖逻辑不可变的受管存储假设，也必须明文把 exact revision 保证限于此假设，并解释 standalone `PluginPackageRecord.configurationResource()` 的验证边界。
- 给 CC02/CC07 增加 find 之后/read 之前的文件替换、截断、symlink/root ancestor 替换反例：拒绝或返回已冻结 R1 字节，不能把改变后规则标记为 R1。新校验打开的任何 stream/walk 同样纳入真实 owner，避免为补摘要引入新的丢句柄窗口。

## I2：读关闭失败的 FailureSubject/operation 终态尚未落到现有公共模型

**设计定位：§6 第 174 行；§7 第 204 行；CC07/CC08 第 255–256 行。**

第 204 行“读取关闭失败归真实读取父owner，fail-stop并保留其driver/store依赖，不能套用query专用subject伪装部署失败”给出了正确方向，但没有可实现的公共 subject 与 operation terminal mapping。

现存 `FailureSubject.java:8–58` 是 sealed 类型，只有 Engine、DurableTarget、Operation、Candidate、Current、Retirement、Unit，没有 ReadAttempt。现存目录 `FibraEngine.catalogFailStop:487–500` 实际用 Engine subject、RELEASING、空 operationStage，只调用 enterFailStop；`enterFailStop:1024–1028` 不结束当前 operation。部署 `deploymentFailed:863–868` 遇 state 已 FAIL_STOP 会立即包装返回，不再 failOperation。因此，若仅提取并复用 CatalogAttempt 的 close/fail-stop 回调，会出现 Apply 已 FAIL_STOP 但 operation 仍 RUNNING、subject 为 Engine且无操作关联。文档禁止这样做，却未明确替代哪条映射；实现者也可能误加 ReadAttempt 公共变体，超出 §6/§10 已列 API 范围。

**建议在批准前冻结具体表：**

- 公开纯查询：保留 Engine subject、查询自身的安全失败code，无虚构 deployment operation；保留失败 parent attempt 到关闭失败重放。
- Apply/recompile 读取普通失败：Operation subject、PLANNING、对应targetRevision；BOOTSTRAP 已存目标普通失败用 DurableTarget subject；这部分已有文字，补可断言字段即可。
- Apply/BOOTSTRAP/recompile 读取清理失败：明确使用 Operation 还是 DurableTarget/Engine 的既有变体，以及 operationId、failureStage=RELEASING、operationStage=PLANNING、outcome=FAILED、原 TargetSaveState 的关系。推荐 private read attempt 作资源 owner，而其公共失败事实归发起的 Operation（恢复按既有 DurableTarget政策另行明确），无需新增公共ReadAttempt类型。
- 先登记安全 operation 失败事实再 enter fail-stop，shutdown 只观察缓存close终态，不把原操作错误覆盖成一次成功/新失败操作；关闭失败 owner字段仍可达，driver/store不关闭，独立兄弟仍清理。
- CC07/CC08不能只断言抛异常及某个句柄未close；增加 source query、Apply、BOOTSTRAP、Reconcile/requestRecompile 四条路径的 subject/kind/stage/outcome/saveState/targetRevision 精确断言，并让 read+close 双失败显示 cleanup为主与两个安全code。

本 finding 不要求新增第二 owner或公共失败类型；相反是防止共享实现时把 query 的现有 failure projection 偷渡到部署链。

## M1：健康 ReconcileCurrent 与 deploy no-op 的 fingerprint 前提混写

**设计定位：§7 第 202 行。**

该行说“原 no-op/健康reconcile可复用同compiledFingerprint的既有有效代”。实际 `FibraEngine.retryCurrent:566–585` 在 current 且无failed时直接返回或 reconcile PENDING，不重新 capture inputs、不比较 fingerprint；只有 `deploy:594–607` 的 no-op 才比较本次 fingerprint。`RuntimeProvider.java:8–13` 的 metadata原本要求不可变，这使既有generation快路可被限定解释，但不能把源码描述成已经做过同fingerprint检查。

建议改为两类：健康/PENDING reconcile只协调已验证且未换输入的既有代，不承诺发现外部输入/制品变动；Apply/显式requestRecompile/需重建reconcile进入deploy后重捕身份并比较fingerprint。若本次真要让健康reconcile发现新builtin/schema/provider identity，须明确增加代码影响与反例，不在实现中顺便扩大语义。验收至少分别证明健康复用不重复绑定和显式重编译不沿旧identity快路。

## 已核成立的边界（不是漏检免责）

- §7 preflight 在 `planner.inputs` 后且任何 createCandidate之前，足以覆盖直接Apply、Registry、CLI、恢复、runtime disable/recompile的共同路径；不会把业务绑定搬进 config。
- 内部使用候选exact record、公开query单独排队，避免 lane 内调用公开describeDefinitions造成自等待和升级读旧schema。
- local/ancestor/when 无resolved值延期，与package gate仍先raw求值分列，符合当前实现。
- 新命令NOT_SAVED/原目标恢复NOT_APPLICABLE，保存后的运行失败SAVED，制品publish与target保存分开，均写清。
- builtin派生digest包括完整schema，从结构上避免跨重启沿旧selection接受新schema；构造阶段仅预算/shape，语义错误留管理可修复路径，也与目标一致。若实现最终仍在provider构造时compile合法性则违反此设计，应由测试抓出。
- 同步AutoCloseable本身没有要求错误用timeout取消，不强迫artifact引入Reactor；现有描述的先登记、等真实read、close缓存、兄弟逆序、失败保留方向正确。实现仍需确保read/close线性化锁、调度owner与失败引用清理，不可只用try-with-resources代替。
- §9 明说EngineChangeException.view保留授权管理快照，不承诺Throwable整个对象图无配置；官方诊断/输出按白名单投影。因此其安全边界没有继续作不可能的强保证。CC10应按“纯schema诊断对象图”和“真实输出投影”分开验证，不应把view遍历排除后又报告整个异常图无配置。
- 验收CC01–12有真实runtime、制品和文档消费要求，不用prepare编译或假ACK冒充完成。上述I1/I2反例仍须补进矩阵，当前泛化条目不足以证明闭合。

## 读取依据

完整读取被审设计；复核 `FibraEngine` 的 public query/CatalogAttempt/deploy/deploymentFailed/retryCurrent/fail-stop/shutdown关键段、`EngineCommandLoop`、`FailureSubject`、`RuntimeProvider`、`PluginPackageRecord`、`PluginPackageStore.readStoredRecord`、`PluginPackage` snapshot/digest、Java目录reader与已有Engine相关测试。参考前次配置链与错误输出调查时，重新核对本次关键行；没有把其推荐视为已批准决策。

## I1 / I2 最小可冻结修订建议（追加只读设计）

以下是建议，不表示原 hash 已修改或问题已关闭。仅复用现有字段/摘要算法的清晰接缝，不设计全面文件系统防恶意机制。

### I1 推荐 A：既有根摘要同次取得固定资源核验事实

| 比较 | A：既有已核快照带资源摘要 | B：resource.read 内完整包重验并冻结字节 |
|---|---|---|
| 本次真实文件读取 | Store 原完整读取时同chunk计算固定资源摘要；随后resource只读固定文件 | read时新增两次完整包snapshot、manifest、facet摘要、全root遍历，再固定返回字节 |
| exact绑定 | 资源摘要由构成已核packageRevision的相同字节派生；返回bytes核对它 | 必须在完整根digest读取的相同stream capture返回bytes，之后再独立read不成立 |
| 资源失败现场 | 新资源只需一个固定文件stream owner；原Store既有读取不新增额外打开 | 本次owner需覆盖manifest、全部facet文件、walk及root文件，而非单stream；不能直接调用现try-with-resources实现后声称失败句柄可达 |
| 公共record构造 | 要区分携带核验事实的Store record与手造metadata record | 手造record可在read时重新核验，但成本/生命周期面扩大 |
| 决定 | **推荐**，只新增可重建私有内容证据 | 不选，不为一个固定资源扩大整包读取owner |

A 的具体算法与不变量：

1. `PluginPackage.digest(root)` 现有根树读取已按路径/type/length/bytes写入包SHA (`PluginPackage.java:270–327`)。仅当正在读取根的固定 `fibra-config.json` 时，在 **同一updateFile循环、相同byte chunk** 额外更新一个raw SHA-256和真实长度。不能先/后另开文件算摘要，也不把附加SHA写进原packageDigest输入；`fibra-content-v1`规范字节保持不变。
2. 完整root遍历同时记录固定资源的 `ABSENT / REGULAR(length,digest) / 非regular`形状事实。缺失或目录不是安装语义失败，仍允许无执行包暂存；合同比较时分别返回missing/invalid。符号链接仍受原包读取规则拒绝。对超1MiB文件不把全部bytes存内存，只记录长度/digest；稍后合同读取按预算拒绝。
3. 原 `PluginPackage.read` 两个snapshot比较 (`:76–99,348–355`) 保留，并比较固定资源派生事实；返回第二次一致snapshot的身份事实。此事实只有摘要/长度/存在性，不含schema或现值。
4. `PluginPackageStore.record` (`:427–438`) 在建立record时内部传递该事实及其binding tuple（至少root/pluginId/version/packageRevision；可以保存同次PluginPackage身份值）。`readStoredRecord:270–284`已经确认root属store、身份/digest正确；`Transaction.save:594–615`已经重验package，staged→installed只改变state/timestamp，不改变证据绑定。该派生字段不写 `.properties`，重启时从原包读取重建；不增加schema revision或另一持久事实源。
5. `configurationResource()` 创建惰性句柄时捕获此不可变binding与固定资源事实，不I/O、不取store锁。`read()`取得的单一固定文件stream由resource立即登记，有界读出bytes，同时计算实际raw SHA/length；只在与冻结事实一致时返回防御副本。文件被替换成其它规则即拒绝为安全READ/CONTENT_MISMATCH类错误；若返回相同bytes则符合该配置资源绑定。不声称此时整个目录的其它内容也重新校验过。
6. 新增私有派生字段不是授权token，也不是新的业务身份：它只证明这些资源bytes来自原package snapshot。不要公开可写 expectedDigest setter让手造record冒充已核。

**public record builder 的准确合同：**

- `PluginPackageRecord.builder()`及原方法保持可构造纯metadata record；新 `configurationResource()`只接受携带artifact内部核验事实的record。手造record调用该新能力时立即明确拒绝（无I/O），指引先 `store.find(pluginId,revision)`取得已核record。不得silent fallback按location读后当成已核，也不把构造器改成有I/O。
- `toBuilder()`内部保留候选核验事实；最终build仅当root/pluginId/version/packageRevision/managedPackage身份保持匹配时延续。state/timestamp变化不影响内容绑定（原Transaction.save用此路径）；身份/root变化使新方法不可用或build明确拒绝，推荐前者以保留纯metadata用途。该规则必须写进新API Javadoc与consumer测试。
- 这属于新能力的材料性使用前置条件，应纳入§6审批；无需给旧record持久格式增加字段，也不改旧builder签名。已由Store取得且未篡改的正常Engine路径无需新调用。
- 若主线程认为“手造record也必须直接支持固定资源read”是必需公共合同，则不能混用A的无证据路径，改选B并重新列全部I/O owner影响；不要实现两条fallback校验协议。

最小新增验收：Store.find(R1)→变更固定文件→read拒绝；R1缺文件→后来新增→仍不得作为R1规则；同bytes改mtime仍可读；read字节失败+close失败现场；手造record拒绝；toBuilder state/timestamp保留、location/revision变化失效；重启find重建事实；所有既有包digest fixtures保持原算法值。

### I2 推荐映射：private read attempt 持有资源，公开失败沿既有Engine/Operation/DurableTarget

现有 `FailureFact`已有reason/subject/stage/operationStage/targetRevision/message (`FailureFact.java:7–10`)，`EngineOperationSnapshot`已有operationId/kind/stage/outcome/targetRevision/saveState/optional attemptId (`EngineOperationSnapshot.java:6–12`)。不加ReadAttempt公共变体；读取attempt身份只作为Engine私有资源字段，不填candidate attemptId。

| 发起场景 | 普通 read/schema 拒绝 | cleanup失败 FailureFact.subject | operation 与 saveState |
|---|---|---|---|
| 公开 describeDefinitions/describeConfiguration | 返回安全query异常；不新建部署operation，不改上一operation | `Engine(hostInstanceId)` | 保持已有operation快照（若有），FailureFact.operationStage为空；query无目标则targetRevision为空，否则为捕获的durableRevision |
| 新target Apply（含Registry/CLI、runtime disable） | `Operation(operation.id)`；stage PLANNING；operationStage PLANNING | `Operation(operation.id)`；failure stage RELEASING；operationStage仍PLANNING | kind APPLY、stage PLANNING、outcome FAILED、targetRevision为本次candidate targetRevision；saveState沿beginOperation，通常NOT_SAVED |
| 相同target Apply但进入重建（不是真正no-op） | 同上Operation | 同上Operation | kind APPLY，NOT_APPLICABLE；不能只见Apply就写NOT_SAVED |
| 有durable target的BOOTSTRAP | `DurableTarget(durableRevision,durableDigest)`；PLANNING | 同一 `DurableTarget(...)`；RELEASING | kind BOOTSTRAP、stage PLANNING、FAILED、targetRevision为durableRevision、NOT_APPLICABLE；durable PRESENT，convergence BLOCKED |
| 需重编译的ReconcileCurrent/runtime requestRecompile | `Operation(operation.id)`；PLANNING | 同一Operation；RELEASING | kind RECONCILE、stage PLANNING、FAILED、targetRevision为当前durableRevision、NOT_APPLICABLE |

全部部署表行的 `FailureFact.targetRevision` 与该operation.targetRevision一致；`FailureFact.operationStage=Optional.of(PLANNING)`。ordinary schema拒绝Engine保持RUNNING、旧current/admission保留；current不存在而durable存在则PRESENT+BLOCKED。cleanup失败Engine进入FAIL_STOP、两种准入关闭，旧current资源保持原owner；不伪造新的candidate/current，也不清除原target。若旧current存在，convergence是既有代事实，不为了异常视图捏造一次新代；BOOTSTRAP无current必须显式BLOCKED。

实现上只需给共享读取attempt捕获一个私有发起上下文（QUERY或DEPLOYMENT及当前operation身份/BOOTSTRAP durable身份），不要在失败时读取可能不属于本次查询的历史operation来推断。原lane串行持有，context只是错误映射信息，不是第二状态机。

- **部署cleanup失败调用原 `failStop(fact)`，不要照搬catalogFailStop的enterFailStop。** `FibraEngine.java:977–1021` 已经设置 operation.outcome=FAILED、关闭所有unit admission并notify；无需新生命周期机制。在此之前对BOOTSTRAP设置targetConvergence BLOCKED。FailureFact.message只含固定安全码，read+close双失败可用固定 `prior=READ_FAILED`等安全code，不挂原throwable。
- **query cleanup保留既有catalog语义**：Engine subject、空operationStage，沿enterFailStop+关闭unit admission+notify，不把历史成功operation改FAILED。旧operation的SUCCEEDED可以与新的Engine失败并存，因为它不是本次查询operation。普通query失败不制造部署operation。
- cleanup父attempt仍保留在统一字段；其close终态缓存且失败时不清空；后续shutdown首先观察同一close (`FibraEngine.java:1056–1058`)，失败则在begin SHUTDOWN前返回，因此不会覆盖首个FailureFact或operation。关闭loop/通知基础设施依原close结果处理，driver/store不得在此分支继续关闭。
- `deploymentFailed:863–868`在FAIL_STOP时早返回即可，因为此前真正failStop已标记本次operation失败；Error wrapper继续携原saveState与失败时PublishedView。
- 正常读取成功/普通失败但close成功，attempt字段清除；schema纯编译/验证失败交回原deploymentFailed的planning分支，BOOTSTRAP映射DurableTarget，正常Apply/Reconcile映射Operation。

精确验收：对query、Apply新target、Apply同target重建、BOOTSTRAP、Recompile分别注入read失败/close失败/二者失败；断言上述完整字段、target保存次数0、当前/候选owner可达、driver/store close次数0、兄弟close各1、close重订阅不重试/不替换failure。query场景先制造一次成功Apply，再注入query cleanup失败，断言它不会把旧Apply的SUCCEEDED变FAILED；这能直接防止复用通用failStop错误篡改历史operation。
