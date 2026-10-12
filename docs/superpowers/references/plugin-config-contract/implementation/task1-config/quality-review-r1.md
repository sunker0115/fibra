# Task 1 代码质量独立审查 R1

结论：**未通过；Critical 0 / Important 1 / Minor 0。** 仅需收敛批量接纳的隐藏复制及失败集合增长后复审；没有要求扩大 profile、公开 API 或后续 Task 范围。

## 输入绑定与审查范围

- 2026-10-12，`codex/plugin-config-contract`，base/head 均 `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- 实际读取未提交和 untracked 的 12 个生产类、5 个测试类、两个 POM；另读既有 LiteralValue，以及设计 §4/5/6/9、implementation-plan Task1。
- 输入逐文件摘要：`quality-review-r1-input-sha256.json`；该清单 SHA-256：`9c449e60a45ef437bb715699f8ecac8dfdd7331969a06538d018fb5246bc6971`。所有输入与 `spec-review-r2-input-sha256.json` 完全相同。
- 不以实现者回执或先前 89+2 测试通过代替审查；本轮没有重复整套测试。未修改仓库代码、测试或文档，未 commit/push。
- 已做一次定向 Nowledge 检索，返回旧部署/发行背景，未用于本次代码结论；结论以当前文件和独立探针为依据。

## Critical

无。

## Important

### I-1：批量预算门之前复制整个调用方集合，失败诊断也按全部出现次数累积

定位：`fibra-config/src/main/java/com/sstlfsj/fibra/config/ConfigSchema.java:47–48`；平行路径 `:41`；同一问题的失败存储 `:54,69–70,82–87`。

`validateAll` 在读取任一 source/effective 预算前，先 `List.copyOf(inputs)`，再创建等长的 sources 列表。`checkSources` 也先复制整个列表。因而调用方用常量空间表示的大重复列表，会先转成由调用方 n 控制的整批数组，而不是受已有 U/唯一 source 预算约束的内部存储。之后 `budgetFailures` 又给每次预算失败分配记录，最终却只使用第一条。尤其同一预算拒绝 source 的重复项不会增加 total U，但能持续增加该失败列表。

独立可证伪探针：`quality-review-r1-probe/BatchCopyProbe.java` 与 `result.json`。本轮将当前 12 个生产源文件重新 javac 到临时目录，并把该目录放在 classpath 首位，不依赖已有 target 恰好同步。JDK 21、子 JVM `-Xmx32m`：

- 一个 string source + 65537 字节字符串 effective，`Collections.nCopies(1, input)` 返回 index 0 / `BUDGET_EXCEEDED`，exit 0。
- 同一对象的 `Collections.nCopies(10000000, input)` 自身是常量空间；进入方法后在 `Collections$CopiesList.toArray → List.copyOf → ConfigSchema.validateAll:47` 抛 `OutOfMemoryError`，exit 1，尚未到预算扫描。

32 MiB 只是使线性分配可重复暴露的实验参数，不是建议产品内存下限，也不是宣称现有预算应保证任何堆容量或 CPU SLA。问题是预算拒绝路径额外物化无界整批引用，且失败集合保留了 API 根本不会返回的记录；设计 §5.1 明确要求核对错误集合构建与复制，不能仅以库执行前已经检查 U 结案。

最小改法：让接纳扫描先于整批物化，去掉等长 sources 投影，在预算扫描中有界保存真正可能执行的输入快照/唯一 source 及原 index。确定任一预算拒绝即可短路，不再累积失败列表；“先全量接纳后执行库”要求未全部接纳不得执行库，并不要求确认拒绝后继续扫描。规格未规定后项集合预算必须覆盖前项单项预算，不能把现实现的这一偶然顺序当作新增合同。普通非预算语义仍按原 index，并保留非法 API 参数同步拒绝。两公共 batch 入口应一起核对。不要捕获/吞掉 `OutOfMemoryError`，也不要未经设计决定直接新增任意 batch-size 上限。

建议回归以受控 List/计数哨兵证明预算判断发生在整批复制之前，并证明多个预算失败不会累积诊断集合；同时保留现有 BatchOrderTest 和库调用隔离哨兵。已有 100/1000 项重复测试没有覆盖预算门之前的复制。

Rejected source 没有 E 时继续检查后项 effective，是“预算优先”可能需要的线性扫描，**本报告不将该扫描本身单列为 CPU 违约**。修复目标是避免可移除的整批内存放大。

## Minor

无。

## 已核对的正向证据与边界

- SchemaProfile 负责有限语言准入和展开计费，SchemaLibrary 负责库执行，SchemaDiagnostics 负责静态路径投影；没有重复实现标准断言算法。内部 graph 是准入/诊断投影，不是第二配置值源。
- LiteralValue 是 sealed 不可变数据图；ObjectValue/ListValue 构造已冻结容器，NumberValue 规范化，因此 ConfigSchemaSource 不再次深复制是成立的。source 本身没有 networknt/Jackson 对象。
- 单值预算迭代遍历、饱和 U、ref 每条边计费与访问栈检测相互一致；原始深度先于递归 profile/tree 构造检查。source 中立预算与单 schema 资格分开。
- 库 meta loader 白名单使用 SchemaRegistry 所属类加载器；作者 loader 不提供外部资源；RuntimeException 安全投影不保留原 message/cause。JVM fatal Error 正确上抛，未作为缺陷。
- 诊断通过准入图匹配静态 property/items，遇字典或无法共同确认的组合路径截到父路径，不直接格式化第三方 NodePath。
- 测试有真实断言、错误顺序反例和独立 ClassLoader 库调用哨兵。bootstrap 类存在性测试较弱但只是初始 RED 痕迹，已有静态行为覆盖，不以此要求风格重写。
- POM 新增依赖集中于 root dependencyManagement/config；未升级原 Jackson/SLF4J。发行封装、Engine、Node/Tela 消费及最终许可证验证是后续 Task，本报告不以它们尚未完成打回 Task1。

复审范围：I-1 修复的实际 diff、定向反例及必要回归，不要求重复全项目验证。
