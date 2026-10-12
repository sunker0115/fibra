# Fibra 配置合同候选：独立架构复审 R2

2026-10-11。结论：**本架构审查范围 PASS；B0 / I0 / M0，原 I1–I4、M1–M2 均关闭。** 没有发现必须再次修改的材料性设计缺口。此结论可用于汇总独审并提交用户批准，不是生产实现、依赖、公开 API 或跨仓修改的授权，也不是实现验收通过。

## 精确输入与边界

- 完整规格：`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`，SHA-256 `0792890247fd0f1289351e3ce5c481f5e6e7bc3bc41f928f5ff895ed020b4eb8`，完整读取 1–324 行。
- 完整来源索引：`docs/superpowers/references/2026-10-11-plugin-config-contract-evidence.md`，SHA-256 `fb4d9cbc44c232441de813e22b2962158dd277e1fbd05b5669480bdaeb62ace3`，完整读取 1–82 行。它在派发后仅追加初版报告归档与有限组合探针说明；本报告绑定读到的当前版本。
- 推进计划：`docs/superpowers/plans/2026-10-11-plugin-config-contract/task_plan.md:3–32`，仍是设计资格、未批准、实现 pending。
- Fibra 生产基线：`0a674176b8b762b4e77044bcba7f051bf06c41d9`。工作树仅见本专题未跟踪文档目录；本审查不改仓库、不改 Tela、不实现、不运行构建/测试、不提交。
- 对照原报告 `/private/tmp/fibra-config-design-architecture-review.md`，并复核必要既有源码。使用 doc-consistency-review 的矛盾、语义、缺失、可调整和定位检查；未发现新的 A/B/C/E 类问题。

## 原问题关闭证据

| 原项 | 本版实际改动及定位 | 结论 |
|---|---|---|
| I1 通用场景被削减 | 规格 104–132 给出 typed headers/environment、命名模型 map、local/remote、false-or-object、number-or-disabled；142–149 明确有限 allOf/anyOf/oneOf 与 schema-valued additionalProperties；163–169 同时更新接纳模型及其未证明边界 | 关闭。保留普通输入形状，没有要求作者用开放值或 runtime callback 冒充统一结构检查 |
| I2 config 缺命令批量 owner | 165 明确 config-owned batch 先总量预算，再任何编译/执行；188–196 给出 checkSources/validateAll、ConfigValidationInput、ConfigBatchFailure；213 规定索引、错误码及非法参数 | 关闭。Engine 不复制预算算法；独立单项 API 与命令批量保证分开 |
| I3 builtin 摘要漏 facetId | 276–283 明示 facetId、dependency pluginId/facetId、profile/完整 schema，排序及 canonical；312 加身份反例 | 关闭。最终摘要仍唯一，不另添 revision；单 facet 改名及交换内容无法因只排序而漏入身份 |
| I4 作者全包资格无实际入口 | 73、77 写明源→正式 JAR→完整包根，以及临时 store/Engine、disabled selection、真实目录、全部绑定集合比较、全 source 批量资格、finally 关闭；流程进入 archetype/发行/仓外正式 JAR 消费者 | 关闭。已有窄 API 能组成实际流程，名称不被 schema 键取代；不需第二 Registry 或持久“已合格”标志 |
| M1 root ref 元数据自相矛盾 | 140–142：根必需 `$schema`；root ref 明确允许 `$schema/$defs`，仍拒绝断言 sibling | 关闭。root ref 的允许范围已明确 |
| M2 稳定错误枚举/准入不闭合 | 213、221：明确 ConfigSchemaException/ConfigContractException.Code，ENGINE_UNAVAILABLE、同步参数检查、准入顺序、missing/invalid/read/cleanup 优先关系；255–265 补实际 operation 失败字段 | 关闭。没有把 Engine 关闭或 fail-stop 混为普通读失败 |

这六项是设计文字与接缝已经闭合；不是宣称相应生产测试已经存在或通过。

## 架构与实际作者路径复核

**名称与规则的 owner 清楚。** 规格 29–32 保留轻名称目录和按需规则查询；71、75、77 的键是对 descriptor/builtin 名称的引用。作者 QA 全量比较的是同一名称真源与规则绑定集合，不是注册第二份名称。39–42、198–209 划清 config 的中立解码、artifact 的固定字节读取和 Engine 的精确 selection/部署编排；没有 arbitrary file/URL API，也不要求 artifact 反向依赖 config/api/Reactor。

**Java 源、JAR 与完整包根是一份作者源的派生产物。** 73 明确单一可编辑源、正式 JAR 的装配资源、根为唯一运行来源、无 JAR fallback、发行字节一致性；改变 facet/跨 JAR 合包需要显式重新绑定。该流程合理覆盖正式 JAR 作者和完整 package 发布者，不因物理存在三份派生字节就自动构成三个权威。77 的公开 QA 流程补上可执行资格，321–322 约束正式制品及文档实际运行；实现时仍必须兑现，不能仅写 fixture 伪代码。

**固定资源 reader 的新增前置条件已进入审批。** 215–219 要求句柄先登记后读、精确 root 的固定资源、重复观察缓存终态、失败 close 保留 stream、匹配原 snapshot 同 chunk 导出的 raw digest/长度或缺失事实。219 明说 metadata-only builder 不能获得已核能力，toBuilder 改身份丢派生证据，equals/hashCode 保留旧 metadata 语义。这是对原公开 record 新能力的明确限定，不是默默把 path 当 exact revision。原 `PluginPackage.java:77–99` 的两次 snapshot、`:270–299` 的树 digest/file bytes 能承接同 chunk 派生证据；现 `PluginPackageRecord.java:17–30,38–48` 的 builder/location/身份结构说明为何必须明确该前置条件。未把新摘要称为包整体原子快照，217 已限定保护范围。

