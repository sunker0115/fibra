# Fibra `0.4.0` Engine 实施计划

日期：2026-08-24  
状态：已确认并执行中，作为 `establish-fibra-engine` 的唯一实施细节权威源

架构真源：[Fibra Engine 最终架构](../specs/2026-08-24-fibra-engine-architecture.md)。形式化行为真源：[`establish-fibra-engine`](../../../openspec/changes/establish-fibra-engine/)。本计划只规定代码落点、公共签名、TDD 顺序、验证命令和提交边界。三者冲突时先同步文档，不在实现中选择未记录的第三种语义。

## 0. 已核实基线与执行纪律

- 🟢 实施起点：reactor 原有五个框架中立生产模块、一个 Spring starter、example 和 parity，唯一版本真源为 `0.3.1`；本计划完成态以十个发布制品和 `0.4.0-SNAPSHOT` 为准。
- 🟢 `fibra-loader-pf4j/src/main/java/com/sstlfsj/fibra/loader/pf4j/FibraPluginLoader.java:29-155`：当前 loader 同时拥有制品、运行 entry、一步式事务和公开 `runExclusive`。
- 🟢 `fibra-loader-pf4j/src/main/java/com/sstlfsj/fibra/loader/pf4j/PluginUpdateTransaction.java:16`、`PluginCrashRecovery.java:10`：现有单 loader journal/恢复实现是重构输入，不建立第二套并行算法。
- 🟢 `fibra-loader-config/src/main/java/com/sstlfsj/fibra/loader/config/FibraConfigLoader.java:32-245`：当前 loader 混合配置解析、文件写入、运行 reconcile 和 watcher 所有权。
- 🟢 `fibra-loader-pf4j/src/main/java/com/sstlfsj/fibra/loader/pf4j/FibraPluginWatcher.java:27`、`fibra-loader-config/src/main/java/com/sstlfsj/fibra/loader/config/FibraConfigWatcher.java:24`：两个 watcher 是必须迁出并删除的旧公共 API，不保留 deprecated、转发或双路径。
- 🟢 `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java:21-151`：发布制品、外部消费方模块及依赖边界已有自动门禁，必须与共享符号同一任务更新。
- 🟢 实施起点的可复现构建脚本固定六个制品；engine change 先扩为七个，Spring 与 archetype change 完成后统一扩为十个。
- 🟢 `verification/distribution/pom.xml`：仓库外工程不在 Fibra reactor 内，Engine application 当前直接消费 config loader；本 change 将它改为消费 engine。

执行规则：

1. 每个任务先写失败测试，确认失败原因正是缺少目标能力，再写最小实现；测试和实现进入同一提交。
2. 不修改 `fibra-api`、`fibra-core`、`fibra-pf4j-api` 的公开语义；Cordis 71 项测试每个阶段保持通过。
3. 删除 watcher 时同步删除测试、签名和现行文档，不留兼容代码。可复用的 watch 行为测试迁到 engine source/coordinator 测试，不复制实现。
4. 所有管理操作继续由 🟢 `LoaderOperationGate` 提供逻辑可重入、跨线程 fail-fast 的底层门；engine 在其外层提供唯一排队和重试，不把 loader 的 busy 变为阻塞锁。
5. engine 不新增 Spring 依赖；按源码真实使用关系直接依赖 `fibra-api`、`fibra-core`、`fibra-loader-pf4j`、`fibra-loader-config`、PF4J 和 `slf4j-api`，测试可使用 JUnit、Awaitility、`slf4j-simple`。
6. engine 持久状态目录固定为 `<installedRoot>/.fibra-engine/`，其中只有 `transactions/`、`revisions/` 和临时预检目录；安装根、配置根的父目录必须在构造任何 Context、loader、线程或 WatchService 前通过完整校验。
7. deployment 中的 `config/` 只能相对引用包内文件；安装时映射到 `configLocation` 所在配置树。恢复备份写到 engine transaction，再通过目标文件同目录临时文件和原子 move 恢复，因此配置根不要求与插件根处于同一 FileStore。
8. Maven 命令从仓库根执行，先使用项目 `mvn-env` 规定的 JDK 21 与 Maven 3.9.9；定向测试带 `-Dsurefire.failIfNoSpecifiedTests=false`。
9. 每项完成后运行定向测试和 `git diff --check`；直到最终收口才勾选 OpenSpec 完成状态。

