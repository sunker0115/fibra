# Task 1 独立合同增量审查 R3

结论：**通过，B=0，I=0，M=0。** 这次预算前分配修复符合 §5.1 新澄清；R2 的普通失败顺序保持成立，所有接纳预算仍先于库执行。没有发现本轮新增合同问题。本报告仅审查 ConfigSchema 的 batch 改写及其回归，不替代后续阶段或最终发行审查。

## 输入绑定

- 仓库 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`，基线 HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- `ConfigSchema.java` SHA-256：`9900b68b9c8a68752e074d21af9dc1247599dea1a594fe6c96a66022715ec645`。
- 新测试 `ConfigSchemaBatchAllocationTest.java` SHA-256：`cd09f3ae8a81458e45a95c794845d04b4cb3f9bf680e47e215de21f33372593a`。
- 当前设计 SHA-256：`41df19052265b38f2156beff124bb19c07940968a3b0ff23ba598fe02cd09881`。
- `spec-review-r3-input-sha256.json` 自身 SHA-256：`3b8b85b5916ab8a148db901ef11571915b4fe450f4d8fb4fd377f959ab364145`。逐项重算 R2 清单：仅 ConfigSchema 和设计正文变化；其余旧生产源、测试、POM/API/计划输入相同，新增上述 allocation 测试。主线程其他文档/NOTICE 变更不属于本次 schema 生产审查。

## 合同核对

1. **接纳不再物化未知规模集合。** 两个公共入口分别一次正向遍历调用方 List，不调用 List.copyOf/toArray，也不创建等长 sources 投影。Batch 保存最多 128 个 unique source 的 Admission/安全 rejection 和首次 index。确定的中立集合、单 schema、单 effective、entry U 或总 U 超限直接返回，不再建立与错误次数等长的 failure 列表。这与 §5.1 新增“确定预算超限可短路”的要求一致。

2. **库调用仍在完整接纳之后。** admit 只调用现有中立/profile/预算逻辑；两个入口只在遍历完成且未遇预算拒绝时进入 checkSources()/validate()。没有把 SchemaLibrary、JsonNode 构造或 ConfigSchema 编译搬入接纳扫描。普通语义错误仅缓存首错，扫描仍检查后项 effective/unique source 预算；后项确定超限可覆盖该语义错误。

3. **R2 失败顺序未退化。** validate 的 executable 是首个 schema 语义失败之前的连续 input 前缀，因此其本地 index 与原输入 index 一致。前缀按原顺序 compile/validate，先遇实例/编译失败则立即返回；前缀全成功才返回 firstSemanticFailure。checkSources 的 unique 映射保持首次出现顺序和原 index，重复 source 不改变首错定位。

4. **去重与逐次计费分离。** 相同 ConfigSchemaSource 复用 Admission，schema 字节/个数与编译只算唯一项；每次出现的有效 input 仍调用 effective 和 work、累加 total U。即使已发现语义失败，之后合法 source 对应 input 的 U 仍计入总预算。无法取得 Admission 的非法 source 与此前实现一致，不凭空生成 E；仍检查其 effective 原始预算。

5. **执行用已捕获的不可变记录。** 可执行前缀在每项成功计入 U 后保存 ConfigValidationInput；该 record、ConfigSchemaSource 与 LiteralValue 均不可变。执行阶段只读 executable/sources，不再读调用者 List。首语义失败之后的 input 不保留，只有预算扫描临时引用，避免被拒命令积累无用输入。

6. **非法 API 参数与 literal null 区分保留。** null 集合及扫描到的 null 元素同步抛 NPE；ConfigValidationInput 既有非 null source/effective 检查不变，合法 NullValue 继续作为值参与校验。确定预算拒绝后的未访问元素不再检查，包括其可能为 null；这是本次明确允许停止扫描的直接结果，不是将 null 映射成实例错误。代码没有捕获 JVM Error。

没有新增 batch-size 限额、修改 U 公式、profile 阈值、公开 API 或第三方执行语义。已阅质量 R1 对无界复制的实际反例；没有把线性接纳扫描本身或 32 MiB 实验参数扩写成新 CPU/内存 SLA。

## 独立验证

- Zulu21/Maven3.9.9，独立运行：`mvn -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-config -am test -Dtest=ConfigSchemaBatchAllocationTest,ConfigSchemaBatchOrderTest,ConfigSchemaBudgetTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 实际 native exit=0，**18 tests，0 failure/error/skipped**。日志 `spec-review-r3-test.log`。包含 5 项 allocation、5 项 R2 顺序及真实 SchemaRegistry 隔离哨兵、8 项原预算测试。
- allocation 测试以常量空间惰性 List、toArray 拒绝及单次正向读取哨兵，证明千万项首预算失败只读一项；先语义失败后预算拒绝仍读到预算项；接纳通过后不二次读 caller List；遍历到的 null 参数同步拒绝。这些是实际行为断言，不是对 ArrayList 实现细节的镜像测试。
- 本轮独立重跑旧 `BatchCopyProbe`，当前 Maven target/classes 位于旧探针目录前，`-Xmx32m` 下 count=1 与 count=10000000 **均 exit=0**，均返回 index0/BUDGET_EXCEEDED，无 stderr。独立重跑 R1 原 `BatchOrderFailureProbe` 也 **exit=0**。完整 argv/stdout/stderr 为 `spec-review-r3-probes.json`。
- `git diff --check` 退出 0。

实现者全量 94 config + 2 API 的日志已提供，本轮按定点范围未再次重跑全量，因此独立验证声明限于上述 18 项及 3 次原反例执行。R1/R2 其他已核结论和未验证边界继续有效。未修改仓库代码、测试或文档，未 commit/push。
