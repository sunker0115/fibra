# Task 5 作者合同交付证据

状态：原作者门与原发行门均通过；最终独立 verify-archive 重跑 native exit0。源码已停止修改，待主线程独立审查。

## 实施范围

- 12正式Java插件唯一作者源 src/plugin/fibra-config.json，根具名Assembly配置 + 12叶显式启用，最终JAR固定entry。4个API包main为空映射。
- distribution从dependency copy后的精确main.jar提取原始字节，不维护第二份包清单；缺失/重复/目录拒绝，含空格路径/重复执行通过。
- archetype独立POM、metadata、assembly、README和真实生成验证；Velocity3.4.1关键字与本地引用保留。
- Node sanitizer根作者源与README；复制完整包做停用静态资格，原真实Node/Spring HTTP IT保留。
- 共享测试QA helper只进入三个模块testCompile，完整catalog集合比较和checkSources(包括空集)。原读取/关闭、失败宿主保留与fatal/通知异常边界在同一helper测试。
- 仓外原有Java/Node lifecycle fixture迁移名称/合同；java-plugin独立作者源构建；Engine/CLI包从正式JAR复制合同。

## 证据

- commands.txt：精确环境和执行命令。
- red-jar.log/.exit：正式JAR缺唯一固定entry(expected 1 actual 0)。red-template.log/.exit：真实生成目录缺fibra-config.json。red-zip.log/.exit：旧ZIP缺配置合同。
- full-author-final.log/.exit：native exit0，完整archetype + plugins acceptance + spring-host及其reactor依赖，未筛选测试。full-author-reports/保留134份本次XML、1059 tests，failure/error/skip均0。
- distribution.log/.exit与distribution/native.exit：原发行脚本native exit0；distribution/run/保留本次remote发布仓、独立consumer-m2、仓外源码与XML。external-consumer-reports.json：7份XML/11 tests，failure/error/skip均0。
- artifact-bytes.json：12包author/finalJAR/stageRoot/ZIPRoot原字节一致，含最终JAR SHA；ZIP SHA256 c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4。
- external-template-bytes.json：真正仓外生成源/最终JAR/root原字节一致，$schema/$defs/$ref/#/$defs原文保留。
- qa-helper-publication-exclusion.json：84个正式main/sources/javadoc JAR无共享QA helper。
- stage-extraction.log/.exit：含空格路径、重复执行、原始bytes、缺/重复/目录拒绝均通过。
- source-sha256.json：本次51个Task5相关文件的当前字节SHA；根pom.xml亦含Tasks1–4既有改动。

## 边界与运行记录

- 本机JDK21 + 锁定Node22.14.0；本次发行ZIP是本机平台资格，未声称其它操作系统/架构。
- 最初完整作者门的NodeProcessUnitTest在沙箱中因/bin/ps无法生成identity.json而16项失败；原测试不变，在require_escalated环境重跑通过。中途新增测试泛型编译错误已修正并随完整门重跑通过。
- 文件句柄的直接物理owner为Path/evidence/InputStream。QA关闭失败保留Engine/store/targets是沿已批准的保守fail-stop隔离政策，不是文件流直接依赖全部runtime的必然结论。
- close失败注入使用测试resource seam，宿主先静态保留同一个owner再通知终止；普通read失败+close成功由Engine正常关闭。生产Engine策略未在Task5修改。
- 未新增生产Java类、运行依赖、公共API或兼容fallback；未改Tela、未commit/push。Task6更完整仓外/Tela消费和最终独立审查仍由主线程推进。