## 1. 建立 `0.4.0-SNAPSHOT` 与 `fibra-engine` 模块基线

先修改测试：

- 修改 🟢 `ReleaseArtifactBaselineTest`：框架中立运行时列表加入 `fibra-engine`，总发布制品仍暂为七个；外部 Engine application 依赖断言留到第 9 项随消费方同一提交修改。
- 新增 `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/EngineDependencyBoundaryTest.java`：读取 `fibra-engine` 依赖图，断言 compile/runtime 不含 `org.springframework*`、Spring Boot、Spring Shell、Spring AI。
- 新增 `fibra-engine/src/test/java/com/sstlfsj/fibra/engine/FibraEngineStateTest.java`：锁定 `NEW/STARTING/RUNNING/DEGRADED/STOPPING/TERMINATED` 六个终止性状态，确保首个主 JAR 包含真实公共 API class。

确认红灯：根 POM 尚无模块，engine POM/产物不存在，发布基线失败。

再实现：

- `pom.xml`：`revision` 改为 `0.4.0-SNAPSHOT`；在 `fibra-loader-config` 后加入 `fibra-engine`；dependencyManagement 加入同版本 engine；根注释由“五个生产模块”重写为当前事实。
- 新增 `fibra-engine/pom.xml`：可发布 JAR，直接依赖 `fibra-api`、`fibra-core`、`fibra-loader-pf4j`、`fibra-loader-config`、PF4J 与 `slf4j-api`；测试依赖 JUnit、Awaitility、`slf4j-simple`。
- 新增 `FibraEngineState.java` 作为首个真实公共类型；不加入 placeholder、空 facade 或临时 API。
- `scripts/verify-reproducible-release.sh`：生产模块数组和 `module_list` 加入 engine。

验证：

```bash
$MVN -N help:evaluate -Dexpression=revision -q -DforceStdout
$MVN -pl fibra-engine,fibra-parity-tests -am -Dtest=EngineDependencyBoundaryTest,FibraEngineStateTest,ReleaseArtifactBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：版本只解析为 `0.4.0-SNAPSHOT`；engine 进入 reactor、dependencyManagement 和七制品门禁；Spring 坐标不进入 engine。

提交边界：`chore: start 0.4.0 engine development`

## 2. 把 artifact 一步事务拆成可组合参与者

公共签名冻结为：

```java
public interface FibraPluginCatalog {
    List<FibraArtifactDescriptor> artifacts();
    Optional<Class<?>> configType(String pluginId);
}

public record FibraArtifactDescriptor(String id, String version, String sha256) { }

public interface FibraArtifactChange extends AutoCloseable {
    List<String> changedArtifactIds();
    FibraPluginCatalog targetCatalog();
    void commit();
    void complete();
    void rollback();
    void close();
}

