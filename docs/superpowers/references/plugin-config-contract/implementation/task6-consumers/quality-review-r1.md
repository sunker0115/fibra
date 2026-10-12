# Task6 独立质量审查 R1

结论：**B0 / I2 / M0，Ready: No（修复后同范围复审）**。两项均为本轮新增测试的构建/并发依赖问题，不要求改 Fibra 生产合同或依赖版本。

## 审查身份与范围

- 审查对象为 `fibra-task6.patch` 中 3 个 Fibra 消费者文件，以及 `tela-adaptation.patch` 中隔离 Tela 的 19 项变化（6 新增、13 修改）；没有把 Tasks1–5 或 Task7 全量门重新纳入本轮。
- Fibra 分支 `codex/plugin-config-contract`，基准/当前 HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`，变化未提交。Tela 审查源 `/private/tmp/tela-config-consumer.7vs6zn1a/tela`；未读写 Tela 主树。
- 使用 `requesting-code-review/code-reviewer.md` 的质量、架构、测试与发行核对维度。阅读实际补丁、关键被调用源码和运行记录，而非复述规格审查 PASS。
- 本报告判定绑定审查开始时的冻结输入：Fibra 清单 SHA `5fe0438666658641630cc718a11f4425a688c025c34152434805f892a8377b4a`，Tela 清单 SHA `5963ae08a4590ef84a8ef6c58ae250ceca919307b85b3b0930bc0d562e2fb071`；规格报告 SHA `2d4e975202385533274c4f9cfd34a42a2f0cc7c55cde2b16a39e9c4411963b70`。
- 按要求执行一次 targeted Nowledge MCP 查询 `fibra plugin-config-contract Task6 consumer Tela configuration`；结果为旧发行规划，不用于本次判断，不继续追溯。具体判断来自当前冻结代码及独立核验。

## 已成立的质量与证据

1. 复用了 LifecycleConsumer、文件 target store delegate 和原 runtime fixture；没有新增生产 owner、第二执行入口或故障注入框架。Java validator 的 marker 及 trim 仅在测试插件；一次验证断言能区分运行时业务校验与前置 schema 拒绝。
2. CAS 测试的第二 `doOnSubscribe` 位于 `Mono.defer → EngineCommandLoop.submit` 之后；读取真实 `EngineCommandLoop` 确认它先入队再返回结果 Mono。第一命令阻塞于真实 durable save，第二命令在同 expectedRevision 下已入队，释放后由唯一串行 lane 做旧 revision 判定；finally 释放 barrier，Engine 关闭会排空。没有以 sleep 作为并发成立证据。
3. R2 只收紧包根 schema；测试保留 target 字节、selection、current instance、save count、Node PID，并验证包 history 已发表候选与 target NOT_SAVED 分离。disable/enable 路径检查旧 disabled target；Java/Node 原生命周期与正常关闭覆盖仍在。
4. Engine/Registry/CLI 真入口、audit/error cause/suppressed、纯静态 query 和授权 snapshot 值的断言边界正确。Tela 新测试确实用真实 Engine failure 与既有 Gateway error mapper，不过其构建依赖存在下述 I1。
5. Node 的精确输入/target 与实际 JSON Number 舍入断言没有混淆。client 数值来自真实 Host assignment 的正式 Java codec，核验 wire 为 `{"kind":"NUMBER","value":"9007199254740993"}`；renderer 通过正式 npm protocol 后实际 create 接收字符串，没有把 ACK 冒充执行。
6. 实际 agent-view 的 VM import/create/activate/stop 断言覆盖 root unmount 一次、scope closed、unit 移除和已取得 Blob URL 全部 revoke；有限 TelaReact/TelaUi stub 及未执行 React component body 的限制已如实说明。该测试自身与原构建测试有 I2。
7. Java 作者规则只有 `src/plugin/fibra-config.json` 一个源。assembly 复制到 JAR 的 `META-INF/fibra/config.json`；staging 从正式 JAR 提取且 `cmp` 源字节，没有 fallback。agent、OpenAI 的结构约束与现有业务入口一致；未把密钥或业务默认值引入 schema，也未改已有真实低层 validator 的错误测试。

独立复算结果（原始 JSON 收据为 `quality-review-r1-receipt.json`）：

- Fibra 3、仓外消费者 24、Tela 482 个输入全匹配；两个源码 tar 的完整文件集合及每个成员字节匹配清单。Fibra Task6 补丁可对当前源反向 `git apply --check`。
- 54 条实际 JAR 路径对应 27 个独立正式 JAR SHA，逐字节匹配 `author/distribution/run/remote`；两份正式 npm tarball 与 vendor 包相同，安装目录全部普通文件亦与各 tar 成员相同。
- 四 Java source/JAR entry/装配根/base ZIP 字节相同，JAR 本体与 ZIP payload 相同；agent-view source/package/base ZIP 与实际 module 字节相同；Desktop ZIP 的五个包根规则与相同作者源一致。未在 base ZIP 发现本轮收据/采集脚本文件。
- 归档 XML 独立解析：external 19 tests / 8 XML，Tela 34 / 5 XML，failures/errors/skipped 全 0。核对最终 native exits 均 0、三个最终运行日志无 canary。renderer 最终记录 28 通过；`package -DskipTests` 只算完整构建，构建中的旧 npm suite 29 通过不算全 Java。
- 首次 Java RED 只有日志、没有保留 XML；未重造历史。其他已归档 RED 与最终 GREEN 保持分开。

## Important

### I1：Host 单元测试依赖下游 Desktop 的残留 target

- 位置：`/private/tmp/tela-config-consumer.7vs6zn1a/tela/tela-host/src/test/java/com/sstlfsj/tela/host/runtime/TelaHostGatewayErrorTest.java:52–55`，失败点第 54 行。
- 新方法无条件复制 `../tela-desktop/target/client-plugin/agent-view.js`、`tela-client.json` 及 `target/client-package/fibra-config.json`。根 POM 的顺序是 Host 后才 Desktop；Host POM 无生产这些文件的步骤，Desktop 正式构建位于自身 `prepare-package`。所以干净首次 `test/package/verify` 在 Host 测试阶段尚无这些文件。现有先 `package -DskipTests` 再 focused 的成功序列掩盖此依赖。
- **真实窄复现：** 将冻结 `tela-final-source.tar.gz` 提取到 `/private/tmp/tela-task6-quality-clean-enjzcaj_`，独立核对 482 输入 SHA，确认没有任何 target；使用相同正式 Maven cache 和 JDK21，仅运行 `-pl tela-host -Dtest=TelaHostGatewayErrorTest#actualClientContractFailureUsesSafeGatewayProjectionAndLogs test`。nativeExit **1**，**1 test / 1 error**，`NoSuchFileException: ../tela-desktop/target/client-plugin/agent-view.js`，堆栈明确落第54行。完整命令、日志与 XML 均保留在本目录 quality 收据中。
- 最小修复：Host 层用自包含 client 包夹具继续验证真实 Engine、audit 和 Gateway 安全错误投影；真实 agent-view 产物组合保留在拥有构建顺序的 distribution/package/verify 或 Desktop 验收层。不要给 Host 增加反向 Desktop 构建依赖，也不要用 assume/skip 掩盖缺文件。
- 关闭条件：无预先 Desktop target 的相同单方法通过，正式 agent-view 组合证据仍保留。这里只判 Host 错误依赖，不把有明确 package 前置的 distribution 发行消费测试当作同一个问题。

