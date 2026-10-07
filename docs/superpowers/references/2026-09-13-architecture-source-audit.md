# 后续架构真源与外部参考审计

首次建立：2026-09-13。最近复核：2026-09-23。

本文件只记录架构真源映射、固定外部证据、证据等级和本次文档审计结果，不定义新的产品架构、阶段顺序
或实施计划。

## 1. 唯一真源与状态

| 范围 | 唯一真源 | 当前状态 | 不得误用的证据 |
|---|---|---|---|
| Fibra vNext 已交付底座 | [vNext 架构第 1–10 节](../specs/2026-09-07-fibra-vnext-architecture.md) | 已完成；其中 client foundation 后续演进不再由该文定义 | 不能由历史部分绿色构建替代最终账本 |
| Fibra CLI 演进 | [vNext 架构第 11 节 F1–F4](../specs/2026-09-07-fibra-vnext-architecture.md) | F1–F4 已完成 | F1 之前的固定 CLI、REPL 与 ZIP 只属于第 1–10 节，不能抵扣 F1；每个阶段只由该阶段新增契约和直接验收事实证明 |
| Fibra client foundation 与跨执行域插件模型 | [Client Foundation 权威架构](../specs/2026-09-15-fibra-client-foundation-architecture.md) | P0 已本地冻结，保持 `0.5.0-SNAPSHOT`，未正式发布；实施证据见 [P0 实施计划](../plans/2026-09-15-fibra-client-foundation-p0.md) | 不能以接口 shape、禁止依赖或 Fibra 内浏览器 fixture 替代真实 Java/Node/external runtime 组合、client conformance、重启和发行证据 |

上层 Agent 产品架构、事件存储、持久工作及其外部研究资料由独立产品仓维护，不再把产品 P1–P8 或产品
实现取舍列为 Fibra 架构真源。Fibra 文档只保留其公共契约、职责边界和可复核的底座证据。

Fibra CLI 与 Client Foundation 对拍使用的 DSH 契约统一固定为 `@deepseek-ai/dsh 0.1.5-rc.2`、提交
`c291e7961a515f6d7af9304e7fd1d257929aef26`。旧提交 `b0a7d2c` 与 `a66e470` 只保留其历史对拍价值，
不得继续作为 F1 或 Client Foundation 的架构契约真源。

## 2. CLI 固定源码证据

