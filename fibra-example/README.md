# Fibra 场景示例

`fibra-example` 只保留面向使用者的完整接入示例。正式插件及其生命周期、依赖传播和升级验收统一位于 [`fibra-plugins`](../fibra-plugins/README.md)，插件工程起点位于 [`fibra-plugin-archetype`](../fibra-plugin-archetype/README.md)，不在示例目录复制同类回归场景。

| 场景 | 说明 | 入口 |
| --- | --- | --- |
| 内容清洗 | Node sidecar 贡献如何通过 Starter 接入 Spring Boot 宿主 | [`content-sanitizer/README.md`](content-sanitizer/README.md) |

该示例展示从独立 Node package、跨语言 contribution kind、`PluginRegistry` 安装与目标提交，到 Spring Boot HTTP 接口的完整消费链。`FibraEngine` 仍独占完整 `DeploymentTarget`、长期 RuntimeDomain 与 execution unit 事实；宿主只注册业务贡献契约并消费已发布视图，不接触 sidecar 或维护第二份活动状态。
