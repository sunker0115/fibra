# 通用插件配置合同最终独立规格审查

2026-10-12；reviewer：config_final_spec_review。结论：**批准范围内的规格/技术交付门 PASS，B0 / I0 / M0。** 未发现需要修正的实现缺陷、遗漏或额外功能。既有 Jackson 安全告警处置仍待用户决定；本结论不表示风险已接受，不替主线程关闭总目标或授权发布。

基线为 `0a674176b8b762b4e77044bcba7f051bf06c41d9`，审查当前 `codex/plugin-config-contract` 未提交 tracked diff 与 untracked 实现。权威合同为 `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`，SHA256 `127f4eaf277e13d8899989794281874f7d8c01afc1487261d93c6890e4d3145a`。所读源码/差异与证据摘要见同目录 `spec-review-receipt.json`。

本审查只读源码、测试断言、日志/XML及实际制品；未执行 Maven、npm 或其他构建，未改生产/测试/文档，未碰 Tela 主树，未 commit/push。执行一次定向 Nowledge 搜索只得到历史背景，没有用它替代当前合同或事实。

## 规格核对

| 验收 | 直接核对的实现/断言 | 结论 |
| --- | --- | --- |
| CC01 | `EngineConfigurationContracts.requireName/source`、`FibraEngine.describeSelectedConfiguration`；Engine cold/fence/多facet测试；真实 Java entrypoint/validator marker、Node PID及 Tela Host 空 assignments | 静态查询与实例创建分离，不执行 definition/binder/spawn/client import；PASS |
| CC02 | 原 `PluginPackage` digest同chunk派生证据、`PluginPackageRecord` private verified事实、`FixedPluginConfigurationResource`；改写/原缺失/非regular/toBuilder/重启断言；builtin canonical digest及旧target CAS修复 | 动态规则绑定原包身份，builtin规则参与唯一最终身份，无回退或旧摘要映射；PASS |
| CC03 | `FibraEngine.preflightConfiguration` 在原 planner.inputs 后、candidate登记前；未知名称/缺合同/坏文档优先级；外部 Engine/Registry/真实CLI同值断言 | 所有托管 active 输入沿共同门，未为产品/runtime建旁路；PASS |
| CC04 | Engine local/ancestor/when/package gate、ref/if/literal及重复实例断言 | 原求值/延期边界保留，结构门只验 effective 值；PASS |
| CC05 | 候选 records 直接传预检；schema-only升级、保留无关unit及实际并发save barrier/CAS断言 | R2规则拒绝保留旧target/current；已发布R2留历史；原CAS至多一次保存；PASS |
| CC06 | `ConfigSchema`/`SchemaLibrary` 手工精确JsonNode、无默认/转换；Java真实validator一次与trim结果、raw target、null/空object区分 | schema纯检查，runtime继续拥有默认/归一化/业务与资源；PASS |
| CC07 | 固定资源同步read/close终态；`EngineReadAttempt`逆序兄弟清理与缓存终态；五类发起、取消观察、异步close及失败owner引用断言 | 返回前实际关闭，失败owner及父依赖保留，不自动重试终态；PASS |
| CC08 | 五路径字段级 `assertPlanning`，原deploy/save/恢复链保持；原完整reactor回归 | NOT_SAVED、NOT_APPLICABLE、SAVED未ready及UNCONFIRMED未被结构门重定义；PASS |
| CC09 | `SchemaProfile` allowlist/DAG、`SchemaBudget`、固定meta loader、batch顺序/库哨兵/惰性集合断言；真实Node大数边界 | 批量预算先于库执行，精确数字保证及JS Number边界如规格；无CPU/堆/耗时SLA声明；PASS |
| CC10 | `SchemaDiagnostics`静态父路径投影；unknown/typed-map secret canary；真实CLI输出、Registry audit、Tela errorBody/OutputCapture | 新诊断不带值/动态键/库异常；管理snapshot仍可含target，文档边界明确；PASS |
| CC11 | 外部Lifecycle真实Java/Node调用与停止；Tela最终tar中实际Host测试、产品agent-view import/create/activate/stop与decimal wire断言；实际JAR解析SHA | 正式消费者与隔离Tela证明闭合；Electron/OpenAI/完整Tela发行IT未冒称通过；PASS |
| CC12 | 3份实际签名diff；作者QA helper完整集合与关闭失败反例；12规则源/ZIP/JAR字节、模板与example文档；本轮完整及最终发行门 | 作者规则同源、破坏性直接迁移、使用说明/可执行消费者引用与原发行门闭合；PASS |

