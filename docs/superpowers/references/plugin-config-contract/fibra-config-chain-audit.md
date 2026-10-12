# Fibra 通用插件配置合同：配置链与共同接缝只读调查

2026-10-11；核对工作树 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`。开始时唯一未跟踪项为本次计划目录。未修改仓库，未执行测试，不声称实现/发行通过。下文路径除另行说明外均相对本仓，行号对应当前源码。

## 结论

建议沿现有 Engine `deploy` 增加内部静态合同 preflight：使用本次 target 的精确 packages/builtIns 与本次 `DesiredEvaluation`，在 runtime candidate 的 `createCandidate`/prepare 之前完成静态合同读取、资源关闭及 effective LiteralValue 结构校验。结构校验规则实现属于 `fibra-config`；动态私有制品读取属于对应 runtime；Engine 负责选版、facet/definition 对齐与读资源的 attempt 生命周期；runtime 继续绑定、业务 validator 与运行资源。不要在 Registry/CLI/Spring 分散校验，不向 core 添加制品/schema 依赖。

这是扩展既有部署编排，不需要第二 target、Registry 或配置存储。公开名称目录保持轻量；若新增按需合同查询，其公开排队入口和部署内部 exact-selection reader 必须分开。主线程最新候选是包根固定 `fibra-config.json`，只引用既有 facet/definition：与本接缝相容，schema 不必进入 runtime 私有描述或 `RuntimeDefinitionQuery` SPI。名称解释继续复用 runtime reader，固定包资源读取归 artifact，schema/profile 解释归 config。

## 现有配置链事实

1. `fibra-config/.../ConfigDocumentReader.java:24-48,80-126`：JSON/YAML 有深度/字符串/文档长度约束、严格重复键/尾部 token 检查，以 BigDecimal/BigInteger 保留数值；根必须数组，条目 literal 冻结。该 reader 处理实例配置文档，不能直接复用为 schema reader，因为根协议不同。
2. `fibra-config/.../DesiredConfigCompiler.java:19-24,33-40,85-125`：解析配置树固定字段，插件 `config` 保存原始 LiteralValue 模板；`when` 与 config 在采集时验证 AST；disabled include 不读文件；include 内容采集与条件运行分离。
3. `fibra-config/.../ConfigExpressionEvaluator.java:12-15,37-77`：只识别 `$ref/$defined/$eq/$not/$all/$any/$if/$literal`；`$if` 惰性选分支，`$literal` 返回未经再次解释的 operand。JSON Schema 的 `$ref` 与此 raw AST 处于不同数据层。
4. `fibra-config/.../DesiredEvaluation.java:25-31,52-87`：从 raw graph + frozen context 纯派生；先合取祖先和本地 enabled，再求 when；inactive 叶子 `resolvedConfig=Optional.empty`，不求值 config。不得假开 enabled/when 以做 schema 校验。
5. `fibra-engine/.../DeploymentPlanner.java:24-59`：先 compile facets，再对整个 graph 求值，随后逐 entry 查 selection，并以 `!resolved.effective().enabled() || !selection.enabled()` 跳过 execution。package gate 并未参与 DesiredEvaluation。故 package disabled 的 active entry 仍可能因缺失 raw `$ref` 失败；不能宣称所有 inactive 都免求值。
6. 同文件 `:60-87,120-149`：Engine 唯一建立 entry-keyed unit 和依赖图；runtime 返回 plan 必须与其身份、wiring、binding coverage 一致。
7. `fibra-engine/.../runtime/RuntimeTargetSlice.java:30-67`：传入同一 DesiredEvaluation，并核 graph/context 与 target 完全相等；affected entry 与 unitDependencies 精确覆盖。无需让 runtime 重求值或建立另一个配置投影。
8. `fibra-runtime-java/.../JavaRuntimeDriver.java:451-489`：受影响 entry acquire 静态资源、取得实际 definition、核名，再 `bind(resolvedConfig)`；lease/PreparedUnit 由 candidate acquisition owner 转交，不是 schema owner。
9. `fibra-runtime-java/.../JavaDefinitionEntry.java:24-26`：`LiteralValue.toJava` → binder → `definition.prepare`。`JavaClassSpace.java:56-63` 默认 binder 使用 YAMLMapper.convertValue；schema 必须在此之前看完整 literal。当前调查未运行未知字段 probe，不据此断言具体 Jackson 默认值；自定义 binder 可以丢弃字段的接口事实已经足以说明绑定后校验不能替代结构校验。
10. `fibra-api/.../PluginDefinition.java:59-83,146-149`：业务 validator 可选，返回值成为 Prepared config，允许 normalization；Prepared 与具体 definition 关联。schema 必须纯验证、不填默认/强制转换，不重复运行此业务 validator。
11. `fibra-runtime-node/.../NodeRuntimeDriver.java:299-321`：prepare 核静态 definition、复制 resolvedConfig 到 Prepared；没有通用 schema/业务配置执行。Java prepare 前的业务校验与 Node 实际运行的时点并不相同。
12. Tela 主树只读参照 `tela-host/.../client/TelaClientRuntimeDriver.java:82-117`：inspect 制品、核 definition、将 resolvedConfig 放入 Prepared，没有统一结构校验。此主树不一定含前次隔离消费改动，不能冒称已具备新 SPI 或代表最终迁移字节。

## 所有托管入口与保存边界

- `fibra-registry/.../PluginRegistry.java:46-144,174-215`：install/upgrade/package gate/uninstall/deploy/upsert/entry enable/disable/move/remove 通过 mutate 构造完整 ApplyDeployment；reconcile 使用 ReconcileCurrent；最终同一 engine.submit。Registry install/upgrade 先保存不可变 package，再提交新 target，故 target 校验失败不意味着安装事务未发布制品；可以保留未被 target 选中的内容，不自动回滚删除包。
- `fibra-cli/.../CliHost.java:76-89,107-138`：首次空 target apply；已有 target 先 Engine 恢复；显式 apply 重新采集 graph 和完整包清单，再 registry.deploy。CLI 的 install/upgrade/enable/disable 在 `FibraCli.java:483-498` 委派 Registry。
- `fibra-spring-boot-starter/.../FibraAutoConfiguration.java:64-89`：Spring 装配相同 Engine、Registry 和生命周期，没有独立配置提交语义。
- `fibra-engine/.../FibraEngine.java:287-295,555-585`：submit 的 Apply/Reconcile 两种命令；Apply 用 expectedRevision 检查内存 durable revision，构造下一 target；相同 digest 可以 no-op。
- 同文件 `:503-540`：BOOTSTRAP load 精确保存 target/token，存在 target 时 `deploy(target,false,true,...)`；普通恢复失败保持管理面并发布 PRESENT+BLOCKED，不重读文件源。
- 同文件 `:1626-1659`：插件主动 disable 经 fence 产生完整 replacement；runtime requestRecompile 都直接复用 deploy。静态合同 preflight 放 Registry 或 Apply 专有分支会漏这些路径。
- 同文件 `:588-699`：deploy 捕获本次 builtIns/capabilities；`:609-615` 用 target.selection 精确 revision 从 packageStore.find 取得 managedPackage；`:625-648` 登记候选；`:653-670` prepare、plan validation、seal；`:672-686` 才 targetStore CAS 保存；之后 promote/retire/reconcile。
- 同文件 `:594-595,863-949`：save=true 为 NOT_SAVED，恢复/reconcile/no-op 为 NOT_APPLICABLE；成功保存后为 SAVED。schema 恢复拒绝不能映射成新命令 NOT_SAVED；预检成功也不是运行 READY。

## 静态发现、取消和关闭 owner

- `FibraEngine.java:301-368` 公开 describeDefinitions 总是 `loop.submit`；内部 `describeSelectedDefinitions` 从 durableTarget 读 selection，核 expectedTargetRevision；built-in 直接投影 metadata，动态构建 CatalogAttempt。它不适合校验候选 upgrade。
- `EngineCommandLoop.java:14-40,62-66` 用独立 Request/result sink 持有 accepted command，以 concatMap 串行推进；外部取消 result 不会取消执行。若 deploy 内调用并等待公开 describeDefinitions，新 query 被排在当前 command 之后而当前等 query，形成自等待；即使避开排队，也仍会读旧 durable selection。
- `FibraEngine.java:389-484` CatalogAttempt 先取得 inert query 并登记；逐一读，任何普通 read 失败仍统一 reverse close；successful handle 移除，failed handle 保留；close 各终态缓存。`:487-500` 清理失败 fail-stop 并保留 owner。`:1056-1058` shutdown 先观察同一 close，不能绕过它关闭 driver/store。
- `fibra-runtime-java/.../JavaRuntimeDriver.java:93-162` DescriptorRead 在读取前进入 driver 索引，使用同一 JavaFacetDescriptorReader；同步 prepare 的 acquisition node 指向同一 read owner；成功 close 才移除。`:166-203` query 只观察同一 owner，实际读取 boundedElastic、准入与 close 共用同步边界，已开始 read 的 close 等真实终态，cached close 不重试。
- 权威 `docs/superpowers/specs/2026-10-11-definition-catalog-design.md:110-116,168-178` 明确不把 query read/close 包进有 lifecycle timeout 的 runtimeVoid，不另造取消终态，内层/外层资源失败保留依赖现场。

## 推荐的最小同 owner 集成

A. 将“本次精确 PluginPackageRecord + runtime 名称目录 + builtin captured metadata → immutable 合同值”设为同一 Engine 内部读取编排，内部不查询 durableTarget、不再次 loop.submit。公开查询先检查当前 selection/CAS，然后调用它；deploy 将本次精确 records/builtIns/input 交给它。runtime 同步 prepare 与异步名称 query 继续复用原 descriptor reader 和 resource owner；包根固定 schema 不要求修改名称 SPI。

B. Engine 先 `planner.inputs`，在 `createCandidate` 之前启动一个被 Engine 字段持有的读取 attempt；其 scope 是本次命令，不是全局 registry/cache。read 终态后先真实关闭资源，得到不可变纯合同值，再调用 fibra-config 纯 validator。普通 schema 不通过后不遗留读句柄；read/close 失败仍按既有 fail-stop 与 owner 保留语义处理。不要把 CatalogAttempt 放到局部变量后在异常中丢掉；若泛化 CatalogAttempt，应保持唯一 Engine 字段/shutdown 屏障，不增加另一关闭权。其名称可内部调整，公共 API 待设计批准。

C. 结构检查面建议沿当前 executable admission：对本次 `input.entries()` 的 effective literal 校验，按 plugin/facet/definition/revision 精确找合同；全部当前 active entry 可统一纯校验（避免新增 validated-bit 事实源），然后 runtime 仍仅对 affected entries 绑定。local/祖先/when inactive 是无值延期；package gate=false 是“已有求值值但不进入运行”的独立延期，保留其 raw 求值语义，启用时必须校验。若产品需要对 disabled package 的值做显式预览，可以另行声明为预览，不改变 apply 的停用可行性；此范围需在权威设计冻结，不能默认为用户批准。

D. “未知 schema/非法 schema”与“实例不满足合法 schema”分开：包根作者声明由 artifact 的固定资源 reader 与 config profile validator 严格读取，并与 runtime 名称目录对齐；不因 entry inactive 而宣称作者制品合格。是否在纯 install 时主动检查配置合同属于新增安装语义，现存名称目录设计未承诺，需要单独冻结；不可顺带加成无条件 Registry probe。

E. built-in 合同必须递归冻结，并同时进入 canonical identity/fingerprint。现有 `BuiltInFacet.java:13-23` 只冻结名字/依赖/能力，`BuiltInPluginPackage.java:12-23` 接受 provider 提供 digest；`FibraEngine.java:1498-1518` fingerprint 覆盖 metadata 但尚无 schema。只新增 getter 而不参与这里会让变化被 no-op/retained 快路吞掉。builtin packageDigest 是否由 caller 明确更新、自动构造或包含合同衍生摘要，需要与公开建造 API 一并设计；至少 fingerprint 内容必须真实变化。

F. 不把低层 FibraRuntime 嵌入写成经过托管合同：最终架构 `2026-09-07-fibra-vnext-architecture.md:44-48,159-168,619-629` 明确纯 core 不拥有配置/制品。`DefaultPlugins.java:54-72` 消费 Prepared，`PluginInstanceImpl.java:150-167` typed update/definition identity 校验。这些入口维持 typed 业务合同；若宿主想获得静态结构保证，应显式调用公开纯 schema validator 再绑定或使用 Engine，而不是 core 反查 package。

G. schema 错误只输出框架 code、entry/reference、keyword 与受控位置；不保留第三方原始 Error/arguments/schema/config 值或 cause 链。现有 `PluginRegistry.java:181-193` 及 Engine failure 分支会投影 `failure.toString()`，因此安全必须在 schema 库边界就完成，不能只在 UI 遮挡。任意 property 名称/JSON Pointer 也可能携带秘密，应在 profile/错误格式确定长度及内容披露策略。

## 最邻近反例与必须闭合的证据

1. 当前 v1 接受 x:string，候选 v2 只接受 x:number；同一个旧 string 配置升级必须按 v2 拒绝，durable revision 不变。读取旧 selection 会错误通过。
2. 同 package 两 facet 同名 definition、不同 schema，不能只按 definitionId 找；同 facet 两 entry 各自 literal 校验。
3. raw `{"$ref":"/port"}` 经 context 求得 number，应按 number schema 通过；`$literal` 转义产出的对象必须按对象值校验，而非再次按模板解释。
4. disabled/祖先 disabled/when=false 且缺引用：无 resolvedConfig，保存是延期而非完整验证；启用即触发求值和 schema。package disabled 仍有 raw 求值，与以上 case 分开断言。
5. Java 自定义 binder 忽略 unknown key，或 normalization 改值：schema 必须先看到原 effective literal，业务 validator 仅运行一次；schema defaults 不注入 target。
6. Node/client 激活仍可失败；同一 schema 通过不可冒称与 Java 同时完成业务检查或资源就绪。
7. close 被取消观察/close 抛错：Engine command 仍完成真实 read+close；失败 query 对象与底层 owner 可达，driver/store 不假关闭，未知状态不重试 closer。
8. builtin 只改 schema 但保持名称及其他 metadata：不得因 compiledFingerprint 相等走 no-op；保存 target 的 builtin digest 失配应按恢复 BLOCKED，而非静默替换。
9. 两并发 Apply 相同期望 revision：一个保存后另一个明确冲突；预览/发现不能替代提交 CAS。
10. 已保存 target 恢复遇到非法/不满足 schema：PRESENT+BLOCKED、NOT_APPLICABLE、可通过完整 replacement 修正；新命令预检拒绝为 NOT_SAVED；保存后执行失败保留 SAVED。
11. 精确数值超出 IEEE754 保真范围，schema 边界不能先 to double；输入值和 schema 不出现在异常 message/toString/cause/suppressed/audit。

## 来源与限制

已读取本次 task_plan、最终架构相关节、definition-catalog 设计及当前源码。已执行一次 Nowledge 针对搜索（Fibra plugin config schema DesiredEvaluation target owner definition catalog），用于定位历史 owner 约束；返回的旧启动链含已过时 ChangeSet/DeploymentManifest 术语，未据此描述现行实现。本文现行事实均以源码复核为准。没有用局部编译/测试替代正式消费资格；此报告仅供设计与独立审查。

## 包根固定合同候选的补充审查

主线程提出“固定包根 fibra-config.json、轻目录+按需查询、RuntimeDefinitionQuery 不增 schema”的方向，没有 owner 层级矛盾，并使 Java/Node/client 消费同一通用合同更加直接。具体成立条件如下：

- 包内容身份已经覆盖整个 root，不只 facet payload。`PluginPackage.java:108-130,275-300` 使用根目录摘要；因此固定 schema 文件的字节变化自然改变 packageRevision，无须第二 schema registry/version selection。不过 schema 格式自身版本/方言仍需显式冻结。
- `PluginPackageRecord.java:17-29,44-48` 有受管 location；`ManagedPluginPackage.java:11-15,53-56` 没有 root。当前 Engine deploy `:609-613` 立刻把精确 record 映成 managedPackage；应保持本次 exact record 局部值供固定资源读取，不从某 facet payload 的父目录倒推包根，也不为了读取再使用 durable selection 查一次。
- **尚不存在可直接复用的 artifact 异步/保留失败文件 owner。** `PluginPackage.snapshot` 使用 `Files.readAllBytes` (`:119`)，其 digest walk/input (`:279,:317`) 和 Store properties (`PluginPackageStore.java:333-337`) 使用 try-with-resources。它们能报告关闭失败，但并非目录设计所要求的“失败后原句柄可达、终态缓存且不重试”的接口。因此“复用 artifact 静态资源 owner”只能描述目标分层，不能描述已具备的源码事实。本次需补一个固定资源的真实文件 owner，或选择完全由既有 package 读取阶段产出不可变字节且同时证明该阶段资源失败协议的方案。
- 最小关闭权模型：一个 Engine 读取 attempt 持有名称 query handles 与包资源 read handle；每个底层 stream/JarFile 只有其 runtime/artifact owner 关闭，Engine 只观察对应 close 终态。不需要所有句柄强行实现同一公开 SPI；小型私有 close 适配只做编排，不另存资源/refcount/终态。不要把 runtime 私有 DescriptorRead 搬给 artifact 以形式统一。
- 可顺序读取/释放名称 queries，再读取固定合同；这样不必共享运行时 descriptor 与 schema stream 的寿命。若 read1/close1 已失败，后续不读取 schema；若先读 schema，schema close 失败也不能丢 owner继续名称查询。精确 immutable package record 作为值可保留，两者不需要共同 ClassLoader/lease。
- raw byte reader 应有固定文件名、字节限制、拒绝 symlink/非regular、只由精确 record root 解析；不给消费者任意 Path/URL 读取能力。读取 schema 后需确认其仍属该 revision（沿 package store 不可变对象与现有校验，不凭文件名信任）；当前存储实际是可写文件系统，不能夸称任意外部篡改窗口不存在。
- 名称是 runtime 目录唯一事实：配置文件只能引用其 facet/definition，重复引用/未知引用拒绝；不要从配置文件建立第二可创建类型目录。无 config 合同的 definition、空 contract-only facet、多 facet 同名和 builtin metadata 必须有具体定义。
- DSH 参照已在 `docs/superpowers/references/2026-10-11-definition-catalog-evidence.md:93-100` 固定至 c291e7961a515f6d7af9304e7fd1d257929aef26：execute 前登记 wrapper、异步清理可 join 支持“先登记 owner”；但失败后移除 wrapper/仅日志的终态不能直接采用为 Fibra 失败现场保证。无需为本候选增加第二生命周期机制。

## EngineChangeException 与“不回显配置”的精确边界补充

### 已核事实：仅清洗 cause 不能承诺整个异常对象无配置

- `fibra-engine/.../EngineChangeException.java:6–20` 持有 `PublishedView view`，公开 `view()`，super message 仅由 TargetSaveState 决定（`:22–28`）。
- `engine/publication/PublishedView.java:9–13` 包含 EngineSnapshot；`engine/observation/EngineSnapshot.java:7–13` 包含 Optional<DeploymentTarget>；`engine/deployment/DeploymentTarget.java:26–31,55–59` 包含 raw desiredGraph 与 configContext。
- `DeploymentTarget.java:93–98` 的 toString 也包含 graph/context。故 `change.view()` 对象图本身可达配置，`change.view().toString()` 也可能输出配置。新 Apply 拒绝通常携带既有 durable target，而非未经保存候选；仍可能含秘密。不能因“不是本次非法输入”便说无配置。
- `FibraEngine.java:863–949` deploymentFailed 各分支沿 EngineChangeException 包装；`:178–193` 启动失败重订阅也构造 EngineChangeException；`:1428–1438` FailureFact detail 用 `error.toString()`。因此 schema 库原始 Error/arguments/cause 清洗仍必要，但不是对象图保证的充分条件。

### 现公开输出/调用路径

| 路径 | 实际消费 | 现边界 |
|---|---|---|
| Fibra CLI | `CliApplicationRunner.java:306–317` 输出 message 经 safeDiagnostic，再据异常类型给 exit code | 不序列化 exception.view；全体 EngineChangeException 目前按 CLOSING 分类，schema 专用 exit code 若要改应单列行为变更 |
| Registry 审计 | `PluginRegistry.java:174–194` 读取 targetSaveState、view.viewRevision、failure.toString | 不输出 view/graph；schema安全message/cause仍应在最内边界建立 |
| Registry 管理快照 | `snapshot()/get()/list()` 是显式管理读；CLI `FibraCli.java:337–352` 又只打印身份和存在性 | 不应把完整 snapshot 当通用错误 DTO |
| Tela HTTP | `TelaHostGateway.java:429–437,544–574` 返回 text/plain，包含 targetSaveState、viewRevision、root-cause message | 当前不做 Throwable/PublishedView 自动 JSON 序列化；root cause 若残留原值仍会直接回显 |
| Tela ready 保持 | `TelaHost.java:232–261,279–284` 在 NOT_SAVED 时拿异常view与当前view比较 target/current/surfaces | 是实际语义消费者，不是仅为诊断读取；schema专用新异常会让它落入另一分支，可能把原可保持ready的拒绝误分类 |
| Spring starter | 只装配 Engine/Registry，没有通用异常 HTTP 序列化器 | 不可替任意第三方 Spring Controller 的自动序列化配置背书 |
| 现测试/签名 | `RuntimeDriverEngineTest.java:2314–2327`、`PluginRegistryTest.java:244–249` 明确依赖失败时view；`docs/api/fibra-engine-public-signatures.txt:32–38` 固定公开构造器与view | 移除/置null/裁剪view都是材料性契约变化 |

本轮没有发现 Fibra/Tela 上述产品路径自动序列化整个 EngineChangeException。不能由搜索未命中推出所有仓外消费者均安全，也不能承诺任意日志/反射序列化器不会遍历 view。

### 推荐：本期保持 EngineChangeException，明文限定诊断与错误传输合同

**推荐路线 A，满足“错误不回显配置”的输出语义，并保留既有失败事实消费者。** 不新增 schema 专用顶层异常，不把 view 改 null，不伪造裁剪的 PublishedView。

权威设计应使用以下准确保证，而非“异常对象不持有配置”：

> 配置合同读取/编译/校验的诊断值、异常 message/toString、框架日志、FailureFact、审计 detail 与官方错误响应不得包含实例配置值、完整 schema、第三方原始错误对象或敏感 cause/suppressed。EngineChangeException 继续携带既有管理面 PublishedView，用于失败事实一致性；它不是可直接序列化的错误 DTO。查看 desired/config 仍属于授权管理数据访问。官方错误响应必须显式投影受控 code、TargetSaveState、viewRevision 与安全诊断，禁止透传 Throwable、PublishedView 或 DeploymentTarget。

更精确地说，`view()` 已是一个现有公开访问器，但不是“重新读取当前 snapshot”；它保存的是失败时事实。保留它意味着不能承诺“取得异常本身不会附带任何配置对象”，也不能写成“所有配置只通过 published.current 显式获取”。这不是新的安全例外，而是当前公共管理合同的真实能力边界，必须向用户呈现，不能悄悄缩小其要求。

实施落点：schema/profile 边界构造无原值引用的诊断对象，并在包装前断开第三方 error/cause/suppressed；继续既有 EngineChangeException + saveState。需要结构化 schema 诊断时使用本次已经要定义的配置诊断值，不再建立一套通用错误框架。官方日志/审计/HTTP/CLI只白名单投影。不要仅加 Jackson Ignore 注解：那既引入序列化器耦合，也不能断开Java对象可达性。

验证应分两层：

1. 对新的配置诊断/库边界异常，递归检查它自有字段和cause/suppressed不存在有效配置、schema或第三方错误图；确保可安全打印。
2. 对实际 Engine/Registry/CLI/Tela 输出，用不同 sentinel 分别放入旧durable config、候选config、schema注解/枚举/属性名，断言官方错误响应、message/stack trace/审计/FailureFact均不含 sentinel。不要对整个 EngineChangeException 做“无任何config可达”的断言后虚假声称通过；相反需显式测试失败view仍保留真实管理事实且不被自动输出。

材料影响：schema诊断新值/API本已属于本次批准范围；异常类、保存状态、失败时view、Registry与Tela ready策略不变。新增文档需明确错误投影与管理访问边界；隔离Tela验证可复用真实gateway errorBody与ready保持场景，未经授权不改主树。

### 如要求确为“整个 Throwable 对象图绝不能有配置”：路线 A 不合格，需独立批准路线 B

此更强保证只能由**统一无配置 Engine failure receipt 替代异常view**实现，不建议仅 schema 走旁路：

- receipt 只保存失败时 operationId/kind/stage/outcome、TargetSaveState、viewRevision、durable target revision（需要时）以及受控FailureFact，不包含 PublishedView、DeploymentTarget、raw/effective config、schema、Supplier<PublishedView>或能反查配置的闭包。full管理读继续 published.current()。
- 保持一次失败时的receipt不可变，不能在调用viewRevision时现场读current，否则并发下一命令会替换失败事实。读当前snapshot后用receipt.viewRevision比较；不相等则不能据旧失败认定当前ready，转显式当前状态判断。
- 不能以“保留view字段但deprecated/不序列化”声称对象图安全；字段仍可达。不建议仅schema返回新异常，因为EngineChangeException已final、Registry/Tela/CLI均按它识别保存状态，旁路会制造两套变更失败处理且更易漏恢复/close分支。
- 真正调整EngineChangeException需同步构造器、view访问器及 public signature、Fibra所有包装点、Registry审计revision读取、CLI异常分类、引擎测试、TelaGateway错误格式和TelaHost ready逻辑；其他仓外Java消费者必须列迁移清单。即使字段用名字沿用，也属于源/行为兼容变化，不能默默换成不完整PublishedView。
- 单独schema专用receipt/异常路线虽表面触及文件少，但会破坏现消费者对 NOT_SAVED 与 exact-view 的识别，且通用上层包装仍可再附view；不是最小可靠修复。

因此本期推荐 A，并把“输出安全，但现管理异常仍可达管理快照”作为可审阅边界；若用户坚持对象图零配置，必须暂停此部分API落定并准备 B 的完整材料影响，不能把 A 宣称满足 B。
