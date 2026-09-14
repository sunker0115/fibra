# Java/Node 与整体底座打磨实施计划

> **执行者：** 使用 superpowers:subagent-driven-development 逐任务执行；每项先 TDD，再规格审查与质量审查。用户已于本次审查后授权继续，无需重复确认同一方案。

**目标：** 在 `codex/0.5.0-hardening`、`0.5.0-SNAPSHOT` 上完成权威架构第 11.9 节六项底座打磨。取消旧兼容护栏，必要重构直接替换；不扩张产品范围。

**架构：** 唯一依据是 [vNext 权威架构](../specs/2026-09-07-fibra-vnext-architecture.md) 第 4.2、4.4、6.2、6.3、11.9 节。长期 RuntimeDomain、唯一 Engine 控制面、差量受影响闭包和 Scope 所有权不变；Node 明确数据/退出/范围三个事实，Java 明确真实节点所有权与可见类型定义者。不新增 InvocationAuthority、ClassSpace 生命周期、通用 transport 或事务框架。

**技术栈：** Java 21、JUnit 5、Reactor、Node.js、Maven Surefire、现有 JMH。复用 Maven 缓存，不改版本、依赖或 CI；若确需改变这些输入，先列影响面。

## 断点与证据状态

本计划覆盖并替换此前仅补长循环测试的计划。当前实现起点为 `e3f83bb`；已有三个未提交的 Java/Node 测试文件必须保留并按本计划修正，不能重置工作树。

| 项目 | 当前状态 | 尚需工作 |
|---|---|---|
| 1 短超时诊断 | 已有代码与测试证据 | 最终集成回归，不重复建设 |
| 2 生命周期/恢复 | E1 已完成 | 最终集成回归，不重复建设 |
| 3 准入/排空 | N2/E1 复验完成 | 最终集成回归，保留 revision + identity |
| 4 Java/Node 长稳 | N1、N2、J1、J2、E1 已完成 | 继续完成 V1 |
| 5 仓外扩展契约 | 有既有消费者 | 对最终实现跑真实安装、配置、升级、重装、调用中卸载与回收报告 |
| 6 安全/供应链 | 有既有门禁 | 对最终实现复验篡改、路径、依赖、脱敏与发行；不实现新隔离后端 |

最强模型独立审核已完成，参考固定源码：DSH `c291e7961a515f6d7af9304e7fd1d257929aef26` 的 `managed-owner.ts`/`spawn.ts`（输出观察与范围所有权独立）及 Entry 差量更新；cordis4j `6cfd56e684fb403ded952afc09eddb49a5228494` 的 reconcile/detach 顺序；PF4J 3.15.0 的依赖委派。只借用与本项目约束匹配的边界，不照搬回滚、吞掉关闭失败或依赖首命中行为。

已经确认的根因：Node 特殊 `process.stdout.end()` 不物理关闭 fd 1，旧关闭标记会丢尾帧，同步 writer 可能阻塞定时器/终止；Java 的长期首个 PublishedView 缓存可经插件自定义 descriptor 保留 loader，完成的 Update 也不应让调用者持有其旧资源图。不能把无永久根的对象环或一次 GC 未回收直接称为泄漏。

## N1：监督器的真实 stdout EOF 与结构化终态

**文件：** `fibra-runtime-node/src/main/resources/com/sstlfsj/fibra/runtime/node/node-process-supervisor.mjs`、同模块 `src/main/java/com/sstlfsj/fibra/runtime/node/NodeProcessUnit.java`、`src/test/java/com/sstlfsj/fibra/runtime/node/NodeProcessUnitTest.java`。

