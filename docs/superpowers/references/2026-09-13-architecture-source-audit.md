# 后续架构真源与外部参考审计

复核日期：2026-09-13。

本文件只记录架构真源映射、固定外部证据、证据等级和本次文档审计结果，不定义新的产品架构、阶段顺序
或实施计划。

## 1. 唯一真源与状态

| 范围 | 唯一真源 | 当前状态 | 不得误用的证据 |
|---|---|---|---|
| Fibra vNext 已交付底座 | [vNext 架构第 1–10 节](../specs/2026-09-07-fibra-vnext-architecture.md) | 已完成 | 不能由历史部分绿色构建替代最终账本 |
| Fibra CLI 演进 | [vNext 架构第 11 节 F1–F4](../specs/2026-09-07-fibra-vnext-architecture.md) | F1 已完成；F2–F4 尚未实施 | F1 之前的固定 CLI、REPL 与 ZIP 只属于第 1–10 节，不能抵扣 F1；F1 证据也不能抵扣 F2–F4 |
| 上层 Agent 产品 | [CLI + Desktop Agent 产品架构](../specs/2026-09-13-fibra-based-agent-product-architecture.md) | P0–P8 全部尚未实施 | vNext 不再保存第二套产品阶段表；其它文档不能重排或重定义 P0–P8 |

后续架构的 DSH 契约统一固定为 `@deepseek-ai/dsh 0.1.5-rc.2`、提交
`c291e7961a515f6d7af9304e7fd1d257929aef26`。旧提交 `b0a7d2c` 与 `a66e470` 只保留其历史对拍价值，
不得继续作为 F1 或产品阶段的架构契约真源。

## 2. CLI 固定源码证据