public final class FibraPluginLoader implements AutoCloseable {
    public FibraArtifactDescriptor inspectArtifact(Path candidate);
    public FibraArtifactChange prepareArtifacts(List<Path> candidates, Path workspace);
    public FibraPluginCatalog catalog();
    // 现有 applyArtifacts 继续存在，但只编排上述参与者，不保留第二套算法。
}
```

`inspectArtifact` 只返回规范路径对应的 ID、version 和 SHA-256，不创建候选 ClassLoader、不修改磁盘；engine 用它分组，再用 PF4J `DefaultVersionManager` 选择最高 SemVer。`prepareArtifacts` 返回时已经完成复制、安全解压、SHA-256、依赖图、入口类型、候选 ClassLoader 和磁盘 next/previous 准备，但未拆旧运行态、未换安装目录；`workspace` 必须是调用方创建的空目录。change 只能在创建它的 `runExclusive` 所有者线程使用。`close()` 对 PREPARED/COMMITTED 状态执行 rollback，对 COMPLETED/ROLLED_BACK 幂等返回；非法状态迁移抛 `IllegalStateException`。

先新增/改写失败测试：

- `FibraArtifactChangeTest.java`：prepare 零运行态变化；target catalog 同时看见保留包和候选包；commit 执行确定性切换但保留 previous；complete 才清理；各状态 close/rollback 幂等与非法迁移。
- `FibraPluginCatalogTest.java`：无副作用 inspect、artifact 顺序、版本、64 位小写 SHA-256、contract-only 的空 configType、多层依赖候选 configType、关闭 change 后候选类不可再查询。
- 改写 🟢 `FibraPluginLoaderTest`：`applyArtifacts` 的成功、失败、回滚、no-op 全部证明调用同一 change 实现。
- 改写 🟢 `PluginTransactionJournalTest`、`PluginCrashRecoveryTest`：单 loader wrapper 仍保持原 journal-first 和逐 ID 恢复语义。

再实现：

- 新增上述三个公开类型。
- 把 🟢 `PluginUpdateTransaction` 重构为包内 `PreparedArtifactChange`；复用现有 inspector、preflight、journal、crash recovery，不复制 ZIP/依赖/恢复代码。
- 🟢 `FibraPluginLoader.applyArtifacts` 在一个 `runExclusive` 中创建自己的单参与者 workspace/journal，依次 prepare、commit、complete；失败时 rollback 并保留原异常为主异常、恢复异常为 suppressed。
- `catalog()` 返回活动图不可变快照，不暴露 PF4J wrapper、Plugin 实例或 ClassLoader；`targetCatalog()` 生命周期绑定 change。

验证：

```bash
$MVN -pl fibra-loader-pf4j -am -Dtest=FibraArtifactChangeTest,FibraPluginCatalogTest,FibraPluginLoaderTest,PluginTransactionJournalTest,PluginCrashRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：单 loader 与 engine 将使用同一 prepare/commit/complete/rollback 实现；prepare 失败严格早于旧 ClassLoader 关闭。

提交边界：`refactor: expose composable artifact changes`

## 3. 把 config 解析与变更拆成可组合参与者

公共签名冻结为：

```java
public interface FibraConfigChange extends AutoCloseable {
    FibraConfigSnapshot targetSnapshot();
    void commit();
    void complete();
    void rollback();
    void close();
}

public final class FibraConfigLoader implements AutoCloseable {
    public FibraConfigSnapshot resolve();
    public Set<Path> sourcePaths();
    public FibraConfigChange prepareCurrent(FibraPluginCatalog catalog, Path workspace);
    public FibraConfigChange prepareReplacement(Path stagedConfig,
                                                FibraArtifactChange artifacts,
                                                Path workspace);
    // load/refresh/create/update/remove 均保留，但只编排同一底层 change。
}
```

`prepareCurrent` 读取配置当前完整快照；`prepareReplacement` 把 staged 根及其包内 include 映射到配置根，且必须与传入 artifact change 同处一个外层 `runExclusive`。prepare 完成解析、限制、ID、依赖、目标 configType 转换、目标文件备份和临时文件落盘，不改变配置文件、snapshot 或运行实例。内部只保存配置 literal 与 `PluginConfigFactory`，不得保存候选 typed config 对象、候选 `Class<?>` 或候选 ClassLoader。

`sourcePaths()` 返回不可变、绝对归一化集合，包含根配置、最后成功 snapshot 的 include 以及最后一次失败 resolve 的 attempted paths；构造后即可调用。它只为宿主 source 建立监听集合，不启动 watcher、不执行 refresh。

先新增/改写失败测试：

- 新增 `FibraConfigChangeTest.java`：current/replacement prepare 无副作用；候选插件新 configType 可预检；commit 切文件和 runtime；complete 清 previous；rollback 恢复文件、snapshot、entry 和服务；跨 schema 降级失败安全。
- 在 🟢 `FibraConfigLoaderTest` 保留并迁移 load/refresh、typed config、create/update/remove、文件失败和 runtime 失败覆盖，证明便捷 API 只走 change。
- 新增候选 ClassLoader 弱引用测试：prepare 失败、rollback、complete 后不被 config snapshot/factory 强引用。
- 覆盖配置数据载体 round-trip：YAML 标量、列表、映射、null、默认 config、include 来源和 patch 后写回再 resolve 等价。

再实现：

