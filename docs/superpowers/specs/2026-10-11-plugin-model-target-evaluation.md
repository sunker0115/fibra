# Fibra 插件模型的最终架构评估

2026-10-11。状态：最终架构评估及推荐已独审；用户已明确批准声明级目录、D38实施及隔离Tela消费验证。全栈组合schema及归属合同尚未冻结，不恢复Tela P3。

## 1. 评估起点

用户补充的上游变更原则：Fibra是通用插件内核，不能因Tela需要就增加产品能力；必须先证明通用缺口或通用缺陷。下文Tela场景用于验证消费资格，不将产品默认组合、共享界面政策或业务形状提升为Fibra契约。尤其后续组合元数据若不能证明为通用包能力，应保留在Tela；当前未冻结、更未授权实现其Fibra schema/API。

用户明确要求从最终架构指导评估，且不预设简化。本稿先由最终能力推出职责，再审视现有模型；既有代码、测试数量、兼容成本均不能单独证明抽象应该保留。迁移影响放在后面，不反向决定目标。当前[目录设计](2026-10-11-definition-catalog-design.md)据本稿收敛为声明级候选，不是本稿前提；原 metadata 目录路线已被替代。

最终需求来源：

- Fibra vNext §1/§2.1：受信任的 Java、Node、内建及外部 runtime 进入唯一目标/生命周期/发布模型；配置树、静态制品依赖、服务图、资源所有权分别建模。
- Client Foundation §1/§4：Fibra 是通用内核，产品拥有 client runner/transport/业务；版本选择与物理执行分离，同一类型可以有多个独立配置与生命周期的实例。
- Tela `docs/architecture/overview.md` §1、§2.4、§6、§12.1：同一入口管理 Host/CLI/Desktop/full-stack 逻辑插件；同一包跨执行域，升级/停用只影响依赖闭包；一次 full-stack probe install/enable 出现真实 Host contribution、CLI 命令和 React 页面。这是最终产品承诺，不是当前发行包已经做到的事实。
- Tela `docs/architecture/product-target.md` §1.1/§4.2：未知已选包须可发现可创建类型；插件拥有配置规则，runtime 解释私有制品，产品负责组合；目录不等于配置表单或运行就绪。

此前以当前 17 个发行 manifest 均单 facet 判断最终必要性不充分：它们证明当前使用形状，不能推翻已明确的 full-stack 目标。反过来，某个集合类型有测试也不能直接创造业务必要性。

## 2. 由职责推导的语义模型

| 概念 | 独立问题 | 唯一 owner 与不变量 | 如果合并会施加的限制 |
|---|---|---|---|
| Package | 用户安装、选择哪个完整版本，整体启停哪个逻辑插件 | PackageStore 提供不可变内容；Engine target 保存精确 selection；产品提供政策 | 与单一 runtime 合并会把 full-stack 插件拆成多个用户安装/升级对象，或者需要另加等价组合层 |
| Facet（名称可讨论） | 这一部分 payload 由谁解释，依赖什么静态代码，在哪里执行 | 一个 RuntimeId 的 driver 管理实际装载/释放；包内 facet 有稳定身份、payload、依赖及 placement | 与 package 合并会丢失跨 runtime 组成；与 definition 合并会强制每种类型各建载入边界，并难以表示无可创建入口的 contract payload |
| Definition | 同一载入资源对外提供哪一种可创建类型，其配置/工厂合同是什么 | 插件定义类型，runtime 绑定与校验；不持有用户实例状态 | 与实例合并会破坏同类型多配置/多实例；与 facet 合并会把类型数等同 payload/loader 数 |
| Desired entry / runtime instance | 用户创建了哪一份配置，当前是哪一代实际执行 | 唯一 target 保存 desired；runtime/Scope 管理实际代与清理，Engine 编排 | 与包/类型合并会限制多工作区和独立实例启停，或把实例状态藏入插件内部 |

