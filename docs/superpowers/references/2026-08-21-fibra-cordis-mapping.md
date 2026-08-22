# Cordis 4.0.1 → Fibra 源码映射

## 固定真源

- DeepSeek Harness：`141eb6fef83422698aef7a981029e843e8161534`。
- 内置 Cordis：`vendor/cordis`，`package.json` 版本 4.0.1。
- 行为测试语料：Cordis `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的 `packages/core/tests`。

## 文件摘要

| 文件 | SHA-256 |
|---|---|
| `context.ts` | `96b388162d6013c1898de61e35f28917abb93809de24174980f2a1a348d0b115` |
| `events.ts` | `96565a2b5fbf35c26b78cbdc785b2b2e3f01fa55a9fd1f6af5355ab35ccc343c` |
| `fiber.ts` | `750555b47603f88e7ef7a05d1a8d629b355c176a1185af411aac6e3e1e2b7ba3` |
| `index.ts` | `c2232f082c763488225eafcd33530640ad26af0c7d6ea871a4f7e8ebf0eb3704` |
| `logger.ts` | `5121e76fd9f55b9b90dfea09d6e60d122e7530246b8b19970ddebd9c35ccf7d8` |
| `reflect.ts` | `6847b9781044023d65aebb5896a873b9f0a3d777eaf7fa0dd2a7a408eb6b74a6` |
| `registry.ts` | `34bbd60ae502b4f85201c204afb52a9acabde6878d9278493478c1b2183debd3` |
| `service.ts` | `614205c6ce7cf1a057b8b352e96aa4fcd734c967a3d2283a126c9c61f268e3be` |
| `utils.ts` | `8fe273424583d21267ef13f248840e97454d09d836a0ac08323a9482c2069263` |
| `package.json` | `4c9ed665b821f7549cb540f456ce1174b162c3ee4e0711f9526abd266246ecbb` |
| `LICENSE` | `034fb52b1d57360ecbae6cb1632a88f86fd7c3d3f5631a5f082710203dda0be7` |

## Java 映射

| Cordis | Fibra | 保真点 |
|---|---|---|
| `Context` | `Context` | extend、isolate、intercept、共享 root 内核 |
| `Fiber` | `Fibra` | uid、六态、epoch、reload/unload 收敛、root restart |
| `Fiber.effect` | `EffectHandleImpl` | 四种 source、逆序清理、嵌套 metadata、在途元素边界 |
| `ReflectService` | `ReflectRegistry` | strict service、隔离 token、provider 快照、可等待撤销 |
| accessor/mixin | `PropertyKey`、`PropertyAccessor` | effect 所有权、读写 hook、类型校验 |
| association/proxy | `Associated`、`InvocationContext` | 调用方 Context 显式保留，不依赖 ThreadLocal |
| callable service/shadow | `BoundService.invoke` | 服务值与 caller 分离，effect 和日志归 caller |
| `RegistryService` | `PluginRegistry` | 对象身份分组、查询、可等待批量移除 |
| `Events` | `EventBus`、`EventKey` | 五种分派模式、once、prepend、global、filter |
| internal events | `CoreEvents` | plugin/status/service/update/get/set/listener/dispatch |
| `LoggerService` | `LoggerService`、`BoundLoggerService` | 固定 buffer、exporter、级别、名称和所有权 |
| decorators | `@InjectService`、`@InjectServices` | 字段真实依赖、方法嵌套 Fibra |

## Java 语法替换边界

Java 不复制 JavaScript Proxy、可调用对象和原型链语法；对应能力改为编译期可检查的显式 API。这个替换只改变调用写法，不改变作用域、调用方、资源所有权、顺序、异常和完成边界。禁止退化为全局 service locator、ThreadLocal caller 或 fire-and-forget 清理。
