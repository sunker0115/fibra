# Fibra Java 能力包整理实施计划

**目标：** 整理 engine、config、cli-api 的 Java 能力边界，同步公开签名、全部仓内消费和 Tela 消费，保留现有模块、业务行为和唯一生命周期 owner。

**授权：** 用户 2026-10-10 确认开始，允许前一轮列出的公开包名迁移及 Tela 适配，并要求切分支、设置目标；验收后追加授权提交 Fibra。仅本地 commit，不 push 或发布；Tela 独立消费分支仍保持未提交。

**架构：** 以现有 Client Foundation/vNext 模块职责为准。仅对有真实职责和单向依赖证据的类型群迁包；共同锁、资源和包私有协作保留同包。无新增 Maven 模块、依赖、兼容入口、业务状态或执行路径。

**整体视角：** 用户补充要求从整体架构处理。先覆盖全部现有模块的能力、唯一 owner、公开契约、依赖和下游消费，再选择有净收益的 Java 包迁移；不以文件数、目录层数或预设三模块范围替代边界证据。发现范围内真正结构缺口需修正设计，而不是只机械移动文件。

**技术栈：** Java 21、Maven 3.9.9、现有 JUnit/javap/jdeps 与 JavaCompiler。工具链按本地 mvn-env 技能和项目锁定配置核对。

## 基线与工作树保护

- Fibra：`03c8f2a`，初始工作树干净；当前分支 `codex/fibra-package-structure`。
- Tela：已提交 `2f49e23`；当前 `codex/desktop-module-boundaries` 有用户在途 Desktop/OpenAI 修改。消费适配使用独立工作树，不修改该工作树、其计划或现有未跟踪探针。
- 当前静态边界脚本通过；历史测试记录不当成本次证据。
- Nowledge 线程检索已尝试，本机服务拒绝连接；设计依赖当前源码及 Tela 固定参照，不推定历史决策。

## 步骤、验收与停止条件

- [x] P1 整体设计：覆盖全部 44 reactor POM、插件族、两份 npm 契约和仓外消费者。类型映射独审后将 PublishedRevisionConflictException 一并归入 publication；权威决定在 Client Foundation §3.1，全模块依据在 references/2026-10-10-package-structure-audit.md。
- [x] P2 结构门禁：真实编译产物 inventory/jdeps、允许边/包环、正向及反向/循环/未知包编译夹具、受限访问拒绝共 9 项。旧结构预期 RED；新结构 GREEN。负编译按 JDK 实际访问诊断精确断言，缺类/缺符号不能通过。
- [x] P3 迁移：Engine 59、CLI API 27 类型迁移，8 项测试随 owner 移包，两个 Engine 大测试只经 test-source fixture 调用原同包工厂。最终含模板/消费者的 422 份生产 Java 正文归一化后唯一非名称变化为下述 R1；6 份重新生成的签名基线归一化后只有包名变化。Config 保留原包。
- [x] R1 整体 owner 缺口：真实首次 apply 中 A 持有 drain 失败资源、B 启动失败，旧 CLI catch 错误释放两个 store 锁，RED 已确认。现仅清理尚未移交 Engine 的 stores。四个子 JVM 场景 GREEN：成功关闭、普通启动失败、drain 失败保留锁、转移前创建失败释放 package 锁；Engine 构造失败清理沿现有三项测试验证。
- [x] P4 Fibra 验证：根 clean verify、API/包门禁、正式 ZIP、临时 file 仓部署、空缓存六类 Maven consumers、插件模板、动态 CLI/真实 PTY、npm tarball 与跨语言快照全部通过。测试失败先追溯原因，同一问题两次无新证据则停止硬试。
- [x] P5 Tela 消费：独立分支仅适配 43 份 Java 引用并同步文档；本次八模块 clean verify、4,705 Java 测试（含 270 发行 IT）和 23 Desktop 测试全通过，零失败/错误/跳过。CLI ZIP、Desktop ZIP、实际 App 的 Host 字节一致，其 14 个 Fibra JAR 与本次隔离制品一致。
- [x] P6 独立规格与质量审查：规格、质量及文档增量审查均 B0/I0/M0；主线程核对实际 diff、源码身份、迁移完整性、API 归一化及全量/发行证据，目标完成。按后续授权将 Fibra 变更形成本地提交，不推送。

同一工作树的 Maven 构建串行，避免 target 覆盖；上游隔离安装完成后，独立 Tela 工作树可与使用另一份空缓存的仓外 consumer 验证并行。若发现必须改变方法可见性、状态/事务/生命周期语义、新依赖或覆盖其他工作树改动才能迁移，停止受影响部分并重开该设计；继续独立可做项。

## 当前进度

目标已完成（2026-10-10）。旧结构 8 模块 reactor 基线通过；迁移后的初次行为回归仅在新增 R1 用例发现预期缺口，不能报整轮成功。修复后的 74 项定向验证（60 Engine、4 CLI、9 包结构、1 API 基线）全部通过。

