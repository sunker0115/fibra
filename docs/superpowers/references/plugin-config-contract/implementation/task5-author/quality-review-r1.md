# Task 5 独立质量审查 R1

结论：**B0 / I0 / M0，Ready = YES（仅 Task 5）**。未发现需要修正的阻断、重要或次要质量问题。该结论不关闭 Task 6、跨平台或整个配置合同发行总门。

## 输入与方法

- 分支 `codex/plugin-config-contract`，HEAD/base `0a674176b8b762b4e77044bcba7f051bf06c41d9`，审查未提交工作树的 Task 5 文件集。
- `r1-fix/source-sha256.json` 共 54 文件，审查开始与证据核查均逐文件 SHA256 相同；manifest SHA256 为 `e6e69ce37485a43268255a91f785e112538236f73ef6a355310bd27d30d21df5`。根 POM 的既有 networknt 管理属于前序任务，没有误判成 Task 5 新增依赖。
- 按 `requesting-code-review/SKILL.md` 的质量审查模板执行：核真实 diff、新文件、构建与关键依赖路径、测试断言和制品；没有把 spec-review-r2 或作者 REPORT 的结论当证据。
- 一次 targeted Nowledge memory_search：`Fibra plugin config contract Task5 author schema qualification close failure`，仅返回历史插件隔离/发行规划，未用来判定本次实现完成。
- 无仓库源码修改，无 commit/push/Tela 变更；仅写本目录审查文件。未运行 Maven或重跑全量测试；直接复核绑定的 XML、执行日志、输入与正式制品。独立补跑唯一 staging 反例脚本与 `git diff --check`，均 exit 0。

## 质量判断

1. **构建 owner 与启用边界清楚。** `pom.xml:360` 的 Assembly 具名 execution 仅管理配置，无 phase/goals；12 个叶 POM 才显式绑定。复制非过滤、forced=true，输出到 build.outputDirectory；未引入新的运行依赖或构建插件。distribution `pom.xml:143` 后的执行读取 dependency copy 已 staged 的精确 main.jar，脚本 `src/main/scripts/stage-plugin-configurations.sh:9` 枚举实际 staged JAR，无第二包列表和源码 fallback。唯一固定 entry 与目录拒绝逻辑可追溯，原 bytes 不重序列化。
2. **完整包资格只有一个实现。** `AuthorConfigurationQualification.java:50` 对 target/package identity 精确核对，verified record 打开资源；`:90` 精确比较全部 facet、definition 集合，完整稳定顺序 source 调用 checkSources，包括空集。已读 PackageDefinitionCatalog 的 facet 排序、ConfigSchema.checkSources 的全量编译入口与 FixedPluginConfigurationResource 的物理 read/close owner，未发现凭名称代替实现的假门禁。
3. **资源异常策略与批准口径一致。** helper `:60–79` 保存 pending 后读取，真实 close 成功才置空；关闭失败保留同一个 owner、句柄、Engine/store/targets，宿主先静态持有再通知。原始 fatal 对象直接传播；普通通知异常单独保存，不覆盖安全 cleanup 分类。`:111` 阻止失败资源重试或继续 Engine.close。普通读失败且 close 成功时仍由 Engine 关闭已接管的依赖。这里保留整个 Engine 属于既定 fail-stop 政策，并非声明文件流物理依赖全部 runtime；本审查不重设计该政策。
4. **测试源码分享没有泄漏为产品依赖。** 仓外 `engine-application/pom.xml:45–48` 仅从自身相对 base 编译默认测试源与 author-qa 源。三个仓内 POM 共编译同一文件，保留原默认测试源。独立检查 84 个正式 main/sources/javadoc JAR，没有 helper。独立 archetype 生成项目的 POM 无仓内绝对路径借用。
5. **作者语义与模板没有扩大到业务规则。** 12 个 schema 的 definition 名称与 descriptor/entrypoint 对齐；四 API 为显式空映射。按实际 Config 构造器核对 null、数值范围和默认值；schema 不写 default，路径存在性/blank 等仍属业务层。Node sanitizer 的字段 null/省略、空 replacement、空/重复 rules 保留，未知/错型收紧有明确文档。模板真实生成的 `$schema/$defs/$ref/#/$defs/message` 和 pluginId 展开已直接核字节。
6. **原执行覆盖未被静态资格替代。** 新仓外测试 `AuthorConfigurationConsumerTest.java:31/55/85` 分别覆盖全部 12 个停用正式包、真实安装后坏/缺/孤儿合同、同一个 helper 的关闭失败 owner 保留。原 Java/Node lifecycle、CLI、Spring 测试继续执行。原 FibraSpringHostIT 的真实 Node/Spring HTTP 流程源码和完整运行日志均保留；不把 14 项仓外测试说成 Task 6 产品 client 全面集成。

## 独立核验收据

机器可复核收据：`quality-review-r1-receipt.json`，核验程序：`quality-audit-r1.py`。

- 初轮完整作者执行：134 XML / 1059 tests，逐类核 XML 与 `full-author-final.log` 的结果行一致，failure/error/skipped 均 0，native exit 0。
- 修复后当前内部执行：4 XML / 11 tests，SHA 与当前索引相符，failure/error/skipped 均 0，internal.exit 0。历史 Surefire RED 未混入；未引用旧的 12 项错误计数。
- 修复后仓外执行：8 XML / 14 tests，failure/error/skipped 均 0，external.exit 0；24 源输入同时匹配仓内当前源和仓外复制源。实际 XML classpath 的 15 个 Fibra JAR 与索引完全相符，无工作树 target/classes；加 12 staged formal JAR 共 27 项，逐个与锁定正式发布仓的 JAR SHA 相同。
- 12 包 author source / 最终 staged main.jar entry / stage root / ZIP root 原 bytes 全相等，包含三种 shade。ZIP SHA256 `c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4`。
- 真实仓外模板 source / JAR / root 相等；schema SHA256 `cec1dfb2d8663569610e2943ec0149b9281ed123cd078666c9cc29ca33e9fe64`。修复后 archetype 本地 flattened POM、主 JAR 与原正式发布仓分别保持同 SHA，复用正式制品有事实依据。
- 原发行脚本、native.exit 与 final-archive.exit 均为 0；已核末段真实 TTY 和最终 ZIP 验证成功日志。此次审查没有宣称重新执行它们。
- 新补跑 `verify-stage-plugin-configurations.sh`：含空格路径、重复执行、原字节、缺失/重复/目录 entry 反例通过，日志 `quality-stage-r1.log`；`git diff --check` 通过。

## 边界与释放

本次资格限定已验证的本机平台/JDK21/Node22.14.0，不新增其他平台或未来防御框架承诺。无需最小修正。已只读检查进程：无本次 Maven/Surefire/Failsafe/Fibra Node 遗留；仅见既有 IntelliJ Maven server，未干预。审查未启动 Maven，Maven 使用权已释放给主线程。