- [x] 先读上述完整实现与测试。新增真实进程测试：payload 写尾部后关闭自己的 stdout，但继续写 stderr/保持存活；Java 必须先见完整尾部及 EOF，同时 supervisor 仍活着。新增大尾部不截断、payload outcome 与 supervisor exit 分离、缺失/损坏/失败终态保留 session 的用例。
- [x] 运行定向测试观察预期红灯；旧两次 stdout.end 局部尝试已失败，不重复采用。
- [x] 监督器以 `net.Socket({ fd: 1, readable: false, writable: true })` 独占异步输出，payload stdout 自然结束后用 `destroySoon()` 排空待写队列；socket `close` 后再显式 `fs.closeSync(1)`，补足 libuv 故意保留 fd 0..2 的行为。禁止同时使用 `process.stdout`，range quiescence 不抢先关闭数据流，stderr 独立保持可读。
- [x] 内部终态文件改为唯一严格结构化格式，包含 payload outcome 与 range 结果，不兼容旧 `QUIESCENT` 文本；Java 侧校验状态并单独观察 supervisor exit。启动失败也明确结算，不能遗留 pending pipe。
- [x] N1 定向测试全绿，回归 NodeProcessUnitTest；只提交上述文件，避免携带其他未完成测试。

N1a 实际进度：`forwardsStdoutTailAndEofWhilePayloadAndSupervisorRemainAlive` 以 payload `fs.closeSync(1)`
复现旧 supervisor 不传播 EOF，3 秒等待失败（1 tests / 1 failure / 0 errors；`/private/tmp/fibra-n1-eof-red.log`）。
独占 fd 1 输出流并取消范围结算时提前关闭 stdout/stderr 后，同一用例 1/1、NodeProcessUnitTest 4/4 通过
（`/private/tmp/fibra-n1a-eof-green.log`、`/private/tmp/fibra-n1a-process-unit-green.log`）。仅证明原始数据 EOF；
结构化终态、大尾部和 N2 session 协调尚未完成，不能把 N1a 作为整体 Node 关闭已通过。

N1 已由 `ac2a612` 完成。真实大尾部诊断证明 `fs.WriteStream` 在非阻塞管道背压下截断；Node 20 自带
libuv 的 `uv__stream_close` 又明确不关闭 fd 0..2，因此普通 `end()` 或 `destroySoon()` 单独使用都不能让
Java/Python 创建的匿名管道提前 EOF。最终实现由 `net.Socket` 承担背压与排空，在 socket `close` 后显式
关闭 fd1。真实 EOF、4 MiB 尾部、host 关闭单/双输出及 `NodeProcessUnitTest` 32/32 均通过，离线
`Node*Test` 70/70 通过（`/private/tmp/fibra-n1-explicit-close-node-regression.log`）；第二轮独立质量审查
无可操作发现。Windows 实机及其它 Node/libuv 版本仍留到 V1/CI 验收，不以 macOS 结果替代。

## N2：单一 session 关闭协调与请求资源排空

**文件：** `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeSidecar.java`、`NodePluginRuntimeAdapter.java`；按责任提取同包内部 `NodeRpcChannel.java`，如 process 需要异步终态则改 `NodeProcessUnit.java`；对应 `NodeSidecarTest.java`、`NodePluginRuntimeAdapterTest.java`、`NodeRuntimeResourceOwnerTest.java`，以及现有 `NodePublishedCancellationOwnershipTest`。

- [x] RED：分片完整帧、自然半帧 EOF、最后响应紧邻 exit、写端阻塞期间取消/心跳/close 截止、读/定时器回调触发关闭、握手订阅取消、结果失败但范围清理仍未确认。
- [x] GREEN：NodeSidecar 作为实例协调器持有唯一异步关闭屏障，RPC 部件管理帧/pending/期限；停止新准入但持续读取。写入在隔离串行执行链中进行，timer 与 terminate 不等待 writer monitor；回调不 block/join 自己的关闭。
- [x] 请求 result 和 cleanup 分离；远端原请求终态或范围静默确认后才能成功结束 cleanup；缺失范围证明传播清理失败并保留 session。启动资源登记覆盖取消，不依靠 doOnError。
- [x] 回归并修正未提交长循环 Node 用例，JS 路径使用 JSON 编码。验证正常/异常/重复关闭的 PID、线程、session 收口；用现有 PublishedRuntime 真实调用验证租约不会提前释放。
- [x] 规格审查通过后再做质量审查；修正高优先级发现并提交。

