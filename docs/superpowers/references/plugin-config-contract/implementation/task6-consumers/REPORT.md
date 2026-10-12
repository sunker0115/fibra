# Task6 正式消费者与隔离 Tela 自审

状态：DONE，待主线程安排规格与质量独立审查。未 commit/push；Fibra 生产合同未改。仅 3 个 Fibra 消费者文件（清单/最终 SHA 见 task6-fibra-file-sha256.json）以及隔离 Tela 19 个适配文件。Tela 主树与 P3 当前计划未写入。

## 本轮最终收据

- `external-frozen.log/.exit`：正式仓外完整 `clean verify`，19 tests / 8 XML，0 failures/errors/skipped。相对 Task5 的14项，增加5项；既有 Node/Java 功能、hold/drain、loader/PID/session 清理、CLI、Spring、作者 QA 均保留。`external-final` 是收紧 active source query 断言前的绿色，不作为最终源锁运行。
- `tela-package-r3.log/.exit`：完整隔离 Tela `package -DskipTests` nativeExit 0。生成真实四作者 JAR（agent/openai 使用 `-plugin.jar`）、基础 ZIP 和 Desktop ZIP。Java 测试被明确跳过，不能称完整 Java 绿色；构建期间原 Desktop npm suite 29 项通过（其中 renderer 是包装子进程入口）。未启动 Electron UI。
- `tela-focused-final.log/.exit`：34 tests / 5 XML，0 failures/errors/skipped。四作者真实 JAR 的低层准备/错误保留；独立 Host Engine 静态查询、结构拒绝、实际 API definition 执行；真实 Host client assignment/codec；Gateway 安全错误投影和实际日志；原 client pending/启停/关闭回归。
- `tela-renderer-final.log/.exit`：28 项 VM 场景通过。`TELA_CONFIG_WIRE=tela-client-snapshot.json` 使用上一步真实 Engine assignment 经正式 Java codec 输出的 wire，再由正式 npm protocol 与 renderer 真实 create 消费。`wire-sha256.json` 绑定实际输入。
- 最终 XML 只来自对应本轮已实际运行类，复制到 `final-reports/`。`final-reports.json` 记 tests/时间/SHA；不把旧 target 报告或先前 RED 混入合计。

## 覆盖落点

1. `LifecycleConsumerTest` 静态目录场景增加 `describeConfiguration` exact target/package/ref/source；query 与非法结构输入均无 Java entrypoint 初始化、无 validator marker。合法 Engine 启动一次 validator，运行结果为 trim 后 config；raw target 没有反写。测试专用 marker/validator 只在外部 fixture `LifecycleEntrypoint`。
2. Java/Node 原 Registry 生命周期补 R1 宽松→只收紧包根 schema 的 R2 拒绝：NOT_SAVED、旧 selection/current runtime/target bytes/save count、Node PID 保留，R2 已在 package history。disable 后选择 R2 可暂存，enable 拒绝保留旧 disabled target。Node 原进程会话关闭断言保留。
3. 原文件 store delegate 的受控 save barrier 令两条同 captured expectedRevision 的 Apply 确实重叠；一条完成，一条明确原 revision conflict；只保存/推进一次，真实 Java validator 只有一次。没有另造 runtime 框架。
4. 相同有效合法值与未知键/值 canary 经 Engine、Registry、真实 `FibraCli.run` 成功/拒绝。CLI 真 stdout/stderr/native code 在 `actual-output/`。Registry 最终 audit detail 与异常 cause/suppressed 检查；授权 snapshot 可读合法实例 canary，active 后重新 query 的 source 仍等于纯静态规则。Tela `TelaHostGateway.errorBody` 为真实 Engine failure 的安全投影；OutputCapture 检查真实日志，未替换 mapper。
5. 真实 Node start/tool/stop 验 `9007199254740991/2/3`：schema/target 精确保存第三个值，Node JSON.parse/Number 回显为 `9007199254740992`；上界外值在原进程前拒绝。client 的同一大数为 tagged NUMBER 字符串，经 Java Host wire→npm protocol→actual create 精确保留；不更改 wire，不给 agent-view/sanitizer 新加产品字段。
6. 四 Java 作者唯一 `src/plugin/fibra-config.json` 经 assembly 进入 `META-INF/fibra/config.json`，distribution 从真实 JAR 提取并 cmp。model-api 有真实 definition；deterministic 为空 object；agent 要求 dataRoot string/minLength，业务非 blank 验证仍由原 validator；openai 必填 model，可 null baseUrl/timeout，timeout 为正 long 范围，默认60000仍仅业务层提供，API key 未进 schema。
7. agent-view 唯一作者规则通过原 build 复制到 package 根；ZIP 未仅把 schema 放在 client payload。renderer harness import 的是实际 build 的 agent-view.js，实际 create/activate/stop、root unmount 仅一次、scope.closed、unit移除与 URL revoke 均验证。有限 TelaReact/TelaUi stub 不代表真实 React UI/Electron 实机。

