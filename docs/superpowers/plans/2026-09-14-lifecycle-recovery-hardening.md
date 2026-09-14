# 生命周期、变更与恢复打磨计划

> 执行约束：只使用 superpowers 工作流；按 TDD 逐项提交；保持 `0.5.0-SNAPSHOT`、公开 API 与既有语义不变。

**目标：** 关闭权威架构第 11.9 节第 2 项仍缺少的直接证据：生命周期竞态测试不得吞异常，重复关闭的并发观察者共享同一终态，Engine 失败诊断指出来源阶段，进程中断后按已保存目标恢复。

**边界：** 不引入新的恢复日志、事务协议、公共字段或测试专用生产扩展点；现有测试已经充分证明的 Scope/Effect、持久化和 reconcile 语义不重复实现。

**技术栈：** Java 21、JUnit 5、Reactor Test、Maven Surefire、子 JVM fixture。

## 现有证据与缺口

| 范围 | 现有直接证据 | 本次最小缺口 |
|---|---|---|
| Scope/Effect 晚到资源、初始化与清理失败 | `EffectCreationContractTest`、`EffectCleanupContractTest`、`DrainLifecycleTest`、`CleanupFailureDiagnosticsTest` | `ScopeRegistrationRaceTest` 丢弃后台 Future 且存在无界等待；关闭进行中重复 `closeAsync()` 缺共享终态断言 |
| prepare、artifact/target save | `ApplyDeploymentPersistenceBoundaryTest`、`EnginePersistenceBoundaryTest`、`ArtifactStoreTest`、`FileEngineStateStoreTest` | 多数恢复经过正常关闭；缺候选准备中断、artifact 已保存但 target 未保存时的真实进程终止证据 |
| reconcile、retire | `ApplyDeploymentMountFailureRecoveryTest` 已覆盖 reconcile 失败后真实 `halt` 与重启；`RuntimeResourceOwnershipTest`、`ApplyDeploymentPersistenceBoundaryTest` 覆盖 retire 失败 | retire 期间真实进程终止后的重启证据缺失 |
| 失败阶段诊断 | 执行中发布 `PREPARING/SAVING/RECONCILING/RETIRING`；最终失败统一为 `FAILED` | `failure` 文本不保留来源阶段；不能从最终诊断判断失败发生在哪一段 |

## 任务 1：压实 Scope 并发证据

**文件：**

- 修改：`fibra-core/src/test/java/com/sstlfsj/fibra/runtime/ScopeRegistrationRaceTest.java`
- 修改：`fibra-core/src/test/java/com/sstlfsj/fibra/runtime/DrainLifecycleTest.java`

1. 先改测试使 `ScopeRegistrationRaceTest` 保存两个 Future，并以 5 秒截止等待 ready 与任务结果；确认旧测试在注入后台异常时会漏报，修正后异常会传回测试线程。
2. 增加参数化测试：排空尚未完成时两次 `closeAsync()` 都不得提前完成；成功时两者完成且资源只释放一次，排空失败时两者得到同一失败，普通资源不释放且诊断保留。
3. 运行：

   ```bash
   env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
     /private/tmp/apache-maven-3.9.9/bin/mvn --batch-mode --no-transfer-progress \
     -pl fibra-core -Dtest=ScopeRegistrationRaceTest,DrainLifecycleTest test
   ```

4. 提交测试补强；不修改生产代码。

## 任务 2：保留 Engine 失败来源阶段

**文件：**

- 修改：`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/ApplyDeploymentPersistenceBoundaryTest.java`
- 修改：`fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`

1. 先为既有 prepare、artifact save、target save、reconcile、retire 故障注入增加最终 `EngineDiagnostics.failure()` 的阶段断言，确认当前失败诊断不能稳定区分来源阶段。
2. 在内部 `ChangeSet` 记录正在执行的 `ChangePhase`；`fail` 进入 `FAILED` 前把该阶段写入既有 `failure` 文本。`EngineDiagnostics` 形状、异常类型、`phase == FAILED` 和既有错误内容保持不变。
3. `stage()` 发生在 `execute()` 前，因此默认来源必须是 `PREPARING`；最终目标满足性检查仍属于 `RECONCILING`，不得因已暂时切到 `IDLE` 而误报。
4. 运行：

   ```bash
   env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
     /private/tmp/apache-maven-3.9.9/bin/mvn --batch-mode --no-transfer-progress \
     -pl fibra-engine -am -Dtest=ApplyDeploymentPersistenceBoundaryTest \
     -Dsurefire.failIfNoSpecifiedTests=false test
   ```

5. 提交测试与最小实现。

## 任务 3：补齐真实进程中断恢复

**文件：**

- 新增：`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineCrashPointRecoveryTest.java`
- 复用：`fibra-engine/src/test/java/com/sstlfsj/fibra/engine/ApplyDeploymentMountFailureRecoveryTest.java`

1. 先写有界子 JVM 场景，分别在 runtime prepare、artifact 已保存但 target 尚未写入、retire 开始时调用 `Runtime.halt`；父测试要求 5 秒内取得预期退出码。
2. 每个场景重开真实 `ArtifactStore`、`FileEngineStateStore` 和 Engine：
   - prepare 中断：旧目标与旧制品恢复；候选不得发布；
   - artifact 已保存、target 未保存：仍恢复旧目标，孤立的新制品不得被选中；
   - retire 中断：已确认的新目标恢复，新实例达成 `ACTIVE`。
3. 不增加生产 hook；只使用已有 `PluginRuntimeAdapter`、`EngineStateStore` 和测试子进程边界。
4. 运行：

   ```bash
   env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
     /private/tmp/apache-maven-3.9.9/bin/mvn --batch-mode --no-transfer-progress \
     -pl fibra-engine -am \
     -Dtest=EngineCrashPointRecoveryTest,ApplyDeploymentMountFailureRecoveryTest \
     -Dsurefire.failIfNoSpecifiedTests=false test
   ```

5. 提交测试；只有测试暴露真实产品缺陷时才回到 TDD 修复，连续两次失败即停止并报告。

## 任务 4：集成验证与独立审查

1. 运行 `fibra-core`、`fibra-engine` 全部测试。
2. 运行 50 模块 `clean verify`；不重复仓库外发行验证，因为本项不改变发行物形状或公开 API。
3. 运行 `git diff --check`，确认 POM、依赖、公开签名和版本未变化。
4. 由独立审查者核对权威第 11.9 节第 2 项、实际 diff 与测试证据；Critical/Important 问题回到原实现任务修正并复审。
5. 更新本计划完成状态；等待用户推送后，以同一 HEAD 的 Linux CI 作为最终远端证据。