N2 已由 `0a9bbf3`、`efb09a9`、`e02d7f7`、`fbf00ee`、`2090fa3`、`3ec1eee` 完成。
真实 RED 覆盖握手取消遗留、阻塞写、尾响应与 exit 竞态、范围证明失败、监督器 stdin `EPIPE`、
异步 disable 回调、仍活监督器的有界失败、已退出监督器的尾帧排空、晚到半帧协议失败，以及启动和
清理共享异常图。最终独立复验为 Node 86/86、相关 core 45/45、PublishedRuntime parity 1/1；24 轮
真实 Node 制品更新中线程每轮归零、FD 保持 34。规格审查与质量审查均通过，无未关闭问题；Windows 与
其它 Node/libuv 版本仍留到 V1/CI，不用本机 macOS 结果替代。

## J1：退休节点与长期缓存解除插件强引用

**文件：** `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapter.java`、`JavaClassSpace.java`（仅必要时改为入口物化语义名称）；`fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`；`JavaPluginRuntimeAdapterTest.java`、Engine 的重复启动测试，以及 `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/scenario/JavaPublishedViewRetentionTest.java` 与对应 `fixture`。真实 Engine/Java 集成夹具放在 parity-tests，不能给 Engine 增加反向 runtime-java 依赖。

- [x] RED：真实 JAR 多轮更新，记录 loader identity 而不是只比较创建/关闭总数；保留已结束 Update 时旧入口/loader 不再可达。失败节点及其真实先决依赖必须保留，但独立成功节点必须释放。活动 loader 作为 WeakReference 存活对照。
- [x] RED：插件定义 descriptor 类型，Engine 存活并完成替换后，第一份启动结果不能被长期启动缓存额外保留；调用者明确持有旧视图的对照仍应保留类型。
- [x] GREEN：保持 Owner/Update/Loaded 结构和实际依赖节点 identity，逐节点成功释放强引用；完成 Update 清理 old/fresh/catalog/异常引用，只留下必要元数据。不得 finally 一把清空失败资源。
- [x] GREEN：缓存启动完成而非初始 PublishedView；重复 start 等待同一个启动完成事实，再返回当前视图，不重新启动 runtime。
- [x] 失败启动同样有引用门禁：不能只 `.then().cache()`，因为缓存的 EngineChangeException 仍携带 view/cause；在可纠正的启动失败随后被新目标纠正后，长期启动协调不再持有旧异常对象。
- [x] close 与 collect 分开验收；有界重试 GC 只用于受控夹具的引用回收证据，不宣称任意插件的卸载截止，不加全局 Jackson cache flush。
- [x] Java/Engine 定向红绿、模块回归、规格与质量审查后提交。

J1 已由 `410593d`、`7b64936`、`c8b0e5e` 完成。九条 RED 覆盖完成 Update 的旧资源图、失败
退休的真实依赖闭包与独立 sibling、准备异常缓存、重复启动当前视图、启动失败异常长期根、真实插件
descriptor loader，以及关闭订阅惰性和快照幂等。生产实现逐节点释放成功关闭资源，清空完成 Update 的
插件对象与异常引用；Engine 只缓存启动完成事实。规格审查要求补充的“失败被新目标纠正且 Engine 仍
存活”真实 JAR 场景首次即通过，证明旧异常与旧 descriptor loader 可回收、当前 loader 仍存活。
权威模块回归为 Engine 172/172、Java runtime 26/26、parity 126/126；最终独立质量复验 31/31，规格
与质量审查均通过。24 轮真实更新中活动 loader 固定为 3、线程 9、FD 37，关闭后 loader 0、FD 34；
GC 证据只适用于受控夹具，不声明任意插件卸载截止。

