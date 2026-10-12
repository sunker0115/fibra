# Task7 最终门准备（只读核对）

核对日期：2026-10-12。工作目录 `/Users/sunke/dev/ai-project/fibra`。本次未运行 Maven、npm、脚本门或编译器，未修改仓库；以下是待主线程执行的准确入口，不是通过回执。Task6 仍拥有 Maven 单写权，必须收到其明确交接后再运行任何 Maven（含签名、dependency/help goal）。Nowledge MCP `thread_search(query="Fibra plugin-config-contract Task7", limit=3)` 返回 0 条，停止历史扩展。本结论以当前源码为准。

## 1. 最少顺序及证据保存

依据：spec §11 / implementation-plan Task7 / `docs/release.md` 的权威门。先关闭 Task6 证据，再统一 Task7 文档/签名，随后只跑一轮完整门；不要为了更新签名先跑一次必然失败的完整 verify。

0. **先归档再 clean**：Task6 交接时保存当前所有相关 `target/surefire-reports`、`target/failsafe-reports`、archetype invoker 报告、`fibra-parity-tests/target/client-protocol-conformance`、Task6 正式发布仓/消费者报告、实际执行 argv/nativeExit、输入源文件 SHA、输出 JAR/ZIP/tarball SHA。已有 `/private/tmp/...` 独立归档不需复制第二份，但必须核对实际存在且可关联本次输入。不得只保存最后的 Maven SUMMARY。
1. 按第 3 节生成并核对签名（此步骤属于获准 Task7 修改）。完成文档示例和最后修正。
2. 全 reactor `clean verify`。
3. client frozen install 与正式 npm 门，保留 tarball。
4. 用刚才 Maven fixture + 刚才 npm dist 跑真实连接门；复制 fixture 和完整测试报告到 `target` 外。
5. 原可复现发行门；它会删改许多模块 `target`，所以第 4 步归档不能后移。
6. 原架构边界门 + `git diff --check`。
7. 对最终字节核对依赖/声明与 Task6 正式仓外、隔离 Tela 输入身份；逐 CC01–12 对应原断言/正式输出，再做合同独审和质量独审。这里不增加新测试矩阵。

