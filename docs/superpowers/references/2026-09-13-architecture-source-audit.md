# 后续架构真源与外部参考审计

首次建立：2026-09-13。最近复核：2026-09-16。

本文件只记录架构真源映射、固定外部证据、证据等级和本次文档审计结果，不定义新的产品架构、阶段顺序
或实施计划。

## 1. 唯一真源与状态

| 范围 | 唯一真源 | 当前状态 | 不得误用的证据 |
|---|---|---|---|
| Fibra vNext 已交付底座 | [vNext 架构第 1–10 节](../specs/2026-09-07-fibra-vnext-architecture.md) | 已完成；其中 client foundation 后续演进不再由该文定义 | 不能由历史部分绿色构建替代最终账本 |
| Fibra CLI 演进 | [vNext 架构第 11 节 F1–F4](../specs/2026-09-07-fibra-vnext-architecture.md) | F1–F4 已完成 | F1 之前的固定 CLI、REPL 与 ZIP 只属于第 1–10 节，不能抵扣 F1；每个阶段只由该阶段新增契约和直接验收事实证明 |
| Fibra client foundation 与跨执行域插件模型 | [Client Foundation 最终架构](../specs/2026-09-15-fibra-client-foundation-architecture.md) | P0-A/P0-B1–B3 均未通过；Task 2 边界已建立，Task 3/4 现有代码待按最终契约重构 | 不能以产品仓库的 adapter、runner 或传输草案替代 Fibra 协议、唯一 target 或 revision 围栏 |
| 上层 Agent 产品业务路线 | [CLI + Desktop Agent 产品架构](../specs/2026-09-13-fibra-based-agent-product-architecture.md) | 产品业务阶段尚未实施；其 client foundation 前置以 2026-09-15 规格为准 | vNext 不再保存第二套产品阶段表；其它文档不能重排或重定义产品业务路线 |

