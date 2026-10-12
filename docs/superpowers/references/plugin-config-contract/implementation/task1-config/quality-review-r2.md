# Task 1 代码质量增量复审 R2

结论：**通过，B=0 / I=0 / M=0；质量 R1 的 I-1 已关闭。** 本结论限于 Task1 批量接纳修复，不代表后续 Engine/发行/消费者任务完成。

## 输入绑定

- 仓库 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`，实现仍未提交。
- `ConfigSchema.java`：`9900b68b9c8a68752e074d21af9dc1247599dea1a594fe6c96a66022715ec645`。
- `ConfigSchemaBatchAllocationTest.java`：`cd09f3ae8a81458e45a95c794845d04b4cb3f9bf680e47e215de21f33372593a`。
- `quality-review-r2-input-sha256.json` 逐文件绑定本轮所有生产/测试/POM/设计输入；其 SHA-256 为 `3b8b85b5916ab8a148db901ef11571915b4fe450f4d8fb4fd377f959ab364145`，与 spec R3 清单完全一致。
- 实际阅读当前 ConfigSchema 全文、新 allocation 测试、原 order 测试及设计澄清；与 R1 已读实现对照。除 ConfigSchema、设计澄清和新增 allocation 测试之外，原范围输入保持原摘要。
- 按要求做定向 Nowledge 检索，旧部署背景未用于此结论。结论以当前源码及本轮独立探针为依据。

## I-1 关闭依据

1. 两个公共入口均逐项读取调用方 List，预算前不再 `List.copyOf`、`toArray` 或物化等长 sources 列表。private Batch 是单次调用状态，不增加长期缓存或第二配置入口。
2. 预算拒绝立即返回，没有按失败出现次数增长的集合；源码中不存在 R1 的 `budgetFailures`。该短路与设计新澄清一致，不要求后项集合预算覆盖已确定的前项单项预算。
3. unique source 映射最多 128 份，保存首次原 index、Admission 或安全 rejection。可执行实例在单项和总 U 成功后才进入 executable，且只保留首个普通 schema 语义失败之前的连续前缀。之后仍扫描预算，但不保存无用实例；不存在拒绝 source 因没有 E 而使 retained 实例无限增长的问题。
4. executable 是连续前缀，因此 validate() 的本地下标就是原 input 下标；checkSources() 使用首次出现的原 index。前缀的编译/实例失败仍先于其后的普通 schema 失败。执行阶段不再回读调用方 List，只消费已经捕获的不可变 ConfigValidationInput/LiteralValue。
5. 库调用仍只发生在接纳扫描成功结束后。没有改变 U 公式、现有 profile 上限或公开 API，没有新增任意 batch-size 上限，也没有捕获 JVM fatal Error。
6. 新 allocation 测试的惰性 List、禁止 toArray、单向一次 get 和只读取首拒绝项断言直接覆盖原缺陷；它们核对外部可观察行为，不要求生产代码使用某种具体集合类型。读到的非法 null 仍同步拒绝；预算确定失败后的尾部不再读取，符合已明确的短路合同。

## 本轮独立验证回执

原始小堆探针保留在 `quality-review-r1-probe/BatchCopyProbe.java`，本轮按原字节复制至 `quality-review-r2-probe/BatchCopyProbe.java`。另外新增仅用于复审的 `quality-review-r2-probe/BatchBoundaryProbe.java`。所有文件只写临时证据目录。

本轮用 Zulu JDK21 将当前 12 个生产源文件重新 javac 到 `quality-review-r2-probe`，该目录置于运行 classpath 首位，避免旧 target 类或 R1 探针目录中的旧 ConfigSchema 干扰。编译 exit=0。完整命令、native exit、stdout/stderr 见 `quality-review-r2-probe/result.json`。

所有探针使用独立 `-Xmx32m` JVM：

| 情形 | 实际结果 |
|---|---|
| 原探针，65537 字节 effective，重复 1 次 | exit 0，index 0 / BUDGET_EXCEEDED |
| 原探针，同一对象重复 10,000,000 次 | exit 0，index 0 / BUDGET_EXCEEDED；原 R1 的 List.copyOf OOME 不再出现 |
| 超 description 单 schema，同一 source 重复 10,000,000 次 | exit 0，index 0 / BUDGET_EXCEEDED |
| 合法 source/effective，重复 10,000,000 次 | exit 0，index -1 / BUDGET_EXCEEDED；总 U 门可在保存整批前拒绝 |
| 发布检查 `[valid, valid, invalid]` | exit 0，index 2 / INVALID_SCHEMA；去重不改变原定位 |
| 前正确、次项 value 错、末项 schema 错 | exit 0，index 1 / VALUE_MISMATCH |
| 同一未知 profile 输入重复 100,000 次 | exit 0，index 0 / UNSUPPORTED_PROFILE；没有累计无用实例/失败集合 |

小堆参数仅复现 R1 的隐藏分配及验证其移除，不构成产品堆大小或 CPU SLA。边界探针加载库时仅输出既有 SLF4J 无 provider 提示；没有失败堆栈或输入内容输出。

本轮没有调用 Maven 或重复全量测试。实现者 94+2 和合同审查者独立 18 项作为已有证据读取，不冒充本轮执行；本轮独立执行声明限于上述实际源码编译和 7 个探针情形。未修改仓库文件，未 commit/push。

## 分级结果

- B：无。
- Important：无；R1 I-1 已关闭。
- Minor：无。

R1 对职责、不可变 source、库对象隔离、预算/profile 分离及后续任务范围的其余结论继续适用。
