# Java 插件依赖示例

这个场景展示 Java 插件之间如何共享契约、声明运行期服务依赖并安全升级。它保留旧 PF4J 实现中正确的依赖语义，但使用 Fibra 自己的 ClassSpace、Engine 变更协调和 Registry，不引入 PF4J 状态机。

```text
api/                 宿主观察的 checkout quote 类型
contract-plugin/     shipping 契约 1.0；无 entrypoint，只参与制品依赖图
contract-plugin-v2/  不兼容的 shipping 契约 2.0 测试制品
provider-v1/         shipping-rate 服务实现 1.0
provider-v11/        兼容契约下的服务实现 1.1
consumer-plugin/     依赖 shipping-rate 服务并提供 checkout quote
host/                安装、调用、启停与升级示例
```

## 两张依赖图

制品依赖写在 `META-INF/fibra/plugin.yaml` 的 `requires` 中，用于 SemVer 预检和 ClassLoader 可见性：

```text
shipping-rate-contract 1.x
          ↑             ↑
shipping-rate-provider  checkout-quote-consumer
```

运行期依赖写在 `PluginDefinition.require/provide` 中，用于服务就绪与生命周期传播：

```text
shipping-rate-provider --提供 shipping-rate--> checkout-quote-consumer
```

两张图不能合并。共享 Java 类型不等于消费某个服务；同样，服务依赖也不能替代 ClassLoader 对契约类型的可见性。

## 构建与验证

```bash
mvn -pl :plugin-dependency-host -am clean verify
```

测试会验证：

- contract-only JAR 没有虚构的 entrypoint，仍能作为依赖图和 ClassSpace 节点；
- provider 与 consumer JAR 不重复打包契约类；
- provider 1.0 到 1.1 的兼容升级在长期运行域内协调；
- contract 1.0 到 2.0 的不兼容升级在候选图预检时失败，目标未保存，既有制品选择、实例与 Java 资源身份以及业务服务值保留；控制诊断视图可以推进；
- 单独停用 provider 会保存该目标，consumer 与 projection 因服务依赖转为 `PENDING`，所以 `ACTIVE_REQUIRED` 目标未达成；重新启用 provider 后整条链自动收敛；
- 按依赖顺序停止和启动后，完整服务链重新收敛为 `ACTIVE`。

## 运行宿主

```bash
java -jar fibra-example/plugin-dependency/host/target/fibra-example-plugin-dependency-host.jar \
  fibra-example/plugin-dependency/contract-plugin/target/shipping-rate-contract-plugin-0.5.0-SNAPSHOT.jar \
  fibra-example/plugin-dependency/provider-v1/target/shipping-rate-provider-v1-0.5.0-SNAPSHOT.jar \
  fibra-example/plugin-dependency/provider-v11/target/shipping-rate-provider-v11-0.5.0-SNAPSHOT.jar \
  fibra-example/plugin-dependency/consumer-plugin/target/checkout-quote-consumer-plugin-0.5.0-SNAPSHOT.jar
```

[`PluginDependencyScenario`](host/src/main/java/com/sstlfsj/fibra/example/dependency/PluginDependencyScenario.java) 是组合入口。内置的 projection definition 只把长期运行域内的 `CheckoutQuoteService` 投影给本示例观察，并把该值的清理绑定到 projection 所属 `Context`；当依赖链不再可用时，旧值不可继续作为业务结果读取。业务宿主需要稳定的通用贡献调用入口时，应让插件向运行域内 `ContributionDirectory` 注册，再由 Engine 发布不可变路由并通过 `PublishedRuntime` 调用。