- 新增 `FibraConfigChange` 与包内 `PreparedConfigChange`。
- 从 🟢 `FibraConfigLoader` 提取 resolve/prepare/commit/complete/rollback，复用 `ConfigTreeResolver`、`ConfigDocumentWriter`、`LiteralValues` 和现有 reconcile；删除并行 rollback deque 算法。
- replacement 只允许 staged 根的相对 include；拒绝绝对路径、`..` 越界、symlink、重复映射和写出配置树。
- 联合回滚时 config change 先卸载新 runtime并恢复配置文件/snapshot，artifact change 随后恢复旧插件和 prepare 时捕获的旧 entry specs；不在 config change 中重新持有旧插件类型。

验证：

```bash
$MVN -pl fibra-loader-config -am -Dtest=FibraConfigChangeTest,FibraConfigLoaderTest,ConfigTreeResolverTest,ConfigDocumentReaderTest -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：当前配置和 staged deployment 配置共享唯一变更实现；typed config 可对候选 catalog 预检，结束后不保留候选类型。

提交边界：`refactor: expose composable config changes`

## 4. 实现 source/coordinator 并删除 loader watcher 公共 API

新增 engine 内部 `ArtifactDirectorySource`、`ConfigFileSource`、`ReconcileCoordinator` 和测试专用 `ReconcileAction` 接缝。source 只调用无参数 dirty callback，不传路径操作；coordinator 使用容量一的信号位加 `dirtyWhileRunning`，不建立无界事件队列。

先迁移并扩展测试：

- 把 🟢 `FibraPluginWatcherTest` 的原子 ZIP、去抖、busy 保留 dirty、close 等待场景迁入 `ArtifactDirectorySourceTest`/`ReconcileCoordinatorTest`。
- 把 🟢 `FibraConfigLoaderTest` 中 watcher 恢复、missing include、busy、callback close、并发 converge 场景迁入 `ConfigFileSourceTest`/coordinator 测试。
- `ArtifactDirectorySourceTest.java`：只监听 incoming 直接子级原子发布的 `.zip`；部分文件、子目录、删除、close 后事件不触发；构造中途失败释放 WatchService/线程。
- `ConfigFileSourceTest.java`：监听 root、当前 include 和上次失败尝试路径；snapshot 变化后原子替换监听集合；missing include 创建后可恢复；close 等待 callback 边界且 worker 回调 close 不自等。
- `ReconcileCoordinatorTest.java`：突发事件去重；执行期 dirty 保证再跑一次；同一时刻最多一个操作；周期 resync；busy/真实失败有界指数退避；close 停止接收并收敛在途操作。
- 修改 API 基线测试，先断言旧类型和 `watch(...)`/公开 `runExclusive` 不再是用户管理入口；`runExclusive` 保留为 engine 组合事务所需的低层 API，但文档明确普通宿主不得并发绕过 engine。

再实现并删除：

- source 使用 JDK `WatchService`，只拥有自身单线程；debounce 由 source 合并为一次 dirty callback。
- coordinator 使用单个 daemon worker，不持 monitor/Lock 跨 reconcile callback；底层 busy 只触发重试，不写成候选损坏。
- `FibraPluginWatcher.java`、`FibraPluginWatchFailure.java`、`FibraConfigWatcher.java`、`FibraConfigReloadFailure.java` 及其旧测试。
- 🟢 `FibraConfigLoader` 中 watcher 字段、`watch(...)`、callback close 特例和 watchedPaths 所有权；由第 3 项冻结的公开 `sourcePaths()` 提供 engine 所需只读路径集合，公共 snapshot 不暴露可变集合。
- 同步 `docs/api/fibra-loader-pf4j-public-signatures.txt`、`docs/api/fibra-loader-config-public-signatures.txt`，删除现行 README 中旧 watcher 用法。

验证：

```bash
$MVN -pl fibra-engine,fibra-parity-tests -am -Dtest=ArtifactDirectorySourceTest,ConfigFileSourceTest,ReconcileCoordinatorTest,ApiSignatureBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "FibraPluginWatcher|FibraPluginWatchFailure|FibraConfigWatcher|FibraConfigReloadFailure|\.watch\(" --glob '!target/**' --glob '!openspec/changes/**' .
git diff --check
```

成功标准：迁移后的 engine 测试覆盖旧 watcher 的有效行为并新增双源去重语义；生产源码、测试、签名和现行用户文档不存在旧 watcher；历史归档文档可保留且必须标明历史。

提交边界：`refactor: move file watching into the engine`

## 5. 实现 deployment package 的纯预检层

新增 engine 内部数据类型：

- `DeploymentPackageInspector`、`InspectedDeploymentPackage`、`DeploymentDescriptor`、`DeploymentChecksum`。
- 公开 `FibraDeploymentException`、`FibraDeploymentErrorStage`，stage 固定为 `READ`、`VALIDATE`、`PREPARE`、`COMMIT`、`READINESS`、`ROLLBACK`；构造期恢复无法证明一致状态同样报告 `ROLLBACK`。

先写失败测试 `DeploymentPackageInspectorTest.java`：

- 正常 `deployment.properties`、排序后的 plugins、config 根和 `checksums.sha256`；SHA-256 大小写/长度/重复/缺失/额外行。
- ZIP slip、绝对路径、反斜杠、symlink、重复 entry、未知顶层、条目数/单项/总解压大小限制、多个/缺少 config 根、插件路径未排序或重复。
- deployment id 使用插件 ID 同级安全字符集；version 使用 PF4J 3.15.0 已锁定 SemVer manager；同 id/version 不同摘要拒绝，同摘要为幂等 no-op。
- config include 只能留在 `config/`；插件 ZIP 原字节复制到 participant 输入区，不二次打包。

再实现：

- 使用现有 Apache Commons Compress，不增加 ZIP 库；摘要固定 JDK `MessageDigest` SHA-256，不引入 MD5。
- `deployment.properties` 固定键：`deployment.id`、`deployment.version`、`config.path`、连续 `plugin.0`…`plugin.n`；拒绝未知键、空洞序号和重复路径。
- `checksums.sha256` 覆盖 properties、每个插件 ZIP 和配置树中每个普通文件，不覆盖自身；行格式固定为 `<64位小写摘要><两个空格><相对路径>`，按相对路径字典序。

验证：

```bash
$MVN -pl fibra-engine -am -Dtest=DeploymentPackageInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：所有外部字节和路径风险在创建候选插件 ClassLoader、Context、线程或修改运行态前失败。