| 来源 | 固定版本与提交 | 源码位置 | 直接证明 | 不直接证明 / Fibra 自定增强 |
|---|---|---|---|---|
| DSH/Cordis | DSH `0.1.5-rc.2`，`c291e7961a515f6d7af9304e7fd1d257929aef26`；内置 Cordis `4.0.2`，同提交 `vendor/cordis` tree `7feb044c43a476591eb6c61e727667c97c0c8f40` | [commands](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/interaction/commands/src/index.ts)、[process shutdown](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/apps/cli/src/process-shutdown.ts)、[Fiber](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/fiber.ts)、[Reflect service](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/reflect.ts) | command 是插件拥有的注册项；AbortSignal 可使调用方立即停止等待；CLI 有有界、可升级的进程关闭协调；Cordis effect/service 有所有权与清理等待 | command 的 abort-race 不保证被放弃 handler 已停止，也不提供 Fibra 的 PublishedRuntime revision 准入、route 租约或 invocation Scope 排空 |
| AgentCLI | `1.0-SNAPSHOT`，`1962c58a4dcf647d46beeb6fea16b3ed40a26ab5`；JLine `4.0.0` `jdk11` classifier | [pom](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/pom.xml)、[Main](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/Main.java)、[history](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliHistory.java)、[completer](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliCompleter.java)、[highlighter](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliHighlighter.java)、[renderer fallback](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/render/RendererFactory.java) | JLine persistent history、补全、高亮、dumb/ANSI 降级和交互取消的可行组合 | `Future.cancel(true)` 是立即取消请求，不等待 Fibra Scope、route 租约、远端终态或插件资源排空 |
| PaiCLI | `1.0-SNAPSHOT`，`840f01b53aac798d5a51921a89741c83278d9aac`；JLine `4.0.0` `jdk11` classifier | [pom](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/pom.xml)、[Main](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/Main.java)、[history](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliHistory.java)、[completer](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliCompleter.java)、[highlighter](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliHighlighter.java)、[renderer fallback](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/render/RendererFactory.java) | 与其自身固定提交相符的历史、补全、高亮、renderer 与交互取消实现 | 与 AgentCLI 一样，不证明 Fibra 的受管排空；两个仓库当前实现接近也不构成公共架构基线 |
| Picocli | `4.7.7`，tag commit `5fcd4415a2cf834a12b4cb1e262a007beaa6b4af` | [CommandLine.java](https://github.com/remkop/picocli/blob/5fcd4415a2cf834a12b4cb1e262a007beaa6b4af/src/main/java/picocli/CommandLine.java) 的 `CommandSpec.addSubcommand`、`removeSubcommand`、`usageMessage` setter | `CommandSpec` 提供程序化建树、解析与 help 模型 | 这些 mutator 直接证明 `CommandSpec` 是可变解析对象；“不可变命令代”必须由 Fibra descriptor、贡献身份和 revision 定义，不能把 `CommandSpec` 冒充并发只读快照 |
| JLine | `4.4.3` `jdk11`，tag commit `15f6fdd3b737bec2e876dd7d708f08f9efcfdb32` | [LineReader](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/reader/LineReader.java)、[LineReaderImpl](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/reader/impl/LineReaderImpl.java)、[Terminal](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/Terminal.java)、[Attributes](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/Attributes.java) | `LineReader` 明确非线程安全；`readLine` 可用 `UserInterruptException` 表达普通 Ctrl+C；Terminal/Attributes 提供 raw mode 与终端属性控制 | 普通 REPL Ctrl+C、raw lease 字节 `0x03`、外部 `SIGINT/SIGTERM` 的三路归并、去重、退出码和 Scope 排空顺序均为 Fibra F3 自定契约 |

## 3. 命令代、准入与中断结论

1. 一次解析及其 help、usage、补全固定使用捕获的 Fibra descriptor、贡献注册身份和 view revision；不得在
   同一次操作中重新读取最新 view。
2. `CommandSpec` 只能作为由上述捕获事实派生、受单次操作或单条 CLI lane 约束的可变解析对象；它不是
   命令代身份，也不得被插件修改或跨线程并发共享。
3. 解析开始不取得 route 租约。只有执行边界以捕获身份/revision 通过 `PublishedRuntime` 准入的
   invocation 才持有租约并参与排空；准入前代变化返回 stale/revoked，既不调用旧 handler，也不转向
   同名新 handler。
4. 普通 REPL `Ctrl+C` 只中断当前行编辑；raw terminal lease 中的 `0x03` 取消持有该 lease 的已准入
   invocation；外部 `SIGINT/SIGTERM` 进入进程级取消/关闭协调。三者共享幂等协调器，但不能混成同一入口。
5. AgentCLI/PaiCLI 和 DSH 的立即取消只提供交互参考。Fibra 必须由自己的契约测试证明：取消请求之后仍
   等待 invocation Scope、route 租约、远端终态和受管资源按所有权排空。

## 4. 产品参照的证据分级

| 外部项目 | 可由来源直接陈述的事实 | 来源等级 | Fibra/产品推导 |
|---|---|---|---|
| DSH/Cordis | 固定源码中的 host/client runner、UI renderer/slot/layout、业务页面插件注册和 effect 所有权 | `c291e7961` 固定源码：[host runner](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/extensions/cordis-host-runner/src/lifecycle.ts)、[client runner](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/extensions/cordis-client-runner/src/client/runtime.ts)、[renderer](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-renderer/src/client/registry.ts)、[slots](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-slots/src/index.ts)、[layout](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-layout/src/client/index.ts)、[conversation](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-conversation/src/client/index.ts)、[settings](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-settings/src/client/index.ts)、[tool renderer](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-tool/src/client/index.ts) | 是否采用以及如何接入 Fibra `ChangeSet`、PublishedRuntime 与排空由本项目定义 |
| VS Code | 官方文档描述 local/web/remote extension host 和 web extension 入口 | [Extension Host](https://code.visualstudio.com/api/advanced-topics/extension-host)、[Web Extensions](https://code.visualstudio.com/api/extension-guides/web-extensions)；浮动、非契约 | 唯一管理面、多 facet 逻辑包和同一 desired target 是本项目契约 |
| Grafana | 官方文档描述 App Plugin 的页面/UI extension/backend 组成及 backend 启动模型 | [App Plugin](https://grafana.com/developers/plugin-tools/key-concepts/anatomy-of-a-plugin)、[Backend Plugin](https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins)；浮动、非契约 | 跨 facet `ChangeSet`、统一准入和排空是本项目契约 |
| Eclipse Theia | 官方文档区分运行时插件与编译期扩展及其运行位置 | [扩展模型](https://theia-ide.org/docs/extensions/)；浮动、非契约 | client execution session、desired/observed 协调和唯一控制面是本项目契约 |

保留后三组浮动链接的理由是只将其用作术语和设计灵感，不以其当前页面内容建立 Fibra 契约或验收断言；
若未来要把其中行为升级为契约依据，必须先固定发布版本或源码提交并新增对应证据。

## 5. 本次审计记录

| 级别 | 问题 | 处理 |
|---|---|---|
| 审计 P0 | vNext 总状态把第 1–10 节完成与第 11 节路线混成整体完成 | 已拆分状态，并明确现有 CLI/ZIP 不证明 F1–F4 |
| 审计 P0 | vNext 与产品架构各有一套 P0–P8 阶段表且顺序冲突 | 已删除 vNext 阶段表；产品架构成为唯一真源 |
| 审计 P1 | 解析期被描述为持有旧 route 并参与排空 | 已改为仅 PublishedRuntime 准入后的 invocation 持有租约；准入前代变化返回 stale/revoked |
| 审计 P1 | Picocli `CommandSpec` 被暗示为不可变命令代 | 已把不可变身份限定为 Fibra descriptor、贡献身份和 revision，并记录 Picocli mutator 证据 |
| 审计 P1 | 普通 Ctrl+C、raw `0x03` 与外部信号没有分开 | 已定义三类入口和共同的幂等取消/排空协调边界 |
| 审计 P1 | AgentCLI/PaiCLI 的立即取消可能被误作 Scope 排空 | 已明确只采纳交互模式，不采纳排空保证 |
| 审计 P1 | VS Code/Grafana/Theia 的外部事实与项目推导混栏 | 已分栏；唯一管理面、跨 facet ChangeSet、desired/observed 均标为项目自定契约 |
| 审计 P2 | 当前架构文档仍沿用 DSH `0.1.2-rc.1` 旧基线 | 已按维护者确认切换到 `0.1.5-rc.2`/`c291e7961`；旧提交仅保留历史证据 |
| 审计 P2 | VS Code/Grafana/Theia 链接未固定版本 | 接受：已明确降级为非契约灵感；任何契约断言不得依赖其浮动内容 |

## 6. 独立只读复审

首轮独立复审未发现审计 P0，提出 3 项审计 P1、1 项审计 P2 和 1 项版本证据质疑；版本质疑经固定
Git object 复核后由审阅者撤回：

| 级别 | 复审发现 | 关闭证据 |
|---|---|---|
| 审计 P1 | DSH 配置组合、隔离与发布策略的当前采用段仍引用旧提交 | 已统一改为当前契约 `c291e7961`；旧提交只留在明确的历史对拍段 |
| 审计 P1 | DSH UI 结论没有固定源码位置 | 已在第 4 节补齐 client runner、renderer、slots、layout 和 feature plugin 的 `c291e7961` 固定链接 |
| 审计 P1 | 独立复审证据尚未回填 | 已记录首轮发现与逐项处理；第二轮结论在提交前回填 |
| 复审更正 | 质疑 AgentCLI/PaiCLI 的 JLine `jdk11` classifier 标注 | 固定 Git object 中两个 `pom.xml` 均明确声明 JLine `4.0.0` 与 `jdk11` classifier；审阅者重核后撤回该项，第 2 节另增加固定 pom 链接 |
| 审计 P2 | Cordis 行为文档仍把旧 DSH 提交称为固定真源 | 已区分当前架构契约 `0.1.5-rc.2`/`c291e7961` 与既有 71 项历史验收快照；相同 `vendor/cordis/src` tree 只证明语料未漂移 |

第二轮独立只读复审结论：无未关闭的审计 P0/P1/P2。复审确认 `git diff --check`、全仓状态与语义扫描、
Markdown 本地链接和 79 个外部链接检查均通过；新增 8 个 DSH UI 固定源码链接均返回 HTTP 200。固定 Git
object 再次确认 DSH `0.1.5-rc.2`/`c291e7961`、Cordis `4.0.2`/tree `7feb044c43a4`、AgentCLI/PaiCLI 的
JLine `4.0.0` `jdk11` classifier；远端 tag 再次确认 Picocli `4.7.7` 与 JLine `4.4.3` 的固定提交。