Runtime 是解释与生命周期策略，不是 package→facet→definition 之后的另一层目录；ExecutionTarget 是执行位置。CLI command 是贡献形态，不另设 command runtime。Package 的完整保存/选择不等于所有执行域瞬间 ACTIVE，client 离线仍须能如实 PENDING。

建议的语义基数：一个 package 有一到多个 facet；一个 facet 属于一个 runtime、提供零到多个 definition；一个 definition 可被零到多个 desired entry 使用。独立可关闭的实例可以共享 driver 内静态资源，最后的资源使用者退出前不能提前释放 loader/payload。

这些语义独立不意味着增加四套管理器或生命周期状态机；definition 本身不拥有另一套资源清理状态，具体绑定对象仍归 runtime。

多 definition 的理由是“多种可独立配置的类型共享同一代码载入边界”，不是多实例。它是模型允许的能力，不要求动态 Java/Node 为本次目录实现立即扩为多入口制品。

## 3. 架构路线对比

| 路线 | 成立条件及结果 | 评估 |
|---|---|---|
| 一个包固定一个 runtime、一个入口 | 系统只承诺单执行域插件；组合由另一层安装对象负责 | 不适合作为当前最终目标，会把 full-stack 管理责任推到第二层 |
| 多 facet，但每 facet 固定一个 definition | 类型与物理模块天然一一对应；纯 contract 需例外 | 可用于简单作者约定，不适合作为内核普遍约束；需要多类型共享资源时又会长出隐藏载入组 |
| 保留发行、载入、类型、实例的独立语义 | 各层只拥有自己的事实，创建消费完整引用，资源不泄漏 | 当前推荐；理由来自最终职责，不以原签名已存在为理由 |
| package 直接导出类型，载入模块全部内部化 | 需要“类型迁移到另一 facet 仍保持公共身份”等明确兼容目标 | 可作为另一套公共身份设计；当前尚无该要求，不另加 alias/export 注册表。UI 隐藏 facet 不要求改变持久引用 |

普通管理者需要看到包、可创建类型和实例；facet/runtime 应用于作者声明、精确引用和技术诊断，不应强制变成创建向导里的连续选择页。公共目录返回完整可用引用后，产品可以直接传回该引用；不能靠产品猜 facetId/definitionId 或把名称等同 packageId。

## 4. 需要闭合的两条能力链

### 4.1 发现、准备、实例化

目录发现回答“这个精确版本声明提供什么”；准备验证回答“在当前依赖、配置和宿主能力下能否绑定”；实例化/协调产生实际运行事实。三者不能用一个成功标记替代。

| 名称来源 | 最终合同与局限 | 判断 |
|---|---|---|
| 原 runtime 执行受信 metadata 后列名 | 可不启动业务实例，但 Java 会初始化类/构造入口；需要可解析依赖，并可能阻塞管理 lane。失败资源仍须有 owner | 可满足当前已确认的受信 metadata 合同，不因 API 较少就自动成为最佳最终方案 |
| 制品显式声明或构建期派生目录 | 管理读名称不执行目标插件代码；静态声明和实际 runtime 绑定须强制一致，目录成功不保证可运行。派生数据可重建，不能再有人工维护的第二套自由名称 | 若最终要求依赖不齐或插件不可装载时仍能展示声明，这是更合适方向；需修改作者/制品合同，不能把它写成已经批准的要求 |

无论选哪条，仍只由原 runtime 持有 Class/factory/loader，Engine 和产品只消费值。Java 取得失败的现存漏洞必须在原 driver 修复，不能因为改成静态目录而忽略部署路径 D38。当前目录范围是 selected 包；扩为未选包/全库搜索另有真实消费者才进入范围。

disabled 本身不要求静态来源：当前 metadata 候选已允许查询停用包。静态查询也有不同层次：provider 已安装时，Node/client 可沿原 reader 读取已有名称，Java 补充自己的声明/派生信息即可，不必让所有 runtime 改同一种索引格式；此层可以不要求目标插件的静态依赖可装载。只有要求 provider 缺席时仍能读名称，才需要 PackageStore 可理解的公共身份段或等价的不依赖 provider 的声明投影。缺静态依赖与缺 runtime provider 是不同条件，不能统称“离线”后混为一个要求。