## 身份和边界

- `external-input-sha256.json` 锁独立消费者所有输入，与仓内对应源码逐字节一致；root parent 是消费者自有 POM，不依赖 Fibra parent/reactor/target/classes。自己的 fixture JAR 在外部 reactor 构建。
- `resolved-fibra-jar-sha256.json` 的54条实际消费路径（两个独立cache/fixture stage中的重复坐标分别记录）逐字节匹配 `author/distribution/run/remote` 本轮正式发布 JAR。没有重新发布未变的 Fibra 生产源，没有混入旧同坐标 SNAPSHOT。
- Tela Maven cache 从本轮独立 consumer cache 复制，缺失的现有 release 依赖由 Maven 精确解析下载；未从旧 Tela/Fibra SNAPSHOT cache 混拷。
- `tela-input-sha256.json` 锁482最终源输入；原476输入来自旧 reference 副本，并非原 HEAD 的干净 checkout。`tela-changed-inputs.json` 与 `tela-adaptation.patch` 相对那份已锁输入，新增6文件、修改13文件。两份 `*-final-source.tar.gz` 在 target 外保留可复核源码。
- `tela-author-artifacts.json` 验四 Java 源/JAR entry/装配根/实际 ZIP root 完全相等，JAR本体亦与ZIP payload一致；client source/package/ZIP以及真实module字节相等。`tela-distributions/` 已保留两 ZIP，SHA 见 `tela-distribution-sha256.json`。正式 npm 两 tgz 与本轮 Node22.14 发布包同字节，见 `npm-sha256.json`。
- `collect.py` 只是本次临时收据采集器，做 SHA/XML/ZIP等值断言；不是运行时/仓内验收框架。

## 失败及修正轨迹

- `java-validator-red.log/.exit=1`：预期 NoSuchFile，旧fixture没有validator marker；随后仅加真实validator marker/trim，Lifecycle绿色。首次RED XML未单独保留，完整原日志保留；最终XML另存，不重建历史报告。
- `tela-renderer-red.log/.exit=1`：27/28，缺实际package根合同文件；新增作者单源与原build复制后28/28。
- `tela-existing-fixture-red.log/.exit=1`：原client测试在共同结构门报 MISSING_CONTRACT，RED XML存 `red-reports/`；两处null输入夹具仅增加明确null schema，原pending/关闭34项组合回归通过。
- `tela-package-r1.exit=1` 为新增测试 Map 泛型编译错误，不称预期RED；改明确 `Map<String,Object>`。
- `tela-package-r2.exit=1` 为 sandbox切换pnpm store触发无TTY重装提示；保持原命令/依赖，在已批准同一环境重跑 r3 成功。未关闭安全检查、跳过测试或改依赖。
- `tela-pnpm-install.exit=1` 为offline缺锁定typescript tarball；只按原锁下载，`tela-pnpm-install-online.exit=0`，版本与锁文件未变。

## 自审结论

DONE：本轮 Task6 的必要消费/隔离迁移/字节收据已齐，`git diff --check` 通过，canary 未出现在最终 external/Tela/renderer日志。没有发现需修改核心生产合同的问题。原核心 owner五路径/预算/精确数字语义/保存UNCONFIRMED与恢复矩阵沿 Task4/5 已有证据，不重复实现或将本轮局部测试冒充这些全量回归。

CONCERNS：本轮未跑完整 Tela 全部 Java/发行IT矩阵；未执行 Electron 实机或 OpenAI联网；Desktop ZIP构建/本地签名不等于签名公证或干净机器验收。真实client执行是VM中产品module，ACK仅用于既有Host协议测试，绝不作为模块执行证据。Task7全量/签名/发行门与两轮独立审查仍由主线程关闭。既有Jackson依赖资格由主线程处理，本轮未升级依赖。

BLOCKED：无本轮已知阻断。源码冻结；Maven写入权已释放给主线程，不再启动新构建。
