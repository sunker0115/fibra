# Definition 目录取证笔记

基线：`7a7136c449a0a399ab4c12cd00c5ae54ffae84b7`。

- Tela 交接提案仅候选，未获具体 ABI/协议批准；当前目录缺口 D37 与 Java acquire 失败归属 D38 不应作为已解决事实。
- Fibra 权威设计要求取得资源后、后续可失败操作前登记 owner；清理失败保留资源与依赖，mutation fail-stop。
- 当前 `RuntimeDriver.probe/inspect` 是 artifact 检查，不具备未知 definitions 列表及完整精确依赖输入。扩其隐式成本会改变安装语义。
- 当前公开签名与已编译 API 为实现事实；新增设计独立文档必须标识未批准，不能暗改既有 API 说明为已提供。
- 本地 memory 的 package refactor 提醒需检查所有外部消费者与公开签名，已现场核验 HEAD；旧验证不能作为本次目录资格。

## 本轮直接核验

- `DeploymentTargetCompiler:29–66`：selected.enabled 与 dependency.enabled 是部署 gate。原metadata路线需要root闭包；声明级候选无需依赖解析，不新增compiler用途，不伪造enabled target调用完整编译。
- `EngineCommandLoop:30–39,59–64`：submit 立即入队并独立持有执行；公开目录必须外包 Mono.defer，不能直接调用裸 call，也不能取消 read/cleanup。
- `FibraEngine:835–897`：candidate/retirement 阻断下游 shutdown；一般 driver/store actions 是 cleanupAll。目录失败 owner 必须在进入一般 shutdown actions 之前形成同样的依赖屏障。
- `JavaRuntimeDriver:138–230,316–362`：取得依赖、loader、binding/prepared 之间存在多个未登记窗口；外层统一close不能覆盖局部变量丢失。声明查询避开ClassLoader，但原部署D38仍需修。
- `JavaClassSpace:34–59`：类初始化、构造器、definition 方法真实执行；LinkageError 被局部包装，但 definition 直接 AssertionError 等非 LinkageError 路径未被原 acquire RuntimeException catch 覆盖。
- `JavaBuiltInPackage:39–61`：私有集合与metadata精确一致性归prepare owner，包括空集合；声明查询可回显Engine已捕获的公开metadata，但不能说已验证实际集合。
- Node descriptor 直接持 definitionId；TelaClientArtifact.inspect 校验 resources/digest 并返回 definitions，查询不证明 ESM 导出或 renderer 在线。
- PF4J 本地固定 sources `3.15.0` SHA-256 已与既有参考核对一致；AbstractExtensionFinder.findClassNames 与 processor 的写索引体现静态派生路线，但索引是 Java 类名而非 Fibra definition().name()，不能直接充当本项目权威。
- 2026-10-11 查 JDK21 URLClassLoader.close 官方说明：best effort 文件关闭、unchecked/Error 不被捕获、与并发类加载交错行为未定义。保留失败句柄/终态是 Fibra 额外协议，不是 JDK 自动保证。

## 用户追问模型必要性

- 权威 Client Foundation §4 明确：动态 Java facet 产出一个 definition（contract-only 无 definition），Node P0 一个 definition；built-in 与 client-api 的 definitions 集合允许多个。不能说所有 runtime 目前都是多定义。
- 同规格 §4/客户端 factory 段明确：同一个 definition 可被多个 desired entry 引用，生成独立实例。因此多实例需求不能作为一个 facet 多 definition 的论据。
- 多 runtime 对应不同代码解释/生命周期 owner；多 facet 允许同一逻辑 package 合并多个执行部分，属于发行管理选择。已核 Tela `tela-agent`、`tela-agent-view` 实际各为单 facet 独立包，不能说 Tela 当前必须组合成一个多 facet 包。
- 两个单facet包不足以证明全生态形状；后续有界扫描及最终目标核对见下项。不能据当前使用量授权删除公共合同，模型/兼容变更仍须明确批准。
- 后续有界扫描扩到 Fibra distribution 全部 12 个 manifest 和 Tela distribution 全部 5 个 manifest，均为单 facet；确认三 facet artifact 测试和多 definition Java built-in/client 测试消费者。区分发行形状、既有合同与产品必要性，结果纳入候选 §1.1；不等同全生态消费者审计。
- 用户要求从最终架构评估且不预设简化后，重新读取 Tela overview §1/§6/§12.1：明确全栈逻辑包统一管理、一次 install/enable 同时出现三端贡献。因此多facet有最终需求依据，不能用现阶段单facet使用比例推翻。
- runtime 独立审计确认四种身份不等于四套owner；同资源域多工厂/config是多definition的理由，多实例不是。当前built-in多项只找到测试，client真实loader按id分派但Agent产品仅一项；详见 `/private/tmp/fibra-definition-simplification-runtime-audit.md`。
- 最终架构复核指出静态发现不必意味着统一公共索引/全runtime制品改版；Node/client已有名称，仅Java需新增声明或派生信息。provider缺席仍可发现才更强地要求公共身份段。已纠正目录候选的路线成本表述。
- 目录枚举与全栈组合装配不同；任意多项不能默认为全部自动创建或只能任选一项。最终组合意图/物化owner仍须明确，不能用本次单实例目录证明Tela full-stack目标已闭合。