提交边界：`feat: validate Fibra deployment packages`

## 6. 实现 engine journal、崩溃恢复、公共生命周期与状态模型

持久状态固定为 `PREPARING`、`PREPARED`、`COMMITTING_ARTIFACTS`、`COMMITTING_CONFIG`、`VERIFYING`、`COMMITTED`、`ROLLING_BACK`。journal 记录 operation identity/revision、artifact/config participant workspace、每个 participant 阶段及前后 SHA-256；第 8 项 deployment coordinator 只能使用这些状态，不再新增第二份 journal 格式。

公共签名冻结为：

```java
public final class FibraEngine implements AutoCloseable {
    public static Builder builder(Path installedRoot, Path configLocation);
    public void start();
    public void requestReconcile();
    public FibraDeploymentResult applyDeployment(Path packagePath);
    public FibraEngineStatus status();
    public Context root();
    public boolean isRunning();
    public void close();
}

public enum FibraEngineState { NEW, STARTING, RUNNING, DEGRADED, STOPPING, TERMINATED }

public record FibraEngineFailure(FibraEngineFailureStage stage,
                                 Optional<String> revision,
                                 String message,
                                 Instant occurredAt) { }

public record FibraEngineStatus(FibraEngineState state,
                                Optional<String> desiredRevision,
                                Optional<String> appliedRevision,
                                List<FibraEngineFailure> failures) { }

public record FibraDeploymentResult(String deploymentId,
                                    String deploymentVersion,
                                    String appliedRevision,
                                    List<String> changedArtifactIds) { }

public enum FibraEngineFailureStage {
    STARTUP, ARTIFACT_RECONCILE, CONFIG_RECONCILE,
    DEPLOYMENT, READINESS, CLOSE
}
```