2026-10-10 本次根 `clean verify -Dfibra.distribution.verify.skip=false` 原生退出 0：44 reactor 全部成功，156 套件、1,024 项 Java 测试，失败/错误/跳过均为 0（计数不包含嵌套 archetype consumer 日志）；正式 ZIP 仓外验收通过。`scripts/verify-client-packages.sh` 及 Java Engine 快照到 TS consumer 验证原生退出 0。`scripts/verify-distribution.sh` 原生退出 0，临时 file 仓部署、再次 ZIP 验证、空缓存六类 consumers、模板与真实 PTY 全部通过。

Tela 本次八模块 `clean verify` 于 21:09:53 +08:00 原生退出 0，耗时 28:34；111 XML 套件、4,705 Java 测试与 23 Desktop 测试全部通过，包括 270 项 CLI/实际 App 累积发行场景。所有制品身份已核对，未用历史 r11 结果代替本次验收。

规格、质量与文档增量独立审查均为 blocker 0 / important 0 / minor 0。独立核对和主线程复核均确认：Fibra 422 个生产文件唯一行为变化为 R1，Tela 213 个生产文件无非名称变化；未遗漏文件，生产 JAR 无旧类或测试 fixture。两仓 `git diff --check` 通过，无新增 Maven 模块/依赖或可见性变化。

日志与中间证据保存在 `/private/tmp/fibra-package-structure-evidence/`：`baseline-test.log`、`package-boundary-red.log`、`api-comparison.json`、`final-body-comparison.json`、`migrated-tests.log`（R1 RED）、`owner-and-boundary-green.log`、`full-clean-verify.log`、`full-clean-verify-summary.json`、`client-packages.log`、`client-snapshots.log`、`distribution-consumers.log`、`tela-clean-verify.log`、`spec-review.md`、`quality-review.md`。早期 `body-comparison.json` 是 R1 修复前快照，不代表最终源码。首次结构 GREEN 尝试因 JDK 访问错误诊断代码假设不符失败，已核对真实 javac 诊断后修正，无放宽缺类断言。

Tela 消费分支 `codex/fibra-package-consumer`，基线 `2f49e23`；已适配 43 份 Java 源码/测试引用，原 Desktop 工作树未修改。验收在 `/private/tmp/tela-fibra-package-consumer` 完成，随后原样保存至 `/Users/sunke/dev/ai-project/tela-fibra-package-consumer`。新 SNAPSHOT 安装使用隔离 Maven 缓存 `m2/`，未覆盖用户全局缓存。

收口时检测到原 Tela Desktop 分支已由其他工作推进至 `b875438`（Desktop execution domains/model mapping）；本次消费分支和上述验收仍明确基于 `2f49e23`，没有包含、合并或覆盖该并行提交。后续整合两个维护分支时，新增 Desktop/model 改动仍需在合并后的源码上复验，不能直接沿用本次固定基线的结果。

源码与制品身份详见 `source-identity.json` / `artifact-identity.json`：Fibra Java/POM tree 为 `c92e4c1271fe255b5bd76347398f33314e1b6a3b59cff069a3802a29f585e264`；Tela 为 `36c044b44c21cd4ae924f472519601ba4c345dfbf5568288e1fbfa45e040f3f6`。Tela Host JAR 为 `945d6272583eb472a34b03e8f8648291d4f9bfde0c268da108d29448a80320b5`，CLI ZIP 为 `42908b4f9b879f45628b64963a7ef5908c3c316708b094028bc255f76cce2ffb`，Desktop ZIP 为 `7c814c2b66fd32db314b229de6bc69067b06dc11ae2748a55e9e95b576dea33a`。记录的是未提交工作树的精确 Java/POM 字节及其制品，不把分支 HEAD 当成本次实现提交。

## 复验入口与环境边界

本轮使用 JDK 21、Maven 3.9.9、pnpm 11.19.0；本机 Node 22.22.2 与 ripgrep 15.2.0（Fibra CI 锁定 22.14.0 / 15.0.1）。结果只证明当前 macOS ARM64 环境，不替代锁定版本 CI、其他平台、签名公证或公开发布门禁。

- Fibra 全量：`isolated-mvn.sh -o -B -ntp clean verify -Dfibra.distribution.verify.skip=false`。
- 正式分发：`MVN=isolated-mvn.sh scripts/verify-distribution.sh`，只部署临时 file 仓；脚本跳过 producer 重复测试，前置完整测试由上一条提供，再用空缓存编译/运行仓外消费者。
- npm：`scripts/verify-client-packages.sh`；跨语言：`node client/tests/engine-connection/consume-snapshots.mjs fibra-parity-tests/target/client-protocol-conformance`。
- Tela：在独立工作树运行 `isolated-mvn.sh -o -B -ntp clean verify`；其 Desktop pnpm 使用原锁文件。真实外部模型服务本轮未调用；已适配模型插件 Java 引用，产品协议未改，现有确定性/本地模拟模型与实际发行物场景由该构建验收。

`isolated-mvn.sh` 为临时工具包装，位于上述证据目录：设置 JDK 21 与 Maven 3.9.9，并传入 `-Dmaven.repo.local=<证据目录>/m2`。重新执行时需为该目录配置已有第三方依赖缓存，不得把迁移前后的同坐标 Fibra 制品混装。