## J2：制品内类型唯一性与跨制品版本隔离

**文件：** `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/PluginClassLoader.java`、`JavaPluginRuntimeAdapter.java`，同包内部有效类名索引实现及 `PluginClassLoaderTest.java`/`JavaPluginRuntimeAdapterTest.java`。

- [x] RED：同一 artifact 主/lib 与 lib/lib 重复；依赖相连的插件分别携带同库同版和异版并完成真实调用；依赖方直接查找按声明顺序首命中；菱形同一依赖、无关插件同名私有库、parent-first 命中与 miss、multi-release JAR 的 JVM 有效版本。
- [x] GREEN：prepare 汇总每个 artifact 主 JAR 与 lib JAR 的当前有效二进制名称并忽略 module-info，只拒绝该 artifact 自身的重复定义；不同 owner 的同名类由各自 loader 隔离，依赖方自身缺类时沿 `requires` 声明顺序取得第一条成功路径，不增加版本求解或 package wiring。宿主仅在 parent 真正可解析且 parent-first 的类上拥有优先权。
- [x] 同一 artifact 跨主/lib 或不同 lib JAR 的同名有效 class 同样拒绝，诊断列类名和两个来源；共享 loader 不等于可以任由 URL 顺序选代码。MR-JAR 先在单 JAR 内选当前有效版本，再跨 JAR 判重；实际 parent-first 宿主命中仍遵守宿主归属。
- [x] 不同 loader 的同名 `Class` 不可互换；跨插件签名、Service 与 DTO 必须由唯一宿主或 contract artifact 定义。调用方需要同时直接操作同 FQCN 的两个不兼容版本时由插件构建做 relocation/shading；不因 entrypoint 缺失推断 exports，不新增 OSGi/export 元模型。
- [x] 现有 `JavaPluginRuntimeAdapterTest.artifact` 给所有制品复制同名 `fixture.SampleEntrypoint`/`LateLoaded`，需改为各制品独立类名；不能让正常夹具伪造同一 artifact 的本地重复。专测同包冲突的夹具才主动生成同名定义。
- [x] 定向红绿、模块回归、规格与质量审查后提交。

J2 由 `3b5f3dc` 放宽到制品内判重，`0d8eb66` 补齐失败断言下的测试资源释放。旧严格实现
在新增跨制品隔离场景中 7 项按预期 RED；定向复验 32/32，模块回归 42/42。对 `3b5f3dc`
建立的干净克隆联跑为 Engine 172/172、Java runtime 42/42，相关 parity 9/9；测试清理补丁后的
定向复验仍为 32/32。独立规格审查与修复后的质量复审均通过。该结论只声明本地优先与
`requires` 顺序首命中，不声明版本求解；不同 loader 的同名类型仍不得跨契约边界互换。

## E1：失败事实与可纠正门禁

**文件：** `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`（ChangeSet 是其内部类）、`RuntimeResources.java`，现有 `EngineDiagnostics` 契约及 `ApplyDeploymentMountFailureRecoveryTest.java`/`EngineCrashPointRecoveryTest.java`；保存事实的类型位置须遵循现有依赖方向。

- [x] 先对照源码和既有测试：稳定 FAILED 已完成 adopt/settle/retire 时允许显式纠正；部分 adapter adopt、同步协调异常、未知保存与 cleanup failure 封锁。
- [x] 仅为缺口写 RED，再修改结构化失败阶段/保存确认投影；现有控制判断正确的部分不重写。原始失败与 cleanup failure 分别保留，不解析错误字符串控制 gate。
- [x] 若改变公开 DTO，架构、签名基线与消费者同一变更更新，删除旧兼容形状；不得引入 Engine → Registry 反向依赖。
- [x] 定向红绿、架构门禁、规格与质量审查后提交。