执行环境沿当前 implementation-plan：Java 21 / Maven 3.9.9；Node 使用 `client/.node-version` 的 22.14.0，pnpm 11.19.0，Python3/unzip/Bash/rg 15.0.1。应复用 Task6 已验证的锁定工具路径，不能只用开发机 PATH 偶然版本。下列代码只列命令，不代替执行账本：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2"
"$MVN" --batch-mode --no-transfer-progress -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 clean verify
pnpm --dir client install --frozen-lockfile
FIBRA_NPM_PACK_DIR=/private/tmp/fibra-config-contract-evidence/final/npm-packs bash scripts/verify-client-packages.sh
node client/tests/engine-connection/consume-snapshots.mjs fibra-parity-tests/target/client-protocol-conformance
# 此处归档完整报告和六份 fixture，随后才可执行下面的 clean package 门。
bash scripts/verify-reproducible-release.sh
bash scripts/verify-architecture-boundaries.sh
git diff --check
```

`final/npm-packs` 必须是未使用的空目录；脚本发现已有 tgz 会退出 1。若 pnpm 尚未准备，沿 release 文档先用锁定 Node 下的 `corepack enable` / `corepack prepare pnpm@11.19.0 --activate`，已有合格工具则无需重复修改全局环境。MAVEN_OPTS 的仓库设置为脚本内 Maven 子进程提供相同隔离仓；命令行明确的 `-Dmaven.repo.local`（例如仓外 consumer 仓）仍须保留其自身输入，不能强行替换为空间共享仓。

| 入口 | 实际行为、输出及输入边界 |
| --- | --- |
| `clean verify` | 正式 reactor 测试/IT、archetype、签名、JMH 编译等；报告位于各模块 target；真实六阶段 fixture 位于 parity target。只有整个进程原生退出 0 才是全门通过，局部 SUCCESS 不够。 |
| `verify-client-packages.sh` | `set -euo pipefail`；跑 client test/boundaries，比较两份 d.ts，pack 两个 tgz，检查成员/版本/依赖，复制到仓外临时 consumer，offline install + tsc + node。临时 consumer 在 EXIT 删除；使用 `FIBRA_NPM_PACK_DIR` 只持久保留 tarball，日志/原生退出由外部执行器保存。不是产品 renderer 真执行证明。 |
| `consume-snapshots.mjs` | 输入 initial/candidate/retiring/replaced/reconnected/removed 六份 JSON 和当前正式 protocol dist。缺 fixture/import/断言失败由 Node 非零退出；成功打印 `Engine current -> Java codec -> client protocol -> isolated client factories: passed`。内部 factory 是该门自带 fixture，不可替代 Task6 Tela 产品模块真实 import/create/关闭。 |
| `verify-reproducible-release.sh` | 根据正式模块列表执行两次 `clean package -DskipTests` 和一次 `package -DskipTests`；逐字节 cmp 28 组 JAR、30 个展开 POM、发行 ZIP 和含文件 mode/SHA 的目录树。临时快照 EXIT 删除，最终 target 只留最后一轮制品；它不重跑测试，也不保存正式测试报告。保存外部 log/nativeExit 和最终 SHA。 |
| `verify-architecture-boundaries.sh` | 不编译；扫描路径以及生产源码/POM 的旧模型。违规打印命中并退出 1；成功打印架构边界通过。不能替代 ApiSignatureBaselineTest。 |

保存原生退出建议由既有执行器记录；若 shell 重定向，可用 `if command > "$log" 2>&1; then rc=0; else rc=$?; fi` 后立即写 nativeExit，非零即停止后续门。不要只取 `tee` 的退出，也不要用 grep SUCCESS 代替进程结果。

**仓外分发门不重复纳入本地最终命令表。** `docs/release.md` 明示本地最终验收不执行 `scripts/verify-distribution.sh`，push/release CI 执行；Task6 是本次已授权的正式仓外资格工作，复用其精确发布物及报告。脚本实际会 `clean deploy -DskipTests` 到临时文件仓、distribution `clean verify`、独立 consumer `clean verify`，不是只读检查。当前脚本设置 `FIBRA_DISTRIBUTION_EVIDENCE_DIR` 时在 EXIT 写 `native.exit` 并移动临时目录到 `<dir>/run`；未设置则删除。最终若正式输入变化使 Task6 证据失效，由主线程判定受影响门，不能把旧 SHA 通过冒充新字节。此处不建议再次创建空仓重跑。

## 2. 文档可运行性：已证实的缺口与可复用入口

`docs/api/plugin-configuration.md` 的 Java 方法调用与当前 API 对得上：`LiteralValue.of(Map.of(...))` 合法（它位于 public interface，静态方法隐式 public）；`DesiredInputEntry.builder`、`ApplyDeployment.builder` / selections / configContext / expectedRevision 均存在；`DefinitionConfigDocument.parse` 和 `ConfigSchema.compile(...).validate(...)` 也存在。没有证据指控这里 API 拼写/签名编译错误。

已证实的交付缺口：该文档是片段而非可独立运行程序。`engine`、`expectedTargetRevision`、`fullDesiredGraph`、`fullSelections`、`fullConfigContext`、`documentBytes`、`effectiveValue` 都由外部提供；无 imports/类/安装启动/完整 graph 构建/关闭和运行命令。文字要求 fullDesiredGraph 包含 entry，但片段本身没有把 entry 放进去。不能用“README 示例实际运行”记为已验收，也不能仅对这段文本 javac 就声称通过。

最小处理是保留 API 片段定位，同时明确连接已存在的正式可执行演示，不新建框架：

- 宿主：`fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/LifecycleConsumerTest.java` 的 `discoversAnUnknownDisabledJavaPackageAndCreatesFromItsCatalog` 已包含正式 Java 包安装、disabled 静态 query、不触发 class initialization/validator、非法结构拒绝、完整 desired graph + selections + context、有效启动、validator 一次、原 CAS 拒绝及关闭/恢复读取。由 Task6 的仓外 consumer 正式执行并归档，文档链接该入口及其现有执行命令即可形成可复核路径。
- 作者：同目录 `AuthorConfigurationConsumerTest` 及已共用 `AuthorConfigurationQualification` 负责 12 个正式 JAR 合同、完整静态名称集合、空 API facet、停用坏 schema/missing/orphan；archetype 已有生成/装配门。不要从文档再派生第二资格实现。
- 文档纯校验例子的 `violation` 实际是 `Optional<ConfigViolation>`；当前 var 本身可编译，但应在完整例子展示如何判断结果，否则“调用 validate 即通过”的阅读风险尚未消除。这是补全教学行为，不是 API bug。
- 文档开头仍写完整仓外/Tela 验证进行中；只能随 Task6 的真实结果更新，不能现在改为全可用。

## 3. 公共签名生成和差异核对

当前 `git diff --name-only -- 'docs/api/*signatures.txt'` 无输出；基线尚未随实现更新。`ApiSignatureBaselineTest` 不是仅比较本次三模块：它遍历 19 个公开模块，用实际 classpath anchor codeSource 找类，枚举 public 类型（含 public 嵌套类型），`Class.forName(..., false, loader)`，按名称排序后 `javap -protected`；生成器仅规范换行及末尾空白。`-Dfibra.updateApiBaseline=true` 会写全部 19 个基线，无模块选择参数。

Maven 单写权交接后，最小生成入口：

```bash
"$MVN" --batch-mode --no-transfer-progress -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 \
  -pl fibra-parity-tests -am test -Dtest=ApiSignatureBaselineTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfibra.updateApiBaseline=true
