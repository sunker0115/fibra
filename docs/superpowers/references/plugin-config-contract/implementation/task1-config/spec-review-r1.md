# Task 1 独立合同审查 R1

结论：**暂不通过；B=0，I=1，M=0。** 唯一重要问题是批量非预算失败未按输入顺序报告。其余已核范围未发现需要改变批准合同的实现问题。本报告只审 Task 1，不以尚未实施的 Engine、发行、Node 或 Tela 任务阻塞本阶段。

## 输入绑定

- 仓库：`/Users/sunke/dev/ai-project/fibra`；分支 `codex/plugin-config-contract`；HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。已把 untracked 的生产和测试文件作为实际实现读取，而非只审 `git diff`。
- 审查输入清单：`spec-review-r1-input-sha256.json`，包含 12 个新增生产文件、4 个新增测试、2 个 POM、权威设计/实施计划以及既有 LiteralValue；清单自身 SHA-256：`dcc4e38572b33c0868521b4716304cb34347463944e4af39feaf04be645b1b7e`。
- 设计 SHA-256：`718bfda2892042c80abf7570b18bf7b17ea8c14d96a4a81db206299b7cf4647f`；实施计划 SHA-256：`0e672c99eb7bb2102d2ce1de6f9688aeca3dd32f613082897b4164d828664368`。
- `ConfigSchema.java` SHA-256：`b86362498570a5cbb6eb4a7cf3550fb7c1d64074e863d8976faaf77fcd781baa`。
- 实跑 validator JAR SHA-256：`d3f4c61ba35c3543bdf1ab42462bf89721542f4adea5b092ee7b430dfd467181`；对应 3.0.8。已查看固定库源码中数值、计数、组合、日志路径，不把实现者报告作为通过依据。

## I-1：后项 schema 语义错误遮蔽前项实例错误

位置：`fibra-config/src/main/java/com/sstlfsj/fibra/config/ConfigSchema.java:63-68,84`。

权威依据：设计 §6 第 217 行要求“batch输入不可变且按调用顺序定位”“先核全量接纳预算，再检查source并校验值”“只返回首个安全失败”。§5.1 要求整个批次预算先于任何库编译或验证；这要求预算拒绝优先，但没有把后项的 INVALID_SCHEMA/UNSUPPORTED_PROFILE 提升到前项 VALUE_MISMATCH 之前。主线程在本审查期间确认同一解释；这是保留现有合同的顺序要求，不是新增功能。

可证伪案例：

```java
var first = source(Map.of("type", "string"));
var second = source(Map.of("type", "string", "default", "forbidden"));
ConfigSchema.validateAll(List.of(
    new ConfigValidationInput(first, LiteralValue.of(42)),
    new ConfigValidationInput(second, LiteralValue.of("ok"))));
```

两项均在中立/单项/集合预算内。预期第 0 项 `VALUE_MISMATCH`；实际第 1 项 `INVALID_SCHEMA`。原因是预扫描 `admit` 把第 1 项语义失败记入 failures，84 行在顺序编译/验证循环前直接返回；第 0 项从未得到验证。同理，后项未知 profile 会发生同类遮蔽。

独立失败探针：`spec-review-r1-probe/BatchOrderFailureProbe.java`，实际 native exit=1；`order-red.json` 记录完整 argv/stdout/stderr。其断言输出为：`Expected index=0 VALUE_MISMATCH; actual=ConfigBatchFailure[inputIndex=1, violation=ConfigViolation[code=INVALID_SCHEMA, keyword=, instancePointer=]]`。

最小修复：保留全量预算接纳阶段，把预算失败与非预算 schema 资格失败分开。缓存每个 source 的非预算失败并继续预算扫描；只有所有预算可接纳后才按 inputIndex 顺序编译/验证，遇到该项缓存语义失败时返回。不要通过提前验证第 0 项来破坏预算门。增加“前项值错误 + 后项 INVALID_SCHEMA/UNSUPPORTED_PROFILE”的失败先行回归，同时保留“后项单项/集合预算失败必须先于任何库调用”的反例。

## 已核合同与证据

