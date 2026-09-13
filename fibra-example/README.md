# Fibra 场景示例

`fibra-example` 按业务场景组织，每个目录包含该场景的契约、插件、宿主和验证代码，不再把不同场景的模块平铺在同一层。

| 场景 | 说明 | 入口 |
| --- | --- | --- |
| 内容清洗 | Node sidecar 贡献如何通过 Starter 接入 Spring Boot 宿主 | [`content-sanitizer/README.md`](content-sanitizer/README.md) |
| 插件依赖 | Java JAR 的契约共享、服务依赖、兼容升级与预检失败 | [`plugin-dependency/README.md`](plugin-dependency/README.md) |

两类示例展示的是同一个底座的不同接入面：`PluginRegistry` 管理制品和期望状态，`FibraEngine` 在长期
运行域内协调生命周期、完整目标保存和事实视图，runtime adapter 负责 Java 或 Node 的执行差异，具体业务
语义由场景自己的契约定义。可预检失败不会改变运行态；目标保存后的运行或资源回收失败以实际状态与诊断
呈现，不承诺恢复变更前运行态。