git diff --name-only -- 'docs/api/*signatures.txt'
git diff -- docs/api/fibra-config-public-signatures.txt docs/api/fibra-artifact-public-signatures.txt docs/api/fibra-engine-public-signatures.txt
```

生成前复制 19 份现有基线到仓外留痕；生成入口依赖新编译 reactor class，不应手工用旧 installed SNAPSHOT 拼 javap。没有必要手工编写 baseline 文本。最终第 1 节的全 `clean verify` 不带 update 属性，才是比较模式通过证明。确认环境中没有持久注入 update 属性。

预期差异限定为：

- config：公开 `ConfigSchema`、`ConfigSchemaSource`、`ConfigSchemaException`/Code、`ConfigViolation`、`ConfigValidationInput`、`ConfigBatchFailure`、`DefinitionConfigDocument` 及各自 javap 生成的方法。SchemaBudget/Profile/Library/reader/diagnostics 是内部实现，不应意外变成公开签名。
- artifact：`PluginConfigurationResource` 和 `PluginPackageRecord.configurationResource()`；内部 fixed resource / evidence 不应作为新公开类型。record 的 build 实现变化不应凭空造成合同别名。
- engine：`FibraEngine.describeConfiguration`、`DefinitionConfigContract`、`ConfigContractException`/Code、BuiltInFacet 的 configSchemas getter/builder、BuiltInPluginPackage builder 的 `packageDigest` 输入替换为 `implementationDigest`。最终 packageDigest 读取语义仍在；不添加旧输入别名。

若其余 16 份公开模块或 npm d.ts 改变，不能机械接受；回到实际 diff 判断是否越过获准合同（名称 SPI/raw target/client wire 预期不变）。记录父类、方法异常和公开嵌套类型等 javap 实际差异，不能只检查新类数量。

## 4. 依赖、许可证、体积：可复用证据与尚缺绑定

可复用当前项目证据（都在 `docs/superpowers/references/plugin-config-contract/implementation/`）：

- `task1-config/dependency-tree.log`：显示 validator 3.0.8、itu 1.14.0、SLF4J 2.0.18、Jackson databind/core/yaml 3.1.5；这份输出不是完整 release 传递依赖清单（没有全部依赖节点），不能称已覆盖完整依赖/SCA。
- `dependency-security/resolved-inputs.json`：新增两库 JAR/POM 的精确 SHA、字节数。validator JAR SHA `d3f4c61ba35c3543bdf1ab42462bf89721542f4adea5b092ee7b430dfd467181`，itu JAR SHA `5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a`。
- `dependency-security/osv-receipt.json`：2026-10-12T06:48:00Z OSV querybatch HTTP 200，两项空结果。仅该时间/两项查询没有返回已知漏洞，不是全依赖无漏洞保证；保留 request/response 以核对查询坐标。无需把历史 `osv-error.txt` 覆写掉。
- `dependency-security/itu-source-license-receipt.json`：精确 sources JAR SHA、50353 字节、Apache-2.0 源文件版权头；该 sources JAR 无独立 NOTICE/LICENSE entry，回执已明确。
- `THIRD_PARTY_NOTICES.md` 已包含 validator 上游 NOTICE 和 itu 源码版权声明；root POM 明确 validator 版本及 YAML 排除，config 保留原有 Jackson YAML 依赖。
- `dependency-security/task5-library-receipt.json`：绑定旧 Task5 ZIP SHA `c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4`；validator 585170 + itu 57633 = **642803 字节 JAR**；对应 ZIP entry compressed sizes 526444 + 51666 = **578110 字节**。两者都不是整个发行 ZIP 相比改动前的净增量，也不是启动基准。

最终尚需的是 **绑定当前最终字节**，不是重复候选资格研究：

1. 在最后 POM 上保存有效 POM / 完整相关 runtime dependency tree，确认 Jackson/SLF4J 未升级、validator 的 YAML 排除生效、无新 Joni/Graal/log provider。现有定向 tree 可作对照，不替代完整输出。若 Task6 已保存相同 POM SHA 的完整输出，直接复用。
2. 从最终正式 JAR/ZIP 核对两库 SHA 与 notices/LICENSE 实际成员。现有 Task5 库字节/许可证来源可复用；最终 ZIP SHA 改变时不能只转述旧回执。`verify-archive.sh` 检查 THIRD_PARTY_NOTICES 文件存在不等于逐字节确认新声明已进入最终归档。
3. 正式 Java/Node/隔离 Tela consumer 的实跑与 ClassLoader/资源关闭证据使用 Task6 回执；本次未检阅其最终结果，因此不声称已通过。候选 probe 不替代正式启动；没有启动性能基准时仅写功能启动通过/性能未测，不额外增设性能测试门。
4. 所有体积报告采用已有两个条目测量的口径；若没有同条件改动前最终 ZIP，就不报告整个发行增量。两项 OSV 结果明确范围，不扩写为全依赖 SCA 通过。

本次只准备门禁，不对尚在 Task6 修改中的源码做第二次架构审查，不重开已通过 Task1–5 的合同决定。
