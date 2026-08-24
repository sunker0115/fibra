# Fibra `0.3.0` 插件包与事务更新实施计划

日期：2026-08-23  
状态：`0.3.0` 历史实施计划，已完成且已被 `0.4.0` Engine 计划取代

> 本文不得作为当前待办执行。其中 watcher 任务、命令和提交边界只描述 `0.3.0` 历史过程；`0.4.0` 实施以 [Fibra Engine 计划](./2026-08-24-fibra-engine.md)为准。

架构真源：[Fibra 插件制品与事务更新设计](../specs/2026-08-23-fibra-plugin-package-transaction-design.md)。形式化行为真源：[`standardize-plugin-packages`](../../../openspec/changes/standardize-plugin-packages/)。若本计划与架构或 OpenSpec 规格冲突，先修正文档使三者一致，再继续代码；不得在实现中自行选择第三种语义。

## 0. 执行纪律

- 每个阶段先写失败测试，再写最小实现使测试通过；测试和对应实现必须在同一提交。
- 直接删除 `0.2.0` 直接 JAR API、Manifest 描述和旧 watcher 语义，不添加转发、弃用层、双格式识别或兼容分支。
- `fibra-api`、`fibra-core`、`fibra-pf4j-api` 的稳定语义不变；不为 loader 暴露 core lifecycle 线程或调度器 API。
- `fibra-loader-pf4j` 唯一新增生产直接依赖为 Apache Commons Compress 1.28.0，用其 ZIP 中央目录和 Unix mode API 识别符号链接；其 Commons Codec、Commons IO、Commons Lang 运行时传递依赖也由根 dependencyManagement 显式锁版。路径规范化、摘要和原子 move 使用 JDK API，示例 ZIP 构建使用 Maven Assembly Plugin，不手写 ZIP metadata 解析或通用打包组件。
- 所有 Maven 命令从仓库根执行，并先按本机 Maven 环境规约设置 `JAVA_HOME`、`PATH` 和 Maven 3.9.9；定向测试统一添加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 每个阶段完成后运行该阶段定向测试和 `git diff --check`；第 8 阶段以前不更新“已完成”状态或发布结论。

## 1. 建立 `0.3.0-SNAPSHOT` 开发基线

修改：

- `pom.xml`：把唯一 `revision` 从 `0.2.0` 改为 `0.3.0-SNAPSHOT`；增加 `commons-compress.version=1.28.0`、`commons-codec.version=1.19.0`、`commons-io.version=2.20.0`、`commons-lang3.version=3.18.0` 及对应 dependencyManagement，并增加真实 ZIP 示例需要的 `maven-assembly-plugin.version=3.8.0`；版本只定义在根 properties/dependencyManagement/pluginManagement。
- `.flattened-pom.xml`：由正常 Maven 生命周期重新生成，不手工维护其它版本源。
- `openspec/changes/standardize-plugin-packages/tasks.md`：仅在提交完成后勾选 1.1。

验证：

```bash
mvn -N help:evaluate -Dexpression=revision -q -DforceStdout
mvn -N validate
git diff --check
```

成功标准：两个命令都解析为 `0.3.0-SNAPSHOT`，仓库不存在第二个 Fibra 项目版本真源。

提交边界：`chore: start 0.3.0 development`

## 2. 冻结 PF4J 3.15.0 行为和标准包检查

先新增失败测试：

- `fibra-loader-pf4j/src/test/java/com/sstlfsj/fibra/loader/pf4j/Pf4j315BehaviorTest.java`
  - `DependencyResolver` 对存在的 optional dependency 不建立 dependency/dependent edge，也不报告其错误版本；证明 Fibra 必须补检 optional 范围。
  - `DefaultExtensionFinder` 对索引缺类和 `NoClassDefFoundError` 返回空结果而不是传播异常；证明制品类型不能使用该结果。
  - `DefaultVersionManager` 对 `>=1.0.0 & <2.0.0` 的边界值、非法版本和非法约束执行真实行为断言。
- `fibra-loader-pf4j/src/test/java/com/sstlfsj/fibra/loader/pf4j/PluginPackageInspectorTest.java`
  - 标准安装目录和单顶层目录 ZIP；ZIP slip、绝对路径、符号链接、多个顶层目录、额外层级。
  - `plugin.properties` 唯一描述、字段白名单、ID 字符集、SemVer、`plugin.class`/`plugin.requires`、固定主 JAR、`lib/` 非 JAR/子目录。
  - 所有 `lib/*.jar` 的共享运行时包扫描；规范 SHA-256；同版本同摘要 no-op、同版本不同摘要拒绝。
  - 主 JAR 自身索引不存在/空/单声明/多声明；本阶段只读取主 JAR 自身索引，不在缺少完整依赖 ClassLoader 时加载入口类。
