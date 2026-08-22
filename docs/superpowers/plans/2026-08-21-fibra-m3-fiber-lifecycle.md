# M3：Fibra 生命周期交付记录

状态：已完成

## 状态机

插件 Fibra 状态为：

`PENDING → LOADING → ACTIVE → UNLOADING → PENDING`

加载或配置校验失败进入 `FAILED`；最终移除进入 `DISPOSED`。root Fibra 的 uid 固定为 0，dispose 等价于 restart，不进入 DISPOSED。

## 收敛规则

- epoch 是已声明依赖 provider uid 的有序指纹；缺少任一依赖即为 INACTIVE。
- 每棵 Context 树共享一个 Reactor 单线程 lifecycle Scheduler。
- 同一 Fibra 只允许一个 reload 或 unload 在途；目标在途中变化时记录新 epoch，当前阶段结束后继续收敛。
- reload 与 unload 前均让出一个 lifecycle tick。
- 配置更新经过 `internal/update` waterfall。
- plugin apply、配置校验和 startup 异常只使当前 Fibra 失败。
- unload 顶层清理错误被记录并隔离，`dispose()` 仍完成。

## 类插件

类插件使用 `PluginFactory<C,P>` 和 `PluginInitializer<P>`，不反射猜构造器。构造后先执行注解注入准备，初始化 Publisher 完成前，声明的服务不会对 consumer 可见。

## 验收用例

- `FibraInertiaParityTest`
- `PluginAndInvocationParityTest`
- `AnnotationInjectionParityTest`
- `CoreEventsParityTest`
