# 配置合同提交前文档一致性复核

日期：2026-10-12。工作树：`/Users/sunke/dev/ai-project/fibra`；分支：`codex/plugin-config-contract`。本报告只复核当前文档，不承担提交、构建或发布动作。

## 结论

第二轮完整复读收敛：**A=0、B=0、C=0、D=0、E=0，本轮建议修改0项**。指定15份当前文档共4281行均已完整阅读两轮；长文按连续区间分段阅读，未以搜索结果代替全文。主线程最后更新的`closeout.md`另行全文复读，结论不变。

| 分类 | 当前未关闭项 |
|---|---:|
| A：明确矛盾 | 0 |
| B：语义歧义 | 0 |
| C：缺失信息 | 0 |
| D：可选改进 | 0 |
| E：内容归属/放置问题 | 0 |

第一轮识别的1项状态矛盾与2组阶段歧义已由主线程作最小状态同步：验证账本遗留“双独审/决策未关闭”尾句、规格遗留“进入实现/待资格”措辞、依赖资格中未区分历史取证与当前完成事实的措辞。第二轮确认修正未改变公开合同、schema支持范围、预算或资源关闭策略；审查者未修改仓库文件。

## 完整覆盖

| 当前文档 | 行数 |
|---|---:|
| `README.md` | 248 |
| `docs/api/README.md` | 300 |
| `docs/api/plugin-configuration.md` | 157 |
| `docs/release.md` | 138 |
| `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` | 338 |
| `docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md` | 1718 |
| `docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md` | 992 |
| `docs/superpowers/plans/2026-10-11-plugin-config-contract/task_plan.md` | 37 |
| 同目录 `implementation-plan.md` | 152 |
| 同目录 `verification-ledger.md` | 40 |
| 同目录 `closeout.md` | 27 |
| 同目录 `dependency-qualification.md` | 20 |
| `fibra-distribution/src/test/consumers/README.md` | 17 |
| `fibra-example/content-sanitizer/README.md` | 48 |
| `fibra-plugin-archetype/src/main/resources/archetype-resources/README.md` | 49 |

采用`/Users/sunke/.agents/skills/doc-consistency-review/SKILL.md`的全文阅读、分类、三项去假阳性核查及修改后复读流程。

## 合同闭合核对

- **唯一owner与事实源**：作者提供规则，config执行纯结构校验，artifact负责固定资源读取及内容身份，Engine负责原求值后、创建candidate前的统一门和查询；runtime/插件继续拥有类型绑定、默认值、业务校验和资源可用性。产品仍拥有表单、transport、runner及组合策略。
- **配置形状与profile**：显式null、缺失、unknown字段和默认值的责任边界一致，结构校验不注入默认值、不改写输入。有限profile、局部引用、组合/类型化字典和接纳预算的规范要求与使用入口一致，合格状态不被扩称为总内存或耗时保证。
- **示例与实际规则**：读取并核对fs-local、content-sanitizer、archetype的实际schema；fs-local必填且非空路径字符串、sanitizer可null的可选字段及允许空/重复rules、archetype根object或null及对象时必填message，与相应文档一致。
- **生效与revision**：运行时只校验实际active输入，候选精确package revision拥有其配置合同；停用时保留raw desired，重新启用严格检查。发布资格全量检查规则，未与每次管理操作的运行时验证混为一谈。
- **保存与恢复**：普通非法配置在保存前拒绝并保留原target/current；保存后失败不回滚持久目标，save-unconfirmed不猜测保存结果。重启只恢复持久事实，没有新增旁路配置状态或第二执行入口。
- **查询与关闭**：查询沿原Engine命令lane和读取资源owner，真实close完成后返回；普通查询失败不改历史operation。清理失败保留句柄、首个失败及真实owner，关闭屏障和保守隔离策略跨API、规格与两份架构说明一致。
- **builtin身份**：implementationDigest与全部facet声明/configSchemas进入派生packageDigest；configSchemas键只来自声明名称。缺失/语义非法规则按实际查询或启用拒绝，发行资格全量检查，符合实际类型与构建逻辑。

## 阶段状态与去假阳性核查

三项核查均已完成：一是搜索并通读同主题当前入口，排除实际已有定义/章节被误报为缺失；二是打开引用的Task7交接报告及相关源码/schema核对原文，不只依赖摘要；三是对照同文前后段及当前closeout，区分当前结论、规范要求和历史记录。

- `verification-ledger.md:3-5`、规格顶部及资格段、`dependency-qualification.md:3,11,17-20`现在均明确技术门及双独审完成，用户保留Jackson3.1.5并记录告警、升级另行处理。七条既有告警没有被宣称消除，决策没有被扩称为发布授权。
- `dependency-qualification.md`明确旧ZIP和阶段OSV取证的时间边界，最终38个JAR身份延续通过Task7证据关联；实际功能加载与未量化启动性能分开表述。
- 原Task7交接及原独审报告的待审/待决措辞属于审查当时事实，当前closeout明确后续完成与用户决定，并保留原冻结锁，不作为当前矛盾。规范中的“必须验证”是验收要求，不等于当前仍未验证。
- consumers作者验证入口与release普通本地工作流程具有不同适用范围，没有把独立消费者的发行资格门误报为每次普通构建都必跑的门。
- `closeout.md`最后更新准确记录本轮只同步使用入口、计划/账本、规格与依赖资格的阶段措辞，并区分原历史输入锁与提交前当前文档锁。

## 证据与边界

已完整阅读Task7的`REPORT.md`以核对引用：其记录完整clean verify原生0、171份testsuite/1228项测试且失败/错误/跳过均0，正式制品及消费者证据有精确身份绑定；npm证据按源输入与tarball身份复用，并未表述为本轮重跑。最终ZIP为`d1ddccfa380f38d70712228bcb454ee3fe3323fc41dff18fab1c219f4375c30b`。

本复核没有重新运行上述构建、测试或安全查询；846项源码锁与最终制品无漂移由主线程完成核对。本报告不新增性能、Electron实机、OpenAI联网、完整Tela发行IT、跨平台干净机器、零漏洞或许可法律审查的保证。未重开无关旧架构设计或新增测试矩阵。

交付范围仅为本报告。未提交、push、发布或修改既有证据索引、收据及历史审查报告。