### I2：新增 renderer 测试与原测试并行删除、重建同一个 target

- 位置：`/private/tmp/tela-config-consumer.7vs6zn1a/tela/tela-desktop/test/renderer-load.test.mjs:143–147`。
- 新测试同步启动 `scripts/build.mjs`，既有 `test/module-boundaries.test.mjs:57–61` 也启动相同脚本并读取同一产品文件。后者原注释明确只有它写 target；此次新增破坏了此前单 writer 前提。
- `package.json:13` 使用 `node --test test/*.test.mjs`，测试文件可并行执行；两个独立构建子进程都会在 `scripts/build.mjs:12` 执行整个 `target` 的 `rm`，随后异步创建、写入并让父测试读取。当前脚本没有可指定输出根能力，也没有两测试之间的顺序边。合法交错可使一个子进程删掉另一个刚写的文件，或父测试读取到另一构建正在重建的目录，造成 ENOENT/装配中间态等偶发失败。这是新增的共享输出目录竞态。
- **窄验证边界：** 在上述独立临时副本连接已有 node_modules，仅一次用锁定 Node22.14 执行这两个相关测试：`--experimental-vm-modules --test --test-name-pattern='formal agent-view|Desktop runtime has' test/module-boundaries.test.mjs test/renderer-load.test.mjs`，结果 **2/2 通过**。未声称本次复现了偶发失败、未循环碰运气；单次绿色不能提供缺失的互斥/隔离保证。首次选择器只运行另一旧测试，不计为 pair 验证，日志也保留。
- 最小修复：真实 release build 由单一构建 owner 产生，两处断言共同只读；或给测试使用彼此独占的输出根，生产默认路径不变。不要引入全局锁框架或重复产品 build 逻辑。
- 关闭条件：从源码上消除两个并行测试对同一 target 的删写，保留真实产物的 React owner 边界断言与 VM lifecycle 断言，运行相关两测试及常规 npm 入口确认装配完整。

## 结论及移交

没有发现其他应扩大当前范围的类型安全、资源 owner、业务重复源问题。B0 / I2 / M0；Task6 质量门暂不关闭，两个问题交原实现者做窄修并重锁最终输入后复审。

本轮没有跑 Tasks1–5 全 core 或 Task7 全门、完整 Tela Java/发行 IT、Electron 实机、OpenAI 网络；不声称签名公证、干净机器或 SCA 无命中。已知 Jackson 公告由主线程处理，不在本轮擅改依赖。

审查者未修改任何被冻结生产/测试输入。I1 报告后主线程授权原实现者开始修复，故审查结论仍绑定最初冻结源；最终收据会分别记录被冻结副本仍匹配和当前工作副本可能发生的授权修复，不把后者误判为证据污染。所有本审查启动的命令已终态，Maven 独占权在 I1 窄复现结束后已释放，后续没有再启动 Maven。
