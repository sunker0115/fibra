# Definition 目录设计取证与参照

2026-10-11。关联[设计候选](../specs/2026-10-11-definition-catalog-design.md)与[推进计划](../plans/2026-10-11-definition-catalog/task_plan.md)。本文件记录事实与证据边界，不批准公共签名或实现。

## 固定输入

- Fibra设计取证基线：`7a7136c449a0a399ab4c12cd00c5ae54ffae84b7`；初始工作树干净，分支 `codex/definition-catalog-design`。以下代码位置是设计取证时的固定快照；已获授权的实施与最新验证状态见推进计划，不能用该快照断言当前工作树仍无实现。
- Tela：只读消费其未提交 `docs/plans/2026-10-11-fibra-definition-catalog-proposal.md`、`docs/architecture/product-target.md` §1.1/4.2、`docs/plans/current-stage.md` D37/D38、`docs/references/2026-10-10-p3-product-target-evidence.md`；不是已批准 Fibra API。
- Tela 原独审 `/private/tmp/tela-fibra-owner-boundary-review.md` 已读取，方向 B0/I1/M0，I1 是 acquire 返回前失败归属。该报告不代表本稿通过。
- Nowledge MCP thread_search query=`Tela Fibra definition catalog P3-D38`、limit=3，返回 0。历史定位不替代下面源码核验。
- 用户追问 DSH 创建路径后补检 thread_search query=`Fibra DSH 插件 definition 目录`，limit=3；返回历史分层与 BOM 两条会话，未用于证明当前实现，当前创建链以重新读取源码为准。

## 基线代码事实（设计取证时）

路径省略公共前缀 `src/main/java/com/sstlfsj/fibra/`。

| 位置 | 本轮核验结果 |
|---|---|
| `fibra-engine/.../engine/runtime/RuntimeDriver.java:10–21`、`RuntimeArtifactInspection.java:12–18` | 无 definition 枚举入口；inspection 只有 runtime/artifact 身份 |
| `fibra-engine/.../engine/deployment/DeploymentTargetCompiler.java:29–66` | 精确 selection 提供依赖 revision；仅 enabled 集合进入部署闭包，依赖也要求 enabled |
| `fibra-engine/.../engine/EngineCommandLoop.java:30–39,59–64` | submit 独立持有请求，结果取消不取消执行；裸 call 不等同命令排队 |
| `fibra-engine/.../engine/FibraEngine.java:281–291,398–438` | 冷提交、原完整 target/compile/candidate；既有 inert register 再 prepare 模式 |
| 同文件 `:768–833,835–897` | failStop 当前会修改 operation outcome；目录不能把此前完成操作追记失败。candidate/retirement 失败阻断后续 driver/store 关闭，一般 cleanupAll 会继续其他 action |
| `fibra-runtime-java/.../runtime/java/JavaClassSpace.java:34–59` | Class.forName 初始化、入口构造、definition() 实际执行；不能称静态扫描 |
| `JavaBuiltInPackage.java:39–61` | provider 私有 definitions 与 metadata 集合一致性在使用时验证 |
| `fibra-runtime-node/.../runtime/node/NodeFacetDescriptor.java:8–21` | definitionId 已在原 descriptor；不必启动 sidecar 取得名称 |
| Tela `tela-host/.../host/client/TelaClientRuntimeDriver.java:46–51,98–111`、`TelaClientArtifact.java:23–74` | 原 runtime reader 解析 definitions 并校验 payload；产品 UI 无需读该私有格式；目录不证明 ESM 实际导出 |

## 模型适用边界的有界消费者核验

2026-10-11 用户追问多 facet/runtime/definition 的必要性后，重新读取现有规格及消费者。以下是源码/manifest 证据，本轮未运行相关测试。

