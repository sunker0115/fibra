# Fibra 包整理前全模块 owner 与分层审计（2026-10-10）

结论：整体能力层已经成立，不能把本次整理理解为按类数扩大拆包。建议保持全部 Maven/npm 模块和能力 owner，仅对 Engine 与 CLI API 按已拟定的能力包迁移，config 明确保留同包。当前没有证据要求拆分 core/bridge、合并插件 contract，或把 runtime 实现移入 Engine。但发现一处与包导航无关的现存 CLI 失败清理 owner 缺口：`CliHost.open()` 会在 Engine 为保留失败 generation 而拒绝共享清理后，继续直接关闭已转交 Engine 的 stores。它应单独进入生命周期修复/验证，不应被纯 package/import 修改掩盖。

本文件记录迁移前基线发现；实施、问题核销和当前验证以 [执行计划](../plans/2026-10-10-package-structure.md) 为准。长期包边界以 [Client Foundation §3.1](../specs/2026-09-15-fibra-client-foundation-architecture.md#31-java-能力包与模块内依赖) 为准。

## 1. 依据、范围与限制

- 权威：`docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md` §3、§5、§7–11；`docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md` core、config、插件、CLI 与产品边界章节。后者涉及 Client Foundation 时以 09-15 规格为准。
- 与 Client Foundation 规格 §3.1 与 `docs/api/2026-10-10-java-package-migration.json` 交叉核对；本审计不替代其中的逐类型可见性与包 DAG 证明。
- 从根 POM 递归清点：44 个 reactor POM（含根），30 个声明发布的 Maven 制品；另有 2 个 npm 包。额外检查发行模块下 6 个仓外 Maven consumer 及其聚合 POM、archetype 模板 POM、TS consumer。
- 扫描实际主源码类型/显式 imports：30 个包含 Java 主源码的目录，417 个文件（排除 target、测试消费者与 archetype 模板）；另读主入口、资源 owner、注册/调用链、关键 POM、TS 两个主源码、架构门禁和代表性测试。并非逐行审查 417 个文件，也不把 import 扫描当编译器证明；通配符和全限定引用需迁移验证补齐。
- 未修改仓库代码，未运行编译、测试、发行脚本、真实进程/PTY/跨平台验收。启动失败场景为源码链路确认，尚未执行最小复现。
- 审计结束时主线程已并行执行包迁移，仓库工作树出现对应新增/删除/import 变动；本文件的源码路径与行号指向审计开始时的迁移前版本（HEAD `03c8f2ac525916aab7a5949bcc77396b98cc5acf`），应按类名/方法名在新包定位，不把这些并行变动归为本审计修改。

## 2. 整体能力/owner/契约/依赖/处置矩阵

下表的箭头是模块生产依赖方向；契约列列举核心入口而非完整签名基线。包迁移时外部消费者必须同批更新 imports/FQCN，不保留兼容壳。

| 模块/能力 | 唯一 owner 与公开契约 | 允许的依赖方向及真实边界 | 本轮处置 |
|---|---|---|---|
| `fibra-api` | 插件侧 Context、Scope/ScopeView、Services、Effects、Events、取消、日志和值类型合同；不拥有宿主持久状态 | → Reactor；不引用 core/engine/运行时实现。`Context.scope()` 是只读视图，调用者创建的 child 才有关闭权 | 保留现有根 API + value/event/logging/annotation 分组；不按接口数量继续切碎 |
| `fibra-core` | `FibraRuntime`/`RuntimeDomain` 为进程内 owner；`internal` 实现 service graph、Scope 资源树、effect 与 lifecycle 单写者 | → api；公开 runtime facade 调用 internal 实现是同模块实现依赖，不是反向分层违规。没有 config/artifact/engine 输入入口 | 保留 runtime/internal 边界；不要因 internal 较大改成多个无明确 owner 的 manager |
| `fibra-config` | 原始 desired graph、上下文快照、编译/求值、文档输入与编辑事务；`DesiredConfigCompiler` 等为唯一规则实现 | → api LiteralValue、Jackson；不依赖 Engine。输入文档是部署输入，不是 Engine durable target | 保留同包。节点构造/编译/存储的包私有验证器与错误构造形成闭包，细拆会逼出 public helper 或复制规则 |
| `fibra-artifact` | `PluginPackage`/facet 元数据、不可变内容寻址 store、install transaction | 无 Fibra 上层依赖；store 负责 package 文件/锁，存储生命周期由 Engine 接管；不解释 Java/Node 私有 payload | 保留。内容存储与 facet 合同同能力，不把 runtime 类加载挪入这里 |
| `fibra-bridge` | Contribution kind/codec、目录、unit admission、route/in-flight drain | → api；目录不依赖 Engine。每次调用的准入/排空由 directory/admission 持有，发布 revision 由 Engine 持有 | 保留。不要把 Engine remote invoker 全部移入 bridge，否则 bridge 反向知道 PublishedRuntime |
| `fibra-engine` | 唯一 durable target、编译结果、command lane、candidate/current/retirement、generation 编排、公开发布与 RuntimeDriver SPI | → core/config/artifact/bridge；不依赖 Java/Node/registry/Spring。compiled execution facts 与可执行 owner 分离 | 按 deployment/execution/observation/publication/runtime 拆包；根保留唯一编排及其同包协作；没有新增 owner/状态机 |
| `fibra-registry` | package/desired 管理用例、审计和当前发布事实投影 | → engine（及其传递输入合同）。`mutate()` 用 PublishedView 派生完整 ApplyDeployment，经 engine.submit；`project()` 只投影发布结果；不直改 target 文件 | 保留单包。查询/审计可以继续同一应用边界，不因 repository 类名另造基础设施模块 |
| `fibra-runtime-java` | `JavaRuntimeProvider` 创建 driver；driver 私有候选、ClassLoader/wiring/lease、definition、unit 生命周期 | → engine SPI、api/artifact；不依赖 Node。`JavaRuntimeDriver` 构造非 public；Engine 不认识 JavaClassSpace | 保留。class loading 与 unit 执行在同 runtime owner 内；本次只同步 Engine 新包引用 |
| `fibra-runtime-node` | `NodeRuntimeProvider` 创建 driver；driver owns NodeSidecar/RPC/session/unit | → engine/bridge/api/artifact；不依赖 Java runtime。Node payload 启停和资源关闭由这一 driver 完整持有 | 保留。sidecar/RPC 不应移入通用 bridge，不与 subprocess-local 合并 |
| `fibra-client-protocol` | Java 外部 execution wire 值对象、三类 fence、严格 codec | → api LiteralValue + Jackson；不依赖 Engine 或 transport | 保留。wire DTO 不是 runtime session owner；Java 与 TS codec 为跨语言实现，不能视为同语言重复而强行共用运行库 |
| `client-api`（npm） | 纯 TS ClientScope/OwnedClientScope、ClientModuleDefinition/ClientInstanceContext、Assignment、HostCaller 等合同 | 无 production dependencies；没有 loader、transport、DOM 或 renderer。插件不能构造 HostCaller 的 session/revision fence | 保留独立包、当前入口；单文件不构成职责混杂，不能仅按长度拆 API |
| `client-protocol`（npm） | TS envelope、严格 JSON/identity/decimal 校验与 codec | → client-api，import type/re-export 共用值合同；没有 fetch/WebSocket/runner | 保留独立包；不把 protocol codec/fixtures 演进成浏览器 runtime |
| `fibra-cli-api` | 应用元数据、command/input、invocation、terminal 租约合同 | → api/bridge；没有 Engine、Picocli、JLine。应用描述不拥有 Engine/Host/物理终端 | 按 command/input/invocation/terminal 拆包，root 保留 CliApplication；沿现有无环合同细分 |
| `fibra-cli` | `CliSession` 借用 PublishedRuntime；`CliHost` 是参考入口 composition root；单次 command generation、Picocli/JLine、终端与信号生命周期 | → cli-api/engine/registry/Java/Node/tool-api。公开 session 与内部 host 各司其职；固定命令经 Registry、贡献经 PublishedRuntime | 包保留；单独处理 §4 的启动失败 store 关闭越界，不做大规模类拆分 |
| `fibra-spring` | Spring Bean→HostServiceRegistry 显式桥接，Bean 生命周期仍属 Spring | → api/engine/Spring；exporter 只收集启动前 binding；无另建注入容器 | 保留；更新 Engine HostServiceRegistry 等真实 imports 即可 |
| `fibra-spring-boot-starter` | Spring composition root：providers、Engine、Registry、PublishedRuntime、SmartLifecycle | → spring/registry/Java/Node；stores bean 使用 destroyMethod=""，Engine 拥有 store 关闭；额外产品 runtime 由产品自定义 composition root | 保留，不因只装配两个 first-party provider 增加新扩展功能 |
| `fibra-tool-api` | 宿主共享 DTO、ToolContributions kind/codec、可选 ResultSpillStore 服务合同 | → api/bridge；宿主 parent ClassLoader 提供；工具插件 provided，不打包副本 | 保留独立模块和 plugins.tool 包，不下沉 core/engine |

### 插件族：每个发布模块均纳入

| 模块 | owner/公开契约与真实调用方向 | 处置 |
|---|---|---|
| `fibra-fs` | 无 entrypoint 的动态 `FileSystem`/Fs* 服务合同，→ api provided | 保留独立 contract JAR |
| `fibra-fs-local` | 本地文件操作、文件版本/原子发布与 LocalResultSpillStore；→ fs/tool-api/api provided；JNA 仅本 provider 私有 | 保留 fs.local；不把 native 文件发布移入 contract |
| `fibra-tool-fs` | FileToolEntrypoint 需要 FILE_SYSTEM 与 REGISTRAR，工具 handler 通过 service/invocation 访问 filesystem | 保留 fs.tool；不直调 LocalFileSystem |
| `fibra-tool-fs-search` | 文件领域的搜索工具；SearchRunner 通过 SubprocessServices.SUBPROCESS.spawn 执行 rg，仅依赖 subprocess contract | 保留 fs.search；目录属 fs 不代表应补 fs-local 依赖 |
| `fibra-subprocess` | 无 entrypoint 的 Subprocess/ProcessUnit 及输出/取消合同 | 保留独立 contract JAR |
| `fibra-subprocess-local` | LocalSubprocess + POSIX/systemd/Windows Job owner，提供 SUBPROCESS 服务，调用资源受 invocation Scope 管理 | 保留 subprocess.local；与 runtime-node 的受管范围保证不同，不能因同为进程合并 |
| `fibra-shell` | 无 entrypoint 的 Shell 请求、输出、错误与服务键 | 保留独立 contract JAR |
| `fibra-shell-local` | ShellLocalEntrypoint require subprocess/provide shell；LocalShell 经 caller.service(SUBPROCESS).invoke→spawn | 保留 shell.local；这是明确的 capability 组合，不是 shell 反向拥有 subprocess 实现 |
| `fibra-tool-shell` | require REGISTRAR+SHELL，经 contribution 暴露 shell 工具 | 保留 shell.tool，不将业务工具描述纳入 shell contract |
| `fibra-storage` | 无 entrypoint 的 ConfigStore/ConfigDocument/变更监听合同 | 保留独立 contract JAR；不是 Engine DeploymentTargetStore |
| `fibra-storage-json` | JsonConfigStore/JsonDocumentCodec；entrypoint 将 store 加入 effects 并提供 CONFIG_STORE | 保留 storage.json；文件持久化 owner 在插件 Scope，不能复用 Engine target 状态 |
| `fibra-tool-storage` | require CONFIG_STORE+REGISTRAR；通过 service reference 与 contribution 暴露工具 | 保留正式 consumer，而非移回 acceptance/example |

POM 扫描表明各 provider/tool 使用对应 contract 的 provided 依赖；`fibra-tool-fs-search` 的 Jackson 只在该 consumer，JNA 只在本地 provider，未扩散到 contract。还需实际 JAR/manifest/classloader 验收才能证明发行物不重复打包；本审计只确认声明与入口代码。

### 组合、发行与验收层

| 模块/目录 | owner/公开合同与依赖 | 处置 |
|---|---|---|
| 根 `fibra` + `fibra-plugins`、四个领域聚合、`fibra-plugins-acceptance` | reactor/version/build 聚合，不承担运行时 owner；领域 POM 仅 modules，正式子模块继承根 | 保留；目录变化不得变成依赖边 |
| `fibra-bom` | 发布坐标与版本集合，不引入 runtime dependencies | 保留；迁包不改坐标或发布集合 |
| `fibra-distribution` | 参考 CLI ZIP、插件分装、默认 profile、node/rg staging 与隔离仓外消费者门禁 | 保留，确认类名迁移不会破坏主入口、脚本、manifest、consumer 源码；默认 verify.skip=true，普通 Maven verify 不证明仓外 ZIP 验收 |
| `fibra-plugin-archetype` | 生成普通 Java 插件，模板只 provided fibra-api；自身测试验证真实 package/Engine 装载 | 保留并核对模板的源码字符串/FQCN；不引入生成式兼容层 |
| `fibra-plugins-acceptance-host` | 真实插件组合、替换范围、classloader/service/Scope 验收；非发布 host | 保留。它可以依赖所有被测插件，不能据此判定产品模块耦合违规 |
| `fibra-parity-tests` | Cordis parity、跨 runtime、external runtime conformance、宿主重启、公开 API/架构/签名门 | 保留；fake external runtime 仅测试，绝不搬进正式 runtime 模块 |
| `fibra-benchmarks` | JMH 性能量测与 fixtures，不发布 | 保留，更新所引用的新 FQCN；不能把 benchmark 结果冒充生命周期正确性 |
| `fibra-example` → content-sanitizer 聚合、api、spring-host | 示例的场景贡献 DTO 和 Spring 组合；SanitizerPluginManager 管理经 Registry、调用经 PublishedRuntime | 保留；示例业务不向 api/core/engine 上移 |
| distribution/src/test/consumers（core/engine/java-plugin/cli/runtime-provider/spring-boot）与 TS package-consumer | 仅依赖隔离安装/打包制品的公开使用证明，不是 reactor 主模块 | 全部纳入 FQCN 与签名迁移检查；不能只让 reactor 编译通过就宣告交付 |

## 3. 比包名更重要、必须保持的分层事实

1. **内容、目标、观察、审计是不同事实。** Artifact store 保存不可变内容；Engine target store 保存唯一完整目标；Registry 没有独立状态机；config 文档只是部署输入；storage-json 是应用数据。`CliHost.apply():103-133` 加载 profile graph、发布选定 package、调用 `registry.deploy`。它不会把 profile 当重启后的 current 真源；`open():85` 仅 target 为空才自动 apply。
2. **准入与发布是两个不同层的合同。** bridge admission/drain 不认识 Engine；`RemoteContributionInvoker:60` 用 PublishedRuntime.invoke 兑现 revision+registration identity。因此 remote invoker 属于 Engine publication，不能为了“bridge”词义挪入 bridge。
3. **RuntimeDriver SPI 位于 Engine，runtime 实现单向实现该 SPI。** JavaRuntimeProvider:41、NodeRuntimeProvider:38 分别创建自己包内 driver；driver 私有 ClassSpace/sidecar，不能被候选通用袋或 Engine 的 cast 取代。
4. **公共只读 snapshot 不是运行 owner。** candidate/current/retirement 内部可变状态仍由 Engine 单 lane 修改；迁至 observation 的是发布合同，不能把内部 mutable holder 一起搬走或暴露。
5. **CLI command/input/terminal 不等于三套执行 owner。** command 与原始 input 共享 CliInvocation；terminal input 是按键/粘贴事实，应用 input 是原始文本提交。候选包划分正确保留这个差异。
6. **插件 contract 分离决定精确 classloader/wiring 生命周期。** 合并 fs/subprocess/shell/storage JAR 会扩大替换闭包；不能为了较少目录合并。Node runtime 与 subprocess-local 同样有不同范围保证和关闭协议。
7. **测试跨层不等于生产越界。** 整仓 conformance、真实插件验收、外部消费者必须可以组合所有层；只检查负向 POM 箭头不足以证明公开 SPI 的数据可传递。现有 vertical tests 与 consumers 应继续作为正向证据入口。

## 4. 发现：CLI 启动失败清理越过 Engine store owner（现存问题）

### 直接证据

- 权威规格 09-15 §10（约 695–700 行）：Engine builder 成功后取得两个 stores 的关闭所有权；调用方不得再独立关闭。§10（约 735–741 行）：generation 清理未证明时保留现场，不继续破坏共享资源。
- `fibra-cli/src/main/java/com/sstlfsj/fibra/cli/CliHost.java:68-86` build、start、首次 apply；`:88-94` catch 无条件依次 `close(engine)`、`close(audit)`、`close(targets)`、`close(packages)`。
- `fibra-engine/.../FibraEngine.java:992-1008` 的 finishCurrentOperation 对 FAILED unit 抛 EngineChangeException，即使 target 已保存。
- `FibraEngine.java:816-840` 关闭 current 时先转 retirement，再 drain/stop/retire；任何失败使递归 shutdown 的 `.then(...)` 不执行。真正 store close 位于 `:847-848`，因此该失败路径刻意不关闭 stores。已有 retirement 时 `:807-813` 直接返回保留现场错误。
- `fibra-runtime-java/.../JavaRuntimeDriver.java:551-565` stopAsync 只有 scope release 成功才 releaseLease；core draining resource 失败会阻止 scope 成功释放。
- `fibra-artifact/.../PluginPackageStore.java:203-224` close 释放 ownershipLock/channel；`fibra-engine/.../FileDeploymentTargetStore.java:97-104` close 关闭 ownership channel。CLI 越界 close 会释放本应保留的排他锁。

这不是对成功关闭执行无害的第二次幂等 close；在关键失败分支 Engine **第一次 close 没有触及 stores**，CLI 才实际释放它们。

### 具体可触达窗口与最小复现目标

从空 target 首次打开 CLI profile，部署两个真实 Java entry：A 正常 ACTIVE，但 effects 持有 `DrainingDisposable`，其 drain 返回 `Mono.error`；B 返回确定性启动失败。两者可以独立，不需跨 runtime fixture。

1. engine build 成功、空 target bootstrap 成功。
2. `host.apply()` 完成 save/promote，A 活动、B 失败，finishCurrentOperation 抛 EngineChangeException。
3. `CliHost.open()` catch 调 engine.close；关闭 A 的 scope 因 draining resource 失败，Engine 保留 retirement/generation、跳过 stores。
4. 同一 catch 继续关闭 targets/packages，释放排他锁；此时旧 A 的资源释放未证明。

最小失败测试应把上述组合放入独立子进程或具备明确测试清理的 fixture，避免有意 fail-stop 污染测试 JVM；验证初次 open 抛错且资源 dispose 未执行，随后同一 package/target root **仍应拒绝第二 owner**。当前源码预期错误地允许重开。若采用单插件“先注册 draining effect，再启动失败”的变体，须先确认其 settled 能稳定完成；双 entry 更容易隔离启动失败与清理失败。

### 已有证据与未验证边界

- `RuntimeDriverEngineTest.candidateAbortFailureDuringHostCloseRetainsItsOwnerAndSkipsSharedRelease`（约 1241 行）及邻近 retirement 测试明确断言 `store.closes == 0`、无 driver-close、owner 保留。
- `DrainLifecycleTest` 约 348–363 行以失败 DrainingDisposable 证明 scope 不关闭、其它资源不释放。
- `CliHostTest` 当前三条路径是首次启动持久上下文、完整替换、重复包/部署失败保留旧 target；未覆盖首次 open 的“部署失败 + scope drain 失败”组合。
- 本审计未运行该复现，不能报“测试已证实失败”。源码所有权违规已确定，外部锁重开和现场存活的动态结果仍需测试闭合。

### 最小修复边界

在 CLI composition root 显式区分 stores 尚未转交与已交 Engine 的阶段：交给 Engine 后失败清理只调 Engine 的 cached close，不能以清理兜底为由直接释放它 owns 的 stores；audit/termination executor 仍按 CLI 自身 owner 处理。Engine 构造失败本来已有逆序清理（`FibraEngine.java:179-212`），实施前须覆盖 builder 尚未返回及更早本地资源创建失败窗口，避免新增泄漏或重复 owner。无需新增 public API、依赖、store retry 或另一个 lifecycle coordinator。

该问题在当前迁包前源码已存在，**不是包移动引入**。建议作为独立生命周期修复，先补可复现测试再改；本审计不擅自把它纳入纯 package/import 修改。若主任务授权整体 owner 修正，应单独记录行为变化与失败窗口验收。

## 5. 本轮包设计的整体相容性与拒绝项

- 接受 Engine `deployment → execution`，`runtime → publication/observation/deployment/execution`，root 编排在顶部；execution 只放不可变共享事实，不能解释为新增 runtime 执行入口。
- 接受 CLI API `api → input → command → invocation → terminal`，含已记录直达边。输出/退出状态归 invocation，避免形成回指根包的环。
- config 保留是基于统一规则和包私有闭包；不应以“整体架构审查”作为扩大可见性拆包的理由。
- 保留所有其他能力包与模块：当前证据未显示抽象 owner 错置；较大类/文件不是本轮修改依据。
- `PluginCatalog/PluginCatalogEntry` 当前无生产消费者；它们是待单独决定的遗留公开抽象，不是运行中的第二事实源。不得仅因空引用顺手删除公开 API。非权威 Harness 参考文档仍有旧 Catalog/DesiredStateRepository 用语，应在相应文档整理时纠偏，不能据此推翻 09-15 owner。
- 拒绝新 core-common/shared SPI JAR、新 infrastructure 层、合并插件 contract、将 TS fixture 发布为 runner、以新 public helper 穿透包私有闭包。本轮均无真实业务合同收益。

## 6. 后续验证要求（本审计未执行）

1. 包迁移：完整 imports/FQCN/反射字符串/资源/模板/测试源扫描；包括 Engine 与 CLI 公开签名、内部 nested type、benchmark、仓外 consumers 和外部产品仓。
2. 签名：从构建产物重新生成并对照映射，确认只有已授权包名变化，没有成员可见性、方法、字段、枚举或 generic 合同漂移。
3. 行为：继续现有 core、config、target codec/store、RuntimeDriver、contribution admission、CLI session/terminal、真实 Java/Node/external conformance 和重启门禁。测试 fixture 仅打开测试同包访问，不复制生产行为。
4. 发行：不能将 `mvn verify` 与默认跳过的仓外 ZIP 验证混为一谈；单独验证 isolated Maven consumers、npm tarballs/TS declaration baseline、模板、插件 manifest/classloader、CLI ZIP 和平台所需进程/PTY 检查。
5. Owner 修复：若被主任务采纳，独立执行 §4 最小失败复现与提前创建资源失败、Engine 构造失败、正常 close、cleanup fail-stop 的回归；包整理本身不能作为这条风险关闭的证据。