### 4.2 单个类型创建与全栈组合装配

目录中存在多个 definition，不能据此判断它们是替代选项、可独立创建的类型，还是一个功能必须共同创建的组成部分。枚举全部后自动启动全部是不成立的默认规则。

最终 full-stack 目标还需要明确“由谁把包中的若干类型和配置物化为一组 desired entries”。当前产品固定组合由 ProductBaseline/Planner 负责；当前第三方安装提案是 install 选包、目录选择类型、再创建 entry。这证明两条入口意图不同，不证明任意未知 full-stack 包的自动装配已经闭合。

推荐保持清晰分工：Fibra 负责通用身份、完整目标、CAS、依赖和生命周期；产品负责调用与用户输入；插件特有的组成关系不能靠 Tela 按 pluginId 硬编码猜测。全栈插件一次管理操作共同生效是已有最终承诺，待决定的是其装配意图/输入怎样表达。对未知包，需要显式、可验证的组合输入/声明，并物化到同一个完整 target；不得建立第二运行状态或在每个 facet 偷建独立入口。

此处记录最终能力缺口和 owner 约束，尚不选择新 descriptor、模板语言或自动创建 API，也不恢复 Tela P3。目录是必要前提，但“选择一个 definition”不足以证明上述 full-stack 完成标准。