builder 命名方法：`artifactSource(Path, Duration)`、`configSource(Duration)`、`requiredEntries(Collection<String>)`、`readinessTimeout(Duration)`、`rootCloseTimeout(Duration)`、`resyncInterval(Duration)`、`retryBackoff(Duration, Duration)`。默认两个 source 关闭、required entries 为空、readiness 60 秒、root close 30 秒、resync 30 秒、重试 250 毫秒到 30 秒。全部集合防御复制；duration 非负且 max 不小于 initial；路径绝对归一化且不得互相包含造成 incoming 被安装扫描。

先写失败测试：

- `EngineTransactionJournalTest.java`：状态/participant 字段排序、合法迁移、缺失/重复 participant、损坏、force/atomic move 不可用时拒绝。
- `EngineCrashRecoveryTest.java`：每个持久状态、artifact/config 每个半提交组合、COMMITTED 后清理崩溃、无 journal 空目录清理、有 previous 无 journal 拒启、摘要未知拒启；恢复只处理磁盘和配置文件，不创建 PF4J ClassLoader。
- `FibraEngineBuilderTest.java`：所有路径/时间/集合组合、默认值、防御复制、路径别名与 symlink、验证失败零资源、state root `<installedRoot>/.fibra-engine`。
- `FibraEngineLifecycleTest.java`：NEW→STARTING→RUNNING；初载、config、required readiness 任一失败后逆序清理并 TERMINATED；不可 restart；close 幂等；close 顺序用真实 root/loader 资源观测。
- `FibraEngineStatusTest.java`：四个数据载体的相等性、Optional 默认、不可变列表 round-trip，失败快照按 stage 唯一且不保存 Throwable 或插件类型。

再实现 `EngineTransactionJournal`、`EngineCrashRecovery`、上述公开类型及包内 `EngineResources`。journal 的临时文件 force、父目录 force、原子 rename 和 payload-first/journal-last 清理沿用 🟢 `PluginTransactionJournal` 已验证模式。engine builder 的 `build()` 固定按“全部路径校验 → `<installedRoot>/.fibra-engine` 崩溃恢复 → root → plugin loader → config loader”执行；恢复早于 PF4J manager/ClassLoader 创建。Spring 可在 lifecycle start 前取得 root；build 不创建线程/source，也不公开内部 loader。`start()` 执行初载、配置和 readiness，成功后才创建 source/coordinator。build 或 start 任一步失败都逆序释放已取得资源，start 失败后该实例终止且不可重启。

验证：

```bash
$MVN -pl fibra-engine -am -Dtest=EngineTransactionJournalTest,EngineCrashRecoveryTest,FibraEngineBuilderTest,FibraEngineLifecycleTest,FibraEngineStatusTest -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：engine 是 root、两个 loader、source、worker、journal root 的唯一终止性所有者；无 Spring 依赖。

提交边界：`feat: add the Fibra engine lifecycle`

## 7. 把 source/coordinator 接入真实 desired state

新增内部 `DesiredStateReader`、`DesiredState`、`EngineRevision`，把第 4 项的 source/coordinator 接入 engine 和两个变更参与者。reconcile 不消费事件差量，每次读取完整安装目录、incoming 候选和配置依赖树。incoming 按插件 ID 选择唯一最高 SemVer；低于已安装版本的候选忽略，同版本同摘要 no-op，同版本不同摘要失败；成功不要求删除候选文件。

一轮同时发现 artifact/config 变化时，固定先执行独立 artifact 事务，再对执行后的活动 catalog 执行独立 config 事务；两个事务不共享候选 catalog、不合并 rollback。artifact 失败不阻止 config 独立尝试，二者按 `ARTIFACT_RECONCILE`、`CONFIG_RECONCILE` 分别记录或清除失败。只有 `applyDeployment` 使用第 8 项联合事务。

先写失败测试：

- `DesiredStateReaderTest.java`：候选目录排序、同 ID 最高 SemVer、低版本忽略、同版本摘要规则、稳定文件检查、完整配置来源摘要、相同内容相同 revision、任一插件或 include 字节变化产生新 revision。
- 扩展 `ReconcileCoordinatorTest.java`：手工 deployment 执行期间只累计 dirty；artifact/config 独立尝试和独立失败；成功更新 desired/applied revision；失败保持最后成功 applied revision；下一次成功只清对应 stage 的 retry和失败。
- `FibraEngineReconcileIT.java`：真实多插件目录和 typed config；先 consumer 后 provider 最终收敛；失败保留最后成功服务与 applied revision；修正后自动恢复。

再实现：

- engine `start()` 成功初载后启动 coordinator/source；`requestReconcile()` 与两个 source 进入同一信号位；`applyDeployment()` 通过同一 coordinator 的独占操作入口执行。
- 停止接收后关闭 source、丢弃尚未开始的重复信号、等待正在执行操作完成；不得持 monitor/Lock 跨 loader 调用或 Fibra await。
- revision 为完整 desired 输入的规范 SHA-256：已安装/候选插件 descriptor+摘要、配置 snapshot literal+来源摘要；不使用时间戳作为身份。

验证：

```bash
$MVN -pl fibra-engine -am -Dtest=DesiredStateReaderTest,ReconcileCoordinatorTest,FibraEngineReconcileIT -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：双 watcher 不直接交叉调用 loader；丢事件由完整重读和 resync 修复；并发源只产生一个串行收敛域。

