# 整体公开 API 收口资格

2026-10-15；基线 `e1338146e5c11bd848db69c5cbdf830e7a3cfa20`，工作分支 `codex/public-api-boundaries`。
以下绑定已验证的提交前工作树，不能把基线 SHA 当成本轮实现提交；实现以包含本记录的 Git 提交定位。
设计见[稳定边界](../../specs/2026-10-15-public-api-boundaries-design.md)，范围见[完整清单](../../../api/public-api-inventory.md)。

## 证据位置与隔离

本轮原始证据根为 `/private/tmp/fibra-api-boundaries-evidence-20261015`。
各运行目录有 `command.json`、`stdout.log`、`stderr.log`；记录实际 argv、cwd、起止、原生退出及仓内命令的源码摘要。
`environment.json` 记录 JDK21、Maven3.9.9、Node22.14.0、pnpm11.19.0、ripgrep15.0.1 与 Bash5.3。

`build-m2` 与 `consumer-m2` 均排除了先前的 `com/sstlfsj`；构建只发布到本轮 `public-repository`。
交付目录 `artifacts/maven` 仅保留各正式模块最新已通过批次，另含二进制 ZIP 和两份 npm tarball。
不写默认 Maven 仓，不覆盖 Tela 正在消费的 e133814 file 仓或其独立 m2。

## 实际门禁

| 证据目录 | 实际结果 |
|---|---|
| `visibility-red` / `visibility-green` | 十个内部型可见性先红后绿，真实 SPI 保持公开；原包边界测试通过 |
| `registry-cas-red` / `registry-view-red` / `registry-audit-red` | 分别复现旧请求丢失编辑版本、旧四字段读形状、排队 no-op 误报 SAVED |
| `registry-green-final` | Registry 23项，0失败/错误/跳过；保留原失败、UNCERTAIN、watch、关闭，并覆盖增量 fresh/CAS 无重试 |
| `signatures-update` | 只更新 engine/registry/runtime-java/runtime-node；19份当前签名414类型，其余15份及npm声明不漂移 |
| `full-verified-publication-bash5` | 原生0；完整 clean deploy，175份XML共1268项，0失败/错误/跳过；含正式插件IT、重启、签名与发行ZIP仓外工具/恢复/升级/TERM/INT验证 |
| `architecture-boundaries` / `client-public-packages-green` | 原生0；架构检查与npm打包消费者/声明/协议fixture通过 |
| `public-distribution-consumers` | 原生0；仓外六个消费模块22项，0失败/错误/跳过，含新Registry合同、core、Java/Node、runtime SPI、CLI与Spring |
| `public-host-contract-consumer` | 原生0；原通用Host API外部消费者4项，0失败/错误/跳过 |
| `real-model-compile` / `real-model-public-zip` | 原生0；使用新ZIP根lib的26个JAR，真实Model API原JAR/原provider类字节不变，类型共享、替换、移除及关闭通过 |
| `release-reproducibility` | 原生0；两次clean package及一次已有JAR再package，30模块全部POM/主JAR/sources/Javadoc、ZIP和目录权限逐字节一致；包装比较不替代上述正式测试 |

独立设计、实现规格、代码质量及文档一致性审查均清零，记录见[设计复审](design-review.md)与[实现复审](implementation-review.md)。
最终交付复核独立重算源码、XML、消费来源和新旧制品摘要，结论B0/I0/M0；这些审查不替代上表运行结果。
可机读结果及代码/签名摘要见[验证回执](verification-receipt.json)。

## 源码与制品对应

`artifact-sha256.json` 记录本轮528个交付文件。30个正式模块的最新 POM/主 JAR/sources/Javadoc/ZIP 已与构建输出逐字节比较。
`source-jar-correspondence.json` 核对429份 Java 源文件与公开 sources JAR；`published-source-sha256.json` 及
`final-source-and-old-delivery-binding.json` 确认858个非文档输入未在正式验证后改变。后续文档状态同步单独检查。
仓外fixture源摘要见 `consumer-source-sha256.json`；55个实际消费POM/JAR均与本轮公开仓匹配，
来源跟踪为对应file仓，见 `consumer-resolution-sha256.json`，没有借用Fibra reactor classes。
Model probe 的输入、显式类型集合与26个根lib摘要位于 `model-consumer/inputs.json`、`zip-lib-sha256.json`；
未把插件目录内的 `main.jar` 混入宿主classpath。

本轮ZIP SHA-256：`4f9656c1f16d4e66ff4181c188c015904928bc3f566025ee14104ac3c1a6d653`。
可复现门结束后的30模块输出也与本轮已交付公开仓逐字节一致，见 `final-reproducible-publication-comparison.json`。
e133814旧交付526个文件全部保留原摘要，ZIP仍为
`7abac3af044e87dce072107b4795313c26c4065f266e38ce08308055e9200639`。

## 失败记录与运行边界

首次离线 deploy 被 Maven 在首模块拒绝；改正常模式写本地file仓。首次客户端运行PATH缺pnpm，补已安装11.19.0后原门通过。
首次正常模式整仓在ZIP的INT检查失败，实际4而预期130；未改断言。最小相同后台trap探针表明系统Bash3.2保留SIG_IGN，
已安装Bash5.3恢复默认，见 `sigint-shell-disposition-probe.json`；选用Bash5.3后完整原门通过。
Registry首次GREEN还曾使用非法非SHA256测试fixture，已修正fixture并记录；不是生产修复或忽略测试。

运行资格限本机macOS ARM/JDK21及上述工具环境；未执行远端CI、真实Windows/Linux内核或浏览器UI验证，
不将相应适配器的模拟测试当作这些平台的实机证明。

## Tela未来升级交接

当前Tela可继续固定e133814，不需要暂停其无关工作。本轮未修改Tela。
升级本轮未发布0.5.0-SNAPSHOT时须整批重编，并切换到明确的新制品路径/摘要，不能跟随默认同坐标SNAPSHOT。

- 完整请求使用 `PluginDeploymentRequest(expectedRevision,selections,graph,configContext)`；在开始读取/编辑输入前捕获target revision，无target为0，冲突不自动换latest重试。
- Bootstrap普通管理读取由 `snapshot.engine().durableState()`、`snapshot.engineDiagnostics().mutationGateOpen()` 改为snapshot直接字段；两个旧请求测试及mock同步迁移。
- 高级attempt/current读取通过 `registry.diagnostics().engine()`；分次读普通snapshot与diagnostics须比较viewRevision。
- 十个内部实现/遗留型不再公开；真正RuntimeProvider/Driver/Candidate/Generation/Unit与Host类型合同保持支持。

不改变target持久格式、贡献kind/wire、API inventory装载、Store/恢复或Engine生命周期owner；不要求Tela改变合法的Engine直接组合方式。