DSH 标准 Web 链提供了更具体的参照：Host Loader 中包的 `dsh.client` 声明投影为按包名去重的 browser row，每个 Web boot 对该 row 创建一次 client entry。同包多个 Host entries 不对应同数量 client entries，当前页面也不消费 graph 增删直到 reload。因此，先区分“包级共享前端”与“后端每实例伴随一个前端”两种最终语义；全栈目标本身不能推出后者。装配声明可以很小，不应因需要表达关联就预建通用模板 DSL。源码位置及当前动态更新限制见[DSH 证据补充](../references/2026-10-11-definition-catalog-evidence.md#dsh-的-hostclient-自动关联与实例数量)。

## 5. 依赖与失败边界

必须分别说明静态代码依赖、执行先决、服务选择和关闭顺序。当前 `DeploymentPlanner:73–77` 将 facet 静态边展开到被依赖 facet 的全部 active desired units，`FibraEngine:610–613` 又要求这些 unit ACTIVE 后才 reconcile consumer。这是当前粗粒度部署先决合同，不是 service realm 的实际 provider 选择。

本轮没有运行反例，不将其直接判成 bug。但评估多 definition 时不能忽略：共享同一 facet 的不相关类型/实例可能被同一边覆盖。最终验收需用“一个 facet 内两种类型或两个 realm，其中一个 provider PENDING”的场景证明阻塞/替换范围符合声明；若需要更细语义，再由真实 owner 明确边的含义，不能在目录内修补。

无论模型取舍，类型发现、绑定和启动失败必须区分；新目标保存与运行失败必须区分；资源关闭失败不能清除 owner 或先释放先决依赖。这些是执行正确性要求，与 ID 数量无关。

## 6. 参照与证据边界

- DSH 固定源码的 `name → import → unwrapExports → registry.plugin` 把模块 specifier 当入口，entry 配置仍产生独立实例。直接采用入口可发现及 owner 先登记原则；不据此要求 Java/Node/client 共用 JS 模块边界。既有源码位置见[目录证据](../references/2026-10-11-definition-catalog-evidence.md)。
- [VS Code manifest](https://code.visualstudio.com/api/references/extension-manifest) 分别声明包身份、main/browser 与 extensionKind。仅支持“发行对象与入口/位置不同”的局部判断；不证明同时运行所有入口或 Fibra 跨域事务。
- [Terraform provider](https://developer.hashicorp.com/terraform/plugin/framework/providers) 返回多个 resource factory，每个 resource 实现管理自己的类型名，实例操作不应意外共享数据。仅支持“provider/类型/实例可分离”的局部判断；不引入 Terraform state、RPC 或声明其发现完全静态。
- 17 个当前发行 manifest、built-in/client 多定义测试和三 facet artifact 测试只证明当前使用与兼容面；不能替代最终目标与场景资格。

以上外部资料于 2026-10-11 读取，使用官方一手资料；参照未作为新增依赖。

## 7. 决策与后续门

建议保留多 runtime、多 facet、facet 内零到多个 definition 的语义容量；收敛公共操作到安装包、发现/选择类型和管理实例，并明确全栈组合的物化责任。此结论不要求沿用当前每个类型名或 SPI，也不自动批准原目录签名。

目录发现推荐独立于目标插件执行的声明查询；具体 API、Java 制品字段和迁移影响已在目录候选列明。用户明确不需要兼容代码，并已批准本期实施，provider/制品/消费者直接迁移。全栈安装的组合意图如何输入、哪些属于目录之外的产品用例另需明确，不能通过“按现有实现”或“默认全部创建”跳过。

本期按目录候选分别实施目录、D38修复和消费者验证；持久 target 三段引用、manifest、Java/Node/client/built-in SPI 和外部消费者的迁移成本只用于选择实施路径，不用于否决正确的最终边界。不提交代码、不恢复 Tela P3。

## 8. DSH 核验后的具体推荐与验收

本节给出主线程的目标推荐。用户说明不知道哪条最合适，主线程据此承担选型判断并细化设计；该答复不等于批准 API、制品或实现。

1. 保留发行包、载入部分、可创建类型、配置实例四种独立语义。一个包可以跨 runtime；一个载入部分可以没有入口，也可以提供多个类型；同类型多个实例各有配置和生命周期。普通插件无须用满所有基数，管理者无须逐层手填身份。
2. 推荐目录以不可变制品的显式声明回答可创建类型，在各 runtime 的实际装载阶段核对所用声明与入口。管理展示与执行验证成为独立阶段。Java metadata 可在 prepare 核对；内建实际集合在其 definition 被 prepare 使用时核对，仅 selection 不保证已核对。Node 准备只验证描述符/入口，client 所选 ESM 导出在客户端加载时验证，当前不具备全量集合校验。具体阶段和未闭合窗口见目录候选§2。这里推荐 Java 显式名称，避免把名称发现转成构建期执行 Java 插件的隐含要求。
3. 目录仍由各 runtime 解释私有格式，Fibra 提供统一值与入口；不因名称变成静态就把产品 descriptor 搬进 Engine。当前选包消费者不要求缺 provider 仍可查询，因此暂不建议公共包身份索引。目录名称可见不代表依赖可载入、配置有效或实例可启动，错误必须按阶段区分。
4. 全栈自动装配应来自明确的包关联/管理意图。DSH 的包级共享 client 说明不必给每个 Host entry 配一份前端；若插件确实要求实例级配对，就显式表达。Fibra 可以承载两种结果，但不根据 definition 个数猜组合、不自动把所有类型实例化。具体装配输入属于后续合同设计，不预建模板 DSL。
5. 所有展开结果进入同一完整 target，由原 CAS、driver 和 Scope 生命周期收敛。不能照搬 DSH 当前页面直到 reload 才更新 graph 的限制：Tela 最终启停/升级仍需同时协调声明的执行面，客户端离线如实 PENDING，无关资源保持。
6. D38 继续修原 Java 取得协议。静态目录避免名称查询取得 ClassLoader，但部署准备仍执行 metadata/bind，既有失败资源丢失并不会随查询来源变化消失。

原 runtime metadata 目录的成立前提是接受依赖齐备、受信代码执行和管理 lane 等待。它满足较窄发现保证；不能仅因保留制品格式就确定为最终方案。主线程已据上述推荐重写目录候选：root-only声明查询、单facet静态句柄和Java单名称，不增加compiler解析入口。无兼容迁移增量另行独审，不先实现旧方案再并存另一目录来源。

| 最终架构反例 | 需要证明的行为 |
|---|---|
| 同一包含 Host/client 两个执行部分 | 一份精确版本选择与包 gate，两个 runtime 各自持有资源；保存/运行状态分离 |
| 同载入部分两种工厂、同类型两个配置实例 | 类型选择正确，资源可共享而实例互不折叠；多贡献不会被误计为多实例 |
| contract-only 部分或依赖未齐的声明查询 | 空类型集合与不可读取声明不同；声明可见不被描述为准备成功 |
| 声明与实际入口不一致 | Java/built-in在prepare拒绝；client所选导出缺失在实际加载拒绝，不能宣称保存前或全量集合验证；Node遵循既有单入口协议；不回填目录，target保存与运行失败分开 |
| 包级共享前端与逐实例配对前端 | 前者不按 Host entry 数复制，后者只按显式关联物化；不能由基数猜测 |
| full-stack 停用/升级与离线 client | 同 target 的明确执行面协调、围栏与排空；无关实例资源保持，离线不假 ACTIVE |

这些是最终语义的验收要求，不声称本次已运行。目录交付仍按原总目标推进；full-stack 装配边界不会自动扩成 Tela P3 实现授权。

## 9. 最终插件形态与 Tela 调整建议

本节回答用户“最终形态是什么、是否影响Tela、怎样调整”。这是目标推荐及差距，不是当前可解析的新composition协议，也不扩大本期目录实施授权。用户明确不需要兼容代码；新SPI、Java描述符和消费者直接迁移，详见目录候选§5/§8。

### 9.1 作者交付一个完整逻辑包

简单插件可以只有一个执行部分和一个入口；全栈插件把相关执行部分放在同一个包里，共用精确版本与package gate。不是每个插件必须具备全部形态，不为凑结构加空payload。

例如一个GitHub插件的目标形态：

```text
github/
  fibra-package.yaml        包身份、版本、facets、执行位置、静态依赖
  lib/host.jar              后端工厂及服务/工具/CLI贡献
    META-INF/fibra/plugin.yaml
                            Java entrypoint与必需definitionId声明
  client/                   浏览器模块、资源和原client类型声明
  启用组合声明              哪些完整类型引用共同构成功能、配置输入如何分配
```

末项是最终全栈能力仍须新增的通用包声明语义，字段和物理存放位置尚未冻结，不能把这棵树视为当前可安装fixture。CLI通常是后端definition的贡献，不是第三个runtime或必需第三个实例。Java可继续一个facet一个入口；同一个definition可以创建多份配置独立的连接器。client同一模块可导出多个可创建类型，注册多页面/renderer则不必产生多个类型。

声明分为两种：类型目录给出“能单独创建哪些类型”；启用组合给出“使用某项功能需要一起配置/创建哪些类型”。后者不能从类型数量推断。插件特有组成关系来自包作者；Tela不能为未知包按名称硬编码，也不能把所有目录项自动实例化。

### 9.2 默认组合与实例数量

推荐普通全栈扩展采用包级共享界面，后端实例按用户配置创建。这里的“共享”表示一份逻辑client desired entry服务多个后端实例，不是不同浏览器窗口共享同一个JavaScript对象。各符合执行target的客户端仍拥有自己的模块执行与Scope。

| 项目 | 例子 | 数量/职责 |
|---|---|---|
| 精确包selection | github@digest | 一个逻辑发行版本及gate |
| 后端类型 | github/host/connector | 可创建connection-a、connection-b两份独立配置/生命周期 |
| 共享前端类型 | github/ui/panel | 一份逻辑界面entry展示两个连接器，每个符合目标的client独立执行 |
| CLI贡献 | github list / github sync | 由后端实例注册；多实例时按明确实例路由，不能产生名称冲突 |

如果某插件要求每个后端配专属界面，组合必须明确配对身份和配置；不是系统的隐式默认。纯CLI/headless入口不为没有Desktop的运行环境强建client entry；Desktop首次加入应按既有唯一target设计规则物化相应界面，离线已保存client保持PENDING。当前只有固定optional界面装配，不声称通用组合物化已经实现。

一次“安装”保存完整包，不启动实例；首次“启用这项组合”在配置齐备后展开明确的desired entries，并经一个完整ApplyDeployment/CAS保存。再次启用必须幂等且保留既有配置和子entry的显式disabled，不重建已经存在的实例。package gate停用/升级覆盖该包明确执行面，实际协调与保存分开，不能承诺跨runtime分布式ACID。

组合展开要有可验证的实例归属：选定的组合、完整entry身份/父级和类型引用必须可以从同一个target重建或校验。不能靠id前缀认领未知实例，也不另存组合实例数据库。包级共享entry与连接器配置实例分别具备精确引用；处理最后使用者移除、共享界面启停及升级保留配置时，必须先冻结这些归属/引用规则。该窗口是组合合同的真实后续设计内容，目录不能替它解决。

### 9.3 Fibra与Tela的调整边界

| 层 | 最终调整 | 当前状态与影响 |
|---|---|---|
| Fibra包/制品 | 静态类型声明；全栈组合关系作为包级可验证数据并由公开元数据提供给产品 | 类型目录候选已设计；组合schema/API还需独立设计，不能写成当前manifest已接受新字段 |
| Fibra执行 | 原完整target、精确选择、CAS、准备、围栏、排空与资源owner继续唯一；组合展开结果仍是原desired entries | 不增加createWholePackage旁路，不由Engine解释Workspace/Session/React；Java D38仍需实施 |
| Tela client runtime | 实现新必需静态目录SPI，复用TelaClientArtifact；实际client模块仍由原runner/Scope装载关闭 | 是破坏性SPI消费更新；client wire和React工厂不因目录必然改版，当前find仍未核全量集合 |
| Tela产品计划 | ProductBaseline声明Tela自己的required/optional和固定默认组合；ProductTargetPlanner消费未知包公开类型/组合+用户输入生成完整候选 | P3目标owner已设计，当前生产只有FibraPackageBootstrap/ManagedEntryOverlay；不能宣称Planner已经存在 |
| Tela管理入口 | CLI/Desktop调用同一Host用例；包启用展示组合、类型创建展示完整引用，保存结果与ACTIVE/PENDING/FAILED分开 | 目录闭合后才开放未知类型创建；自动全栈启用还依赖组合合同，不能仅凭目录完成P3 |
| Tela发行 | agent/model-api/model-deterministic/model-openai四个实际带entrypoint的Java包补必需名称，合成可执行fixture同步更新并重新打包；固定完整引用与公开Fibra依赖同步核对 | 现agent和agent-view可继续两个产品baseline包；只有需要同一安装/升级单位时才合包，不机械合并；model-api不能按名称视作contract-only |

源码依据：当前 `tela-distribution/src/main/tela-plugins/tela-agent/fibra-package.yaml` 与 `tela-agent-view/fibra-package.yaml` 分别是单Java/client包；`tela-agent-plugin/.../META-INF/fibra/plugin.yaml`只有entrypoint；`FibraPackageBootstrap.java:38–56,115–150`含固定清单和旧编排；`ManagedEntryOverlay.java:32–57`是现有声明解析。Tela `product-target.md`§1的ProductBaseline/Planner仍是待实现设计，不能从文档中的类型名推断生产已实现。`TelaClientTransportHub.java:299–303,309–322`按符合target/capabilities的client分发同一逻辑unit，支持区分逻辑entry与实际client执行，不证明新组合归属已闭合。

### 9.4 推荐推进顺序

先完成本期Fibra静态目录+D38及公开制品消费，直接迁移SPI/Java声明，不保留兼容代码；再冻结最小全栈组合及归属合同；经单独恢复/启动授权后推进Tela P3的唯一Planner和CLI/Desktop管理用例。最后以真实一个全栈probe的一次启用、两个后端配置实例共享界面、停用/升级/离线恢复及无关资源保持验收最终形态。Session/Work/Approval业务无需因目录模型改写，只有真实组合归属需求才扩其相关合同。
