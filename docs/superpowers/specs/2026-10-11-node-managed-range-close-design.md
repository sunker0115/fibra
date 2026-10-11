# 既有 Node runtime 进程范围关闭设计

日期：2026-10-11。用户已批准本期纳入原supervisor、关闭测试及生命周期文档；真实反例、设计独审、生产实现、最终实施双审及完整发行/消费者验收已完成，N1～N4闭合。原设计独审B0/I0/M1（报告`/private/tmp/definition-catalog-node-final-design-review.md`，SHA`c203cfdc72330551c7565e0e0353d35dbd9b69393d2c9fdd66d3f3d5a499dd5d`）绑定设计`021e46dc9395296dabdd5e966aa21669f9b5140014bd203607add2617e706615`，M1归档数量已修正；最终实施及集成审查均B0/I0/M0。完整执行和验证边界见[交付记录](../plans/2026-10-11-definition-catalog/delivery.md)。本次只更新首段阶段状态，正文合同不变。不恢复Tela P3，不commit/push。

## 1. 最终架构与问题归属

Node runtime原本就是通用能力。修复服务于每个Node插件的自然退出、EOF、协议失败、取消宽限耗尽、握手/心跳/传输失败及显式关闭入口，不含Tela策略。

最终架构仍由runtime唯一owner解释私有资源事实。`NodeProcessUnit`拥有所物化的supervisor与session；supervisor拥有POSIX payload进程组的关闭控制和观察；`NodeSidecar`拥有session唯一异步关闭屏障；`NodeRpcChannel`拥有framing、pending、请求期限和输出排空。Engine/Scope只等待这些owner的真实结算，不另行枚举PID或补判关闭成功。

四个事实必须分开：payload结果、stdout/stderr传输终态、supervisor退出、进程范围静默。直接子进程退出、signal调用成功、close被接纳或`isAlive=false`均不能代替范围证明。原请求终态尚未取得、需依赖范围结束兜底的请求，范围未证明结束时不能清理成功或释放相应调用租约。完整尾响应可以独立证明原请求终态并成功完成该请求drain，即使session范围关闭失败；这不证明session/实例清理成功，不释放仍由该失败session拥有的资源。保留`NodeRpcChannel.Request.complete/completeFailure`与原`exitedSupervisorStillDeliversTailResponseWhenRangeProofIsMissing`合同，不改变请求SPI或participant语义。

源码参照见[Node关闭基线](../references/2026-10-11-node-lifecycle-baselines.md)。DSH固定源码的唯一range owner、EPERM继续等待、结束后不再使用旧PGID直接适用；其无限range等待、catch-all direct-child fallback和Linux成员快照不能照搬。Codex及匹配Node/libuv支持控制、direct child与输出事实分离，但Codex成员fallback不提供闭合组证明；Tini的PID1/subreaper前提不同且主child退出不代表整个组结束。完整固定源码对拍作为独审输入，不能仅凭项目名字推导相同保证。现有实现的owner分层符合目标，缺口在同owner把一次受阻观察/控制错误提升为整次关闭失败；本修复不建立新框架或第二owner。

## 2. 实际反例及证据边界

此前两个原生完整`clean verify`均失败，最后有效66类XML为599/1/0/0；不同既有NodeSidecar测试出现kill EPERM。日志没有具体operation，不声称它们必然属于下述受控窗口。

首条稳定反例沿原`NodeProcessUnit.launch → materialize → close`执行未改production resource。test launcher只给supervisor加preload，payload PID=PGID、execArgv不含hook；仅own PGID的signal 0在闸门闭合时抛EPERM，释放后调用原系统操作。测试侧另行只读确认真实ESRCH，不参与生产结算。原配置T=5s，范围在时限内实际消失，原supervisor仍输出FAILED、exit1，原close拒绝并保留session。同launcher无故障正控制得到QUIESCENT、exit0、删除session。

报告`/private/tmp/definition-catalog-node-lifecycle-stable-red-report.md`（补原工具终态后SHA`1ed89057d45830d978d23e8ca70661e040e0cd3d9c5cca499d80d0f9b6d9a327`），33份实际归档文件身份见`/private/tmp/definition-catalog-node-lifecycle-stable-red-evidence/sha256.json`。原production resource SHA为`9704e060b710af727bfbb3b8420f3dda8eec1d8d215cc66576c5d3b471119927`，首条有效新test SHA为`1c2b0eb08ff3f6a7cb8096b694879818ab636ed25d3ee4f280e619fbc194590e`。首轮canonical路径夹具就绪失败不算生产RED，stderr未捕获的限制保留。

## 3. 同 owner 的关闭状态与转换

关闭生命周期只有一次，仍由原`shutdown` promise及Java缓存close回执拥有。范围观察仅为owner内部三种事实：

| 系统结果 | 范围观察 | 后续动作 |
| --- | --- | --- |
| 同PGID的signal 0成功 | PRESENT，未证明结束 | 原阶段内继续观察，到阶段截止推进一次软/强信号 |
| 同PGID任一范围操作返回ESRCH | ABSENT，既有边界内取得消失证明 | 吸收为结束事实，停止所有后续probe/signal，沿原payload/传输/Java结算 |
| 同PGID探测或TERM/KILL返回EPERM | UNKNOWN，尚未取得消失证明 | 保留同owner及原截止，继续观察；不是成功，不重启close，不退回只判断payload |
| 其他errno或程序错误 | 本次关闭不能依该规则结算 | 原失败路径，保留失败现场，不吞入UNKNOWN |