E1 只读核对结论：沿用 EngineDiagnostics，增加原始 `failedPhase`、`targetSaveState` 与
`cleanupFailures`，不建立平行事务 DTO。`TargetSaveState` 的唯一归属应在 Engine，由 Registry 消费；
`NOT_APPLICABLE` 区分不写部署目标的 context-only 操作，`NOT_SAVED` 表示部署写入尚未完成，恢复可靠
已保存目标为 `SAVED`。现有 gate 分支不重写。实现前列清公开 API 迁移影响：EngineChangeException、
Registry 审计 DTO/repository、CLI/benchmark/依赖验收宿主消费者、engine/registry 两份签名基线及相关
断言；不保留旧布尔 accessor/旧包别名。缺口测试为第二个 adapter adopt 抛错、原始 prepare/bind 失败
叠加候选清理失败、context-only 切换后稳定 FAILED 可纠正；已有保存/retire/mount 用例改为结构化断言。

E1 由 `243f69b` 完成结构化事实与公开 API 直接迁移，`1ee918f` 关闭规格审查发现的恢复前失败、
关闭覆盖原失败及主/suppressed 清理事实缺口。首轮契约 RED 为 59 个缺失符号；最小 schema 后定向
41 项有 10 项按预期失败，GREEN 为 41/41。规格修复 RED 为 34 项中 4 项失败，最终 34/34；Engine
全量 176/176。受影响 21 模块 `clean test` 通过，CLI 137/137、Java runtime 42/42、Node runtime
86/86、Registry 24/24；API baseline 与 retention 门禁 4/4，插件依赖 example Failsafe 2/2。
全仓旧 `targetSaved()` 与 Registry 旧枚举引用归零。规格复审和独立质量审查均通过；本地证据为
macOS/JDK 21，Linux 留待同一 HEAD CI，Windows 保持未实测声明。

## V1：六项最终集成验收

- [ ] 在最终代码上重跑 Java/Node 多轮真实制品更新与资源曲线，记录环境、HEAD 和原始日志路径。FD、堆和一次耗时不设拍脑袋阈值；不把 artifact 历史留存当泄漏。
- [ ] 跑现有 `ContributionInvocationBenchmark`、`EngineTransactionBenchmark` 短基线，报告样本不冒充优化证明。
- [ ] 审核并补齐 `verification/distribution` 的 Java/Node 仓外消费者生命周期契约报告，不允许取得 Engine 内部类型；复用发布依赖缓存，仅隔离消费者自身构建输出。
- [ ] 跑根 `clean verify`、短超时诊断门禁、篡改/路径/依赖/脱敏安全用例、发行 ZIP、可复现制品比较和最终仓外消费者。
- [ ] `git diff --check`，最强模型独立最终审查，回填行为验收账本。macOS 结果不能替代 Linux/Windows；不自动 push，待用户推送后取得同一 HEAD Linux CI，Windows 保持未实测声明。

## 统一测试命令与记录规则

本机按 mvn-env 选择 Java 21；Maven 实际可用路径如下，依赖写缓存或启动进程需要时通过工具授权：

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home /private/tmp/apache-maven-3.9.9/bin/mvn -pl fibra-runtime-node -am test -Dtest=NodeProcessUnitTest -Dsurefire.failIfNoSpecifiedTests=false
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home /private/tmp/apache-maven-3.9.9/bin/mvn -pl fibra-runtime-node,fibra-runtime-java,fibra-engine -am test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home /private/tmp/apache-maven-3.9.9/bin/mvn clean verify
```

每项完成时在对应复选框下记录实际失败断言、通过测试数、日志路径和提交，不以旧日志或代理自报代替最终 diff 与验收。相同问题两次尝试仍失败先报告机制与证据，不重复盲改；没有 TaskCreate 能力时本计划复选框即进度载体。
