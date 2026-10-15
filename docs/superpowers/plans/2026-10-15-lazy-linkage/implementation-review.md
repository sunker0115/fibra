# 独立实现审查

审查者：lazy_linkage_runtime_analysis，只读核对 Core/Driver 生产 diff、两组 Core 测试、
Java Engine/Driver 测试及设计/API/架构/changelog；未代替主线程执行 Maven。

初审 B1/I1/M0：普通同步 subscribe throw 被 Reactor 转成 onError 后遗漏上游取消；
注入、update、事件、重复订阅/终态及同步 releaseScope 的反例不足。

修订：首次 onError 与直接 catch 先认领 terminal，再共用取消路径；取消错误 suppressed，
Swap 防止重入/重复取消。新增 RuntimeException/AssertionError 正式反例原生22/F4，
然后修复；其余受控路径补齐正式反例。

最终复核 B0/I0/M0：原错误 identity、fatal 边界、Scope 普通 close 与失败 drain 的区别、
Scope/lease 保留和成功 stop 后释放、共享 loader 与新 runtimeInstanceId、实际保存阶段
均未弱化。无公共 API、依赖、持久状态或新资源 owner。

独立文档/API 审查者 review_api_convergence：B0/I0/M0；确认同 view resolve/handle、
显式 LOAD 与迟发 activation、CNFE-only fallback、fatal/异步保证上限口径一致。

主线程已核验受影响模块78份XML、661项测试原生通过；完整发行、公开消费者及真实 Model
资格单独记录，不能以本审查或定向测试抵扣。用户补充不添加兼容代码，最终 diff 需继续核对。