- `fibra-loader-pf4j/src/test/java/com/sstlfsj/fibra/loader/pf4j/PluginPackageFixtures.java`：测试专用目录包、ZIP、JAR 和 properties 构造器，替换各测试类复制的 Manifest JAR helper；不进入生产代码。

再实现：

- 新增公开 `FibraArtifactErrorStage.java`、`FibraArtifactException.java`、`FibraPluginLoaderBusyException.java`。
- 新增包内 `InspectedPluginPackage.java`，只保存规范路径、descriptor、主 JAR、排序后 Classpath、摘要和主 JAR 自身索引声明，不保存已加载的入口类型或入口对象。
- 新增 `PluginPackageInspector.java`，使用 Commons Compress `ZipFile/ZipArchiveEntry` 读取中央目录并拒绝 symlink/非普通条目，再负责复制、安全解压、目录检查、摘要和主 JAR 自身索引读取；它不创建插件 ClassLoader、不改安装目录、不调用业务入口。
- `fibra-loader-pf4j/pom.xml` 增加 `org.apache.commons:commons-compress`，版本只从根 dependencyManagement 继承；不增加第二个 ZIP 库。

定向验证：

```bash
mvn -pl fibra-loader-pf4j -am -Dtest=Pf4j315BehaviorTest,PluginPackageInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

成功标准：三项 PF4J 载荷性断言直接由 3.15.0 测试锁定；全部非法候选在创建活动 ClassLoader 前给出稳定 stage；外部 ZIP 字节不变。

提交边界：`feat: define standard plugin packages`

## 3. 用目录 manager 构造完整 prospective 图

先新增失败测试：

- `FibraDirectoryPluginManagerTest.java`：只扫描插件根直接子目录，忽略 `.fibra-preflight`、`.fibra-transactions` 和直接 JAR；按排序后的 `lib/*.jar` 建立当前插件 ClassLoader。
- `PluginGraphPreflightTest.java`：覆盖缺失必需依赖、循环、必需/optional 范围、重复候选 ID、单包破坏现有 dependent、关联多包共同有效、三层旧/新 dependent 并集、contract-only 依赖，以及自身索引缺类、`NoClassDefFoundError`、非 Fibra 入口、错误定义 ClassLoader和依赖入口不继承。
- `FibraPluginClassLoaderTest.java`：覆盖 PDA、共享运行时父加载、两个插件私有依赖不同版本隔离、provider/consumer 通过独立 contract ClassLoader 看到同一个接口类型。

再实现：

- 删除 `FibraJarPluginLoader.java`、`FibraJarPluginManager.java`、`FibraPluginCandidate.java`。
- 新增 `FibraDirectoryPluginLoader.java`：使用 PF4J `DefaultPluginClasspath` 的 `lib/`，创建 `FibraPluginClassLoader`。
- 新增 `FibraDirectoryPluginManager.java`：只使用 `PropertiesPluginDescriptorFinder`、目录 repository 和无操作 PF4J `Plugin` wrapper；提供严格全量 load/resolve/unload。
- 修改 `FibraPluginClassLoader.java`：保持 PDA 和共享包父加载，目录内主 JAR及私有依赖均由本插件 ClassLoader 加载。
- 新增 `PluginGraphPreflight.java`：以“当前安装图 - 同 ID 旧包 + 全批候选”创建临时 manager，补检 optional edge，并用目标插件 ClassLoader 对 inspector 读取的自身索引执行不初始化的入口类型/定义 ClassLoader 检查；关闭全部临时 ClassLoader，返回候选与旧/新受影响闭包。

定向验证：

```bash
mvn -pl fibra-loader-pf4j -am -Dtest=FibraDirectoryPluginManagerTest,PluginGraphPreflightTest,FibraPluginClassLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

成功标准：任何预检失败都不改变安装目录、活动 manager、Fibra entries 或当前 ClassLoader；临时 manager 的 ClassLoader 全部关闭。

提交边界：`feat: validate prospective plugin graph`

## 4. 建立不会跨 lifecycle 自等的逻辑事务门

先新增失败测试：

- `LoaderOperationGateTest.java`
  - 同一普通阻塞线程嵌套 `runExclusive -> mount/update/unmount` 可重入。
  - 一个线程持门时，另一个同步管理调用立即抛 `FibraPluginLoaderBusyException`，不排队。
  - Reactor `Schedulers.single()` 和 Fibra lifecycle/effect 回调中的管理调用立即报忙。
  - 持门线程等待 `fibra.await()/dispose()` 时不存在被持有的 Java `Lock`。
  - 事务期间 `artifactIds()/entryIds()` 返回上一次完整提交快照；`fibra()/configType()` 报忙，不暴露中间态。
  - 活动事务或 `runExclusive` 回调内调用 `close()` 立即报忙且 loader 保持打开；空闲时 close 作为最后一个独占事务完成全部资源释放。
- 在 `FibraConfigLoaderTest.java` 增加 config reconcile 与 artifact apply 竞争测试，断言一个操作提交、另一个明确报忙，绝不交叉更新 snapshot、entry 或安装图。

再实现：

- 新增包内 `LoaderOperationGate.java`：短临界区只维护 owner thread、重入深度、closing 状态和已提交 `LoaderIdentitySnapshot`；snapshot 只含 artifact ID/version 与 entry ID，不含插件类或运行对象；执行 action 前释放内部锁。
- `LoaderOperationGate` 使用 `Schedulers.isInNonBlockingThread()` 拒绝同步管理 API，不修改或暴露 `fibra-core` 调度器。
- 修改 `FibraPluginLoader.java`：全部变更 API 和 `runExclusive` 进入事务门；`artifactIds/entryIds` 读提交快照；提交只在完整操作成功后发生。
- 修改 `FibraConfigLoader.java`：一次 reconcile 只进入一次 `runExclusive`；同线程嵌套 loader 调用保留逻辑所有权，不持有物理锁。

定向验证：

```bash
mvn -pl fibra-loader-pf4j,fibra-loader-config -am -Dtest=LoaderOperationGateTest,FibraConfigLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

成功标准：真实 root lifecycle 回调的反向管理调用在 Awaitility 规定时间内失败返回，测试线程不依赖超时中断解除死锁；配置与制品事务没有交叉中间态。

提交边界：`feat: coordinate loader operations without lifecycle locks`

## 5. 实现 journal-first 批量更新与崩溃恢复

先新增失败测试：

- `PluginTransactionJournalTest.java`
  - journal 临时文件写入、文件/父目录 force、原子 rename、字段排序、重复/缺失 ID、非法状态迁移、旧存在状态和旧/新摘要解析；原子 move 或目录 force 不支持时不得降级。
- `PluginCrashRecoveryTest.java`
  - `.fibra-preflight` 和空无 journal 正式目录可清理；无 journal 却有 `previous` 拒绝启动。
  - `PREPARED` 未交换；`INSTALLING/APPLYING` 的每个合法逐 ID 半交换组合；新安装 ID 撤回；批次逆序恢复。
  - `plugins/<id>` 与 `next/<id>` 同时存在或同时缺失、旧/新/未知摘要错误、journal 损坏均以 `ROLLBACK` 拒绝。
  - `COMMITTED` 新摘要完全匹配后只清垃圾；任一 ID 不匹配拒绝；成功提交、成功回滚和构造期恢复都按 payload-first、journal-last 清理，覆盖清理期间再次崩溃。
- 重写 `FibraPluginLoaderTest.java`
  - 构造器只恢复磁盘；首次 `loadArtifacts` 初始化；初始化前 apply/mount 拒绝；显式 unload 后再次 `loadArtifacts` 从完整磁盘图重载。
  - `applyArtifacts` 安装、升级、降级、空列表、重复路径/ID、no-op 和显式多包事务。
  - 三层 dependent-first dispose、dependency-first load/start、同制品多 entry 稳定顺序和旧 ClassLoader关闭。
  - 新入口 mount 失败后旧目录、started 状态、entries、服务全部恢复；恢复自身失败形成 `ROLLBACK` cause/suppressed 并保留事务目录。
  - 目标版本 `configType` 不兼容时以 `APPLY` 失败并回滚，不保留旧 typed config。

再实现：

- 新增 `PluginTransactionState.java`、`PluginTransactionJournal.java`、`PluginCrashRecovery.java`。
- 新增 `PluginUpdateTransaction.java`：只负责正式事务时序、目录原子 move、运行态快照/恢复和异常组装；预检仍由 `PluginGraphPreflight` 唯一负责。
- 重构 `FibraPluginLoader.java`：构造器先恢复事务再创建活动 manager；`applyArtifacts` 作为唯一候选入口；删除 `loadArtifact/reloadArtifact`。
- `PluginInstanceSpec` 与 `PluginConfigFactory` 不增加旧类型兼容字段；恢复时只调用目标 ClassLoader 的当前 `configType`。

定向验证：

```bash
mvn -pl fibra-loader-pf4j -am -Dtest=PluginTransactionJournalTest,PluginCrashRecoveryTest,FibraPluginLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

成功标准：设计第 7.4 节每个合法/非法目录组合都有独立测试；构造期在创建活动 ClassLoader 前完成恢复或稳定拒绝；正式 apply 的原失败与恢复失败链顺序确定。

提交边界：`feat: apply plugin packages transactionally`

## 6. 改造 watcher 和配置协作

先修改失败测试：

- `FibraPluginWatcherTest.java`：只接受原子发布的 ZIP；严格升级；同 ID 去抖；多插件不隐式拼批；候选保留；close 等待。
- `FibraConfigLoaderTest.java`：配置事务与制品事务竞争时不交叉提交，直接同步 refresh 收到 loader 报忙且旧 snapshot/运行态不变。
- 新增 `FibraConfigWatcherTest.java`：制品事务报忙时保留 dirty 并在释放后重试；报忙不调用 failure sink；真正配置 apply 失败仍可观测。

再实现：

- 修改 `FibraPluginWatcher.java`、`FibraPluginWatchFailure.java`：删除 JAR candidate 字段和 reload 调用，统一调用 `applyArtifacts(List.of(zip))`。
- 修改 `FibraConfigWatcher.java`：捕获 `FibraPluginLoaderBusyException` 时重新标 dirty，由现有 single-runner 继续；关闭时等待重试或在 closing 后终止，不新增独立事务队列。
- 修改 `FibraConfigLoader.java`：配置转换失败继续使用自身 `CONVERT/APPLY/ROLLBACK` 语义；不把 loader 报忙包装成配置候选损坏。

定向验证：

```bash
mvn -pl fibra-loader-pf4j,fibra-loader-config -am -Dtest=FibraPluginWatcherTest,FibraConfigLoaderTest,FibraConfigWatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
```

成功标准：并发文件事件最终至少执行一次最新候选；瞬时事务竞争不丢事件、不污染最后失败；关闭没有遗留 watcher 线程。

提交边界：`feat: retry plugin and config watchers on loader contention`

## 7. 真实三插件示例和仓库外黑盒验收

仓内模块与构建修改：

- 根 `pom.xml` 新增非发布模块 `fibra-example-contract-plugin`，放在 provider/consumer 之前；增加 contract/provider/consumer 示例版本 properties。
- 新增 `fibra-example-contract-plugin/pom.xml`、`src/main/java/example/fibra/contract/Greeting.java`、`src/main/plugin/plugin.properties`，无扩展索引，生成 contract-only 标准 ZIP。
- 修改 provider：删除 `example.fibra.provider.api.Greeting`；以 `provided` 依赖 contract 模块；properties 声明 contract 版本范围；生成 v1/v2/broken 标准 ZIP。
- 修改 consumer：只以 `provided` 依赖 contract，不再依赖 provider JAR；properties 只声明 contract 二进制依赖，Fibra 配置继续声明运行时 provider 服务依赖。
- 新增共享 `build/plugin-package-assembly.xml`；主包使用 Maven Assembly Plugin 把 `plugin.properties`、主 JAR和非 provided 私有依赖放入唯一顶层 `<plugin-id>/lib/`。provider 的 v2/broken 分类包使用模块内显式 assembly descriptor，不写 Java 打包器。
- 修改 `fibra-example-host/pom.xml`、`FibraExampleHost.java`、`FibraExampleHostIT.java`、配置和 README：Host/Failsafe classpath 排除三个插件类型，只复制 ZIP，覆盖多 entry、等待服务、批量升级和失败恢复。

仓库外工程修改：

- 新增 `verification/external-consumer/contract-plugin`，把 `Greeting` 从 provider 移入 contract-only 模块；根 POM 模块顺序改为 `core-app, contract-plugin, provider-plugin, consumer-plugin, host`。
- provider/consumer 都以 `provided` 依赖 contract；Host classpath 不含 contract/provider/consumer 类型。
- 外部插件均生成标准 ZIP；至少一个 executable 在 `lib/` 携带私有依赖，并验证另一个插件不可见。
- 把该独立工程从“仅脚本可用的版本哨兵夹具”收敛为唯一用户插件工程模板：给出可直接构建的默认版本和 `mvn verify`，README 分别说明最小 executable、可选 contract/consumer 与开发 Host；不另建 Maven Archetype 或第二份模板。
- 修改 `scripts/verify-external-consumer.sh`：检查 ZIP 单顶层目录、properties、固定主 JAR、contract 类型只在 contract 包、Host 无插件类型，并从 ZIP 安装目录启动真实 Host。

验证：

```bash
mvn -pl fibra-example-host -am verify
bash scripts/verify-external-consumer.sh
```

成功标准：Host classpath 不含任何插件/contract 类；provider 与 consumer 从同一个 contract ClassLoader 得到同一接口；私有依赖隔离；多包更新必须由一次显式批量 apply 完成；用户按模板 README 可在独立目录执行一次 `mvn verify` 产出标准 ZIP，黑盒脚本构建的就是同一份模板。

提交边界：`test: verify standard plugin packages end to end`

## 8. API、文档和发布门禁收口

修改：

- `docs/api/fibra-loader-pf4j-public-signatures.txt`：删除两个直接 JAR 方法，加入 `applyArtifacts`、稳定错误类型和 busy 异常；不修改 `fibra-api`/`fibra-core` 公共签名。
- `docs/api/fibra-loader-config-public-signatures.txt`：只有真实公开签名变化时更新，不因内部 watcher 重试改动基线。
- `THIRD_PARTY_NOTICES.md`：加入 Apache Commons Compress、Commons Codec、Commons IO、Commons Lang 及其 Apache-2.0 归属；依赖说明与根 POM 版本一致。
- 整体重写 `docs/superpowers/specs/2026-08-22-fibra-pf4j-loader-architecture.md` 为 `0.3.0` 当前实现契约；删除 `0.2.0` 直接 JAR正文，不在旁边保留废弃段落。
- 更新 `docs/superpowers/specs/2026-08-23-fibra-config-loader-architecture.md`、`docs/superpowers/references/2026-08-21-fibra-opensource-baselines.md`、`README.md`、`docs/api/README.md`、`docs/release.md` 和示例 README；明确同版本重发规则、重复 contract 诊断、配置 schema 失败回滚和逻辑事务门。
- 全仓 `rg` 删除 `reloadArtifact/loadArtifact/FibraJarPluginManager/Plugin-Id Manifest/直接 JAR` 的现行 `0.3.0` 语义；历史 `0.2.0` 发布记录只在明确标注历史时保留。
- 完成后勾选 OpenSpec tasks，严格校验并归档 change；归档前不得把 `0.3.0` 写成已发布 release。

最终验证：

```bash
mvn clean verify
bash scripts/verify-external-consumer.sh
bash scripts/verify-reproducible-release.sh
openspec validate standardize-plugin-packages --strict
git diff --check
git status --short
```

成功标准：全部 reactor、仓库外隔离消费、逐字节可复现、API 基线和 OpenSpec 严格校验通过；工作区没有生成物；文档对“已实现、开发目标、已发布”无歧义。

提交边界：`docs: freeze 0.3.0 plugin package API`

## 9. 最终代码审查门禁

在判断 `0.3.0` 完成前单独执行一次代码审查，逐项检查：

1. 任何预检失败是否完全早于旧 ClassLoader/entry/安装目录变化；
2. 是否存在持有 Java 锁执行 `Mono.block()`、PF4J 回调或用户入口的路径；
3. journal-first、force/原子 rename、逐 ID 摘要恢复是否与设计逐项一致；
4. `PluginConfigFactory`、异常、Spring Bean、静态缓存和 ThreadLocal 是否保留旧 ClassLoader 类型；
5. 所有 ZIP/JAR/流/ClassLoader/watcher 是否在成功、失败、回滚和 close 路径关闭；
6. 是否残留直接 JAR、Manifest 描述、兼容转发或 provider-owned contract 特例；
7. 三项 PF4J 3.15.0 行为测试是否直接调用真实依赖而非复制其算法。

只有审查问题清零并重新通过第 8 阶段全部命令后，才允许把 OpenSpec change 归档并评估 `0.3.0` release。