提交边界：`feat: reconcile Fibra desired state`

## 8. 实现联合 deployment 事务

内部状态固定为 `PREPARING`、`PREPARED`、`COMMITTING_ARTIFACTS`、`COMMITTING_CONFIG`、`VERIFYING`、`COMMITTED`、`ROLLING_BACK`。journal 记录 deployment identity/revision、两个 participant workspace、每个 participant 阶段及前后 SHA-256；临时文件 force、父目录 force、原子 rename 和 payload-first/journal-last 清理沿用 🟢 `PluginTransactionJournal` 已验证模式。

先写失败测试：

- 扩展 `EngineCrashRecoveryTest.java`：使用真实 artifact/config participant payload 覆盖联合事务每个半提交状态及再次恢复幂等，并证明恢复完成后才构造 PF4J loader、半提交候选 ClassLoader 从未创建。
- `FibraDeploymentTransactionTest.java`：prepare 全部先于旧 entry/ClassLoader 拆除；commit artifact 后 config；required readiness 后才 commit revision；失败 config→artifact 逆序 rollback；原异常为主异常、恢复异常依发生顺序 suppressed。
- `FibraDeploymentIT.java`：真实 contract/provider/consumer 三层依赖和 typed config 联合升级、降级；新插件与新 schema 只有同时出现才成功；不兼容版本、坏 config、启动失败全部恢复旧服务、文件、图和 revision。

再实现 `FibraDeploymentTransaction`，复用第 6 项的唯一 `EngineTransactionJournal` 和 `EngineCrashRecovery`：

1. inspector 复制 package 到 engine transaction input；
2. artifact change prepare 并保持候选 catalog；
3. config replacement change 对 target catalog prepare；
4. journal PREPARED；
5. artifact commit，config commit；
6. required entry readiness；
7. 原子写 `revisions/applied.properties`；
8. journal COMMITTED；
9. config、artifact complete，payload-first 清理。

失败时 config rollback 先卸载新 runtime、恢复配置文件/snapshot；artifact rollback 再恢复旧目录/ClassLoader和 prepare 时的旧 entry specs。崩溃恢复不启动候选业务代码来猜图；不能由 journal+摘要证明时构造 engine 失败并以 `ROLLBACK` stage 报告。

验证：

