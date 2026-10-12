# Task 5 合同独立审查 R1

结论：**B0 / I1 / M0；Ready: No**。正式作者源、构建字节链、仓内作者 QA、模板与 Node 原执行证据成立；尚缺规范 §4 要求的正式 JAR 仓外全包作者资格流程。该项已由主线程确认属于 Task 5，不转移到 Task 6。

审查仅写 `/private/tmp` 收据和报告，未改源码、未运行 Maven、未 commit/push。采用 requesting-code-review 流程；执行一次 Nowledge MCP `thread_search`，query=`fibra plugin config contract author QA Task5`，limit=3，返回空集，未以历史记忆替代当前证据。

## 冻结输入与审查方法

- 首次及结束前复算 `source-sha256.json` 全部 51 个输入，51/51 相等。逐文件回执：`spec-review-r1-input-sha256.json`。
- 权威合同为 `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` §4、CC01/03/11/12；同时核对 author-schema-inventory、author-build-preparation、implementation-plan。
- 实际读取源码/POM、模板/metadata/assembly、作者 schema 与 Config/Entrypoint/Node configure 实现、stage/verify/archive/distribution 脚本；独立解析既有 XML 和实际 ZIP/JAR，不把实现报告作为验证结果。
- 未机械重跑完整作者/发行门：冻结输入未变化，现有原生退出值、XML 和制品足以核验通过部分；发现的缺口是仓外测试源中没有资格流程，重跑现有测试无法补证。新增的只读 Python 检查结果在 `spec-review-r1-evidence.json`。

## I1：仓外消费者没有执行全包作者资格

定位：`fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/EngineConsumerTest.java:136–145`（安装正式包后直接 deploy）；同文件新增 `:229–237` 仅从 JAR 复制合同到根。

整个 `fibra-distribution/src/test/consumers` 中没有 `DefinitionConfigDocument`、`ConfigSchema.checkSources` 或 `configurationResource()` 调用。已有 `describeDefinitions` 只用于旧格式拒绝、API 空目录及 lifecycle 静态目录测试，没有将实际整包 catalog 与文档全部 facet/definition keySet 比较。因此 11 项仓外测试证明原执行路径已迁移且可运行，不能证明规范 §4 明确要求的“只依赖正式 JAR 的仓外作者消费者”发布资格。只部署所选 definition 也无法发现 orphan schema、额外空 facet 或未执行 definition 的坏 schema；API 空包根缺合同也可能绕过执行预检。

最小修正：继续保持唯一窄测试 helper。可将其唯一源码放在现有仓外 engine-application 的专用 `src/author-qa`，随既有消费者源码完整复制到仓外；该消费者及三个仓内测试通过 compiler test source roots 共编译同一份源码，保留原测试目录，不新增生产模块/JAR/SPI，也不在生成工程引入仓库路径依赖。新增真正以发布 JAR 为唯一 Fibra 依赖的作者 QA：disabled selection、真实 target revision、整包 `describeDefinitions`、verified record 固定资源真实 close 后 parse、精确集合比较、全部 sources 的 `checkSources`（含空集）。补真实已安装但未活跃的坏合同发布失败反例，正常/读失败/关闭失败沿同一 owner 处理。保留原 11 项执行/生命周期/CLI 门，不以新 QA 替代它们。

修复后应跑受影响仓内共编译与仓外作者门，重新形成对应原生 exit/XML/最终制品与输入 hash；无需因纯测试 helper 移动而无依据重跑所有无关状态机测试。

## 已核实的合同

