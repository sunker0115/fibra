# 通用插件配置合同推进计划

日期：2026-10-12。用户批准的配置合同设计、实现、正式制品/真实消费者验证、使用文档及最终双独审已完成，随后按用户授权提交、推送并快进合入 main，提交为 `0d3f770`。基线 `0a674176b8b762b4e77044bcba7f051bf06c41d9`，当前按用户要求回到 `codex/plugin-config-contract` 修复 main CI 的视图观察竞态。用户已决定本次保留 Jackson 3.1.5 并明确记录告警，升级另行处理，该决定不授权发布。配置合同交付范围及证据见[最终交付记录](closeout.md)。

## 目标与边界

扩展既有 fibra-config/artifact/runtime/Engine：作者提供 definition 权威静态配置描述；Fibra 静态发现并统一校验 effective LiteralValue；runtime 拥有绑定、业务规则和资源；产品拥有组合/UI。沿原 target/CAS/求值/资源 owner，不建立配置中心、第二 Registry/数据库或表单平台。名称目录 D37 不重开。局部测试、设计通过不等于目标完成。

## 阶段与验收

1. **现状与有界模式分析（complete）**：已核工作树、权威规范、完整配置链、公共消费者、先前候选证据和固定外部来源；事实与未验证边界已归档。
2. **设计资格（complete，仅设计范围）**：已比较路线并形成具体候选，类型化字典/有限分支有固定Pi/OpenCode场景及局部库probe依据；不是正式profile/制品资格。规格见 ../../specs/2026-10-11-plugin-config-contract-design.md。
3. **独立设计审查与批准（complete）**：初版问题已修，三类最终复审均B0/I0/M0，审查规格hash `3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`。回执在 ../../references/plugin-config-contract/review/。2026-10-12用户“批准”，覆盖已呈现公共API/制品/依赖及builtin破坏性迁移；Tela主树保持只读。
4. **实现计划及执行（complete）**：按[实施计划](implementation-plan.md)完成唯一 owner 实现及反例；README、作者/宿主文档、模板、Java/Node/client 说明及签名已同步，无兼容路径。
5. **发行与真实消费者（complete）**：正式作者/发行装配、Maven/npm制品、真实 Java/Node/client/builtin 路径及隔离 Tela 均按批准范围验证；最终字节与消费者证据绑定。Electron 实机、OpenAI 联网及完整 Tela 发行 IT 不在本次已验证范围。
6. **最终独立审查及交付（complete）**：完整 clean verify、原可复现发行、最终 ZIP 资格及合同/质量双独审通过，均 B0/I0/M0。已授权配置合同验收闭合；该结论不授权发布或接受既有依赖安全风险。
7. **合并后 CI 竞态修复（complete，本地验证）**：受控慢订阅者稳定复现旧谓词漏掉失败视图，修复后等待关联本次目标 revision 的失败清理终态，保留保存回滚、资源存活、重试和恢复断言；同时修复 Node 取消测试的 STOPPING 瞬态等待及 Registry 测试的异步交付假设。定向 17 项、完整 `clean verify` 44 模块/1229 项通过；独审要求保留的 DRAINING 阶段断言已补并单独回归通过。生产通知契约、依赖和超时不变。此处记录提交前的本地验收，修复后的 GitHub CI 结果须单独核验。
8. **预期超时验证补强（complete，本地验证）**：已实测延后 writer 到关闭后才执行仍能通过旧断言，并为真实管道写、配置资源关闭、CLI 交接及两个 Node 尾响应关闭场景建立精确线程/等待阶段证据。新 5 处定向验证通过，4 组反例共 5 项均明确 assertion failure；全部临时修改按 SHA 恢复后，完整 clean verify 44 模块/1229 项通过，独立审查无剩余问题。没有只用启动 latch 或瞬时 isDone 替代超时，没有生产接口/依赖改动；第 7 阶段修复及两处有真实排空屏障的测试完整保留。此处记录提交前的本地验收，Linux/GitHub CI 结果须在推送后单独核验。

## 必需反例

- 不执行插件代码的精确发现；同名多 facet 不串合同；unknown/missing/非法 schema。
- raw/effective、local/祖先/when inactive 延期、package gate 独立覆盖、启用重验。
- 候选新 revision 升级、builtin 内容身份/深不可变、CAS 并发。
- 保存前拒绝/保存后未 ready/恢复失败分类，取消观察、读取和关闭失败 owner 保留。
- 精确数值、纯校验、不填默认/转换、敏感值不回显、有界工作。
- 所有管理入口与 runtime 同门；业务 validator 与资源可用性不冒充静态保证。

## 停止条件

- 具体公共 API/制品/依赖未批准：继续证据与设计审查，不开启生产实现。
- 同一问题连续两次失败：记录并报告，停止硬试。
- 资源失去 owner、第二事实源、来源/实现不匹配：设计不得通过。
- 不恢复/关闭 Tela P3 目标；不改 Tela 主工作树；不自动 commit/push。

## 错误与限制

- 初次创建分支被沙箱 `.git` 只读限制拒绝；使用获准的 require_escalated 后成功，非自动审批拒绝。
- 工具输出合并过大曾截断；后续按文件和小节有界读取，不将未读部分视作证据。
