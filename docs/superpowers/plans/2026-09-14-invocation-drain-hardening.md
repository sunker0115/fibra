# 调用准入与排空打磨计划

> 执行约束：只使用 superpowers 工作流；按 TDD 实施；保持 `0.5.0-SNAPSHOT`、公开 API 和冻结语义不变。

**目标：** 完成权威架构第 11.9 节第 3 项的缺口证明：清理失败立即撤销贡献准入，真实同名替换不会让旧 registration identity 调到新 handler，执行中的调用取消与停用仍等待 invocation Scope 的真实清理终态。

**边界：** 不新增公开状态、错误码、诊断 record 或通用调用框架；订阅取消不伪造无人接收的结果，协作取消仍由既有 `CancellationToken` 表达。

**技术栈：** Java 21、JUnit 5、Reactor Test、Maven Surefire。

## 已有证据与最小缺口

| 范围 | 已有证据 | 本次缺口 |
|---|---|---|
| stale / revoked | `PublishedRuntimePublicationTest`、`ContributionCallTest` 已分别验证异常类型和 bridge 同名重注册 | Engine 层缺“当前 revision + 真实旧 identity”组合 |
| lease / invocation Scope | `PublishedRuntimeLeaseTest` 已验证受影响更新、子 Scope 清理、无关更新 | 取消发生时 handler 已完成；缺 handler 执行中取消再停用的直接组合 |
| cleanup failure | `ContributionCallTest`、`PublishedRuntimeLeaseTest` 已验证停用后失败排空和资源保留 | `ContributionDirectory.release` 在活跃贡献的调用清理失败时未撤销准入，后续调用仍可进入 |

## 任务 1：清理失败立即撤销贡献

**文件：**

- 修改：`fibra-bridge/src/test/java/com/sstlfsj/fibra/bridge/ContributionCallTest.java`
- 修改：`fibra-bridge/src/main/java/com/sstlfsj/fibra/bridge/ContributionDirectory.java`

1. 先写复现测试：注册并取得一个 call，直接 `failCleanup("x")`，不预先 dispose registration；当前 routes 和最新 routes 都必须拒绝新 acquire，snapshot 不再发布该贡献，registration dispose/目录 close 返回同一 `ContributionDrainException`。
2. 先运行测试并确认当前实现红灯：清理失败后仍能 acquire。
3. 在 `release` 的 cleanup-failure 分支内，以同一个 monitor 关闭 `accepting`、从 `entries` 删除并发布新 view；保留 live entry 和失败终态，直到其余 lease 归零后再以 `ContributionDrainException` 完成排空。
4. 不改变成功 release、显式 revoke、重复 close 或 batch drain 的既有行为。
5. 运行：

   ```bash
   env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
     /private/tmp/apache-maven-3.9.9/bin/mvn --batch-mode --no-transfer-progress \
     -pl fibra-bridge -am -Dtest=ContributionCallTest \
     -Dsurefire.failIfNoSpecifiedTests=false test
   ```

## 任务 2：压实 Engine 准入身份边界

**文件：**

- 修改：`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/PublishedRuntimePublicationTest.java`

1. 用真实配置更新产生同名新 contribution 和新 registration identity。
2. 使用新 view revision + 旧 identity 调用，断言 `ContributionUnavailableException`，旧、新 handler 均未被错误调用；使用旧 revision 断言 `PublishedRevisionConflictException`。
3. 使用新 revision + 新 identity 调用成功，证明拒绝没有误伤新注册。
4. 只补测试；若现有实现通过，不改生产代码。

## 任务 3：执行中取消与调用中停用

**文件：**

- 修改：`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/PublishedRuntimeLeaseTest.java`

1. 构造 handler 尚未终止且 invocation Scope 有受控清理资源的调用；取消宿主订阅后发起停用。
2. 在 handler 取消已观察到、Scope 清理未完成时，断言停用未完成、provider 资源未释放、当前视图不再准入该 contribution。
3. 释放清理闸门后，断言停用完成、provider 资源只释放一次；用当前 revision 调用旧 ID 得到 `ContributionUnavailableException`。
4. 不把订阅取消断言成业务 `cancelled` 结果；只证明取消路径继续排空并保持 lease。

## 任务 4：验证与审查

1. 运行三个相关测试类，再运行 `fibra-bridge`、`fibra-engine` 全部测试。
2. 运行一次 50 模块 `clean verify`；日志只写临时文件，避免回传大段输出。
3. `git diff --check`；确认 POM、依赖、版本和公开签名未变化。
4. 两名独立审查者分别做规格与质量复审；Critical/Important 返回原任务修正。
5. 更新本计划完成记录；等待用户推送后的同一 HEAD Linux CI。