- `docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md` §4：runtime 是解释器与生命周期 owner；一个 facet 对应一个 runtime，executionTarget 表示 placement。动态 Java 一个 definition，contract-only 无运行实例；Node P0 一个 definition。相同 definition 可创建多个独立 unit。
- `fibra-distribution/src/main/distribution/plugins/*/fibra-package.yaml`：当前全部 12 个 manifest 各含一个 `main` facet，runtime 均为 java。
- Tela `tela-distribution/src/main/tela-plugins/*/fibra-package.yaml`：当前全部 5 个 manifest 各含一个 `main` facet；tela-agent-view 是 client，其余四个为 java。只读当前工作树，不证明其未提交内容已发行。
- `fibra-artifact/src/test/java/com/sstlfsj/fibra/artifact/PluginPackageTest.java` 的 `readsCanonicalThreeFacetPackageAndComputesContentIdentities`：三 facet、包内与跨包依赖、各自 payload 身份及不可变列表已有测试合同；它不是生产需求证明。
- `fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriverTest.java`：`builtIn(provider, consumer)` 与 `builtIn(provider, matching, isolated)` 将多个 definition 放入同一个 host facet。多定义是已有真实测试消费者，不只是集合类型声明。
- `client/packages/client-api/src/index.ts:124–132`：一个 definition 支持多个 desired entry，entry module 导出 definitions 集合；`client/tests/engine-connection/consume-snapshots.mjs:70–99` 放入 unrelated/fixture 两种定义并按 definitionId 精确选择。
- Tela `tela-host/src/main/java/com/sstlfsj/tela/host/client/TelaClientArtifact.java:45–51` 接受非空、无重复的 definitions 列表。这证明 reader 能力，不证明当前发行插件包含多项。

结论：多 runtime 有不同执行 owner 的依据；多 facet 是现有发行组合能力，多 definition 是部分 runtime 的已有合同。当前扫描不足以证明后两项不可删，也不足以证明可以安全删。目录候选保留最终模型容量，不强制消费者采用多项结构；身份收缩需独立批准及迁移分析。Nowledge thread_search `Fibra 多 facet definition runtime`（limit=3）返回空，未用历史推测代替当前证据。

## Java 取得失败：已执行基线反例

独立审查报告 `/private/tmp/fibra-definition-java-acquisition-audit.md`；复现入口 `python3 /private/tmp/fibra-acquire-audit/run.py`，结果 `output.txt`，源码身份 `source-sha256.txt`。

Zulu JDK 21.0.2 直接 javac **当前全部 runtime-java 主源码**到临时 classes 并置于 classpath 首位；其他 Fibra 模块用现存 target/classes，第三方依赖读取本地 POM 对应缓存。无 Maven、无生产修改、无 install/deploy、无共享制品缓存写入。主线程已读探针源码、运行输出与审计结论；它不是干净全 reactor 或发行资格。

主线程逐项比较 source-sha256.txt 与当前 11 份 runtime-java 主源码，一致；没有拿旧 target/runtime-java 类代替本次源码。其他模块未 clean 重编的限制仍成立。

| 探针 | 故障注入 | 实际结果 |
|---|---|---|
| metadata-close-failure | definition 抛错，loader closer 再抛 IOException | prepare 失败带 cleanup suppressed；candidate/driver/重复 driver.close 均 SUCCESS；close 次数1、pool=0 |
| dependency-close-failure | 已取得依赖，父 descriptor 非法，依赖 closer 失败 | candidate.close SUCCESS；driver.close 及重复观察报 leased；pool=1、references=1，负责释放的局部 lease 丢失 |
| failed-parent-releases-dependency | 父 metadata 失败且父 closer 失败 | 父未成功释放仍关闭依赖；candidate/driver.close SUCCESS；close 次数2、pool=0 |

第一轮探针环境缺 snakeyaml-engine 导致 NoClassDefFoundError；按本地 jackson YAML POM 补齐后运行以上三条。环境搭建错误不计入产品 finding。完整命令和原始文件保持仓外；临时文件丢失后须按上述源码位置与故障顺序重建，不假称原证据仍可读。

根因：`JavaRuntimeDriver:138–230` 中 loader 在 createWiring 局部，metadata 成功后才放 pool；异常只 suppressed cleanup 并 releaseAll 依赖。`Candidate.prepare:316–362` 在 lease→bind→局部 values→prepared 间还有同款窗口。现有 `StaticLease:840–866` 已能保留已交付 lease 的失败依赖，但无法覆盖尚未交付的 lease。现有 `staticLeaseRetriesOnlyDependenciesWhoseReleaseFailed` 测试不能替代上述证据。

