# Cordis core 71 项逐条门禁

真源：Cordis 提交 `8cc9e33fab69e2d0476d126baaf2acb24e6a6ab4` 的 `packages/core/tests`。每个原始 `it` 对应一个独立 Java `@Test`；Java 只替换动态语法表达，不放宽可观测行为。

| Cordis spec | 原始项数 | Java 主门禁 |
|---|---:|---|
| `associate.spec.ts` | 5 | `AssociateSpecParityTest` |
| `decorator.spec.ts` | 1 | `DecoratorSpecParityTest` |
| `dispose.spec.ts` | 13 | `DisposeSpecParityTest` |
| `events.spec.ts` | 7 | `EventsSpecParityTest` |
| `fiber.spec.ts` | 8 | `FibraSpecParityTest` |
| `invoke.spec.ts` | 2 | `InvokeSpecParityTest` |
| `isolate.spec.ts` | 3 | `IsolateSpecParityTest` |
| `logger.spec.ts` | 9 | `LoggerSpecParityTest` |
| `plugin.spec.ts` | 10 | `PluginSpecParityTest` |
| `reflect.spec.ts` | 4 | `ReflectSpecParityTest` |
| `service.spec.ts` | 5 | `ServiceSpecParityTest` |
| `shadow.spec.ts` | 4 | `ShadowSpecParityTest` |
| 合计 | 71 | 12 个测试类 |

`fibra-parity-tests/src/test/java/com/sstlfsj/fibra/migration` 另保留 37 项实现期回归，覆盖拆得更细的 Reactor、事件、注解和生命周期边界；它们不计入上述 71 项。

时序测试禁止 `Thread.sleep`。Publisher 在途边界使用 Reactor `Sinks`/`TestPublisher`，状态收敛才使用 Awaitility 4.3.0。服务撤销、effect 清理与插件移除必须等待完成，不得使用 fire-and-forget 伪造成功。

完整交付命令：

```bash
mvn verify
```