| 来源 | 固定版本与提交 | 源码位置 | 直接证明 | 不直接证明 / Fibra 自定增强 |
|---|---|---|---|---|
| DSH/Cordis | DSH `0.1.5-rc.2`，`c291e7961a515f6d7af9304e7fd1d257929aef26`；内置 Cordis `4.0.2`，同提交 `vendor/cordis` tree `7feb044c43a476591eb6c61e727667c97c0c8f40` | [commands](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/interaction/commands/src/index.ts)、[settings secret redaction](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/settings/settings/src/redact.ts)、[process shutdown](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/apps/cli/src/process-shutdown.ts)、[profile boot signals](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/apps/cli/src/profile-boot.ts)、[Fiber](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/fiber.ts)、[Reflect service](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/reflect.ts) | command 是插件拥有的注册项，并由 `recordInput: false` 显式决定原始输入不进入 `command/run`；settings 由 schema `role('secret')` 标出秘密位置，并对 `object/dict/array` 可达字段在跨线边界结构化删除；AbortSignal 可使调用方立即停止等待；CLI 让正常完成与 Unix 信号共用同一个 pending shutdown，先启动定时器再异步调用完整 disposer，超时直接调用 `forceExit`，profile boot 将 `SIGTERM`/`SIGINT` 分别投影为 0/130；重复信号可以升级；Cordis effect/service 有所有权与清理等待 | DSH redactor 明确不覆盖藏在 union/intersection/transform 中的 secret，也没有证明 Picocli/JLine 命令历史或诊断脱敏；command 的 abort-race 不保证被放弃 handler 已停止，不提供 Fibra 的 PublishedRuntime revision 准入、route 租约或 invocation Scope 排空。Fibra 吸收“一个 pending、全链截止、正常/信号共用仲裁、强制退出不等待诊断 I/O”，但按本项目首个信号幂等契约不复制“第二信号立即退出”作为已排空证据 |
| OpenAI Codex CLI | `0.154.0`，tag commit `6b9826e3aa83b1a5947db50f4332cb9c65f1b340` | [message history](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/message-history/src/lib.rs)、[history config](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/config/src/types.rs)、[best-effort sanitizer](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/secrets/src/sanitizer.rs)、[turn interrupt routing](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/tui/src/app/thread_routing.rs)、[terminal ownership and restore](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/tui/src/tui.rs) | `history.jsonl` 只提供 `save-all`/`none`，`append_entry` 原样持久化文本并保留敏感模式检查待办；另一个 sanitizer 仅以固定正则 best-effort 处理少数已知 key/token 形态。TUI 以 thread/turn 身份记录 pending interrupt 并去重；临时交出终端时先暂停自身事件读取、恢复终端，外部程序返回后重设模式并恢复事件读取；公共恢复链独立尝试关闭 terminal modes 并保留首个错误 | Codex 没有证明 F2 所需的命令 descriptor 敏感语义或安全历史；Fibra 不采用其 `save-all` 默认值，也不把启发式正则当作正确性边界。Codex 的 interrupt 身份、单输入 owner 与终端恢复只作为 F3/F4 所有权参考，不证明 Fibra Scope、route、共享失败事实或远端资源排空 |
| AgentCLI | `1.0-SNAPSHOT`，`1962c58a4dcf647d46beeb6fea16b3ed40a26ab5`；JLine `4.0.0` `jdk11` classifier | [pom](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/pom.xml)、[Main](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/Main.java)、[history](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliHistory.java)、[completer](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliCompleter.java)、[highlighter](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/cli/PaiCliHighlighter.java)、[inline renderer](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/render/inline/InlineRenderer.java)、[renderer fallback](https://github.com/WaterDimension/AgentCLI/blob/1962c58a4dcf647d46beeb6fea16b3ed40a26ab5/src/main/java/com/paicli/render/RendererFactory.java) | 一个 CLI 生命周期内复用 `Terminal` 与 `LineReader`；用 `DefaultHistory` 子类过滤启发式敏感行；以 `Candidate`/`Highlighter` 承载动态补全和只影响编辑显示的高亮；读取期间经 `LineReader.printAbove()` 输出并保持输入；按 ANSI 能力降级 plain renderer。任务期另开 executor、进入 raw mode、轮询 standalone ESC 并 `Future.cancel(true)` | 直接终端写入、renderer 自身同步和 ESC burst 分类是该单体应用的局部所有权，不证明单一 terminal lane、resize/redisplay 合并或失败后的统一恢复；`Future.cancel(true)` 只发立即取消请求，不等待 Fibra Scope、route 租约、远端终态或插件资源排空 |
| PaiCLI | `1.0-SNAPSHOT`，`840f01b53aac798d5a51921a89741c83278d9aac`；JLine `4.0.0` `jdk11` classifier | [pom](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/pom.xml)、[Main](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/Main.java)、[history](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliHistory.java)、[completer](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliCompleter.java)、[highlighter](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/cli/PaiCliHighlighter.java)、[inline renderer](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/render/inline/InlineRenderer.java)、[renderer fallback](https://github.com/WaterDimension/PaiCLI/blob/840f01b53aac798d5a51921a89741c83278d9aac/src/main/java/com/paicli/render/RendererFactory.java) | 固定提交中的上述 JLine 关键文件与 AgentCLI 对应文件逐字相同，因此重复证明同一套长驻 reader、history/completion/highlight、`printAbove` 和 renderer 降级组合，不构成第二套独立架构证据 | 与 AgentCLI 相同：不证明 Fibra 的命令代、受管 terminal lane、调用准入、取消或排空；Fibra 只吸收已由两仓固定源码证明的交互机制，不复制单体命令树和取消实现 |
| Picocli | `4.7.7`，tag commit `5fcd4415a2cf834a12b4cb1e262a007beaa6b4af` | [CommandLine.java](https://github.com/remkop/picocli/blob/5fcd4415a2cf834a12b4cb1e262a007beaa6b4af/src/main/java/picocli/CommandLine.java) 的 `CommandSpec` mutator 及[短选项解析](https://github.com/remkop/picocli/blob/5fcd4415a2cf834a12b4cb1e262a007beaa6b4af/src/main/java/picocli/CommandLine.java#L13898-L14090) | `CommandSpec` 提供程序化建树、解析与 help 模型；默认 POSIX 短选项语义接受参数附着形式 | mutator 直接证明 `CommandSpec` 是可变解析对象；“不可变命令代”必须由 Fibra descriptor、贡献身份和 revision 定义。Picocli 不提供 Fibra 的敏感历史/诊断保证；该保证由捕获代 descriptor 的 `sensitive=true` 和内置 `tools invoke --input` 契约定义 |
| JLine | `4.4.3` `jdk11`，tag commit `15f6fdd3b737bec2e876dd7d708f08f9efcfdb32` | [LineReader](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/reader/LineReader.java)、[LineReaderImpl](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/reader/impl/LineReaderImpl.java)、[Display](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/utils/Display.java)、[BindingReader](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/keymap/BindingReader.java)、[KeyMap](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/keymap/KeyMap.java)、[DefaultHistory](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/reader/src/main/java/org/jline/reader/impl/history/DefaultHistory.java)、[Terminal](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/Terminal.java)、[Attributes](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/Attributes.java)、[AbstractTerminal raw mode](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/impl/AbstractTerminal.java)、[NonBlockingInputStream](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/utils/NonBlockingInputStream.java)、[Signals](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/utils/Signals.java)、[TerminalBuilder](https://github.com/jline/jline3/blob/15f6fdd3b737bec2e876dd7d708f08f9efcfdb32/terminal/src/main/java/org/jline/terminal/TerminalBuilder.java) | `LineReader` 明确一般实现非线程安全，但专门允许 `printAbove` 随时调用；`LineReaderImpl.printAbove` 在锁内清除当前 display、写入消息、`redisplay(false)` 并 flush，因此可作为读取期间异步消息入口。`LineReaderImpl` 固定 bracketed-paste 开关和 begin/end 序列，`beginPaste` 读取至 end；`BindingReader.readStringUntil` 提供该边界读取，Kitty bindings明确列出 Shift-Enter、Ctrl-Enter、Shift-Tab 序列。`DefaultHistory.attach` 关联 reader 并读取 history 文件；`readLine` 用 `UserInterruptException` 表达编辑态 Ctrl+C，并在方法内部进入读取状态后才安装、离开时恢复 terminal signal handler/attributes；`Display` 在 UTF-8 彩色终端可直接写 `Terminal.output()`。`AbstractTerminal.enterRawMode` 清除 `ISIG`/`ICANON`，`NonBlockingInputStream` 支持截止读取，`Signals` 提供 JVM 进程信号注册，`TerminalBuilder.nativeSignals(false)` 允许 Fibra 将进程信号与 terminal handler 分离 | JLine 只提供机制，不关闭调用方在进入 `readLine` 前发布编辑状态产生的信号空窗，也不定义 Fibra 的三路归并、首次信号语义、5 秒截止、退出码、应用 raw input handler、paste 事件边界、换行归一化、无法编码修饰键时的保守语义、renderer 帧契约、输出队列或 Scope 排空顺序；这些都是 Fibra F3/F4 自定契约，并由本项目测试与真实 PTY 门禁证明 |

Codex 最新本地源码 `36f0dbe796d9bb1a18a0fc0640ed08b3e1d54564` 仅作为非契约实现参照：
[event stream](https://github.com/openai/codex/blob/36f0dbe796d9bb1a18a0fc0640ed08b3e1d54564/codex-rs/tui/src/tui/event_stream.rs)
明确 `EventBroker` 复用的是同一 crossterm 输入源，多个 `TuiEventStream` 可以存在但不能同时轮询，否则会
互相偷取输入；[restore_common](https://github.com/openai/codex/blob/36f0dbe796d9bb1a18a0fc0640ed08b3e1d54564/codex-rs/tui/src/tui.rs)
逐项尝试恢复并保留首错。Fibra 只吸收“同一物理输入源单读者”和“逐项恢复”的机制，不把它扩张为进程级
全局 terminal broker；多个不同 terminal 各自拥有独立 `CliSession`/lane，并可共享 `PublishedRuntime`。

## 3. 命令代、准入与中断结论

1. 一次解析及其 help、usage、补全固定使用捕获的 Fibra descriptor、贡献注册身份和 view revision；不得在
   同一次操作中重新读取最新 view。
2. `CommandSpec` 只能作为由上述捕获事实派生、受单次操作或单条 CLI lane 约束的可变解析对象；它不是
   命令代身份，也不得被插件修改或跨线程并发共享。
3. 解析开始不取得 route 租约。只有执行边界以捕获身份/revision 通过 `PublishedRuntime` 准入的
   invocation 才持有租约并参与排空；准入前代变化返回 stale/revoked，既不调用旧 handler，也不转向
   同名新 handler。
4. 普通 REPL `Ctrl+C` 只中断当前行编辑；raw terminal lease 中的 `0x03` 取消持有该 lease 的已准入
   invocation；外部 `SIGINT/SIGTERM` 进入进程级取消/关闭协调。F3 已将三者接入同一幂等协调器，但没有
   混成同一输入入口。
5. 当前 CLI 进程始终拥有它创建的 `CliHost`；外部 `SIGINT/SIGTERM` 均在 signal handler 返回前同步关闭
   准入，再由异步 worker 取消并等待 active invocation 完成，最后关闭该 Host，分别投影 130/0。当前不存在
   attached/shared client，不为假设场景预建 owner/non-owner 抽象。
6. 进程关闭采用 DSH 已证明的“正常完成与信号共用一个 pending、先启动截止再执行 disposer”模式，但
   Fibra 的 5 秒截止覆盖同步取消回调、invocation 排空和 Host close 全链；正常完成先以原子仲裁关闭信号
   准入，已接受信号则独占退出结果。关闭协调器不执行诊断 I/O，且在 `System.exit` 真正返回以前继续保留
   硬截止；shutdown hook 卡住时由 `Runtime.halt` 终止是 Fibra 针对 JVM 的自定增强，不冒充 DSH 直接事实。
   raw terminal 中断由框架在 invocation token 仍可观察时统一投影；只有
   handler 成功或不带其它失败的纯取消异常成为 130，显式非成功状态、业务异常与 `suppressed` 清理失败保留，
   不要求插件手写取消状态。
7. AgentCLI/PaiCLI 和 DSH 的立即取消只提供交互参考。Fibra 已由第 9 节契约测试与发行门禁证明：取消请求
   之后仍等待 invocation Scope、route 租约、远端终态和受管资源按所有权排空。
8. 应用未配置 input handler 时保留命令型 REPL；配置后，每次提交的原始文本在 trim、shell 分词、
   `exit`/`quit` 和命令解析之前进入一个有限 invocation。通用命令代、补全、高亮和命令历史不参与该模式，
   slash、prompt history、Agent/Session journal 属上层产品；应用以显式结果请求继续或退出。DSH 的插件化
   command 只证明产品交互服务可插件化，不直接证明 Fibra 的 raw input 生命周期。
9. raw renderer 把 bracketed-paste 作为一个事件，归一化 `CRLF`/`CR` 为 `LF`；内部换行、`0x03` 和 slash
   均为字面内容。修饰键只在终端明确编码时报告，当前仅冻结 Shift/Control，不能从大写字符推断 Shift。
   这些语义由 JLine 固定序列提供机制、由 Fibra 契约和 PTY 门禁证明，不反向声明为所有传统终端都能区分。

## 4. 产品参照的证据分级

| 外部项目 | 可由来源直接陈述的事实 | 来源等级 | Fibra/产品推导 |
|---|---|---|---|
| DSH/Cordis | 固定源码中的 host/client runner、模块表 evaluator、UI renderer/slot/layout、业务页面插件注册和 effect 所有权 | `c291e7961` 固定源码：[host runner](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/extensions/cordis-host-runner/src/lifecycle.ts)、[client runner](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/extensions/cordis-client-runner/src/client/runtime.ts)、[module evaluator](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/extensions/cordis-client-runner/src/client/evaluator.ts)、[renderer](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-renderer/src/client/registry.ts)、[slots](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-slots/src/index.ts)、[layout](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-layout/src/client/index.ts)、[conversation](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-conversation/src/client/index.ts)、[settings](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-settings/src/client/index.ts)、[tool renderer](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/client/ui-tool/src/client/index.ts) | DSH evaluator 使用模块表与函数注入，不证明 Fibra 的 Blob/native import；是否采用以及如何接入 Fibra `ChangeSet`、PublishedRuntime 与排空由本项目定义 |
| VS Code | 官方文档描述 local/web/remote extension host，并在源码中把 host 类型、running location 与 host manager 分开；Web Host 自己负责 execution-local URI、fetch、origin 与 CSP | [Extension Host](https://code.visualstudio.com/api/advanced-topics/extension-host)、[running location](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/services/extensions/common/extensionRunningLocation.ts)、[extension service](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/services/extensions/common/abstractExtensionService.ts)；既有 Web 资源固定源码链接继续保留历史证据 | 直接支持 `RuntimeId`/manager identity 与 `ExecutionTarget` 分轴；Web loader、URI、fetch、CSP 和 carrier 属产品 execution，不下沉 Fibra runtime |
| Wasmtime Component | `instantiate_pre` 尽可能完成链接与预实例化，但不创建 instance | [官方 `Linker::instantiate_pre`](https://docs.wasmtime.dev/api/wasmtime/component/struct.Linker.html#method.instantiate_pre) | 只支持“静态 generation 可共享、运行 instance 后置”的分层；Fibra 的 candidate/save/promote 和 lease 仍由本项目定义 |
| Terraform Plugin Framework | provider 显式声明 resource/data-source type 与 schema，配置中的每个 resource block 是独立受管对象 | [官方 Framework 概览](https://developer.hashicorp.com/terraform/plugin/framework)、[Schema](https://developer.hashicorp.com/terraform/plugin/framework/handling-data/schemas) | 支持“definition/type 必须显式、desired entry/instance 与 provider binary 分离”的参照；Fibra 不复制 Terraform state/RPC |
| Grafana | 官方文档描述 App Plugin 的页面/UI extension/backend 组成及 backend 启动模型 | [App Plugin](https://grafana.com/developers/plugin-tools/key-concepts/anatomy-of-a-plugin)、[Backend Plugin](https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins)；浮动、非契约 | 跨 facet `ChangeSet`、统一准入和排空是本项目契约 |
| Eclipse Theia | 官方文档区分运行时插件与编译期扩展及其运行位置 | [扩展模型](https://theia-ide.org/docs/extensions/)；浮动、非契约 | client execution session、desired/observed 协调和唯一控制面是本项目契约 |
| OpenAI Codex app-server | 核心 crate 定义 app-server protocol 类型并导出 TypeScript/JSON Schema；同一 server 可由 stdio 进程入口或 in-process transport 驱动 | `0.154.0`，tag commit `6b9826e3aa83b1a5947db50f4332cb9c65f1b340`：[protocol exports](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server-protocol/src/lib.rs)、[common protocol types](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server-protocol/src/protocol/common.rs)、[in-process transport](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server/src/in_process.rs)、[CLI process entry](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/cli/src/main.rs) | 只支持“核心拥有协议与多语言导出、进程内外复用协议语义”的取舍；不证明 Fibra 的 revision 围栏、插件生命周期、`ChangeSet`、Agent/Session 协议或资源排空 |
| OCI Image/Distribution specs | descriptor 用 `digest`、`size`、`mediaType` 描述内容；distribution 按 digest 拉取 blob 并要求消费方核对内容摘要 | Image spec 固定提交 `af26a05fba5ee648512f4ea3c9fda1fcc1b6d6dc`：[Descriptor](https://github.com/opencontainers/image-spec/blob/af26a05fba5ee648512f4ea3c9fda1fcc1b6d6dc/descriptor.md#L4-L42)；Distribution spec `v1.1.1`、提交 `a139cc423184af6078077b9b7ee336eddbd03f8f`：[pulling blobs](https://github.com/opencontainers/distribution-spec/blob/a139cc423184af6078077b9b7ee336eddbd03f8f/spec.md#pulling-blobs) | Fibra 只借鉴内容 descriptor、digest 寻址与消费端校验，不声明 OCI 兼容，不复制 registry API，也不把 OCI 可选 `urls/data` 带入 lifecycle control snapshot |
| Web Platform | WHATWG URL/Fetch、Web Crypto digest、File API object URL 和 HTML module loader 是浏览器原生机制；object URL 的执行受页面 CSP 约束 | Living Standards：[URL](https://url.spec.whatwg.org/)、[Fetch](https://fetch.spec.whatwg.org/)、[Web Crypto digest](https://w3c.github.io/webcrypto/#SubtleCrypto-method-digest)、[File API object URL](https://w3c.github.io/FileAPI/#dfn-createObjectURL)、[HTML modules](https://html.spec.whatwg.org/multipage/webappapis.html#integration-with-the-javascript-module-system) | 这些机制由产品浏览器 runtime 选择和验证；Fibra 只发布 transport-neutral protocol/API，不以 Playwright/Chromium 或某种 CSP 作为 core release gate |

保留 VS Code、Grafana、Theia 三组文档链接的理由是用作术语和设计背景；只有 VS Code 上表固定提交列出的
资源转换事实参与本次资源边界取舍，其余浮动页面不建立 Fibra 契约或验收断言；
若未来要把其中行为升级为契约依据，必须先固定发布版本或源码提交并新增对应证据。

### 4.1 Client protocol 数值编码参照

| 来源 | 可直接陈述的事实 | Fibra 取舍 |
|---|---|---|
| [RFC 8259 第 6 节](https://www.rfc-editor.org/rfc/rfc8259#section-6) | JSON 可以表达更大或更精确的数，但基于 IEEE 754 binary64 的实现只在 `[-(2^53)+1, (2^53)-1]` 整数范围内能保证数值精确一致 | 不把 JSON number 的语法能力误当作跨 Java/浏览器的精确数值契约 |
| [I-JSON RFC 7493 第 2.2 节](https://www.rfc-editor.org/rfc/rfc7493#section-2.2) | 需要精确交换、但超过 IEEE 754 精度或范围的数建议编码为 JSON string，并以 64 位整数为例 | 精确 `BigDecimal` 与 64 位围栏不使用原生 number token |
| [ProtoJSON 格式](https://protobuf.dev/programming-guides/json/#representation-of-each-type) | `int64`/`uint64`/`fixed64` 默认输出十进制字符串，以避免被 double 或 JavaScript number 处理时丢失精度 | 64 位 revision/注册身份在 wire 上用规范十进制字符串；不复制 ProtoJSON 同时接受 number 的兼容分支 |
| [GraphQL ID](https://spec.graphql.org/October2021/#sec-ID) | ID 即使经常由数字构成，也始终序列化为字符串，因为它是不透明身份而非可计算量 | client 只保持和比较 revision/注册身份，不对它们做算术 |
| [Google `Decimal`](https://github.com/googleapis/googleapis/blob/master/google/type/decimal.proto) | 任意精度十进制值以字符串承载，再由各语言转换为 `BigDecimal`/`Decimal` 等本地类型 | `LiteralValue.NumberValue` 的精确十进制内容在 wire 上以规范字符串承载 |
| [MongoDB Extended JSON v2](https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/) | 通用 JSON 容器用 `$numberLong`/`$numberDecimal`/`$numberDouble` 等 tag 加字符串的规范形式保留数值类型和精度 | Fibra 不复制 BSON 类型系统；只为已有 `NumberValue(BigDecimal)` 定义 NUMBER tag，并包裹 ObjectValue 消除 tag 形状冲突 |

上表只证明跨语言协议将“不透明身份、64 位整数、任意精度十进制数”与普通可计算 number 分类的成熟做法。
Fibra 的单一规范 wire 形式、范围与错误码仍由 Client Foundation 规格和本项目测试自定，不宣称由上述项目直接证明。
其中 tagged decimal 的 1000 字符上限是 Fibra 为对齐 JSON parser 现有 number 工作量预算而定的安全边界，不是外部标准宣称的通用值。

## 5. 本次审计记录

| 级别 | 问题 | 处理 |
|---|---|---|
| 审计 P0 | vNext 总状态把第 1–10 节完成与第 11 节路线混成整体完成 | 已拆分状态，并明确现有 CLI/ZIP 不证明 F1–F4 |
| 审计 P0 | 当时 vNext 与产品架构各有一套 P0–P8 阶段表且顺序冲突 | 已删除 vNext 阶段表；该历史结论已由 2026-09-15 Client Foundation 规格更新：Fibra P0 定义 foundation，产品业务路线由独立产品仓维护 |
| 审计 P1 | 解析期被描述为持有旧 route 并参与排空 | 已改为仅 PublishedRuntime 准入后的 invocation 持有租约；准入前代变化返回 stale/revoked |
| 审计 P1 | Picocli `CommandSpec` 被暗示为不可变命令代 | 已把不可变身份限定为 Fibra descriptor、贡献身份和 revision，并记录 Picocli mutator 证据 |
| 审计 P1 | 普通 Ctrl+C、raw `0x03` 与外部信号没有分开 | 已定义三类入口和共同的幂等取消/排空协调边界 |
| 审计 P1 | AgentCLI/PaiCLI 的立即取消可能被误作 Scope 排空 | 已明确只采纳交互模式，不采纳排空保证 |
| 审计 P1 | VS Code/Grafana/Theia 的外部事实与项目推导混栏 | 已分栏；唯一管理面、跨 facet ChangeSet、desired/observed 均标为项目自定契约 |
| 审计 P2 | 当前架构文档仍沿用 DSH `0.1.2-rc.1` 旧基线 | 已按维护者确认切换到 `0.1.5-rc.2`/`c291e7961`；旧提交仅保留历史证据 |
| 审计 P2 | VS Code/Grafana/Theia 链接未固定版本 | 接受：已明确降级为非契约灵感；任何契约断言不得依赖其浮动内容 |
| 审计 P1 | Client Foundation 引用 Codex app-server，但审计中没有对应固定证据 | 已补 `0.154.0` 固定源码，只支持协议所有权、多语言导出和进程内外 transport 复用语义，不把 Fibra 自定生命周期契约归因于 Codex |
| 历史审计 P1 | client snapshot 把 raw URL/inline bytes 混入控制面，URI 校验被迫在 Java 与浏览器间追求错误的一致实现，且交付地址会污染 target 内容身份与 digest cache | 稳定 `ResourceDescriptor` 与 digest 仍保留为协议契约；2026-09-17 已撤回 Fibra `ClientResourceProvider` 实现，授权资源 gateway、URL/HTTP/IPC 和 cache/loader 均由产品 runtime 持有。固定 VS Code execution-local 资源转换与 OCI descriptor/digest 只作为边界参照 |
| 历史审计 P0 | `blob:` 动态 import 被误当成完整模块依赖系统，未约束相对/bare import，React probe 也可能隐式依赖共享 React；同时未声明 object URL 所需 CSP | 2026-09-16 曾冻结自包含 ESM/CSP；2026-09-17 已随 browser runtime 回归产品仓而撤出 Fibra 当前契约，保留为产品实现参考 |
| 历史审计 P0 | verified bytes cache 在 core/Web 两处表述不同，可能先缓存未校验 provider bytes | 2026-09-16 曾定义 Fibra Web cache；2026-09-17 已撤出 Fibra 正式实现，digest/descriptor 仍属 protocol，cache/loader 由产品 runtime 负责 |
| 审计 P1 | framework-neutral 门禁只搜索 `react/document/window` 文本，无法证明 package 依赖图与 TypeScript lib 没泄漏 | 增加不含 DOM lib 的 core 编译门禁和 package/workspace 依赖图检查；文本搜索仅作为补充信号 |
| 历史审计 P1 | P0-A 只验证隔离 harness，却被命名为整体风险门，真实 Engine 状态机直到外围迁移后才碰撞 | 2026-09-16 曾拆为旧 P0-B 三段式；2026-09-17 已由 compile-only、真实 Engine、重启恢复、发行/独立消费四门 P0-A–P0-D 与新 Task 7–13 替代 |
| 历史审计 P1 | client foundation 下沉只由架构自证，未说明为何不等待第二个外部产品消费者 | 当时只补了文字理由，没有提供真实跨仓消费者或 walking skeleton；2026-09-17 复审据此撤回 browser runtime 下沉，保留通用 SPI/protocol |
| 审计 P2 | 对象模型字段与 `fibra-package.yaml` 短 key 没有显式映射 | 已补 `id/runtime/target/capabilities` 到完整对象字段的映射，并明确 `format` 与计算所得 digest 的边界 |
| 历史审计 P1 | P0 非目标被统一写成“后续由产品阶段进入”，混淆 Fibra 平台欠账与上层产品职责 | 旧第 14 节成熟度账本已删除；当前职责边界、重新冻结门和既有成果处置分别以 Client Foundation 第 1、12、14 节为准 |
| F2 P2 | F2 当时的自动化宿主没有可受控的真实终端模拟器；`script`/`expect` PTY 缺少 JLine 终端能力协商响应 | 已由 F4 仓外原生 xterm PTY 门禁关闭：直接验证 history 重启、补全、高亮、32x10 窄终端、resize/redisplay、renderer、失败/取消恢复和应用原始输入；不反向改写 F2 当时的阶段证据 |
| 历史架构替代 | 2026-09-15 曾把 host client adapter、浏览器 runner、Web loader 和 renderer 下沉 Fibra | 2026-09-17 核心可执行性复审已撤回该替代：Fibra 保留逻辑包、统一 RuntimeDriver SPI、协议与执行协调；产品拥有具体 client runtime、浏览器 runner、transport、carrier 和 renderer |

### 5.1 2026-09-17 核心可执行性复审

本轮由三路独立只读审计分别覆盖 Task 6–10 契约链、Task 11–13 最终交付链和反向失败场景；主线程再以
实际代码、Git blame、历史线程和外部源码交叉核对。共同确认此前“架构已冻结、无 P0/P1”的结论无效，原因
不是 Task 9 实现偏离，而是设计本身存在以下断链：

1. `ExecutionRuntime.id()` 使用 `ExecutionTarget`，使同为 `host` target 的 Java 与 Node 无法同时注册；
2. 架构要求 Host 消费 Java 私有 prepared artifact，同时又禁止 runtime 模块依赖；公共 SPI 无法传递
   ClassLoader、definition 和 binder；
3. 保存前 compile 缺少 configContext/evaluated desired，且 Java `definition()` 实际会执行受信代码，
   “完全纯 compile”无法完成配置绑定与校验；
4. `ReplaceConfigContext` 可以改变实例而不改变 target digest/revision，同一 target 身份对应多个有效运行态；
5. `ExecutionUpdate` 没有 commit/ownership 状态，`ExecutionHandle` 只给 ID 快照，无法证明旧 execution、
   invocation 和 client resource read 退出前制品仍被租用；
6. 全局 facet DAG 在 execution 分区后丢失，跨 runtime 的依赖优先启动和反依赖停止不可表达；
7. wire kind 字符串无法解析到 `PublishedRuntime` 使用的同一 `ContributionKind`/codec；
8. composition root、真实 Host 重启、发行清单、npm 发布与 API baseline 没有落实到完整阶段门；
9. Task 7 的契约冻结早于第一条真实 Java → Host → Engine walking skeleton；Task 8 又刻意只验证静态
   prepare，因此二者都不能证明最终组合可执行；
10. 2026-09-15 把已经确认的产品侧 browser runtime 边界反向下沉 Fibra，但没有新增真实消费者或
    跨仓证据支撑。
11. 新 `PluginDefinitionRef` 虽改成三段完整身份，设计却没有规定 runtime descriptor 如何显式产出
    `definitionId`；旧 Node adapter 继续以 artifactId 暗中充当 definitionId，完整引用在新 driver 中无法验证；
12. `ExecutionUnitKey` 的基数没有落到 desired entry，首版 walking skeleton 直接以 artifactId 建 unit；同一
    facet 被两个 entry 引用时会错误合并 config、instance、self-disable 和生命周期；
13. `BuiltInPluginPackage` 仍让 Engine 持有匿名 `PluginCatalog`，compiler 又把 built-in facet 排除于 runtime
    slice，实际绕过了“Java driver 唯一拥有 definition 与执行”的核心约束；
14. provider 没有 `contractIdentity`，`compiledFingerprint` 无法观察 runtime 契约或内建声明变化；即使
    capability 逻辑正确，Host/runtime 升级仍可能错误走同 target no-op。
15. unit 依赖从 facet 展开为“该 facet 全部 active entries”后，新增或删除同 facet 第二个 entry 会改变既有
    dependent unit 的依赖集合；只沿旧 unit DAG 做 reverse closure 会错误 retained 旧 plan；
16. 静态资源共享只写了同一 candidate 内 lease，没有冻结跨 attempt 的 Java wiring 复用；局部替换 A 而保留
    B 时若重建公共依赖 C 的 loader，会因 `ServiceKey<T>` 包含 `Class<T>` 而分裂类型身份；
17. built-in 已改为 provider 私有 definition，但重启门只覆盖动态 package 缺失/损坏，尚未证明持久 built-in
    digest 与新进程 provider metadata/私有 definitions 严格匹配。
18. 首版纯 metadata `BuiltInPluginPackage` 仍把 package 压成单 facet，并按 pluginId 禁止第二个 facet；同时
    没有 built-in facet dependencies/capabilities，实际无法做到“与动态 facet 相同的全局编译”。
19. Task 9 的 `closeAdmission()` 只在 Java/Node unit 内改布尔值，没有同步撤销 `ContributionDirectory`
    真实 route；Engine 的 probe test 只记录了 `admission:*` 事件，因而在生命周期顺序正确时仍允许 retiring
    handler 被最新 view 调用；
20. 新 Java driver 能 mount 插件，却没有把 Engine 的 registrar 作为 unit-owned service 发布到实例 Scope；
    既有插件真实调用 `context.services().require(ContributionServices.REGISTRAR)` 时才会暴露断链；
21. Node 测试只覆盖受控 stop/start failure，没有覆盖 ACTIVE sidecar 异常退出；sidecar 私有 failure 不会自动
    改写 unit observed，Engine 又只会原地 reconcile PENDING，于是死亡进程可能永久显示 ACTIVE；
22. 生命周期 fence 已改为 `unitTargetRevision`，但 Assignment 仍只有 runtimeInstanceId，新的 external session
    只能看到 snapshot 全局 targetRevision，无法为 retained unit 重建精确 tuple；
23. 重新设计 composition root 时遗漏了已存在的 `HostServiceRegistry` 构造面；Spring bridge 虽能收集 Bean，
    新 Engine 却未冻结到长期 RuntimeDomain，保留原测试会变成未接线的假能力。
24. entry-keyed unit 已要求同一 facet 的不同 entry 拥有独立 resolved config，但 client `Assignment` 仍只有
    facet/runtime/resource 身份，没有 `desiredEntryId`、`definitionId` 和 config；产品侧 external runtime 不能仅靠
    正式协议重建两个不同配置的实例，现有 fixture 又没有读取 `resolvedConfig()`，因此门禁仍在用 facet 代替 unit。
25. 自停用只写成了“插件可以 disable”，没有冻结这是持久 desired 命令还是 availability reconcile，也没有
    定义迟到请求的精确围栏、保存失败行为和修改范围；结果 Java 没有接线，Node 又把 `fibra.disable` 误接到
    `requestReconcile`。最终契约必须是 `RuntimeUnitFence + reason`，由 Engine 基于当前 durable target 构造仅
    关闭该 entry 的完整 replacement。
26. observed 被当成 reconcile 的返回值，没有设计 ACTIVE 后插件内部状态变化如何进入 PublishedView；Java
    driver 因而缓存启动结果，Engine 发布时又可能为 view 与 `targetSatisfied` 二次采样。最终增加只发布事实的
    `requestObservationRefresh(RuntimeUnitFence)`，并要求每次 publish 对每个 unit 单次冻结采样。
27. Java per-unit registrar/control 的基数只写了“注入 service”，没有检查 `ServiceKey` 的 realm/cardinality。
    第二个 Java unit 会在默认 realm 重复提供同一 Host service；正确做法是只把 registrar/control 派生到同一
    unit-local realm，业务 services 继续留在正常 realm 以支持跨 unit 解析。
28. 重启条款把核心可验证的 view/runtime fence、产品侧 session/resource gateway 和 store 内部
    save-unconfirmed 窗口写进一个 Host fixture，缺少明确 seam 与 oracle；同时误把稳定 `ContributionId` 和可
    重复计数的 registration 数值当成必须跨 Host 变化的临时身份。最终证据拆为 Engine/store 故障注入、核心
    两 Host 进程门和产品 runtime/gateway 三层，并以完整
   `(viewRevision, ContributionId, registrationIdentity)` 而不是单字段判断调用准入。
29. Java driver 又把每代新分配的 `runtimeInstanceId` 传给 `PluginInstance.id()`；现有工具插件从当前
    `PluginInstance.id()` 派生 contribution provider，导致发布视图泄漏临时 execution 身份，CLI 仍按稳定
    desired entry id 调用时全部失效，也与“重启后 `ContributionId` 业务身份保持”的契约自相矛盾。根因是设计
    只列出了各类身份，却没有逐字段冻结谁可进入 publication。最终规定 `PluginInstance.id()` 与
    `ContributionId.providerInstanceId` 都取 desired entry id，`runtimeInstanceId` 只进入 unit fence/observed，
    `PluginInstance.identity()` 只作进程内对象身份。
30. 重写 Engine 时把旧实现已经验证过的启动协调退回为永久 `Mono<PublishedView>.cache()`，同时删除了并发
    启动精确视图和首份 descriptor loader 回收回归。结果首次 view 及原始失败图被 Engine 长期持有；简单改成
    `then().cache().then(current)` 又会重现已由 `98ccd53` 证明的排队 replacement 竞态。根因不是新方向要求
    改变，而是重构清单只迁移了类型/API，没有把既有资源与并发不变量列入 replacement ledger。最终恢复为
    启动期间共享精确 bootstrap 结果，完成后动态切换到 current view，失败后只保留文字事实。
31. Java/Node 新 generation 把 `PreparedUnit/Prepared` 永久设为 final；即使 lease 已成功关闭，已结束 unit 仍可
    通过共享 generation 或 command lane 的最后一次 inner subscription 持有 bound definition、ClassLoader、
    payload 和 resolved config。旧设计其实已明确“完成对象只留 metadata”，但第二轮计划只写了 close 次数与
    lease 计数，没有保留强引用/GC oracle。最终要求成功释放后显式断开私有对象，失败时才保留完整现场。
32. Node contribution 用普通 `sidecar.request()` 返回值承接调用，却没有把底层 `NodeRpcChannel.Request` 登记为
    invocation Scope 的 `DrainingDisposable`。下游取消会让 PublishedRuntime 的 usingWhen 立即完成本地清理并
    释放 route lease，而远端请求仍在取消宽限内执行，unit 因而提前进入 `STOPPING`。此前设计文档写过“远端
    终态前必须排空”，但测试只覆盖 Node RPC 自身取消和目录本地 lease，缺少一条跨 Scope→route→RPC 的所有权
    链。最终由调用 Scope 持有 request，subscriber cancel 只触发远端取消，远端终态后才释放 route lease。

审查方法上的根因是“证据范围错配”：负向依赖测试、反射签名、文件分类、前端技术可行性和局部绿色测试被
扩大为最终架构证据；没有逐场景跑通 package publish → target compile → candidate prepare → save → promote
→ reconcile → drain/stop/retire → restart。重新冻结必须以真实类型 walking skeleton、失败矩阵、重启和
发行物为证据，不能再以“未发现问题”替代正向证明。

第二次遗漏进一步说明问题不只在“有没有 walking skeleton”，还在测试数据是否具有判别力：首版组合测试用
空 desired graph，只证明三个 runtime 可以各产出一个 artifact-keyed 空 binding plan，完全没有经过
definition 解析、同 facet 多实例、built-in 恢复或 runtime contract 升级。审查者沿用了测试中的 artifact=unit
假设，又只核对 SPI shape 和生命周期阶段，因此在文档互相一致时仍没验证业务基数。后续门禁必须至少包含
同 facet 两个 entry、缺失/错误 definitionId、built-in 与动态 facet 同图、contractIdentity 变化四个反例。

Task 10 前置审查继续确认了同一种证据错配：Task 9 的 fake unit 只能证明 Engine 调用过一个名为
`closeAdmission` 的方法，不能证明真实目录 route 已关；Java/Node driver 测试使用空 contribution descriptor，
不能证明 registrar/codec/gateway 接通；Node 测试只有主动 stop，没有失控退出；protocol 测试分别验证 snapshot
和 lifecycle fence，却没有让一个 retained assignment 从新 session 重建资源 tuple；Spring 被推迟到下游迁移，
因此 Host service 构造面没有进入核心评审输入。也就是说，之前设计的问题不是实现时偶然漏线，而是设计评审
没有为“真实 route、真实插件注册、异常进程、新会话恢复、真实 composition root”各准备一个能证伪的纵向场景。
这些场景现已加入 Task 10/11，完成前不得再次宣称设计冻结。

这也解释了为什么第二轮评审已经说“主方向正确”，仍会继续发现问题：评审把架构层方向稳定误写成契约层
完成。单一 `RuntimeDriver`、entry-keyed unit、durable target 和产品侧 browser runtime 这四个方向没有被新
发现推翻；但方向正确并不自动给出命令/事件语义、身份基数、失败行为和可执行证据。后续状态不得再使用笼统的
“设计无需修改”，而必须逐项标注 `Specified → Executable → RED → Green → Integrated → Restarted → Released`，
并为每项维护“事实 → owner → 刺激 → seam → oracle → 测试 → 命令 → 状态”链路。

对修订后 external/client 契约的再次反向审查又暴露第 24 项：审查已经接受 `unit = desired entry`，却只把
`unitTargetRevision` 补进 Assignment，没有逐字段追问“一个全新产品 session 仅凭正式 snapshot 是否拥有启动该
unit 的全部输入”。这使 Java/Node 的 entry-keyed 实现与正式 client 协议发生语义分叉。重新冻结要求 Assignment
同时携带 `desiredEntryId + definitionId + resolved config`，并以同 facet 两 entry、不同配置、新 session 重建的
跨语言门禁证明；不得让产品私下补字段或把配置烘焙进资源。

随后对 entry-keyed 修订进行的独立反向审查又发现 2 个 P1 和 1 个 P2：旧/新展开依赖边必须共同参与
affected closure；Java/Node 静态 generation 必须按精确 wiring identity 跨 attempt 复用；built-in 必须进入
同 digest 成功、旧 digest/声明不匹配失败且 target 不变的重启矩阵。三项均已写入权威架构与 Task 8/9/11
门禁，未推翻单一 `RuntimeDriver`、entry-keyed unit 或产品侧 browser runtime 边界。

Engine 实现走查又把第 18 项显式化：最终 built-in 类型改为 package identity + 多 facet metadata；P0 要求一份
built-in package 的全部 facets 属于同一 provider/runtime，不引入跨 provider fragment 合并。disabled package
gate 下 dormant entry 不触发 definition prepare，重新 enable 时再严格校验，避免损坏插件无法先安全停用。

本轮最终取舍由权威架构记录：统一为单一 `RuntimeDriver` owner；完整 configContext 进入
`DeploymentTarget`；Java/Node 各自拥有制品与执行；browser/client 实现回到产品仓；Fibra 只保留通用 SPI、
协议和不发布的 conformance fixture。

### 5.2 2026-09-19 最终架构实施复审

本轮不是在既有实现上补兼容，而是从最终 owner、identity、cardinality、lifecycle 和证据链重新审计。复审
确认单一 `RuntimeDriver`、完整 durable target、entry-keyed unit 和产品侧 browser runtime 四个方向正确，
同时发现“方向正确”仍不足以冻结以下签名级语义：

1. `RuntimeProvider` 先前只被描述为“创建 driver”，没有规定能否跨 Host 复用、是否可持有 services/driver、
   谁关闭 driver，以及一次创建失败后能否重试。最终冻结为不可变可复用 factory；每次顺序 create 返回新的
   Host-bound driver，Engine 独占关闭权，并覆盖两个 Host、构造失败清理与后续重试。
2. capability 曾作为 `RuntimeHostServices.capabilities()` 的 live 查询暴露，允许 current generation 随环境
   漂移。最终只由 Engine 每次编译捕获到 `RuntimeTargetSlice`；key presence 表示可用，value 只作描述；active
   unit 校验其传递静态 facet 闭包，失败 attempt 不改写 current 的冻结观察。
3. `requestReconcile(runtimeId, unitKeys, reason)` 缺少 unit 代次身份，也把外部连接的一次批量故障拆成多个
   部署。最终改为不可拆分 `Set<RuntimeUnitFence>`，逐项拒绝 missing/wrong/stale/retired fence；FAILED 闭包
   原子 replacement，仍匹配的非失败 units 随后按同一 DAG 唤醒。
4. `ClientModule` 只有生命周期 shape，没有把 Assignment 的身份/config 与模块实例创建绑定，产品 runner 仍
   可能引入全局 registry 或跨 session 缓存。最终冻结 `ClientEntryModule.definitions[]`、
   `ClientModuleDefinition.create(ClientInstanceContext)` 和无参 lifecycle；protocol conformance 从正式
   `host.snapshot` 只使用公开字段，在新 session 重建两个独立 entry 实例。
5. Java unit 首版对任何首次观察到的 FAILED 都请求 replacement；确定性启动失败会在同一 durable target 上
   无限重建。最终只有曾观察 ACTIVE 的实例后来 FAILED 才关闭准入并请求一次 replacement；未到 ACTIVE 的
   启动失败只发布 FAILED，等待显式新 target/reconcile 决策。

此前设计和复审没有提前发现这些问题，原因不是缺少更多接口清单，而是验证方法存在五个系统性偏差：

- 在真实 Java→Host→Engine walking skeleton 之前冻结公共契约，先证明了类型可组合，却没有证明状态可运行；
- 用负向依赖、反射签名、fake/空 graph 和手工 codec round-trip 代替端到端正向证据；
- 只审查单个正常路径，没有沿 `事实 → owner → 刺激 → seam → oracle` 逐身份检查失败、重启与并发；
- 重构清单迁移了新类型，却没有建立旧实现已验证不变量的 replacement ledger，导致资源强引用、启动协调等
  既有保证在重写时丢失；
- 把局部 Green、架构方向稳定和 Released 混写成“完成/无需修改”，使仍缺重启、独立消费和制品证据的任务
  过早关闭。

因此当前计划只允许 `Specified → Executable → RED → Green → Integrated → Restarted → Released`，并把
external RuntimeProvider fixture、client API/protocol conformance、真实 Host、重启和发行物分别作为不同
oracle；任何一层都不能冒充另一层。Task 13 的最终结论必须基于最终工作树同炉门禁与新的独立复审，不能引用
本节文字代替执行证据。

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

2026-09-16 第三轮独立只读复审发现 Web 入口缺少模块闭包/CSP、cache 所有权歧义及 core 边界门禁不足；
对应问题已经回填到第 5 节，并同步修正 Client Foundation 架构和计划。固定 Git ref 已确认 VS Code Docs
`81d76c22e9a4f5c63733c38e4ccedaf8c9f051bf` 与 OCI Distribution `v1.1.1` peeled commit
`a139cc423184af6078077b9b7ee336eddbd03f8f`；本轮新增的 VS Code Docs、VS Code CSP 与 OCI 源码链接均返回
HTTP 200。P0 实现完成后的最终架构/代码复审仍由计划 Task 13 承担，不能由本次文档复审抵扣。

2026-09-17 在撤回旧冻结结论后，重新进行了架构状态机、实施计划可执行性和跨文档一致性三路独立复审。
首轮暴露了 seal 后无法回收、plan-affecting capability 变化漏重编译、retained external unit revision 围栏、
Engine/bridge 反向依赖、Task 前沿循环和 Host fail-stop 无退出端口等 P0/P1；权威架构与计划据此改为
`abortAsync`、全量 `requestRecompile`、`unitTargetRevision`、Engine-owned `RemoteContributionInvoker`、
`HostTerminationPort` 独立 notification lane 和专用 verification modules。当时最终轮报告“无未关闭
P0/P1/P2”，但本次真实 driver 实现已证伪该结论：definition/unit 基数、Node definition 来源、built-in owner
和 runtime contract identity 四项仍未闭合。该轮结论撤回，不能再作为当前设计已闭环的证据；只有修订后的
同 facet 多 entry walking skeleton、真实 Engine、重启和发行门全部通过并再次独立复审，Task 7–13 才可关闭。

## 7. F2 独立只读复审

F2 复审依次发现并关闭：bootstrap descriptor 未参与历史敏感识别、Picocli 短选项附着值遗漏、
解析/handler 诊断可能回显敏感值、history 保存失败提前返回而未关闭 terminal，以及敏感值以 `-` 开头时
未进入诊断脱敏集合。对应回归覆盖 bootstrap 与动态 descriptor、附着值、分离且以 `-` 开头的值、解析失败、
handler 失败和 terminal 关闭；复审同时核对 DSH/Codex 固定源码与证据边界。

F2 阶段独立只读复审结论为无未关闭 F2 P0/P1；当时保留的 P2 是 history、补全、高亮、窄终端与重启
尚未在直接 TTY 复验。该历史结论不反向改写；F4 已用仓外原生 xterm PTY 自动门禁补齐并关闭此项，同时
验证 resize、redisplay 和 renderer。

## 8. F3 独立只读复审

F3 独立复审共分三次：首轮检查初始实现，第二轮复核修正，最终轮只读确认关闭。复审未发现 P0；发现的
5 项 P1 均先补确定性失败测试再修复：

| 级别 | 复审发现 | 关闭证据 |
|---|---|---|
| F3 P1 | 5 秒截止未覆盖同步取消回调与阻塞 Host close | `CliProcessShutdownTest` 分别阻塞取消 subscriber 与 Host close，证明独立计时器从首次信号开始覆盖全链并强制投影 8 |
| F3 P1 | 正常完成可能在检查信号后覆盖并发到达的信号结果 | `tryCompleteNormally` 与 `interrupt` 共用原子仲裁；测试证明正常完成先胜时关闭后续信号准入，信号先胜时主线程等待同一退出结果 |
| F3 P1 | raw handler 需要手写 `CANCELLED`，框架可能看不到 token | 动态与 bootstrap handler 只传播 `InterruptedIOException`；框架在 invocation `finally` 以前统一投影纯取消，发行 fixture 不再返回手写状态 |
| F3 P1 | 已接受信号到异步 worker 真正运行之间仍可准入新 invocation | signal handler 内同步执行 `stopAdmission`，潜在阻塞取消留在异步 worker；排队但不执行的 Executor 测试证明 `interrupt` 返回前准入已关闭 |
| F3 P1 | token 已取消时会覆盖显式错误状态，且忽略 `suppressed` 清理失败 | 仅“成功 + 已取消”或无其它失败的纯取消异常投影 130；4/7 显式状态、独立业务失败与 try-with-resources `suppressed` 清理失败回归均保持错误 |

最终独立只读复审结论：无未关闭 F3 P0/P1，无新增 P2，可以提交 F3。复审核对 CLI 100 项测试全绿、
`git diff --check` 与发行脚本语法通过。F2 当时保留的桌面 history/补全/高亮/窄终端复验，以及包含最新
F3/F4 改动的空 Maven 仓、五类消费者和 archetype 全套隔离门禁，后来均由 F4 的独立证据补齐；不由 F3
阶段的 xterm 中断实测或更早空仓结果抵扣。

## 9. F4 证据与独立只读复审

F4 以 JLine 4.4.3 的终端机制、Codex CLI 0.154.0 的单事件循环与重绘模式、DSH 0.1.5-rc.2 的产品交互
插件边界为固定参照；`CliSession` 所有权、应用原始输入、事件归一化、renderer 帧、PublishedRuntime
准入和 Scope/route/远端资源排空仍为 Fibra 自定契约。实现与直接证据统一记录在
[行为验收账本第 10 节](2026-09-11-behavior-verification-ledger.md#10-f4-cli-框架冻结与最终交付证据)。

F4 独立只读复审共四轮。前三轮发现并关闭：进程截止未覆盖同步取消/阻塞退出、输出生命周期锁与 terminal
lane 互等、恢复失败未传播、JLine handler 安装前唤醒空窗、输出模式交接遗漏、恢复步骤未全部尝试、终端
失败未封锁再次 acquire，以及重复关闭复用同一异常对象等 P1/P2。最终轮又复核了两个不同 terminal 会话
共享同一 `PublishedRuntime` 的并行执行边界，确认 Codex `EventBroker` 没有被误写为进程级全局 broker。

最终独立只读复审结论：无未关闭 P0/P1/P2。审阅者实跑
`CliInvocationCoordinatorTest`、`CliProcessShutdownTest`、`CliProcessSignalHandlersTest`、
`CliTerminalControllerTest` 与 `CliSessionTest` 共 50 项，全部通过；另确认 `git diff --check` 通过。最终
`CliProcessShutdownTest` 直接证明 Host close 失败后 `System.exit` 阻塞仍由独立截止调用 `Runtime.halt`，且
强制退出回调返回前不会提前发布结果；`CliSessionTest` 证明两个 terminal lane 并行调用同一 runtime、关闭
A 后 B 保持可用且输出不串流。Codex 最新 `36f0dbe` 只作为固定提交的非契约实现参照，未替换
`0.154.0`/`6b9826e3` 契约基线。

复审后的最终源码投影已在临时全新 checkout 重新通过根 50 模块 `clean verify`、发行 ZIP 仓外验收和三轮
可复现制品门禁；CLI 137 项测试全部通过。此前 F4 空 Maven 仓门禁覆盖冻结后的 27 个正式制品、依赖坐标、
发行脚本、五类消费者、archetype 与两组真实 PTY；最终审查修正未改变这些发行输入，因此按用户明确要求
不重复下载空仓。仓库内 Markdown 相对链接全部可解析，新增 Codex 固定源码链接返回 HTTP 200。