有效payload PID尚未发布的明确启动失败沿原合同处理：不存在已登记范围，仍核START_FAILED及原终态。Windows既有taskkill/直接子进程较弱后端不改，也不声称靠ESRCH验证Windows范围。

PRESENT与UNKNOWN只能通过真实系统观察变为ABSENT。ABSENT是本次owner的不可逆事实；不能在确认后再probe复用标识或发送升级信号。UNKNOWN可以变回PRESENT，仍不构成成功。原预算用尽且仍PRESENT/UNKNOWN则范围FAILED，即使payload已退出也不能写QUIESCENT。

EPERM诊断只在同shutdown内部保留有限首/末operation、errno和PGID，最多固定大小记录；到期一次输出安全错误说明，不每20ms追加日志或异常链。记录是诊断，不是第二生命周期状态。观察到ABSENT后不把已恢复EPERM强制保留成清理失败；独立transport、状态写入及其他错误不得因ABSENT被抹去。

## 4. 时间、信号与结算不变量

关闭起点仍为原`terminateManagedRange`中的一次startedAt，T为原`NodeRuntimeOptions.terminateTimeout`。EOF、TERM、KILL分别沿原T/3、2T/3、T的阶段截止；TERM/KILL各最多一次，不因EPERM每轮重复发信号。阶段等待只使用原剩余时间，不刷新startedAt，不各自再加完整T，不扩大Java侧现有fallback预算。

保留原循环的边界观察语义：一次系统检查在截止边界返回ABSENT可结算；否则到期不再额外睡眠或创建下一观察预算。已确认ABSENT后立即停止该PGID的所有后续操作。同步系统调用调度和原Date.now的既有粒度不因本修复被包装为硬实时保证。

signal成功只证明调用被接受，仍等待范围确认；signal ESRCH直接给出同范围的消失证明，不再启动后续probe；signal EPERM记录受阻控制事实并依原阶段观察。不要用child.kill兜底替代同PGID范围owner，也不要借ps/libproc快照把只剩zombie判成整个组已消失。

范围成功后仍等待原payloadCompletion并写唯一终态文件；输出以真实EOF排空，不能因payload exit或范围静默提前截断。Java仍要求supervisor退出、exit0、合法QUIESCENT，才清理session。范围/监督器/传输失败缓存原终态并保留现场，重复/并发close及观察取消不重试物理关闭。

原PROTOCOL错误加范围成功仍为PROTOCOL；范围失败则原cleanupFailure优先为TERMINATE并保留原错误。不能通过修改优先级、删除目录或抑制异常让既有测试通过。

## 5. 参照成立前提与明确拒绝

本期目标保持原较弱的POSIX numeric PGID边界及Windows taskkill边界。离组后代、数值PGID在尚未取得消失证明前的重用、机器失效和无法执行清理不在新的强保证内；DSH Linux scope、Windows Job或Tini PID1/subreaper不自动赋予Darwin相同能力。

若稳定反例或对拍发现需要原生范围身份、group keeper或平台容器才能兑现当前目标，应停止小改并重新评估owner，不扩大catch或增加第二观察入口。新依赖/API/协议及更强平台保证超出当前授权，不在本设计中隐含交付。

## 6. 可证伪验收与交付门

| ID | 实际验收 |
| --- | --- |
| NLC-01 | 同launcher无注入的真实child正控制：原resource完整bytes、独立PID/PGID、payload无hook、原close正确删除session |
| NLC-02 | 探测EPERM闸门期间同session owner/close保持未成功结算；原请求终态未证且依赖范围兜底的request-resource保持未结算；原T内释放后production自身取得真实ESRCH并正确关闭，现有真实RED转GREEN |
| NLC-03 | TERM、KILL分别受阻EPERM，范围在原预算内自然结束；实际trace每阶段至多一次signal，只有真实ESRCH允许成功 |
| NLC-04 | payload已死但永久UNKNOWN至原截止仍FAILED；永久PRESENT且真实活成员到期仍FAILED；保留status/session/owner，原T不延长 |
| NLC-05 | 其他errno明确失败；EPERM之后范围消失，但输出传输或状态文件写失败，仍不得清理成功 |
| NLC-06 | duplicate/protocol错误与成功/失败范围证明分别保留PROTOCOL和cleanup优先TERMINATE；未取得原请求终态的资源不能仅凭结果错误成功释放；完整尾响应后该request drain成功与session关闭失败可并存，原测试必须保留 |
| NLC-07 | 成功/失败后的并发、重复close回放同一次终态；中断/取消观察不取消物理关闭或重新发信号；ABSENT之后不再使用旧PGID |
| NLC-08 | 原Node三类全部回归及真实自然退出、尾帧、阻塞stdin、抗SIGTERM后代、失败入口；POSIX夹具明确Linux/macOS适用，Windows不冒充已测 |
| NLC-09 | spec与fresh quality独审当前source/resource、实际RED/GREEN、原生日志/native exit/XML；helper观察UNKNOWN也不能误当absence，不mock production终态 |
| NLC-10 | 原完整clean verify通过后执行原file remote/空消费者、distribution及reproducible门；新Node制品须重核producer/consumer bytes及隔离Tela消费，不复用旧SHA证明新组合 |

没有任何局部测试或设计门足以关闭总目标。目标范围、public contract、原脏Tela工作树、P3及commit/push边界不变。
