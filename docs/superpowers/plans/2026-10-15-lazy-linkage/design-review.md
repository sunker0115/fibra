# 独立设计审查

审查者：独立子代理 lazy_linkage_runtime_analysis；只读核对候选与固定 Reactor 3.8.6 源码。

初审 B0/I3/M0：收窄 post-terminal subscriber throw 承诺；明确取得 Subscription 后即
登记取消、同步 subscribe throw 取消上游及重复订阅；明确多值 resource、health、parallel
完整等待与 serial 首项的语义。

修订后复核：B0/I0/M0。Scope/admission/lease、清理失败保留、SAVED 与
NOT_APPLICABLE、真正 fatal 边界闭合。仅关闭设计门，未代替实现与运行验证。

另由 lazy_linkage_core_analysis 在固定 JAR 的独立临时探针核对：Outcome 值传输、
hidden error 的 scalar 避让、取消竞态和 context；最终14项断言原生 javac/java exit0。
源与结果：/private/tmp/fibra-lazy-linkage-reactor-probe/OutcomeProbe.java 与
outcome-early-registration-{commands.json,1.stdout,1.stderr}。
违约下游 fatal 仍服从 Reactor drop 规则，独立对照结果保存在 downstream-fatal-1.stdout。
