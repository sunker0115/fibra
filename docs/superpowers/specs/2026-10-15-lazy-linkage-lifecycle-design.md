# 延迟链接失败与生命周期终结

状态：设计/实现/文档独审 B0/I0/M0，正式验证通过，见[资格记录](../plans/2026-10-15-lazy-linkage/verification.md)。当前基线 dde0e5d（初审79626c0）。继承 Host API 的通用实现授权，不增加公开 API，
不改变 Java 类隔离、目标保存顺序、资源所有权或 Tela 产品设计。
本轮直接替换原错误传播路径，不保留新旧双实现、兼容开关、旧签名转发或按产品选择的分支。

## 1. 已证伪的前提

未声明 `hostApis` 的插件可以在 definition 正常返回之后，直到 factory/Handler 或
start 才首次解析 Host 类型。`PluginClassLoader` 正确拒绝未导入类型，但由此产生的
`NoClassDefFoundError` 会经过 Core 的生命周期 lane。固定 Reactor 3.8.6 的
`Exceptions.isJvmFatal` 包含 `LinkageError`；`Mono.fromCallable`、defer 和
同步订阅中的裸 throw 因而可能只留下调度器日志，不向等待者发终态。

Tela 的独立 JVM 反例为 6/F1 和 394/F1；父进程 20 秒回收自己的 child，137 个输入
无漂移。来源与摘要保存在本轮 findings。既有静态拒绝、391 项绿灯均不覆盖此窗口。

## 2. 唯一责任边界与替代方案

Core 负责受控生命周期回调的执行和信号交付；Java Unit 负责 Scope/admission/lease
取得后的启动失败与清理；Engine 仍独占 target 保存及 operation 结果。

采用局部内部 Outcome 传输：把受控调用的结果或非 VM 致命错误先作为普通值跨过
Reactor 的调用边界，再原样还原为同步 throw 或 onError。原异常对象、cause 和
suppressed 保留。Outcome 只是单次调用结果，不是业务状态、恢复依据或资源 owner。

- 同步 Dispatcher.call：lane 内直接调用；跨 lane 捕获 Outcome，block 返回后在调用者
  栈原样重抛。Callable 的 checked Exception 维持 IllegalStateException 包装。
- Dispatcher.mono/run：同样捕获 Outcome，错误 publisher 使用
  `Mono.error(original).hide()`，避开 MonoError 的 Callable scalar 快径。
- 受控 Mono 回调（start、drain、dispose）：每次订阅保护 supplier 和同步 subscribe。
  `Mono.create` 只发非空 Outcome 值，绝不以 sink.error 传裸 LinkageError；之后还原为
  hidden error publisher。取消通过 Disposable.Swap 转交源，订阅前已取消就不执行回调；
  Reactor Context 显式传入源；取得 Subscription 时立即登记取消，不能等 subscribe
  返回才登记。发终态前一次性认领；桥自身不能把已发终态后的 throw 重新解释为源失败。
  取消后的迟到错误只丢弃 Outcome 值。并发取消不能保证源代码完全没有开始执行。
  源在 onSubscribe 后同步 throw 时，先取消已取得的上游，再交付失败；取消本身失败
  以 suppressed 保留，真正 VM fatal 仍重抛。重复 onSubscribe 拒绝第二订阅。
  Reactor 可能先将同步 throw 转成 onError，因此首次 onError 与直接 catch 使用同一
  取消路径；已终止的合规 Subscription 的 cancel 为 no-op。
- 真正 VirtualMachineError / ThreadDeath 在捕获或 onError 转换之前原样重抛，不能
  被当作插件普通失败、清理成功或已经完成的 Engine 回执。这是受控源回调及源信号的
  分类承诺；违约下游 subscriber 自己抛错仍服从 Reactor 的 drop/错误处理规则。

拒绝：放宽 Host 类型可见性、预执行 factory、扫描全部字节码、提前引用全部 API、
产品局部保护、全局 Reactor Hooks、重试/watchdog。只在 Unit.mount 扩一个 catch 无法
让原本挂起的 mount 返回；纯 Mono.error 仍会经 scalar 快径 throw；直接
Mono.create(sink.error) 在取消竞争下也会重新触发 fatal。异常信封加层层 unwrap
可行但没有保留原 identity 的局部值传输直观。