```bash
$MVN -pl fibra-engine -am -Dtest=EngineTransactionJournalTest,EngineCrashRecoveryTest,FibraDeploymentTransactionTest,FibraDeploymentIT -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

成功标准：一次联合部署只有 engine journal；两个 loader 不创建顶层 journal；所有崩溃点均恢复完整前态或证明完整后态，未知态拒绝启动。

提交边界：`feat: apply deployments as one engine transaction`

## 9. 迁移纯 Java example 与仓库外 application

先改测试：

- 🟢 `fibra-example/engine/application/src/test/java/com/sstlfsj/fibra/example/engine/application/FibraEngineExampleApplicationIT.java`：不再直接构造 Context/两个 loader；用 engine 执行初载、显式 deployment、失败回滚和关闭。
- `verification/distribution/engine-application` 增加 engine 黑盒断言；根工程仍不继承 Fibra parent、不加入 reactor、不引用 `target/classes`。
- 🟢 `ReleaseArtifactBaselineTest` 的外部 Engine application 依赖固定为 `fibra-engine:compile` + `slf4j-simple:runtime`，禁止直接依赖两个 loader。

再实现：

- 修改 `fibra-example/engine/application/pom.xml`、`FibraEngineExampleApplication.java`、配置、README 和 assembly 输入，改为只使用 `FibraEngine`。
- 修改 `verification/distribution/engine-application/pom.xml`、源码、README 与 `scripts/verify-distribution.sh`；外部 application 仅通过 engine 的公开只读视图做断言。
- 示例插件包继续由 Maven Assembly 生成；新增 deployment assembly descriptor，只组合既有插件 ZIP 和配置，不写 Java ZIP 工具。

验证：

```bash
$MVN -pl fibra-example/engine/application,fibra-parity-tests -am verify
bash scripts/verify-distribution.sh
git diff --check
```

成功标准：纯 Java application 和仓库外 application 不再复制 load/watch/readiness/rollback/close 编排；真实多插件 deployment 可成功和回滚。

提交边界：`test: verify Fibra engine outside the reactor`

## 10. 冻结 API、文档和七制品发布门禁

修改：

- 新增 `docs/api/fibra-engine-public-signatures.txt`，重建两个 loader 签名；🟢 `ApiSignatureBaselineTest` 加入 engine。
- 重写 `docs/api/README.md` 中 loader watcher/直接宿主编排段，新增 engine builder、reconcile、deployment、状态、失败和关闭契约。
- 更新 `README.md`、`docs/release.md`、loader 架构文档、开源参照文档；明确 plugin package 与 deployment package、SHA-256、配置树映射、同版本不同摘要、contract 重复诊断、schema 变更失败回滚。
- 更新 `ReleaseArtifactBaselineTest`、`verify-reproducible-release.sh` 和 THIRD_PARTY_NOTICES；本 change 只宣称七个运行时制品，九运行时/十总制品由后续 Spring/archetype changes 收口。
- 全仓删除现行文档和源码中的 loader watcher 语义；不删除明确标注历史的发布记录。

最终验证：

```bash
$MVN clean verify
bash scripts/verify-distribution.sh
bash scripts/verify-reproducible-release.sh
openspec validate establish-fibra-engine --strict
git diff --check
git status --short
```

成功标准：Cordis 71 项、loader、engine、example、外部消费、API、十制品和可复现构建全部通过；生成物未进入 Git；文档明确区分 `v0.3.1` 正式基线与仅位于开发分支的 `0.4.0-SNAPSHOT`。

提交边界：`docs: freeze the Fibra engine API`

## 11. 审查与后续计划门禁

实现完成后单独执行代码审查，逐项确认：

1. prepare 失败是否严格早于旧 entry/ClassLoader/安装目录/配置文件变化；
2. engine、loader、source 是否存在持 Java 锁跨用户回调、PF4J 回调或 Reactor/Fibra 等待；
3. source 是否只发 dirty signal，coordinator 是否只有一个串行执行域；
4. journal、participant workspace、摘要和崩溃恢复是否逐状态可证明；
5. config factory/status/failure/日志/静态缓存/ThreadLocal 是否保留插件 ClassLoader 类型；
6. close 是否覆盖构造失败、启动失败、执行中关闭、回调内关闭和重复关闭；
7. 是否残留 watcher、第二套一步事务、兼容转发、MD5 或 Spring 依赖；
8. public record/list/path 是否不可变、规范化且有默认值和 round-trip 测试。

问题清零并重新通过第 10 项全部命令后，才勾选并归档 `establish-fibra-engine`。随后按真实 engine API 重写并提交：

1. `docs/superpowers/plans/2026-08-24-fibra-spring-runtime-integration.md`；
2. `docs/superpowers/plans/2026-08-24-fibra-plugin-archetype.md`。

两份后续计划不得重新实现 engine 的 watcher、reconcile、readiness、deployment、rollback 或 close。
