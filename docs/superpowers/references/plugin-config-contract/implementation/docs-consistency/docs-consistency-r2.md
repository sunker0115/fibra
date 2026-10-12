# 插件配置合同文档一致性审查 R2

结论：在本次配置合同范围内，完整复读一轮后 A–E 均为 0 项，产生 0 项修改建议。R1 的 A1 已闭合，本次文档一致性审查达到收敛条件。本结论不代表整个功能、作者模板或发行消费已验收。

## 范围与方法

应用 `/Users/sunke/.agents/skills/doc-consistency-review/SKILL.md`，按完整通读、五类归档、呈现前三项核查执行。已重新完整读取当前工作树下以下 7 份文档，共 3884 行；架构长文按连续区间读取，未用关键词搜索替代通读。

| 文档 | 本轮完整读取行数 |
|---|---:|
| `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` | 338 |
| `docs/api/plugin-configuration.md` | 150 |
| `README.md` | 248 |
| `docs/api/README.md` | 300 |
| `docs/release.md` | 138 |
| `docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md` | 1718 |
| `docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md` | 992 |

审查仅覆盖本次配置合同的跨文档冲突、语义、遗漏和位置，不重开无关旧架构。沿用 R1 对 config/artifact/Engine 公开类型和预检路径的逐源核对，并重新读取本轮修正对应的真实 shutdown 路径及测试断言。首次审查已执行一次 targeted Nowledge 搜索；未取得可替代本轮源码的现行合同证据，本轮不依赖旧记忆判断。

## A. 前后矛盾（必须修）

0 项。R1 A1 已闭合，核实证据如下。

`docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md:796–799` 原文：

```text
关闭新准入，排空已接纳的 command
→ 观察 Engine 读取 attempt 的缓存 close 终态
→ 读取屏障成功后发布 SHUTDOWN / CLOSING
→ generations 封准入、drain、stop、retire
```

同文档 `:812–814` 原文：

```text
当前读取失败隔离策略下，读取屏障失败保留原 operation、首个 failure 及 Engine 持有的资源，返回同一失败，
不建立新的 SHUTDOWN operation。该整体保留是保守失败策略，不表示每个固定文件流都物理依赖全部 runtime；
详细边界见[配置合同 §7](2026-10-11-plugin-config-contract-design.md#7-生效范围时序升级和恢复)。
```

对应 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java:1170–1173` 原文：

```java
    private Mono<Void> shutdown() {
        if (readAttempt != null) {
            return readAttempt.closed.then(loop.call(this::shutdown));
        }
```

其后的 `:1177–1182` 才建立 SHUTDOWN operation 并发布 CLOSING。上述文案与配置合同设计 `:277` 的“shutdown在新建SHUTDOWN operation前观察同一缓存close终态”一致。

`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineConfigurationContractTest.java:184–187` 原文：

```java
            assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
            assertSame(error, assertCode(CLEANUP_FAILED, f.engine.closeAsync()));
            assertEquals(closes, f.closes.get()); assertEquals(nameCloses, f.provider.nameCloses);
            assertEquals(0, f.provider.driverCloses); assertEquals(0, f.store.closes);
```

同测试 `:188–193` 继续断言历史 operation 不变、失败 owner 存续且只保留失败句柄。这里仅核对测试源码，未重新运行测试。

## B. 语义不清（建议修）

0 项。§10 已将读取屏障失败与后续共享资源关闭失败分开；后者才进入 SHUTDOWN FAILED。整体保留是当前隔离政策，不推导为每个文件流与全部 runtime 存在物理依赖。政策是否收窄仍是可另行评估事项，本轮不视作已经批准的变更，也不据此要求修改合同。

## C. 缺失（建议修）

0 项。配置合同来源、名称真源、候选 revision 绑定、active 实例预检、停用/升级/no-op/恢复行为、错误分类、读取与清理 owner、作者资格和发行状态在对应文档中均有承接。作者模板和仓外消费仍在验证的状态已明确，不作为缺陷，也不把它们算作完成证据。

## D. 可调整（可选）

0 项。没有因个人措辞偏好或尚未决定的关闭政策取舍提出额外修改。

## E. 内容定位错位（必须修）

0 项。设计文档承载合同、边界和门禁；API 文档承载调用和错误语义；README/API 索引提供入口；release 承载资格要求和验收状态；两份架构文档保留 owner、预检位置和关闭规则，并链接配置合同。§10 此次补充属于其关闭所有权职责，没有复制整套配置矩阵。

## 呈现前核查与边界

已完成三项核查：

1. 内部搜索：回查两份合同/架构中的读取、SHUTDOWN、首个失败、缓存 close、隔离和保守策略描述，确认没有另一节继续要求读取屏障失败后建立新 SHUTDOWN。
2. 引用回读：通过带真实行号的原文重新读取 §10、配置合同 §7、`FibraEngine.shutdown()` 和对应测试，报告引用与当前文件一致。
3. 同文档交叉核查：对照 Foundation §4 的查询清理所有权（266–268）与 §10 的正常顺序、读取屏障例外（796–814）和后续共享关闭失败（818–822），语义一致；配置合同 §7 锚点存在。

本轮未修改项目文件，未运行 Maven 或其他测试，未访问 Tela 主树。唯一新增文件是本审查报告。无需为本轮审查引入新的材料性架构决定。
