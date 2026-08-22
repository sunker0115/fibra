# M4：注册表、事件、日志与注入交付记录

状态：已完成

## 插件注册表

`Context.registry()` 按插件入口对象身份分组。函数插件使用 `Plugin` 对象，类插件使用 `PluginFactory` 对象作为身份。注册表提供 runtime 数量、存在性、Fibra 快照及可等待的批量移除。

## 事件

统一 hook 表支持：

- `on`、`once`、prepend、global 和 target filter；
- `emit`、`parallel`、`serial`、`bail`、`waterfall`；
- `internal/plugin`、`status`、`service`、`update`、`get`、`set`、`listener`、`dispatch`。

`parallel` 等待所有 listener 后聚合错误；`serial` 按顺序等待并在首个 bail 值停止；`once` 在用户回调前注销。

## 日志

`LoggerService` 保留固定对象的有界 buffer、多个 exporter、按 exporter 精确注销、级别过滤和名称推导。最终输出使用 SLF4J 门面；core 不绑定 provider。通过 `Context.logging()` 注册的 exporter 归当前调用方 Fibra。

## 注入

- 字段 `@InjectService` 被编译为真实 descriptor 依赖。
- 方法 `@InjectServices` 创建嵌套 Fibra，服务撤销或替换时自动卸载和重新激活。
- 所有注入路径复用同一 Fibra/ServiceKey 生命周期，不建立旁路容器。

## 验收用例

- `EventParityTest`
- `CoreEventsParityTest`
- `LoggerParityTest`
- `AnnotationInjectionParityTest`
- `PluginAndInvocationParityTest`
