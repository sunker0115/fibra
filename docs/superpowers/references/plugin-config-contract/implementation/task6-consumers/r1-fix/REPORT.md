# Task6 质量 R1 窄修交接

DONE，等待最终规格与质量复审。原 `../REPORT.md`、原始锁/补丁/RED/绿色收据保持不变；本目录是其后续修正。仅改变隔离 Tela 的 4 个文件：`TelaHostGatewayErrorTest.java`、Desktop `package.json`、`module-boundaries.test.mjs`、`renderer-load.test.mjs`。Fibra 工作树生产/消费者输入、Tela 生产 Java、规则作者源、npm依赖和build脚本均未变。

## I1：Host 测试的下游构建依赖

质量审查以原482锁输入建立无target副本，单独运行Host方法得到1test/1error：`NoSuchFileException ../tela-desktop/target/client-plugin/agent-view.js`。根因是Host在reactor中早于Desktop，而该新测试读取Desktop target与distribution产品声明；先package后focused掩盖了问题。原生RED见 `../quality-clean-host.log/.exit`，XML另存 `red-reports/quality-clean-host.xml`。

修正沿Host已有client夹具方式，在自身临时目录创建最小module字节/真实digest/descriptor/包根声明与闭合object配置规则。ID为gateway-fixture/fixture-view，不复制产品声明。仍经真实Engine/Registry结构门、原`TelaHostGateway.errorBody`和OutputCapture检查NOT_SAVED、canary、安全audit/日志、原target和空assignment；没有把错误改成mock，也不使用ACK作为实际执行证据。真正agent-view产品执行仍在renderer/distribution证据内。

`clean-host-green.log/.exit=0`：从旧source tar新建 `r1-fix/clean-source`，仅放入修复后的Host测试，单独 `-pl tela-host -Dtest=TelaHostGatewayErrorTest#actualClientContractFailureUsesSafeGatewayProjectionAndLogs test`，1/1通过。该副本运行前后均没有`tela-desktop/target`，`desktop-target-absence.exit=0`。这是窄干净Host验证，不冒充整仓clean verify。

同款检查：本轮新增Host测试中唯一读取下游target/产品声明处已去除。distribution读取上游实际package JAR以及renderer入口构建后读取自身产物有不同生命周期，未机械修改已有发行IT。

## I2：两个并行测试竞争重建相同target

原module-boundaries和新增renderer测试均spawn同一个`build.mjs`；node跨文件并行，而build脚本删除并重写相同target。该写冲突由实际控制流确认；审查曾跑pair GREEN，未稳定复现运行失败，因此不称动态RED或以GREEN排除竞态。

修正将唯一构建动作放在既有 `pnpm test` 入口：先`node scripts/build.mjs`，成功后才`node --test test/*.test.mjs`。两个测试文件内部均不再调用build，只读实际产物。新增独立`pnpm run test:renderer`入口也先构建一次再执行VM；renderer现有子进程仅用于VM运行，不负责构建。不改变生产build默认输出、不增加锁/框架、不串行化测试来掩盖写冲突。

- `npm-test.log/.exit=0`：真实完整入口29项通过（renderer wrapper含子进程场景）。
- `renderer-wire.log/.exit=0`：独立入口在 `TELA_CONFIG_WIRE=../tela-client-snapshot.json` 下28项通过，实际产品module import/create/activate/stop、scope/root/URL关闭和真实tagged decimal保持。
- 两个测试源无`build.mjs`调用；只由前置入口写target，随后并行只读。

## 最终验证和复用

`tela-focused.log/.exit=0`：原34项/5XML全部重跑通过，失败/错误/跳过均0；清洁Host的1项单列、不与34项重复计总。所有当前XML在 `final-reports/`，摘要见`final-reports.json`。

现有外部19项源输入及3个Fibra任务文件SHA未变，复用 `../external-frozen.*`；未无意义重跑。五作者schema/JAR、真实build agent-view.js、两个正式npm tgz、两ZIP均与上一轮已核SHA完全相同。保留旧包制品收据，不声称此次重新package；变更仅影响测试编排。`reused-evidence-sha256.json` 将复用证据和真实Java wire绑定到具体摘要。

最终仍有482源输入，相比上一轮只变4文件；相比最初476输入为新增6、修改15共21项。`tela-input-sha256.json`是新最终锁，`changed-since-r1.json`/`fix.patch`仅此次窄修；`tela-adaptation.patch`是本任务完整隔离适配。新完整source tar在本目录，旧锁与tar均保留。没有新增依赖或触及Tela主树/P3计划。

CONCERNS：原独立审查结论不能自动覆盖新输入，须规格/质量同范围复审。仍未执行Electron实机、OpenAI联网、全Tela发行IT；这些边界不因修复改变。没有新增核心合同或产品行为问题。

BLOCKED：无。所有构建已终态，Maven写入权释放给主线程；不再更改冻结源。