**inactive 与全包资格没有矛盾。** 75、77 是作者发布 QA；159–165 的中立安全解析与被选 schema 语义资格分开；242–247 明确 local/祖先/when inactive、package gate、无执行 entry、dormant sibling 的不同处理。B 的单 schema 语义错误可延后，坏全局 JSON/envelope/全文件预算仍影响 A，这是读取同一个严格文档不可避免且已经公开的边界。全包 QA 要拒绝 inactive 坏 schema，不意味着保存 inactive 草稿必须先通过全包资格。

**builtin 身份保留管理修复。** 271–285 既把 schema 进入最终身份，又不在 provider 构造阶段编译语义错误，保留管理入口。旧 selection 包括 disabled 不自动映射新摘要；PRESENT+BLOCKED 与显式 CAS replacement 的破坏性迁移已经明确。implementationDigest 仍由 provider 对实现负责；框架保证 schema 不可被作者忘记改摘要而绕过，未虚称能自动散列运行时实现。source 的深不可变与构造前中立预算，283/312 的 mutation/排序/改名/profile 反例足以明确设计要求。

**示例与真实消费范围相符。** 48–68、83–102 的 fs-local 必填 root/spillDirectory 与当前 `LocalFileSystemConfig.java:7–12,20–23` 相符；minLength 不冒充非 blank/路径语义。114 明说 example-connectors 是能力例子，不冒充发行插件。305、311–322 要求 Java/Node/产品 client/builtin、真实 import/create/配置/close、正式 Maven 消费者，旧 51 项与 Desktop 实机证据仍分开；没有只验证 catalog DTO 就宣称真实消费。

## 参照与有限 profile 的判断

新版保留了原审查中已由固定 OpenCode/Pi 源码证明的 typed dictionary 和有限变体。来源索引 50、65 引用固定 settings/MCP/provider 配置，规格 110–132 把它们映为通用作者形状，不把产品字段硬塞核心；这正是本轮应采用的最小能力。拒绝 regex、递归/外部引用、结构化 enum、multipleOf 等的边界仍明确，未声称全 JSON Schema。

163、169 明确 U 是待正式资格的接纳分数，不能据此推导 CPU/内存硬界；来源索引 79、82 清楚区分 10/28 断言局部探针与正式资格、保留错误聚合风险。因此不把“尚未跑全部正式测试”重复列为设计阻断。后续若库源码/反例证明当前预算不够，按 163/299 回预算/依赖门，不能静默删掉已纳入的普通字典和变体。

171 对 Node Number 的边界是必要且正确的范围限定：Engine effective LiteralValue 的精确校验不等于 Node 执行时任意精度。规格没有为迁就 Node 静默缩小所有 runtime 数值域，也没有把小数落在安全整数范围误当精确保证；319 要求真实消费边界反例。此处不要求在本设计顺带改 wire。

## 批准与后续门

未发现漏写的材料性批准影响：175 声明候选公开签名，219 声明已核 record 前置条件，273–285 声明 builtin builder/内容身份破坏性迁移，297–301 声明新依赖/发行/模块影响，305 限定隔离 Tela 请求且主树只读，324 保留材料性批准与最终消费者/独审门。计划 13–16 与之相符。

本复审不要求额外设计改动。主线程可连同 schema、生命周期独审汇总后呈现审批；若其它审查改变本报告涉及的公开签名、profile、身份算法或部署时序，应以最终新 hash 再核。设计通过只关闭当前审查项，不关闭总目标，不将正式测试待办记成已通过。

## 最终候选三处修文复核

2026-10-11，按主线程要求仅复核三处最小修文及相邻合同，未重开全调查。最终规格 SHA-256 实读为 `3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`。

- 最终规格 77–79：作者 QA 保留固定资源句柄至真实 close 成功；已移交 Engine 的 store 由 Engine 关闭，不重复关闭；资源 close 失败保留依赖并令 QA 失败，不无条件继续关 store。与 217、225 的资源 owner/失败保留一致，修正原 finally 文字可能诱导的旁路清理，没有新增所有权入口。
- 最终规格 219、223：固定路径的缺失、nonregular、regular 三类事实分开；nonregular 为 READ_FAILED，真正缺失仍为 MISSING_CONTRACT。与既有树允许目录但固定合同要求 regular 文件相容，不把一个同名目录误当缺失，也不扩大文件读取能力或改变内容摘要算法。
- 最终规格 291–293：additionalProperties 接受的动态键仍是实例输入；诊断裁到最近静态字典父路径，动态键以及后续拼接路径均不输出，CC10 包含 secret canary/嵌套对象/数组。与纯安全错误投影一致，堵住动态键被误当 schema 声明名称的泄漏窗口，不削减 typed-map 校验能力。

最终结论仍为 **本架构审查范围 PASS，B0 / I0 / M0**，原六项全部关闭，三处修文无新增冲突。该结论绑定上述最终规格 hash；仍不表示生产实现、依赖/公共 API/跨仓授权或正式验收已经完成。