后续架构的 DSH 契约统一固定为 `@deepseek-ai/dsh 0.1.5-rc.2`、提交
`c291e7961a515f6d7af9304e7fd1d257929aef26`。旧提交 `b0a7d2c` 与 `a66e470` 只保留其历史对拍价值，
不得继续作为 F1 或产品阶段的架构契约真源。

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
| VS Code | 官方文档描述 local/web/remote extension host、web extension 入口以及浏览器代码必须打成单文件；固定源码中 Web Extension Host 先让主线程把逻辑 URI 转成 execution-local browser URI，再在 worker fetch，origin/CSP 由 Web Host 约束 | [Extension Host](https://code.visualstudio.com/api/advanced-topics/extension-host)；Web Extensions 文档固定提交 `81d76c22e9a4f5c63733c38e4ccedaf8c9f051bf`：[single-file bundle](https://github.com/microsoft/vscode-docs/blob/81d76c22e9a4f5c63733c38e4ccedaf8c9f051bf/api/extension-guides/web-extensions.md)；资源边界固定提交 `28a499366afd2a052ca33ffadd47f33fc78594f5`：[URI DTO](https://github.com/microsoft/vscode/blob/28a499366afd2a052ca33ffadd47f33fc78594f5/src/vs/base/common/uri.ts#L426-L432)、[worker 转换后 fetch](https://github.com/microsoft/vscode/blob/28a499366afd2a052ca33ffadd47f33fc78594f5/src/vs/workbench/api/worker/extHostExtensionService.ts#L66-L71)、[主线程转换](https://github.com/microsoft/vscode/blob/28a499366afd2a052ca33ffadd47f33fc78594f5/src/vs/workbench/api/browser/mainThreadExtensionService.ts#L192-L194)、[Web Host CSP](https://github.com/microsoft/vscode/blob/28a499366afd2a052ca33ffadd47f33fc78594f5/src/vs/workbench/services/extensions/worker/webWorkerExtensionHostIframe.html#L4-L8)、[origin 校验](https://github.com/microsoft/vscode/blob/28a499366afd2a052ca33ffadd47f33fc78594f5/src/vs/workbench/services/extensions/worker/webWorkerExtensionHostIframe.html#L24-L36) | Fibra 只借鉴“稳定逻辑资源与 execution-local 交付地址分离”及单文件浏览器入口约束；唯一管理面、多 facet、digest cache 和同一 desired target 是本项目契约，不复制 VS Code URI/RPC/module shim |
| Grafana | 官方文档描述 App Plugin 的页面/UI extension/backend 组成及 backend 启动模型 | [App Plugin](https://grafana.com/developers/plugin-tools/key-concepts/anatomy-of-a-plugin)、[Backend Plugin](https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins)；浮动、非契约 | 跨 facet `ChangeSet`、统一准入和排空是本项目契约 |
| Eclipse Theia | 官方文档区分运行时插件与编译期扩展及其运行位置 | [扩展模型](https://theia-ide.org/docs/extensions/)；浮动、非契约 | client execution session、desired/observed 协调和唯一控制面是本项目契约 |
| OpenAI Codex app-server | 核心 crate 定义 app-server protocol 类型并导出 TypeScript/JSON Schema；同一 server 可由 stdio 进程入口或 in-process transport 驱动 | `0.154.0`，tag commit `6b9826e3aa83b1a5947db50f4332cb9c65f1b340`：[protocol exports](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server-protocol/src/lib.rs)、[common protocol types](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server-protocol/src/protocol/common.rs)、[in-process transport](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/app-server/src/in_process.rs)、[CLI process entry](https://github.com/openai/codex/blob/6b9826e3aa83b1a5947db50f4332cb9c65f1b340/codex-rs/cli/src/main.rs) | 只支持“核心拥有协议与多语言导出、进程内外复用协议语义”的取舍；不证明 Fibra 的 revision 围栏、插件生命周期、`ChangeSet`、Agent/Session 协议或资源排空 |
| OCI Image/Distribution specs | descriptor 用 `digest`、`size`、`mediaType` 描述内容；distribution 按 digest 拉取 blob 并要求消费方核对内容摘要 | Image spec 固定提交 `af26a05fba5ee648512f4ea3c9fda1fcc1b6d6dc`：[Descriptor](https://github.com/opencontainers/image-spec/blob/af26a05fba5ee648512f4ea3c9fda1fcc1b6d6dc/descriptor.md#L4-L42)；Distribution spec `v1.1.1`、提交 `a139cc423184af6078077b9b7ee336eddbd03f8f`：[pulling blobs](https://github.com/opencontainers/distribution-spec/blob/a139cc423184af6078077b9b7ee336eddbd03f8f/spec.md#pulling-blobs) | Fibra 只借鉴内容 descriptor、digest 寻址与消费端校验，不声明 OCI 兼容，不复制 registry API，也不把 OCI 可选 `urls/data` 带入 lifecycle control snapshot |
| Web Platform | WHATWG URL/Fetch、Web Crypto digest、File API object URL 和 HTML module loader 是浏览器原生机制；object URL 的执行受页面 CSP 约束 | Living Standards：[URL](https://url.spec.whatwg.org/)、[Fetch](https://fetch.spec.whatwg.org/)、[Web Crypto digest](https://w3c.github.io/webcrypto/#SubtleCrypto-method-digest)、[File API object URL](https://w3c.github.io/FileAPI/#dfn-createObjectURL)、[HTML modules](https://html.spec.whatwg.org/multipage/webappapis.html#integration-with-the-javascript-module-system)；当前作为权威但浮动的机制依据，P0 将由 lockfile 固定的 Playwright/Chromium 门禁验证实际组合 | Fibra 不自写 URL parser、HTTP cache 或 module resolver；P0 loader 只执行已验 digest 的自包含单文件 ESM，并显式要求 `script-src 'self' blob:`、禁止 `unsafe-eval`。平台标准不证明 Fibra 的生命周期和授权语义 |

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
| 审计 P0 | 当时 vNext 与产品架构各有一套 P0–P8 阶段表且顺序冲突 | 已删除 vNext 阶段表；该历史结论已由 2026-09-15 Client Foundation 规格更新：Fibra P0 定义 foundation，产品文档只定义其后的业务路线 |
| 审计 P1 | 解析期被描述为持有旧 route 并参与排空 | 已改为仅 PublishedRuntime 准入后的 invocation 持有租约；准入前代变化返回 stale/revoked |
| 审计 P1 | Picocli `CommandSpec` 被暗示为不可变命令代 | 已把不可变身份限定为 Fibra descriptor、贡献身份和 revision，并记录 Picocli mutator 证据 |
| 审计 P1 | 普通 Ctrl+C、raw `0x03` 与外部信号没有分开 | 已定义三类入口和共同的幂等取消/排空协调边界 |
| 审计 P1 | AgentCLI/PaiCLI 的立即取消可能被误作 Scope 排空 | 已明确只采纳交互模式，不采纳排空保证 |
| 审计 P1 | VS Code/Grafana/Theia 的外部事实与项目推导混栏 | 已分栏；唯一管理面、跨 facet ChangeSet、desired/observed 均标为项目自定契约 |
| 审计 P2 | 当前架构文档仍沿用 DSH `0.1.2-rc.1` 旧基线 | 已按维护者确认切换到 `0.1.5-rc.2`/`c291e7961`；旧提交仅保留历史证据 |
| 审计 P2 | VS Code/Grafana/Theia 链接未固定版本 | 接受：已明确降级为非契约灵感；任何契约断言不得依赖其浮动内容 |
| 审计 P1 | Client Foundation 引用 Codex app-server，但审计中没有对应固定证据 | 已补 `0.154.0` 固定源码，只支持协议所有权、多语言导出和进程内外 transport 复用语义，不把 Fibra 自定生命周期契约归因于 Codex |
| 审计 P1 | client snapshot 把 raw URL/inline bytes 混入控制面，URI 校验被迫在 Java 与浏览器间追求错误的一致实现，且交付地址会污染 target 内容身份与 digest cache | 已改为稳定 `ResourceDescriptor` 与独立 `ClientResourceProvider` 数据面；固定 VS Code execution-local 资源转换与 OCI descriptor/digest 证据。URL/HTTP/IPC 只属于具体 adapter，各语言使用原生实现，不作为跨语言协议契约 |
| 审计 P0 | `blob:` 动态 import 被误当成完整模块依赖系统，未约束相对/bare import，React probe 也可能隐式依赖共享 React；同时未声明 object URL 所需 CSP | protocol v1 的 `client:web` 入口冻结为自包含单文件 ESM，P0 React probe 将依赖闭包编入自身 bundle；共享前端模块解析单列 P0 后能力。P0 Web Host 显式使用 `script-src 'self' blob:` 且禁止 `unsafe-eval`，并验证 capability 不满足时拒绝装载 |
| 审计 P0 | verified bytes cache 在 core/Web 两处表述不同，可能先缓存未校验 provider bytes | core 只提供 single-flight cache，且不暴露原始 `put`；Web loader 回调必须完成读取、byteLength/SHA-256 校验后才能返回可提交值，失败移除 pending 且不污染 cache |
| 审计 P1 | framework-neutral 门禁只搜索 `react/document/window` 文本，无法证明 package 依赖图与 TypeScript lib 没泄漏 | 增加不含 DOM lib 的 core 编译门禁和 package/workspace 依赖图检查；文本搜索仅作为补充信号 |
| 审计 P1 | P0-A 只验证隔离 harness，却被命名为整体风险门，真实 Engine 状态机直到外围迁移后才碰撞 | 已把 P0-A 降为 client 技术栈门，并将 P0-B 拆成 Engine 核心风险门、管理面/调用方迁移和真实 fs 最终证据；中间按模块前沿验证，最终合并仍禁止兼容层和双模型 |
| 审计 P1 | client foundation 下沉只由架构自证，未说明为何不等待第二个外部产品消费者 | 已明确驱动力是同一逻辑插件跨 Host/CLI/browser execution 的一致生命周期，并补首批场景、用户收益、时机与可证伪退回边界；CLI/Desktop 和 DOM/React probe 均不冒充第二产品 |
| 审计 P2 | 产品表把 `host-java`/`host-node` 写在 `facet role` 列，混淆 role 与 runtime | 已将列名改为 `facet 形态`，保留 `role=host/command/client` 与 `runtime=java/node/client` 两根正交轴 |
| 审计 P2 | 对象模型字段与 `fibra-package.yaml` 短 key 没有显式映射 | 已补 `id/runtime/target/capabilities` 到完整对象字段的映射，并明确 `format` 与计算所得 digest 的边界 |
| 审计 P1 | P0 非目标被统一写成“后续由产品阶段进入”，混淆 Fibra 平台欠账与上层产品职责 | 已在 Client Foundation 架构第 14 节建立成熟度账本：永久内核、最小正式实现、参考 adapter、验证夹具、P0 后 Fibra 能力与永不属于 Fibra 的业务职责分别列示；Task 13 必须按实际证据回填 |
| F2 P2 | F2 当时的自动化宿主没有可受控的真实终端模拟器；`script`/`expect` PTY 缺少 JLine 终端能力协商响应 | 已由 F4 仓外原生 xterm PTY 门禁关闭：直接验证 history 重启、补全、高亮、32x10 窄终端、resize/redisplay、renderer、失败/取消恢复和应用原始输入；不反向改写 F2 当时的阶段证据 |
| 架构替代 | 旧 vNext 与产品草案把 host client adapter、浏览器 runner 和 transport 归上层产品，并以第二个非 Agent 消费者作为下沉前提 | 已废弃。2026-09-15 起由 Client Foundation 规格定义 Fibra 侧的逻辑包、多 facet、协议、执行协调与 P0 验收；Electron + React 只保留为上层产品的首个消费组合 |

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