1. **12 包单源与装配。** 独立比较 source、最终 staged main.jar 固定唯一 entry、stage root、ZIP root 及 ZIP 内 main.jar，全部一致；又比较保留发布仓中 12 个精确主 JAR 与本次 stage JAR hash，以及发布 ZIP 与当前 ZIP hash，全部一致。四 API 的 main 为 `{}`；八真实 definition 与最终 JAR descriptor 精确一致，包括 `fibra-subprocess-local`、`fibra-shell-local`、`fibra-tool-shell` 的真实名称。三个 shade 包均以最终主 JAR 核查。
2. **构建启用边界。** 根 Assembly 3.8.0 pluginManagement 具名 execution 只有 configuration，无 phase/goals；实际 12 leaf 同 id 显式 process-resources/single。发行日志只显示这 12 个正式模块启用，独立 external/java-plugin 与 generated-plugin 自带配置。读取既有隔离 Assembly probe 的 POM、三个 native exit0 与字节收据，确认自定义含空格 outputDirectory、既有输出保留和 forced 同长度同 mtime 更新有实际证据；未把该 probe 冒充正式 shade 验收。
3. **发行根来源。** `stage-plugin-configurations.sh` 遍历已 stage 的精确 `plugins/*/lib/main.jar`，无第二份 12 包清单、无手写规则和读取 fallback，要求固定 entry 唯一且非目录。现有 edge probe 覆盖含空格路径、重复执行、缺失/重复/目录拒绝。`verify-archive.sh` 新比较只依赖 ZIP 自身及其解包内容，不读取仓库 stage。
4. **schema/默认职责。** 与实际 Config 构造器及 Entrypoint 核对字段、nullable/正数/整数范围。tool-fs 根及字段允许 null；search limits/timing 及内层可选字段允许 null；四 API 并非 null definition；两个 Void 定义为显式 null。schema 不含 default。路径、blank、业务 provider 组合限制继续由 runtime 处理，未把 minLength 冒充路径/非 blank 保证。Node 根 object 非 nullable，replacement string|null，rules array|null 与三个精确枚举，允许空值/空数组/重复项，不注入默认。
5. **仓内 QA owner。** 实际 helper 使用 `engine.describeDefinitions(pluginId, revision)` 整包 catalog 并核身份；store.find 取得 verified record；pending 外层持有句柄，真实 close 成功后才 parse；facet/definition keySet 精确比较且空 sources 仍调用 checkSources。acceptance/archetype/Node 均实际调用该 helper。close 失败宿主先静态保留整个 owner 再通知终止；helper 阻止随后 engine close；成功或 read 失败但 close 成功由 Engine 单独关闭，fatal 重抛且普通通知异常不覆盖 cleanup 分类。该保留是已批准保守政策，不解释为文件流物理依赖全部 runtime。
6. **模板真实生成。** 独立核保留的仓外 generated-plugin，pluginId 正确展开，`$schema/$defs/$ref/#/$defs` 原文字面保留，作者源/最终 JAR entry/安装根一致；schema SHA256=`cec1dfb2d8663569610e2943ec0149b9281ed123cd078666c9cc29ca33e9fe64`。生成工程无 Fibra parent 和测试 helper 仓库路径依赖。原 archetype Java runtime IT 保留，Node 原 Spring HTTP IT 保留；对应实际 XML 均通过。
7. **helper 不发布。** 在保留的 `author/distribution/run/remote` 中独立打开全部 84 个 main/sources/javadoc JAR，无 AuthorConfigurationQualification 或 author-qa entry。未将 consumer-m2 当正式发布仓；归档移动后的 settings 含原 file 路径，未用它伪称可直接再运行。
8. **fixture 与原门。** 检查 external Java 包及 CLI 组包新增固定合同；改变 lifecycle descriptor/facet 的 fixture 同步重装配 JAR 与根规则，没有盲拷原 external 定义合同。原发行脚本仍创建独立 consumer-m2，原生产 publish 的 skipTests 外未加新跳过项；可选证据目录保留运行树，默认清理行为保留。

## 证据范围与释放

- `full-author-final.exit=0`，独立汇总 134 份 TEST XML：1059 tests，0 failure/error/skipped。
- `distribution.exit=0`、`distribution/native.exit=0`，独立汇总 7 份仓外 TEST XML：11 tests，0 failure/error/skipped。
- `final-archive.exit=0`；原发行日志包含仓外动态插件真实 TTY 完成，未声称重新跑过或证明其它 OS/架构。
- ZIP SHA256：`c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4`。
- Task 6 的 CAS/validator 次数/安全实际出口/Node 数值/client 产品/Tela 尚待后续，不作为本次 Task 5 阻断；I1 为 Task 5 自身已批准合同缺口。
- 本审查未启动 Maven/Node 测试进程；结束时只读定向 `ps` 未发现 fibra-config-contract、Surefire 或 Maven 测试残留。Maven 写入权释放给主线程。
