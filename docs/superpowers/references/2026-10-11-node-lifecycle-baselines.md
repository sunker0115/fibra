# Node 进程范围关闭的源码参照

日期：2026-10-11。本文记录已读取的参照源码与采用边界，不把参照的运行资格当作Fibra测试通过。用户已批准既有Node runtime进程组关闭修复；最终实现须先有真实runtime反例和完整生命周期设计独审。

## DSH 固定源码

本机`/Users/sunke/dev/ai-project/deepseek-harness`实际HEAD为`c291e7961a515f6d7af9304e7fd1d257929aef26`，只有未跟踪`.codegraph/`，未修改或运行参照项目。四份实际源码指纹归档于`/private/tmp/definition-catalog-node-dsh-source-sha256.json`：

| 文件 | SHA256 |
| --- | --- |
| `packages/subprocess/subprocess-local/src/managed-owner.ts` | `1cb92ee31829672fb6659dfac78f66f5fc68c5a9f41bc97f41d13985f8f58b09` |
| `packages/subprocess/subprocess-local/src/spawn.ts` | `e96e3f1a8ec0a5a753ebf2b40a6da90f63a0546809fd2b19e9e4b62f0a6c6d37` |
| `packages/subprocess/subprocess-local/src/index.ts` | `81398ec668d58923aa5f23555b7f56b10637b9f0ad04706a7023002c58b7665d` |
| `packages/subprocess/subprocess-local/tests/spawn.spec.ts` | `11bc5e505de1e5e7aff41af8a58fe54d2df151b940b36afcf1d12cbcade7ad4e` |

实际采用依据：

- [managed-owner.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/subprocess/subprocess-local/src/managed-owner.ts)第7～15行分立`signal`、`waitForExit`及provider私有cleanup。`ManagedProcessLaunch.direct`是直接子进程结果，不能代替range owner的终态；`waitWithAbort`只取消调用方观察，共享等待继续存在。
- [spawn.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/subprocess/subprocess-local/src/spawn.ts)第399～449行，fallbackOwner对同一负PGID执行signal 0：成功表示仍未静默，ESRCH表示消失，EPERM继续等待。`observation`复用同一promise，`stopped`使已确认结束状态不可逆；不是重新启动关闭或把权限错误当作成功。
- 同文件第519～563行，`observeRangeExit`确认后记录`rangeExitObserved`，取消后续escalation，`kill`拒绝再次向旧标识发信号。TERM/KILL控制与range观察分开；第575～616行的`done`结果及输出结算不能代替`waitForExit`。
- [index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/subprocess/subprocess-local/src/index.ts)第94～118行，dispose保留live集合，并行观察direct result与managed-range退出，两者成功后才移除handle；一个成员失败不跳过其他成员结算。
- [spawn.spec.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/subprocess/subprocess-local/tests/spawn.spec.ts)第1073～1092行有EPERM仍视为活跃、调用方有界等待返回false的测试；第1191～1203行验证确认结束后重复terminate不再probe/signal复用的PGID。这些测试本次仅阅读，未执行。DSH该fixture会模拟系统结果，不能作为Fibra真实ESRCH证明。

| 采用类别 | 对Fibra的意义 | 前提及未解决窗口 |
| --- | --- | --- |
| 直接采用 | 唯一range owner；发信号与确认静默分开；EPERM不构成消失证明；取消只影响观察；已确认结束后不再使用旧PGID | 原NodeProcessUnit/supervisor已经承担相应职责，沿原边界补完整观察状态，不增加跨runtime框架 |
| 最小改造 | DSH等待range结束，适配为Fibra原configured T内EOF→TERM→KILL分阶段观察 | DSH该owner等待本身没有Fibra的最终截止；不能照搬无限等待。最终deadline、UNKNOWN结算和资源保留须写明并验证 |
| 本项目特有 | Java侧要求supervisor退出、range终态和传输/请求资源各自结算；关闭失败缓存原终态且保留session；清理失败优先于原PROTOCOL错误 | DSH单个handle的done/wait合同不等价于Fibra的runtime participant及Scope清理，需要Fibra原session链证明 |
| 拒绝 | `signalTree`第343～350行catch任意错误后尝试direct child.kill；Linux directSettled加live-member快照为false即结束；其他errno退回direct child状态 | 都不能证明Fibra要求的同一PGID消失。不能吞异常、只杀leader或引入Java第二observer来伪造QUIESCENT |

