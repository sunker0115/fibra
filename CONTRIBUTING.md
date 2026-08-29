# 贡献指南

感谢你对 Fibra 的关注。提交改动前，请先在当前开发分支确认问题，并尽量让一次贡献只解决一个明确目标。

## 开发环境

- JDK 21；
- Maven 3.9.9；
- Git。

在仓库根目录执行完整验证：

```bash
mvn --batch-mode --no-transfer-progress clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

第一条执行全 reactor 测试和 `artifact` 门禁；第二条验证十个发布 `artifact` 可复现；第三条在隔离 Maven 仓库和仓库外工程中验证真实消费。局部开发可以先运行受影响模块的测试，但提交前必须完成与改动影响相称的完整门禁。

## 架构与代码

- `fibra-engine` 是插件、配置、source、reconcile、deployment 和关闭的唯一托管入口；宿主不得绕过它重新组合内部 loader。
- `fibra-core` 只实现 Cordis 等价的 Context/Fibra 生命周期，不感知 PF4J、配置、Engine 或 Spring。
- PF4J 只负责 `artifact`、依赖图和 ClassLoader；动态业务生命周期只由 Fibra 管理。
- 六个框架中立运行时 `artifact` 不得引入 Spring；Spring 适配不得复制 Engine 的协调算法。
- 修复缺陷时优先增加复现测试；新逻辑必须同时包含测试。
- 日志使用 SLF4J，不使用 `System.out` 或 `System.err`。
- 不在同一 Pull Request 中夹带无关重构、格式化或依赖升级。

改变公开契约或模块边界前，请先更新对应设计文档和 OpenSpec。公开 Java 签名变化还必须同步 `docs/api/*-public-signatures.txt`；不得只修改签名基线来掩盖意外破坏。

## 提交问题

提交前请先搜索已有 Issue。缺陷报告至少应包含：

- Fibra、JDK、Maven、PF4J 和 Spring Boot 版本；
- 最小复现代码或仓库；
- 预期行为与实际行为；
- 完整异常堆栈和必要日志，移除凭据及业务敏感数据。

安全漏洞不要通过公开 Issue 报告。仓库公开后，请使用项目安全策略中指定的私密渠道。

## 提交 Pull Request

Pull Request 应说明问题、架构取舍、影响面和验证结果。提交前请确认：

- 相关测试和全仓门禁通过；
- 对外行为变化已同步 README、API 文档、设计文档或 OpenSpec；
- 没有提交构建产物、IDE 元数据、凭据或私有配置；
- 破坏性变更明确说明迁移影响，不保留未经设计确认的兼容分支。

提交贡献即表示你有权提交相关内容，并同意该贡献按照项目根 `LICENSE` 中的许可证进行许可。
