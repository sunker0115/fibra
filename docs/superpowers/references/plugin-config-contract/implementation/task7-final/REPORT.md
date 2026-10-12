# Task7 最终交接

状态：技术门完成，待合同/质量两类独审；总目标未关闭。没有 commit/push、外部发布、Tela 主树写入或新增依赖版本。所有构建命令已结束，Maven 写入权释放。

## 本轮结果

- 原完整 `clean verify` 原生0，171份本轮XML / 1228 tests / 0 failures、errors、skipped；包含API签名比较、架构Java断言、真实Node/Java、重启、archetype、正式插件/CLI/装配、parity156与benchmark编译。日志有预期故障注入输出，未将其误算失败。XML按本轮时间核对，明确排除未参加reactor的嵌套consumer旧target。
- 六阶段fixture已在后续clean前归档；`consume-snapshots` 原生0，消费本轮Java codec输出与已合格的当前正式TS dist。两npm制品及28项源输入全部匹配锁定Node22.14/pnpm11.19原门回执，当前dist逐成员等于其tarball；复用原通过，不声称再次运行npm全门。
- 原 `verify-reproducible-release.sh` 原生0：两次clean package及一次package逐字节比较28组main/sources/Javadoc JAR、30展开POM、ZIP与含mode的目录树。实际84个JAR、30POM与最终ZIP另存 `final/artifacts/`，不会随target清理丢失。
- 19签名由既有ApiSignatureBaselineTest自动生成，原生0；仅config/artifact/engine三文件改变（115增/1删），其他16不变。新增类型/嵌套Code、Engine query及builtin schemas符合批准面，builder旧packageDigest输入被implementationDigest替换，无兼容别名；最终clean verify没有update属性。
- 原架构shell门与diff-check原生0。最终release全30模块完整runtime dependency tree和config effective POM已保存；validator3.0.8、itu1.14.0、Jackson3.1.5、SLF4J2.0.18未漂移，validator传递YAML排除实际生效，config原直接YAML仍保留。

## 最终字节及消费资格

最终ZIP SHA256：`d1ddccfa380f38d70712228bcb454ee3fe3323fc41dff18fab1c219f4375c30b`。

相对Task5旧ZIP `c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4`，只有 `runtime/bin/rg` 与 `runtime/platform.properties` 两项字节改变。最终28主JAR、30POM和ZIP全部38JAR与旧正式仓/清单相同，因此Task6正式Java/Node/隔离Tela功能证据可按SHA复用；并非把旧ZIP当作新ZIP通过。

本轮将最终flattened distribution POM与bin ZIP发布到全新本地file repository，使用一个独立消费者缓存解析正式ZIP和POM坐标，核对实际远程来源记录并逐字节cmp；原 `verify-archive.sh` 对该新ZIP运行真实CLI/启动/停止验证，原生0。没有执行完整verify-distribution第二套状态机或重跑已闭合Tela矩阵，没有外部发布。

首次临时坐标执行器在reactor根解析POM，被workspace resolver满足而未写独立cache，后续cmp原生2（`final-zip-coordinate-first.*`）。ZIP当时已实际下载且cmp通过、本地deploy成功；没有重复发布或清缓存。随后仅把POM解析移到仓外目录，取得实际文件仓来源并与最终POM及ZIP重新cmp通过；最终原生0。该执行器修正不涉及仓内源码或生产合同。

官方microsoft/ripgrep-prebuilt v15.0.1 darwin-arm64 asset归档SHA `2fa16464fd8638588a67c7fc172d3c4b57fbdc65dff366e10b0b0e90734628a6`，binary SHA `6ef40346bf31fcce79d9614c7745c198542925a0c7d4911e1ffe794c53392ac1`；其实际自报 `ripgrep 15.0.0 (rev 3a612f88b8)`。已保留官方asset identity与stdout，不声称二进制自报15.0.1或旧ZIP工具早已锁定。

最终28主JAR内META-INF/LICENSE、THIRD_PARTY_NOTICES以及ZIP根文件均匹配当前源。新增两库实际JAR共642803byte、最终ZIP两entry压缩合计578110byte，限于两个库，不能称整个发行净增量。sources/Javadoc附属包由原repro覆盖，未把主JAR通知检查扩称全部附属包有NOTICE或完成法律审查。

Task6复用输入再核3个Fibra文件/12份证据/482个Tela文件均匹配；外部19项/8XML、Tela34项/5XML、干净Host1、npm29、真实Java wire→产品renderer28分别引用Task6R2，未重复计入本轮1228。当前Java功能启动/ClassLoader/真实Node进程与产品VM模块执行有证据；Electron实机、OpenAI联网、完整Tela发行IT与启动性能benchmark未验证。

## 原全量发现与窄修

1. 第一次全量原生1，parity156中10errors；6个既有测试作者夹具缺固定合同，造成MISSING_CONTRACT及后续descriptor/observation缺失。只增加63行真实类型/闭合object规则，不改断言、生产路径或新增框架。定向原10项/6XML原生0。
2. 第二次全量原生1，在NodeProcessUnitTest 49项中1error；本轮实际708项/78XML，其余未执行模块旧XML排除。identity.json存在先于写完，测试读取到MissingNode。主线程核对后授权只把测试payload改为同目录identity.tmp完整写入再rename，保留PGID/生产字节/execArgv/entrypoint与关闭预算断言。原49项定向原生0。
3. 两次失败按不同根因修正，随后第三次全量原生0。RED、两次定向GREEN和最终GREEN分别归档，不混总数；最终额外测试改动7文件见 `task7-tests.patch`。

## 收据入口与风险

完整日志/argv/started/finished/native exit、XML、六fixture、source tar及实际发行物在 `/private/tmp/fibra-config-contract-evidence/final/`。本目录归档精确manifest和收据。源码锁为846项 `build-source-inputs.json`，用户文档锁为 `user-doc-inputs.json`；命令与本轮输入/产物SHA汇总见 `evidence-index.json`。逐CC01–12落点见[最终验证账本](../../../../plans/2026-10-11-plugin-config-contract/verification-ledger.md)。

既有26坐标OSV查询发现Jackson3.1.5七条公告；新增networknt/itu无命中只说明该次查询范围/时间。新schema路径未见直接触发，但外部plugin binder条件可达不能排除。最终38JAR与查询清单同SHA，可复用该风险结论；不能写零漏洞或SCA全通过。用户尚未选择是否扩范围升级，本轮保持批准版本，既有告警并未被自动接受。最终独审与该风险处置由主线程收口。
