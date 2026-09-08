# 贡献指南

开发环境固定为 JDK 21、Maven 3.9.9 和 Git。提交前在仓库根执行：

```bash
mvn --batch-mode --no-transfer-progress clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

架构约束：

- `fibra-core` 只实现通用生命周期、Scope、服务、事件和 effect，不感知制品、具体 runtime、Engine 或 Spring；
- `fibra-engine` 是托管变更的唯一 command loop，runtime adapter 通过同一个 `PluginRuntimeAdapter` 参加 ChangeSet；
- `fibra-artifact` 与 `fibra-config` 可脱离 `FibraRuntime` 独立使用；
- `fibra-registry` 只投影 Engine 的 artifact、desired、observed 事实，不建立第二状态机；
- `fibra-bridge` 只负责贡献目录、调用适配和排空，不负责安装或生命周期决策；
- 中立模块不得依赖 Spring，具体 Harness 或 Agent 类型不得进入 core；
- 不引入兼容层、第二事务门或运行时类型分支。

新逻辑必须带测试。日志统一使用 SLF4J，不使用 `System.out` 或 `System.err`。公开契约或模块边界变化必须先更新 vNext 设计与 API 签名基线。

Pull Request 应说明问题、架构取舍、影响面和验证结果。不要提交构建产物、IDE 元数据、凭据或私有配置。安全漏洞请按 [SECURITY.md](SECURITY.md) 的私密渠道报告。