本轮保证受控同步回调、同步订阅 throw 与正常异步 onError；不承诺接管第三方异步
线程直接 throw、永不返回/永不发终态的 Publisher 或所有 VM 致命错误的进程恢复。
不新增 VM fatal 的 Host 通知承诺；沿现有截止与 fail-stop 边界处理。

## 3. 修改范围与资源失败语义

Core 使用同一个 package-private 工具集中错误分类、Outcome 和 Mono 回调保护。
Dispatcher、插件注入/start、OwnedResource 的取得/排空/销毁、ResourceDrain、Cleanup
及同族 update 返回错误处复用它；不把内部工具变为跨模块公共 API。
EventBus 的同步 emit/bail/waterfall 已经经过 Dispatcher；异步事件回调的同款
defer/subscribe 窗口也复用该保护，保留原 parallel 等待完整 Publisher 后聚合、serial
用 Mono.from 取首项的短路语义。OwnedResource 的多值资源流和 SupervisedResource
的 health 继续直接订阅原 Publisher；只复用 fatal 分类，不得用 Mono 桥截断这些流。

factory 在实例构造早期执行。此时 Unit 已持有 Scope/admission，但 Core 尚未登记实例；
Unit 捕获非 VM 致命 Error 后关闭 admission，进入原 failStart，清理 Scope。
初始化后已登记的实例仍由原 Scope 清理，不能无条件删除登记而遗失资源。
Java runtime 在现有同步启动捕获点区分真正 fatal；releaseScope 的同步 SPI throw
进入原 cleanup 错误路径，保留原 cause，不引入新 SPI。

清理失败沿原 owner 保留：启动错误仍为主因，清理错误按现有规则 suppressed；drain
失败保留资源与 lease；stop 仅在 Scope 清理成功后释放 lease。独立资源的 allSettled
仍继续尝试。重复 close 重放原回执，不伪装所有清理失败都能成功关闭。

## 4. 保存阶段与文档口径

明确声明的 Host API 缺失/版本不兼容、重复类与静态绑定拒绝仍在 prepare/LOAD，
新 apply 为 NOT_SAVED。隐式依赖直到 factory/start/运行时才被触及时可以更晚失败；
Host API 导入不是全字节码预链接保证。

Engine 在 reconcile 之前已经保存 target；factory/start 失败的新 apply 为
SAVED/RECONCILING。不得改写为 NOT_SAVED。bootstrap/reconcile 无新保存为
NOT_APPLICABLE；bootstrap 可完成启动并显示 BLOCKED，而非统一抛 apply 异常。
Engine 的错误投影保留失败事实，不新增把原 Throwable 作为 EngineChangeException
直接 cause 的保证。原对象身份在 Core/Unit 边界验证。

## 5. 验收与停止门

1. 独立设计审查；正式 RED 在隔离 child JVM，父进程限时、记录日志并回收自己 child。
2. Dispatcher 的 call/mono/run、同 lane/跨 lane、成功/null/错误身份、后续健康调用。
3. factory、注入、start supplier/返回 Mono 的同步订阅/异步 onError，以及
   supplier/source/drain/dispose 的同族失败、上下文、取消与重复终态。
4. 成功清理、清理失败保留 owner/lease、独立 peer、重复请求/关闭、预构造 VM fatal。
5. 真实 Java 缺导入 Handler 在 factory/start 迟发解析；保留原 LOAD 拒绝测试，验证
   SAVED/NOT_APPLICABLE、健康 peer、实际类型隔离、物理 loader close 与可回收边界。
6. 定向与全仓测试、独立规格/代码审查、架构/签名/客户端/可复现发行；新隔离 Maven
   仓库的公开消费者与真实 Model 反例。旧 e133814 与79626c0制品一字节不改。

不自动提交、推送或修改 Tela。材料性 owner/保存态/公开契约分歧须先更新设计并独审；
同一问题两次失败停止盲改。所有门闭合前仅报告阶段进展，不报告完整交付。
