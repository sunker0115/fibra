# 源码参考与行为证据

本目录只保存架构取舍的来源、固定版本、源码定位和验证映射。Fibra 已完成范围与 F1–F4 由
[vNext 架构](../specs/2026-09-07-fibra-vnext-architecture.md)定义；产品 P0–P8 只由
[CLI + Desktop Agent 产品架构](../specs/2026-09-13-fibra-based-agent-product-architecture.md)定义。
参考项目的实现不自动成为 Fibra 的实现承诺，文章解读也不能替代固定提交的源码与测试。

## 当前证据链

- [Cordis 行为映射与验收证据](2026-09-09-cordis-behavior-evidence.md)：DeepSeek 内置 Cordis 与
  cordiverse/cordis 的固定真源、文件摘要、当前落点和 71 项行为边界。
- [cordis4j 设计对拍与采用边界](2026-09-11-cordis4j-design-evidence.md)：固定提交、设计契约与实现
  定位，以及 Fibra 对每项能力的吸收或拒绝结论。
- [插件依赖、装载与更新基线](2026-09-09-plugin-dependency-baselines.md)：IDEA、PF4J 与 DeepSeek
  Harness 的依赖图、ClassLoader、配置更新、客户端插件和发布策略证据。
- [行为验收账本](2026-09-11-behavior-verification-ledger.md)：71 项 Cordis 原始行为与 44 项 Fibra
  额外回归的逐项方法清单和通过证据；现有 CLI/ZIP 属于 vNext 第 1–10 节，不证明 F1–F4。
- [后续架构真源与外部参考审计](2026-09-13-architecture-source-audit.md)：唯一真源矩阵，DSH/Cordis、
  AgentCLI、PaiCLI、Picocli 4.7.7、JLine 4.4.3 的固定源码证据，外部事实与 Fibra 推导分级，以及本次
  审计级别 P0/P1/P2 闭环。

## 用户提供的解读材料

- [Cordis 插件系统：插件、服务与事件如何协作](https://mp.weixin.qq.com/s/Xm_jMF-wIepmuO5Q3BFDAQ)
- [拆解 dsh：这套插件设计该如何借鉴](https://mp.weixin.qq.com/s/CCAMmQHYQ8I1Kxq27Du2Gw)

两篇文章用于发现需要复核的问题：Fiber 状态、Service 依赖、Effect 所有权、Event mode、DSH 动态
插件适用边界和诊断成本。项目结论仍以本目录记录的固定源码提交为准。

## 文档职责

- vNext spec 记录 Fibra 第 1–10 节已完成范围和第 11 节 F1–F4；产品 spec 独占 P0–P8 编号与顺序。
- `specs` 记录已确认的架构决定、边界和验收条件，不在 `references` 建立平行架构或实施计划。
- `references` 记录支持决定的外部事实、版本、源码定位与测试映射。
- 行为验收账本只证明逐项覆盖和执行结果，不替代架构理由。
- 过程记录和被替代的旧 API 只留在 Git 历史，不重新混入当前设计真源。

参考资料应按来源版本更新。清理废弃设计时，保留仍支持现有语义的源码定位和测试证据；删除失效
结论前，核实是否已有可追溯的替代说明。测试名称或数量只能说明覆盖范围，不能替代对断言和执行结果
的检查。
