# Fibra 场景示例

`fibra-example` 按业务场景组织，每个目录包含该场景的契约、插件、宿主和验证代码，不再把不同场景的模块平铺在同一层。

| 场景 | 说明 | 入口 |
| --- | --- | --- |
| 内容清洗 | Node sidecar 贡献如何接入纯 Java 和 Spring Boot 宿主 | [`content-sanitizer/README.md`](content-sanitizer/README.md) |
| 插件依赖 | Java JAR 的契约共享、服务依赖、兼容升级和事务回滚 | [`plugin-dependency/README.md`](plugin-dependency/README.md) |

两类示例展示的是同一个底座的不同接入面：`PluginRegistry` 管理制品和期望状态，`FibraEngine` 管理生命周期与代际事务，runtime adapter 负责 Java 或 Node 的执行差异，具体业务语义由场景自己的契约定义。