只读源码核对确认：config未依赖artifact/runtime；artifact不理解schema且未新增Reactor；Engine仅编排，未重写原target/CAS/runtime owner。公开新增范围与已批准签名一致，旧builtin builder `packageDigest` 输入直接替换为 `implementationDigest`，没有兼容别名。

## 独立复核的证据

- 逐项重算846个构建源输入与10份用户文档SHA，均零漂移。源码锁包括未提交新文件；审查末尾再次核对。
- 直接解析归档171份本轮XML：1228 tests，failures/errors/skipped均0；`clean-verify.exit=0`。没有把未运行consumer旧报告并入。六本轮Java codec fixture→当前TS consume原生0。
- 签名生成原生0，仅config/artifact/engine三文件改变；最终clean verify未使用update属性。旧生命周期断言保留；Task7七处窄修只补真实作者夹具规则及测试identity文件原子发布。
- 实际28主JAR和30 POM同时对当前路径、Task5发布路径逐项重算SHA，全部一致。Task6外部24源文件、54条已解析JAR路径也全部匹配。最终Tela源tar逐项匹配482输入锁；external19、Tela34、干净Host1分别按XML核对，不重复计入1228。
- 原可复现脚本原生0，代码确实执行两次clean package及一次package并比较main/sources/Javadoc、展开POM、ZIP与目录mode。该门跳过测试属原复现门职责，测试由独立clean verify证明。
- 最终ZIP SHA256为 `d1ddccfa380f38d70712228bcb454ee3fe3323fc41dff18fab1c219f4375c30b`。独立打开ZIP核38个JAR摘要；12份作者源、ZIP包根合同、ZIP内主JAR固定entry逐字节一致；根LICENSE/NOTICE与当前源一致。
- 新本地file repository正式ZIP/POM坐标解析、原verify-archive、架构shell门、diff-check均原生0。直接重算独立cache ZIP/POM与当前正式产物相同，`_remote.repositories`确为fibra-final。首次坐标执行器退出2已原样保留；修正仅POM解析的仓外cwd，不重复deploy、不改产品。
- 最终ZIP仅rg及platform.properties相对Task5变化；正式Java/Node/Tela消费者复用由不变JAR/POM字节支持，新ZIP另跑原archive门。未把旧ZIP通过直接当作新ZIP通过。

用户说明已明确：接入代码为依赖完整宿主目标的片段，并链接真正可运行Lifecycle/Author消费者及发行命令；null/unknown/default/raw-effective/disabled/upgrade/恢复保存状态都有落点；没有把结构通过写成路径、进程、服务可用性保证。

## 独立保留的风险与范围

1. 保持已批准networknt3.0.8/itu1.14.0及既有Jackson3.1.5；现有26坐标OSV检查命中Jackson七公告。新schema路径未见直接触发，外部插件binder条件风险不能排除。最终38 JAR与已查清单相同可复用风险记录，但不能称SCA零命中、已排除全部可利用性或风险已获接受。是否扩范围升级/如何交付由用户决定，不能由本实现PASS代替。
2. 未验证Electron实机、OpenAI联网、完整Tela发行IT、干净机器跨平台运行或启动性能；实际client证据是VM中产品module及正式protocol，有限UI stub边界保留。
3. 独审没有重开已批准profile、保守fail-stop所有权或管理snapshot语义，没有新增防御矩阵、依赖升级或产品组合需求。

最终技术门已可关闭；总目标、安全告警处置、提交和发布仍由主线程按用户授权处理。
