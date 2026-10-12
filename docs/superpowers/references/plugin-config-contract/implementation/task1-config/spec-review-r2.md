# Task 1 独立合同审查 R2：I-1 定点复审

结论：**通过，I-1 已关闭；本轮 B=0，I=0，M=0。** 预算失败仍先于库编译/验证；预算接纳后，普通 schema 语义失败与实例失败共同按输入顺序返回。结论仅关闭 Task1 的已知合同问题，不关闭整个插件配置目标或后续发行/真实消费者门。

## 输入绑定与修复范围

- 仓库 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`，HEAD `0a674176b8b762b4e77044bcba7f051bf06c41d9`。
- `ConfigSchema.java` SHA-256：`93ca3e953fac11b8a5f69a798ccf9ed957c7303965f725a086774ae90354e6c7`。
- 新增 `ConfigSchemaBatchOrderTest.java` SHA-256：`c5115396194bef36b11e6d70da0df699401594567a37559ce43bc0faef127988`。
- 新输入清单 `spec-review-r2-input-sha256.json`，自身 SHA-256：`9c449e60a45ef437bb715699f8ecac8dfdd7331969a06538d018fb5246bc6971`。与 R1 清单逐项重算比较，原 21 个审查输入只有 ConfigSchema.java 变化，新增上述测试；SchemaProfile、SchemaBudget、SchemaLibrary、公共 API 其余类型、POM、设计和实施计划均与 R1 相同。
- `spec-review-r2-production.diff` 保存精确生产 diff。用于生成 diff 的 R1 原文已校验匹配 R1 SHA `b86362498570a5cbb6eb4a7cf3550fb7c1d64074e863d8976faaf77fcd781baa`，不是以 HEAD 的 untracked 缺失状态代替原实现。
- 工作树中另有 THIRD_PARTY_NOTICES 等主线程变更；本轮未将其纳入 I-1 修复审查，也未触碰。

## 实际代码与测试核对

ConfigSchema.batch 的扫描阶段仍先收集/计算 unique schema 字节数、schema 个数、单项预算及总 U。source 准入失败仍缓存到 rejected，但只有 BUDGET_EXCEEDED 加入 budgetFailures 并在任何库调用前返回。非预算失败延迟到第二个按 index 遍历的循环，先看当前 source 缓存错误，再 compile/validate 当前项；不存在先验证前项再检查后项预算的旁路。未改公式、阈值或库入口。

已完整读取新增的 5 个仓内测试，具体覆盖：

1. 前项 VALUE_MISMATCH 优先于后项 INVALID_SCHEMA。
2. 前项 VALUE_MISMATCH 优先于后项 UNSUPPORTED_PROFILE。
3. 前项有效时返回后项 INVALID_SCHEMA；checkSources 保持正确索引。
4. 后项 effective 或 schema 预算错误仍优先于前项实例/语义错误，包括 checkSources。
5. 独立 URLClassLoader 以 platform loader 为父级，在真实 SchemaRegistry 加载处抛 AssertionError；后项单实例预算、129 unique source、重复实例超总 U 均断言库尝试数为 0，正常 compile 控制组断言为 1。这个测试已把 R1 仓外时序证据落入仓内，不是仅由返回码推断库未执行。

隔离哨兵不改生产代码、没有测试开关；检查的是本次时序安全边界，不要求吞掉 JVM fatal Error。新增测试实际通过，未用缺依赖或错误异常类型冒充哨兵命中。

## 独立验证

- 重跑 R1 的原 `BatchOrderFailureProbe`，探针代码不变：原 native exit=1，本次 **native exit=0**。记录 `spec-review-r2-order-probe.json` 包含完整 argv/stdout/stderr；直接消除了同一失败反例。
- 独立运行 Zulu21/Maven3.9.9：`mvn -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-config -am test`，**native exit=0**，config 89 + API 2，0 failure/error/skipped。`ConfigSchemaBatchOrderTest` 的 5 项全部通过，含实际隔离哨兵；日志 `spec-review-r2-test.log`。
- `git diff --check` 退出 0。
- 核对实现者保留的 `batch-order-red-2.log`：5 tests、3 assertion failures、0 errors；包名笔误导致的先前编译失败未当作行为 RED。本结论主要依据实际代码、独立探针及独立重跑，不依赖实现者自报结果。

R1 其余审查结论和范围限制继续有效；本轮未重做全域资格、未承诺资源 SLA 或产品日志/发行消费已验证。未修改生产/测试文件，未 commit/push。
