# M2：Context、服务与关联属性交付记录

状态：已完成

## 已实现契约

- `ServiceKey<T>` 显式声明服务名和 Java 类型。
- `provide` 返回可等待的 `ServiceRegistration<T>`；撤销先移除 binding，再通知并等待 consumer 收敛，最后移除 provider 快照。
- 每个 ACTIVE Fibra 保存依赖实现快照，读取沿 Fibra 父链解析并校验 isolate token。
- `extend`、`isolate`、共享 label 和 `intercept` 都创建不可变父链视图。
- `BoundService<T>` 与 `InvocationContext` 显式保留调用方 Context，服务内创建的 effect、插件和日志 exporter 归调用方 Fibra。
- `PropertyKey<R,T>`、`PropertyAccessor<R,T>` 和 `Associated<R>` 是 Cordis accessor、mixin、association 的 Java 类型安全替代；注册仍受 effect 生命周期管理。
- `internal/get` 与 `internal/set` waterfall 可以观察、替换或阻止默认服务读写。

## 不变量

- 同名服务在同一 isolate token 下只能有一个 provider。
- `strict=true` 时只返回 ACTIVE provider。
- 不从类名、字段名或泛型猜测服务名。
- 关联属性的读取使用创建 `Associated` 的调用方 Context，不退回 accessor 注册 Context。
- Context 关闭后，非内部公共操作必须失败。

## 验收用例

- `ServiceFibraParityTest`
- `ContextPropertyParityTest`
- `PluginAndInvocationParityTest`
- `CoreEventsParityTest`
