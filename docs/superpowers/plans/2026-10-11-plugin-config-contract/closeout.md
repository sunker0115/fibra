# 通用插件配置合同最终交付

2026-10-12，分支 `codex/plugin-config-contract`，基线 `0a674176b8b762b4e77044bcba7f051bf06c41d9`。用户批准的设计、实现、正式制品与真实消费者验证、使用文档和最终独审已完成。没有 commit、push、外部发布或 Tela 主工作树修改。

## 完成范围

作者规则进入唯一正式包；config 纯校验、artifact 固定资源身份、Engine 原求值后保存前结构门及查询已贯通。runtime 保留绑定、默认、业务校验与资源职责，原 target/CAS 和保存状态保持。按用户要求直接迁移，不提供兼容别名、双格式或缺合同回退。

普通非法配置拒绝本次提交并保留原 target/current。资源关闭失败沿已批准的保守隔离策略处理；这一策略不是行业统一要求，也不代表普通结构错误需要停止 Engine。发布资格检查全包规则与字节一致性，运行时按实际生效输入检查，不在每次管理操作中重跑发行验收。

## 最终证据

- 完整 `clean verify` 原生退出0：171个 testsuite XML、1228项测试，失败/错误/跳过均0。归档另有3份 failsafe-summary，不重复算作测试报告。两次不同根因的失败与定向修复各自保留。
- 六份本轮Java codec输出由当前TypeScript正式制品消费通过；npm原资格按28项源输入、2个tarball及当前dist精确复用，不声称本轮重跑npm全门。
- 原可复现发行门通过，两次clean package及一次package比较84个主/源码/Javadoc JAR、30展开POM、ZIP及目录mode。签名自动生成仅config/artifact/engine三份变化，最终验证使用普通比较模式。
- 最终ZIP SHA256为 `d1ddccfa380f38d70712228bcb454ee3fe3323fc41dff18fab1c219f4375c30b`；28主JAR、30POM与正式消费者输入一致。变更的ZIP另行完成本地正式坐标独立缓存解析及原archive真实CLI启动/停止检查。
- CC01–12最终合同审查与代码质量审查均B0/I0/M0。主线程核对实际diff、源码摘要、原生退出、测试报告与两份独立结论。

具体证据见[实现交接](../../references/plugin-config-contract/implementation/task7-final/REPORT.md)、[合同独审](../../references/plugin-config-contract/implementation/task7-final/spec-review.md)、[质量独审](../../references/plugin-config-contract/implementation/task7-final/quality-review.md)及[验证账本](verification-ledger.md)。完整日志、XML、源码tar和发行物保存在 `/private/tmp/fibra-config-contract-evidence/final/`；仓内归档文本收据，不存大型二进制。

## 收口改动和边界

独审后只更新使用文档顶部状态句、计划/账本阶段事实，以及规格和依赖资格中遗留的待实现/待验收措辞，未改合同语义、生产代码、测试或发行物。原846项构建输入锁和10份用户文档审查时输入锁保留；原收口文档摘要保存在 `task7-final/closeout-doc-inputs.json`，提交前的当前文档摘要另存 `task7-final/precommit-doc-inputs.json`。原实现者证据索引包含审查中的临时收据摘要，保留为 `evidence-index-before-final-reviews.json`，终审定稿后重建索引；这不是重跑测试或修改测试结果。

既有Jackson3.1.5在该次OSV查询命中七条公告。新schema路径未见直接触发，外部插件绑定的条件风险仍不能排除。2026-10-12用户明确决定：“本次保留 3.1.5 并明确记录告警，升级另行处理”。本次不再等待升级范围选择，不改变依赖版本；该决定不表示告警已消除或授权发布。详情见[依赖资格](dependency-qualification.md)。原独审报告保留审查当时的待决定状态，本次决策文档摘要另存 `task7-final/jackson-decision-doc-inputs.json`，不覆盖原收口文档锁。

未验证Electron实机、OpenAI联网、完整Tela发行IT、跨平台干净机器或启动性能。隔离Tela的实际产品模块VM执行与真实Java wire证据不被扩称为上述验证；Tela自己的P3阶段保持不变。