尚未执行的分支：direct definition AssertionError、bind/多 entry 失败、菱形/循环依赖、query cancel/close、全部新目录消费者。它们继续进入 DC-06～10，不宣称已修复。JDK 官方 [URLClassLoader.close](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URLClassLoader.html#close()) 只给 best effort 关闭等基础行为；关闭失败仍保留 owner 是 Fibra 自身协议，不能靠重复调用 JDK close 假定恢复。

## 成熟参照与采用分类

下列版本是核验对象，不宣称最新；参照只做阅读，未运行其测试。

### PF4J / ServiceLoader / OSGi

- PF4J 本机 `org.pf4j:pf4j:3.15.0:sources` SHA-256=`7b8333b0d59a9cbe6bdc31771f7bb250b6d21fe99275539a855135593a311597`。`AbstractExtensionFinder.findClassNames:219–225` 取索引条目；`find:79–150,171–204` 使用 plugin loader，实例化查询受 started gate；`ExtensionAnnotationProcessor:94–136` 编译期收集并写扩展索引。[官方说明](https://pf4j.org/doc/getting-started.html)。**最小改造参照**：静态派生索引。差异：类名不是 Fibra 的运行定义名称，不能不改作者合同直接套用；其 started gate 不满足 disabled 查询。
- JDK21 [ServiceLoader.Provider](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.Provider.html)：type 供检查/筛选、get 才取得实例。**直接采用原则**：发现和实例化分离。差异：类型描述不是业务 definitionId，也不接管 Fibra target、依赖或 loader 关闭。
- [OSGi Core 8 wiring 规范](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html) §7.3：绑定精确 revision，更新/卸载后旧 wiring 仍可能被使用。**直接采用原则**：精确绑定与仍被使用资源保留。**拒绝引入**：完整 package resolver/OSGi 生命周期，当前任务不需要，也不能由该规范推断 Fibra acquisition cleanup 已闭合。

### pi

本机 `/Users/sunke/dev/ai-project/pi`，HEAD `f1b2e77f5b13b2a199b1052cb79c235451afe7d7`，实际 origin `earendil-works/pi`，只有未跟踪 `.codegraph/`；不将最初推测的 badlogic/pi-mono 当成本次已核远端。

- [extensions/loader.ts](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/extensions/loader.ts) `:741–875` 从 `.pi/extensions`、显式路径、`package.json` 的 `pi.extensions` 或 index 文件发现入口；只得到路径。
- 同文件 `:559–590,614–629` 使用 jiti.import，再执行 `factory(load.api)`，成功 commit、失败 discard；`250–267,518–547` 暂存注册并登记 event disposer。**直接采用原则**：发现路径与执行注册分开、成功后发布受管注册。
- `test/suite/regressions/8423-extension-factory-failure.test.ts` 读取证据：失败后 flag/provider/listener 与 captured API 受控失效，不破坏其他并发 factory；未执行。
- **拒绝外推**：这些注册事务不证明任意模块顶层 timer/socket 副作用可回滚，也不证明 cleanup 失败资源保留。session 级 extension reload 不是 Fibra 多实例/精确版本生命周期。

### Codex

本机 `openai/codex`，HEAD `5fe4fc8f7cd16688b5c661d8cdb76e2a340b046d`，仅未跟踪 `.codegraph/`；不代表当前桌面闭源部分实现。官方资料使用 [插件架构](https://developers.openai.com/plugins/concepts/plugins)与[打包说明](https://developers.openai.com/plugins/build/plugins)，2026-10-11 读取，格式事实与下面固定源码分开。

- `codex-rs/plugin/src/manifest.rs:7–38`：manifest 声明 name/version 和 skills/MCP/apps/hooks 资源；`utils/plugins/src/plugin_namespace.rs:43–77` 查 manifest；`core-plugins/src/loader.rs:837–920` 从 active installation 取得 root，再读 manifest cache。该加载路径 disabled 提前返回，不能外推为 Fibra 的 disabled definitions 合同。
- `ext/skills/src/loader/discovery.rs:54–174` 有界扫描 skill 文件；`loader/host.rs:346–395` 读文本、frontmatter 与 metadata 构成 SkillMetadata。**直接采用原则**：声明式目录无需执行 skill 脚本；资源解释集中在对应 owner。
- `codex-mcp/src/connection_manager/tool_catalog.rs:165–177,697` 使用 ready client / `list_tools_for_client_uncached` 取得工具。**重要区别**：manifest/skill 目录与 MCP 运行能力发现是两条链；不能声称 Codex 全部能力零执行发现。
- **最小改造参照**：若 Fibra 改成静态声明 definition 身份，目录可类似读取描述。**拒绝照搬**：marketplace、远端安装、skill prompt/身份规则不是当前包目录要求，不新增此类子系统。

### DeepSeek Harness

本机 HEAD 重新核为 `c291e7961a515f6d7af9304e7fd1d257929aef26`，仅未跟踪 `.codegraph/`；与 Fibra 旧参照一致，但本次重新阅读关键源码。

- [plugin-inventory/src/index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/host/plugin-inventory/src/index.ts) `:56–96` 的 list 直接投影 ctx.loader.entries，带 entryId/moduleName/enabled/fiberPhase，可附 preset composition。**直接采用原则**：不建立第二 lifecycle truth；**拒绝混同**：它是已配置 entries，不是未知包 definition 目录。
- `vendor/loader/src/config/entry.ts:132–136,286–338`：disabled 阻止 refresh/init，import→unwrap→registry.plugin→fiber.await；没有证明 disabled 包可执行目录加载。
- 创建链进一步核验：`vendor/loader/src/config/tree.ts:97–104,145–160` 接受 name 创建 entry，cordis: 查 builtins，其余由内部 importer 或模块 import 解析；`vendor/loader/src/index.ts:192–198` 规范化 default 导出；`entry.ts:277–300` 导入后直接创建插件。它不需要 Fibra 新增的独立 definitionId。Fibra 缺的是新增三段身份后前半段的公开发现入口，不能将 DSH inventory 非等价误写成“DSH 无法创建未知插件”。本次只证明已知模块 specifier 的创建链，不声称 DSH 已提供任意包市场枚举 UI。
- `packages/client/modules/src/index.ts:720–738` 与 `client/manifest.ts:1–29,48–95` 解释 package.json 的 dsh.client 和资源 boot graph；`client/system.ts:113–120,172–190,215–229` 分开登记 factory、首次 import/require 执行与缓存 exports。**直接采用原则**：资源到达、模块求值、实例治理分开；这些资源 ID 不是通用 definition 集合。
- [vendor/cordis/src/fiber.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/fiber.ts) `:517–523` 在 execute 插件代码前登记 wrapper；`:355–396` 每 yield disposer 立即 collect，`:486–521` 用 inFlight 让 owner join 异步清理。**最直接采用原则**：后续可失败代码执行之前完成 owner 登记。
- 同文件 `:432,486–500,675–686` 先取出 disposers，finally 移除 wrapper，unload 捕获错误写日志。**拒绝采用这一失败终态**：它不提供 Fibra 所需的 cleanup 失败后资源/先决依赖持续可达保证。

### DSH 的 Host/client 自动关联与实例数量

用户进一步询问“一次创建是否自动组成后端和前端多个实例”后，主线程重新沿同一固定 DSH HEAD 核对标准 Web 启动链；本段是源码结论，未启动 DSH 实测。

- `packages/client/ui-chat/package.json` 同时导出 `.` 和 `./client`，`dsh.client.platform=web`；`src/index.ts:13–19` 的 Host apply 注册 Chat settings，`src/client/index.ts:1–2` 导出独立浏览器 apply。是实际双面包，不是用空 Host 函数假定整条链。
- `packages/client/modules/src/index.ts:510–529` 监听带 Loader entry 的 `internal/plugin` 并扫描已有 entries；`:720–738` 按 Host 来源解析 package.json 的 `dsh.client` 与 `./client`。Host 插件不是直接远程调用前端 factory。
- 同文件 `:874–879,914–935` 只投影存在 fiber 且未 disabled 的 Host rows；同来源按 sourceKey 合并，图表按 packageName 去重，多个不同活动来源却解析成同一 packageName 时拒绝。没有按 Host entryId 复制前端 row；最后活动来源消失才移除包 row。
- `packages/client/web/src/boot.ts:68–84` 每次 Web boot 建自己的模块系统和根 Context；`boot-client.ts:47–55` 对 manifest 每个 package row 调用一次 `loader.create({ name })`。这里没有传 Host entryId/config，因此不是后端实例配置的一比一镜像。
- `packages/client/modules/src/client/system.ts:63–79,114–120` 的 module exports/factory 缓存是每个模块系统按包名持有；模块求值缓存与 Cordis 插件实例生命周期仍不同。
- `packages/client/hmr/src/client/index.ts:160–164` 明确忽略 graph 帧，初始 boot graph 在页面 reload 前保持；rebuilt 只刷新已有 entry。故不能从 Host 图增删推导当前已打开页面会同步创建/销毁插件，更不能外推为跨端原子实例事务。

结论：标准路径自动关联“Host 加载的包与它声明的浏览器入口”，每个 Web boot 为每个包 row 建一个 client entry；同包多个 Host entries 不据此产生等量 client entries。包级共享前端与后端逐实例伴随前端是两种不同装配语义。DSH 证明简单包声明可驱动前端装配，不证明 Fibra 已有全栈目标/排空保证，也不要求先发明通用模板 DSL。

## 对当前方案的推导

三类参照没有共同要求所有发现必须静态，也没有一个现成实现同时提供 Fibra 的跨 runtime definition 身份、selected 精确 revision、disabled 查询、Java lease 失败保留。由最终管理场景推导，主线程推荐声明级目录，让尚不可运行的包仍可展示入口；不以现有制品兼容成本决定目标。Java 新名称字段带来明确迁移成本，Node/client 沿原 descriptor；不引入统一公共索引。

Java acquisition 失败保留属于本项目必须自行设计和反例验证的部分；参考项目的 owner 机制只能支持方向，不能抵扣 DC-06/07。无新增第三方依赖：现 JDK、Reactor、descriptor reader 足以表达本次窄能力，引入 PF4J/OSGi/JS runtime 会增加第二装载/生命周期 owner 且不消除现有闭包责任。

## 声明候选的阶段与迁移核验

- `PluginPackageStore.java:158–173` 可按精确 pluginId/digest 读记录，`PluginPackageRecord.managedPackage()` 已有 root facets；声明读取不需要新增 compiler 解析用途。
- `BuiltInFacet.definitionIds()` 已是公开不可变 metadata；Engine 可直接投影声明。`JavaBuiltInPackage.validateDefinitions()` 的实际集合校验仍属于 prepare，不由目录冒充。
- `JavaFacetDescriptor` 当前只有 Optional entrypoint；私有 reader 只接受 entrypoint，故增加 definitionId 是真实制品/公共值签名变化。旧 reader 拒绝新字段。用户明确不需要兼容代码，候选不保留旧构造重载/default SPI/旧制品运行分支；统一重编provider与consumer、重新打包executable插件。
- `NodeFacetDescriptor` 当前 protocol/definitionId/entrypoint/contributions 已给静态名称。进程单入口协议没有全量工厂名称枚举，不以成功 prepare 证明 JS 实现已运行。
- Tela `TelaClientRuntimeDriver.java:93–111` 准备读取声明并验证所选 ID。renderer `module-loader.ts:30–53` 验证资源后 import，再用 find 选择所需导出；不校验重复或全量集合。Java/TS Assignment 均没有完整声明名称数组。因此“所有 runtime 在 prepare 做全集匹配”不是现有能力，也不是本期目录候选承诺。

以上为当前源码核对，未运行新的目录/renderer 行为测试；具体候选见目录设计§2/§4/§5。