DSH更强的Linux scope与Windows Job属于其platform owner，当前POSIX PGID方案没有因此获得相同containment。数值PGID重用、后代主动离组及Darwin没有等价原生范围的边界必须如实保留；如果最终目标要求更强身份/范围，应重开平台owner设计，不在本修复里隐含承诺。

## 与当前Fibra的对应

权威职责见[架构§6.3](../specs/2026-09-07-fibra-vnext-architecture.md#63-node-runtime)。原`NodeSidecar.finishClose`等待`NodeProcessUnit.close`，再join原RPC读写和stderr终态；尚无原请求终态的pending请求需要范围成功才可清理成功，完整尾响应则可独立结算该request的drain。cleanup失败时session仍失败、保留原PROTOCOL错误为suppressed；`NodeRuntimeDriver`分别关闭Scope和session，已成功的Scope可结算，只有两者成功才释放runtime payload lease。此链不需要转移给Engine或Tela。

当前supervisor的观察和发信号均对非ESRCH异常直接throw，`beginShutdown`把一次EPERM提升为整次FAILED。这与上述DSH的range观察语义有直接差异。修复方向必须从owner完整关闭生命周期推导，再用真实runtime反例验证；不是因为目录查询需要进程或Tela需要特判。

## Codex 固定源码

本机`/Users/sunke/dev/ai-project/codex`实际HEAD为`5fe4fc8f7cd16688b5c661d8cdb76e2a340b046d`，只有未跟踪`.codegraph/`。以下源码由独立参照审查读取，主线程另核关键实现及实际字节；未执行Codex测试。

| 文件（以Codex目录为根） | SHA256 |
| --- | --- |
| `codex-rs/utils/pty/src/process_group.rs` | `26668450452dd42b4a9a5a61b873e336a2cfe72be33410b9352d72f31e5bcf22` |
| `codex-rs/utils/pty/src/process.rs` | `f6dca08eec90266e43c7bebe9d1e651611d8d074614bae9df707c11c1c64d71c` |
| `codex-rs/utils/pty/src/pipe.rs` | `c147cbbf3c9bde1f15c83ded3c4641a6b5ca290f3639256b2cf5b4f7efe5d820` |
| `codex-rs/utils/pty/src/pty.rs` | `840f07e5875466ae2da0182c36f7041cebd6f11ce7b73e265b48d738c6ebac80` |
| `codex-rs/utils/pty/src/child.rs` | `2f4a8c11061a544527b15b6c9af9aa71f280502f7682cbed939f78b02c9725bb` |
| `codex-rs/utils/pty/src/child_reaper.rs` | `60cd52bdec3b94d6874a21ca736df63a674324e7ddfc97c16a894a806357fc05` |
| `codex-rs/utils/pty/src/posix_child.rs` | `920ddf7e9bb061dba055d2841c3f39bbe0ba954117b74e387e5a2392cf976bc6` |
| `codex-rs/core/src/exec.rs` | `83f72b788b041e2865ebd5b10ce6878b4a471ea4082116c821d87ef39b710888` |

[process_group.rs](https://github.com/openai/codex/blob/5fe4fc8f7cd16688b5c661d8cdb76e2a340b046d/codex-rs/utils/pty/src/process_group.rs#L122)第122～225行区分signal投递与未找到；macOS在PermissionDenied时枚举组成员、逐个getpgid再kill。任一成员成功即可返回true，另一些成员失败仍可被遮蔽；无成员且无错误返回false。它提供best-effort投递机会，不提供Fibra完整范围结束证明，不能把这一返回值写成QUIESCENT。

[process.rs](https://github.com/openai/codex/blob/5fe4fc8f7cd16688b5c661d8cdb76e2a340b046d/codex-rs/utils/pty/src/process.rs#L219)第219～269行分开request_terminate与reader abort，并保留waiter回收child；第397～453行也分开driver退出与output channel结束。[child.rs](https://github.com/openai/codex/blob/5fe4fc8f7cd16688b5c661d8cdb76e2a340b046d/codex-rs/utils/pty/src/child.rs#L54)及child_reaper.rs在取消/Drop后保留或移交direct-child回收owner，不能移植成数字PGID的稳定身份。

采用输出、direct child、控制及范围事实分离；拒绝把成员fallback、吞kill error或direct child死亡当作范围完成。它没有原T内EPERM→真实ABSENT的完整观察合同，不照搬整个Rust PTY框架。若未来要在长期group信号受阻时仍能控制成员，须另行评估native范围owner、身份race与平台交付成本。

## Tini 与当前 Node/libuv

Tini固定`v0.19.0`实际commit为`de40ad007797e0dcd8b7126f27bb87401d224240`，实际下载文件`/private/tmp/fibra-reference-tini-commit.c`的SHA为`48b982967a950c35533df82b2f29eb4a863a6de836306ae76e4f57fbb1abf11f`。[tini.c](https://github.com/krallin/tini/blob/de40ad007797e0dcd8b7126f27bb87401d224240/src/tini.c#L501)第501～603行转发signal并waitpid回收，ESRCH只warning，EPERM等其他错误直接fatal；第665～679行主child回收后退出，不等价于整个组已消失。Linux PID1/subreaper使其能收养孤儿，不赋予Darwin相同能力；没有Fibra总T、状态文件及输出代理合同。拒绝作为第二supervisor引入；将来Linux容器需PID1回收时才重开containment设计。

本机Node执行版本为`22.22.2`、uv为`1.51.0`，Node注释tag`v22.22.2`解引用为commit`2645dc73720b1b4f27c49f395d3c66025ce126cc`，固定version.h一致。只核版本匹配，未审计二进制构建来源或证明其可复现构建。

| 固定下载文件 | SHA256 |
| --- | --- |
| `/private/tmp/fibra-reference-node-commit-uv-process.c` | `cedc79cb473c0dde5c270ff2ee7b7956cbdfe0b3aceba19597b9a75a21e119f0` |
| `/private/tmp/fibra-reference-node-uv-version.h` | `aef7fb55ffcece76db9cb7f87d0632e432369747f329679779616cd0721981da` |
| `/private/tmp/fibra-reference-node-child_process.js` | `9f7c4dfcbd7e3d2e8006f6e19fc09695322edd20ef280a68025e2a269bfa12cd` |

[libuv process.c](https://github.com/nodejs/node/blob/2645dc73720b1b4f27c49f395d3c66025ce126cc/deps/uv/src/unix/process.c#L101)第101～177行由uv_process_t等待其direct child，ECHILD不能伪造exit callback；第1101～1116行把Linux/Apple系统kill errno原样返回。`__MVS__`的zombie专用处理不适用于本机。[ChildProcess](https://github.com/nodejs/node/blob/2645dc73720b1b4f27c49f395d3c66025ce126cc/lib/internal/child_process.js#L269)先emit exit、flush stdio，所有已跟踪stream结束后才emit close；exit并不关闭输出流。

直接沿用已经使用的Node/libuv spawn/reap/pipe机制，不引入第二waitpid owner抢走child，不把close handle、成员快照、signal成功或z/OS分支当作组absence。原supervisor承担Fibra特有的原预算、范围证明和终态合同，Java继续拥有缓存回执及session清理。

## 对拍结论和未解决边界

在此次有界候选集中，DSH给出最直接的range观察参考，Codex和Node/libuv给出owner及流事实分离的依据；Tini提供不同平台回收前提，不能替换现有合同。采用原owner、原预算及已用平台机制最适合本期约束。没有任何一份参照完整提供Fibra的有界EPERM恢复、终态文件与Java资源门，不能声称整套设计来自某一个项目。

独立源码对拍报告`/private/tmp/definition-catalog-node-reference-audit.md`的SHA为`6caa06e75206a4915576ddd544bfa16ce8664a3c1bc6dc824e64fb80274ba4fa`；参照范围B0/I1/M0，其中I1要求absence吸收后停止旧PGID操作、UNKNOWN到期必须失败，已作为设计门约束。该报告不等于完整设计或实现通过；原两次失败的具体operation仍未知。主线程重算Codex及下载源码、真实RED归档字节一致，实际运行验收仍须完整生命周期与发行门。