## 本次推荐收敛

- 用户说明不知道哪条最合适；主线程明确推荐声明级目录，细化设计而非再次要求用户替代架构选型。该答复不授权实现。
- 查询只核所选root精确包，缺/disabled依赖、cycle不阻塞声明；部署compiler保留原拒绝规则。built-in投影已有公开metadata，动态每facet一个静态句柄，无新增批量input/依赖解析公开方法。
- Java当前单入口采用标量definitionId。用户明确不需要兼容代码：可执行包缺名称直接拒绝，SPI改必需方法，descriptor只留双参数构造；所有正式provider/包/模板/消费者统一迁移，验证同版公开制品和旧格式拒绝，不做旧二进制兼容门。
- client实际所选导出在renderer import后find，当前不核重复/全量集合；Node单入口协议无全集枚举。不把“装载时验证”写成通用prepare全量匹配，具体窗口见候选与证据。

## Task6 制品迁移预核（只读，尚未迁移）

- 正式八份 executable Java 描述符按真实 Entrypoint 迁移：`fibra-fs-local→fs-local`、`fibra-tool-fs→tool-fs`、`fibra-tool-fs-search→tool-fs-search`、`fibra-subprocess-local→fibra-subprocess-local`、`fibra-shell-local→fibra-shell-local`、`fibra-tool-shell→fibra-tool-shell`、`fibra-storage-json→storage-json`、`fibra-tool-storage→tool-storage`。ShellTool名称由实施agent和主线程现场复核`ShellToolEntrypoint.java:16`及公开graph:156，纠正派发文本中的错误映射，不改生产名称。相应八个测试当前断言完整旧YAML，迁移时须同时更新；不能根据artifactId推导名称。
- archetype 实际模块为 `fibra-plugin-archetype`；模板 `FibraPluginEntrypoint.definition()`使用`${pluginId}`，描述符须同值声明。`src/test/resources/projects/basic/verify.groovy`当前只检查 entrypoint，须增加生成名称及未展开变量断言；不能以模板源码存在代替生成包验证。
- distribution 外部 `Entrypoint` 名称为 `external`且需要 ConfigStore；`LifecycleEntrypoint`名称为`lifecycle`，只需贡献registrar，可用于三个身份各异的公开未知包目录→原CAS创建测试。`EngineConsumerTest.rewriteJavaDescriptor`和`LifecycleConsumerTest.JavaFixture.packageSource`均重新写入旧 entrypoint-only YAML，不仅静态资源需要迁移。后者必须保留原排空、升级、loader回收、Node进程验证。
- parity 生成包的真实名称为：`DynamicJavaEntrypoint→java`；`ContextJavaEntrypoint→conditional-java`、其`Stable→stable-java`；`DisableJavaEntrypoint→self-java`、其`Stable→stable-java`；`RetentionJavaEntrypoint→retention`、其`Failing→retention-failure`、其`Peer→retention-peer`。各生成 helper 必须按实际被写入的 entrypoint 声明相应名称，不在运行时调用插件代码补目录。
- 续轮全仓生成路径检查另核CLI三份测试中的四个入口：`CliHostOwnershipTest.OwnershipEntrypoint→ownership`、`CliHostTest.ContextEntrypoint→context`、`FibraCliTest.FailingToolEntrypoint→failer`、`FibraCliTest.DynamicCommandEntrypoint→command`。对应JAR helper仍写旧entrypoint-only，须迁移以保持原关闭/配置/命令回归有效，不能让缺名称错误提前遮住原断言；Node字符串及Java旧格式拒绝负例不得盲改。
- `scripts/verify-distribution.sh`的 producer deploy/发行 verify 未显式指定本地repo，仍须使用临时 Maven wrapper 注入本次隔离 producer repo；consumer专属repo由后续显式参数覆盖。脚本 EXIT trap 会删除临时remote/consumer现场，制品与来源证据须在退出前保留。当前未运行发行验证，亦未改其脚本或CI。
- 脚本默认逐字节比对范围含 BOM、Java/Node runtime、client protocol和正式plugin模块，未含`fibra-engine`；本次新增Engine公开入口的producer/consumer JAR与POM身份必须另核并保留，不能把原脚本绿色直接外推为新增API来源证明。`ApiSignatureBaselineTest`更新开关写全部模块，后续只能接受实际获批的engine/Java/Node签名差异；client wire基线不因目录变更而改。
- 隔离Tela client只读资源审计报告`/private/tmp/tela-catalog-owner-audit.md`核出Hub的activate两次verify与HTTP resource均调用原Artifact校验；只改query会留下文件关闭旁路。既有SPI足够，必要调整落在原Artifact/Driver/Hub的私有owner和测试，不改Fibra。candidate/Unit和driver索引同一失败owner，closer不复制；stop ACK后关文件准入，保留drain可读。同步HTTP异常不等于Engine已即时fail-stop，须通过原stop/retire/driver.close门观察；方案具体并发行为仍待真实测试。