| 合同 | 审查结果与证据 |
|---|---|
| §4 strict UTF-8/JSON/envelope | reader 在递归值创建前限制深度和节点；重复键、尾部输入、非支持 profile、错误 schema 根形状均有测试；envelope 与 schema 语义分离。未新增 I/O。 |
| §5 有限 profile | 源码拒绝 allowlist 外词汇、断言混合组合节点、错误 type/required/const/enum/ref；typed dictionaries、allOf/anyOf/oneOf、nullable 及 boolean 子 schema 有执行测试。全部 defs（含 dormant defs）参与 DAG 检查与按边计费。 |
| §5.1 中立与按需资格 | Source 只执行中立预算；文档解码不调用 schema 资格/库编译。未知关键词 dormant 及超过单 schema 字节上限但未超全局限额有延期测试。既有 LiteralValue 已深不可变，无第二配置值源。 |
| §5.1 数值/计数 | 数字规范 precision/scale/exponent 先于库节点；源码用 long 计算 exponent/scale 边界，DecimalNode 直接承接 BigDecimal。现有近邻小数/大整数/enum 测试及独立 `1e-256`/`1e256` 比较探针通过。计数上界与非整数/负数的错误码测试通过。 |
| §5.1 batch 编译前预算 | 除 I-1 的非预算失败顺序外，真正的库调用位于预算循环之后。独立隔离 ClassLoader 在 SchemaRegistry 加载处设 AssertionError 哨兵：129 个 unique source 和超总 U 的重复实例都以 -1 拒绝且哨兵调用数=0；正常 compile 控制组命中哨兵=1。见 `AdmissionOrderProbe.java` / `gate-run.json`。 |
| §5.1 边界及按出现计费 | 现有测试覆盖节点/字节/字符串/深度/菱形/组合/U/去重。独立探针补验：128/129 unique source、128/129 object member、16/17 ref edge、4/5 combination depth、64/65 enum item；全部按预期。批量 source 去重不免除实例 U。 |
| §6 公共 API | 所需 7 个公共类型和批准方法均存在；单项和 batch 共享 profile/library adapter；非法 null API 参数同步拒绝；ConfigViolation/ConfigBatchFailure 不携第三方类型。未增 store/default/coerce。 |
| §9 / CC10 安全诊断 | adapter 只投影稳定 code/allowlist keyword/受控 pointer，不持有或返回库 Error/cause/suppressed；动态键路径在字典静态父节点截断，嵌套数组 canary 测试通过。未知字段不输出字段名。RuntimeException 安全转换，未吞 JVM fatal Error。 |
| 宿主 loader 与依赖 | meta URI 精确 allowlist，由 SchemaRegistry.class 固定资源加载；作者 loader 拒绝全部外部 URI。TCCL 陷阱测试通过。根 POM 只新增 networknt 3.0.8 管理及 validator YAML exclusion；原 Jackson 3.1.5、SLF4J 2.0.18 和 config YAML 路径保留。 |

## 验证、覆盖边界和复审要求

独立运行命令：Zulu 21 + Maven 3.9.9，`mvn -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-config -am test`。实际退出 0；84 个 config 测试（其中 21 个新增）和 2 个 API 测试通过，无跳过；输出 `spec-review-r1-test.log`。`git diff --check` 通过。仓外 `ContractProbe.java` 与 `AdmissionOrderProbe.java` 编译/执行退出 0，顺序回归探针故意退出 1。全部命令和输出在 `spec-review-r1-probe/*.json`。

现有测试名 `admitsWholeBatchBeforeCompiling...` 本身只断言结果，不证明编译调用为 0；本次独立哨兵补充了这一证据。修复 I-1 后应保留可执行的等价测试，不能仅凭结果码推断时序。边界探针不等于每种允许词汇所有组合的穷举证明，也不承诺 CPU/heap SLA。

本阶段 canary 证据覆盖 config 返回诊断和异常；正式 Engine/CLI/Registry/Tela 的日志、审计与错误 DTO 投影属于后续任务。本次 config 测试 classpath 无 SLF4J provider，不能宣称已实测产品日志捕获。已核 adapter 无日志输出，固定库的 allowed 执行路径未发现打印现值的日志入口；最终 CC10 仍须在真实宿主完成。

修复后仅需首先复审 I-1 实际 diff、前项错误/后项语义错误与预算优先反例，再执行原 config reactor 回归。未发现需要改动批准 API、扩大 Task1 范围或调整数值上限的理由。

检索说明：按隔离子任务规则执行一次 Nowledge MCP `memory_search`，query=`Fibra plugin config contract schema budget CC09 CC10`，返回 strict/default 的 5 条旧部署/发行背景；没有本次合同证据，结论不依赖其内容。当前工具未提供 graph，且没有同一身份/Space 浏览器证明，未展开图。未写长期记忆。未改生产/测试文件，未 commit/push。
