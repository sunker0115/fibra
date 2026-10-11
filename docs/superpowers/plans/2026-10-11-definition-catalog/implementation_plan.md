# 声明级 definition 目录与 Java 取得修复实施计划

> 实施者：用户已明确批准 Fibra 实施及隔离 Tela 消费验证。使用当前可用的 subagent-driven-development 技能安排有界实现与独立审查；主线程持有公共合同及集成责任。不自动 commit、push，不恢复 Tela P3。

**目标：** 交付所有已选包的一致公开声明目录和 Java 取得失败的可达资源 owner，并证明公开制品可被隔离消费者使用。

**架构：** 以[候选合同](../../specs/2026-10-11-definition-catalog-design.md)为准。Engine 只读精确 root selection，在唯一 lane 内聚合公开 built-in metadata及各动态 runtime 静态声明；原 compiler 保留部署解析。Java 部署取得修复与目录文件句柄分别归原 runtime，不执行目标插件代码来发现名称。

**技术栈：** 当前 JDK 21、Maven、Reactor、JUnit 5、现有 YAML/JSON reader。没有新依赖、模块、target/client wire 格式或发行版本决定。

用户补充“不需要有兼容代码”：必需SPI、双字段descriptor及可执行Java名称统一迁移，不加default、重载或旧格式运行分支。最终全栈形态与Tela影响见[最终评估§9](../../specs/2026-10-11-plugin-model-target-evaluation.md#9-最终插件形态与-tela-调整建议)；组合schema不是本期目录计划的既有能力或已批准范围。

实施依赖顺序细化：任务2先交付目录值、错误和RuntimeDefinitionQuery接口；任务3/4分别实现Java/Node的真实查询方法；任务5集成Engine时同步把方法声明为RuntimeDriver必需SPI并迁移所有替身与外部provider。任务2的SPI方法及纯built-in集成断言随任务5关闭。这样避免为保持中途编译新增临时default、unsupported或假查询实现，最终合同不变。

## 授权范围与文件 owner

以下路径均相对 Fibra 仓根，现存文件已核对。新文件只列候选必须的公共值/SPI，不预建通用框架。

| 任务 | 创建/修改范围 | owner |
|---|---|---|
| 1 | 修改 `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriver.java`、`PluginClassLoader.java`、`JavaClassSpace.java`；修改同模块 `src/test/java/com/sstlfsj/fibra/runtime/java/JavaRuntimeDriverTest.java`、`PluginClassLoaderTest.java` | D38 取得、转交、关闭；沿原私有 candidate/lease；ClassSpace仅反射构造器致命错误解包，不取得或关闭loader |
| 2 | 创建 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/deployment/PackageDefinitionCatalog.java`、`FacetDefinitionNames.java`、`DefinitionCatalogException.java`；创建 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/runtime/RuntimeDefinitionQuery.java`；修改原 `RuntimeDriver.java` | 公共查询值及动态静态读取合同 |
| 3 | 修改 `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaFacetDescriptor.java`、`JavaFacetDescriptorReader.java`、`JavaRuntimeDriver.java`及原 driver test | 名称声明、静态文件 owner、部署名称匹配 |
| 4 | 修改 `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeDriver.java`、`NodeFacetDescriptorReader.java`及 `src/test/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeDriverTest.java` | 复用 Node 描述符，不启动进程 |
| 5 | 修改 `fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java`及原 `RuntimeDriverEngineTest.java`、`RuntimeDriverContractTest.java` | root 查询、排队、清理、失败屏障 |
| 6 | 修改正式 Java 插件的 `META-INF/fibra/plugin.yaml`、`fibra-plugin-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/fibra/plugin.yaml`、分发 Java fixture；修改正式外部 provider fixture和仓外消费者测试 | 作者声明与真实公开制品消费 |
| 7 | 修改 `docs/api/README.md`、`fibra-engine-public-signatures.txt`、`fibra-runtime-java-public-signatures.txt`，本计划和候选状态 | 公共合同说明、签名批准与验收事实 |

表中同目录短文件名属于前一完整目录，不跨包挪动。Node 公共签名是否改变由实际 javap 验证，本计划不预改。各 runtime 的内部文件资源 bookkeeping优先私有内部对象，不新增公开辅助类型。

Tela 主工作树只读。用户已批准隔离 Tela 副本内的 client runtime及 Java 制品迁移，真实公开制品消费已验证，来源与边界见[交付记录](delivery.md)：`tela-host/.../client/TelaClientRuntimeDriver.java`及相关测试；`tela-agent-plugin`、`tela-model-api`、`tela-model-deterministic-plugin`、`tela-model-openai-plugin`各自 `src/main/resources/META-INF/fibra/plugin.yaml`；生成可执行 Java 包的测试helper和必要发行组装/引用核对。四者当前都声明entrypoint，model-api不能仅凭名称当成contract-only。所有名称按真实 Entrypoint核对，保持原plugin/facet/type引用。此范围不包含产品页面、业务功能、完整导出集合协议或 P3 恢复。

## 命令环境

以下命令在已获授权的实施范围内执行。路径依据当前 mvn-env 技能及 POM release=21；执行前验证两条可执行路径。路径失效按技能重新定位，不尝试错误版本。实际测试与签名门状态记录在progress，不以命令模板当作通过证据。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
fibra_goal_mvn=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
fibra_goal_repository=$(mktemp -d /private/tmp/fibra-definition-m2.XXXXXX)
```

该 repository是本次 producer本地仓，不能向全局 `.m2`安装相同 SNAPSHOT。下文每条 Maven命令显式使用它。测试结果记录 producer HEAD+working diff digest，尚未 commit时不能称新 HEAD覆盖了实现。

## 任务 1：先把 D38 已复现故障变成仓内 RED（complete）

实际RED/GREEN及独审绑定见progress；完整Java模块44 tests通过，spec/quality均B0/I0/M0。本门仅覆盖私有Java取得修复，不关闭目录/公开制品/整体目标。

- [x] 从 `/private/tmp/fibra-acquire-audit/run.py`及既有 `JavaRuntimeDriverTest`提取三条故障fixture：metadata+loader.close双失败；已取得依赖后父descriptor失败+依赖close失败；父metadata与parent.close失败时依赖不能先释放。沿原测试注入 LoaderCloser，fixture仅进测试源码。
- [x] 分别断言：candidate.close报告原cleanup失败；driver.close拒绝尚有owner；parent.close失败时依赖close调用数为0。不得用“suppressed中有异常”替代关闭失败，不以第二次close成功解除首次失败。
- [x] 运行定向测试，记录与现探针相同的行为RED，先不改生产代码。

```bash
"$fibra_goal_mvn" -Dmaven.repo.local="$fibra_goal_repository" -pl fibra-runtime-java -am test -Dtest=JavaRuntimeDriverTest,PluginClassLoaderTest -Dsurefire.failIfNoSpecifiedTests=false
```

- [x] 按候选§6在acquire之前挂owner节点，new loader返回后即登记，metadata成功后再发布wiring；PreparedUnit在bind/plan可能失败时仍可达；seal成功前不清空candidate。复用原精确lease/refcount和私有显式重试语义。
- [x] 原参数验证移到loader构造前；关闭父自身失败时保留依赖，成功节点移除、独立失败兄弟不阻止其他兄弟清理。driver只索引同一owner，不创造第二关闭权。
- [x] 同一命令取得GREEN；再覆盖partial dependency、diamond、bind、plan、非致命Error、重复close及成功转交。稳定AssertionError/LinkageError失败断言不能依赖VM灾难恢复。

停止条件：无法让失败资源保持可达、出现refcount双释放、父失败仍清依赖，或同一问题连续两次未解决时停止该修改并报告证据。

## 任务 2：公开合同与所有 provider统一迁移

- [x] 在 `RuntimeDriverContractTest`验证必需方法的正常query与非法返回；更新所有driver实现和测试替身，新增值测试验证空/非空、排序和防御复制。新接口/值不存在造成的编译RED与D38行为RED分别记录，不能混报。
- [x] 按候选§3、§5加入唯一公共合同，完整方法形状为：

```java
// RuntimeDriver的必需方法，无default实现
RuntimeDefinitionQuery createDefinitionQuery(ManagedFacet facet);

// 新RuntimeDefinitionQuery
public interface RuntimeDefinitionQuery {
    Mono<List<String>> readAsync();
    Mono<Void> closeAsync();
}
```

- [x] 值为 `PackageDefinitionCatalog(long targetRevision, PluginId pluginId, String packageRevision, List<FacetDefinitionNames> facets)`和 `FacetDefinitionNames(FacetId facetId, RuntimeId runtimeId, ExecutionTarget executionTarget, List<String> definitionIds)`。构造核null/blank/duplicate、拷贝并排序；不允许靠Engine调用者先排序才安全。
- [x] `DefinitionCatalogException`只暴露(Code,String)与code()；Code精确采用候选§3七项，不能持插件cause/Class。Engine参数/业务错误时机继续在任务5验证。
- [x] 定向运行Engine合同测试取得GREEN；同版公开制品消费者验证留任务6，不交付旧二进制兼容。

## 任务 3：Java声明与静态查询

- [x] 在原 `JavaRuntimeDriverTest`加入此独立于类可载入性的RED断言，使用该文件已有facet和NoopHostServices helper：

```java
@Test
void declaredCatalogDoesNotLoadEntrypoint() throws Exception {
    var source = facet("catalog-app",
        "entrypoint: missing.UnloadableEntrypoint\ndefinitionId: declared-type\n", null);
    var driver = new JavaRuntimeDriver(new NoopHostServices(), List.of());
    var query = driver.createDefinitionQuery(source);
    assertEquals(List.of("declared-type"), query.readAsync().block());
    query.closeAsync().block();
    driver.closeAsync().block();
}
```

- [x] descriptor新增Optional definitionId，只保留双参数规范构造器和原字段accessor；所有调用者统一更新，不加单参数重载。reader沿原64KiB/重复键/未知字段/JAR Class-Path校验；entrypoint-only缺必需名称，实际调用reader的显式probe/inspect、静态query和受影响entry prepare按阶段拒绝；PackageStore/仅安装selection不承诺私有格式已核，不新增Registry无条件probe；空contract-only返回List.of；孤立name、错误类型、blank均拒绝。
- [x] query先由Engine/driver登记，再打开JarFile/stream并由同一owner持有。原reader解析规则只保留一份，可调整其资源入口；不包装便捷reader后假称内部失败句柄可达。
- [x] 分开私有静态资源owner与异步query观察：同步probe/inspect/prepare及取得节点close直接调用owner，不反向Mono.block、不加调度器兜底；在NonBlocking scheduler回归验证原路径和失败清理。owner缓存一次close终态（含fatal），同步读取fatal保留owner并沿原调用栈上抛；query保留cache内部准入、取消隔离和真实终态等待。
- [x] 加故障关闭注入：读成功+close失败、读失败+close失败、stream失败不先关其所需JarFile、close缓存不重试。无资源情形close可成功空操作。
- [x] 新声明metadata.name不等时，在wiring发布和bind前拒绝，走任务1 owner清理；所有可执行包采用相同名称检查，无旧格式分支。增加metadata/类初始化/构造计数全零的真实fixture补强上述不可载入类证据。
- [x] 用任务1同一命令取得GREEN；确认新可执行/contract-only部署通过，旧无名称负例拒绝且资源关闭。

## 任务 4：Node与纯built-in

- [x] `NodeRuntimeDriverTest`为原descriptor名称查询增加RED：结果ID来自descriptor、不创建sidecar、不创建unit，静态读取故障和目录close不遗失Files.walk资源。close观察取消另有移除缓存的变异RED及恢复后全92项GREEN。
- [x] Node仅实现同一单facet句柄并复用原reader；不改descriptor格式、启动协议或私有工厂枚举。规格复审和质量独审均PASS B0/I0/M0，Node阶段关闭；纯built-in集成随任务5通过。
- [x] 在 `RuntimeDriverEngineTest`复用alpha/beta内建fixture加入纯built-in测试：driver实现必需SPI但纯builtin查询不调用它，仍可返回BuiltInFacet声明；metadata实际集合未被使用时不声称已验证；原prepare使用该definition时继续核集合。
- [x] 运行以下两组定向命令，分别记录Node无进程证据和Engine builtin声明结果：

```bash
"$fibra_goal_mvn" -Dmaven.repo.local="$fibra_goal_repository" -pl fibra-runtime-node -am test -Dtest=NodeRuntimeDriverTest -Dsurefire.failIfNoSpecifiedTests=false
"$fibra_goal_mvn" -Dmaven.repo.local="$fibra_goal_repository" -pl fibra-engine -am test -Dtest=RuntimeDriverContractTest,RuntimeDriverEngineTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 任务 5：Engine查询和失败屏障

- [x] 在原Engine fixture加入可闸住read/close的动态query，先验证未订阅不调用、取消仅观察、query先被接纳时close不能越过它、close先准入时拒绝。用Sinks/事件序列同步，不用sleep假设时序。
- [x] 精确r1/r2和disabled root查询、缺root/无target/stale、缺依赖/disabled依赖/cycle、无关坏包分别验证。只有root包不可得才拒绝声明；同时用原Apply证明其部署错误没有被目录消除。
- [x] 新公开方法签名与执行边界：

```java
public Mono<PackageDefinitionCatalog> describeDefinitions(
    PluginId pluginId, long expectedTargetRevision)
```

- [x] 方法外只做参数校验；返回Mono.defer包裹原loop.submit，在lane捕获target+本次captureBuiltIns。动态用store.find精确记录，不调用compiler；builtin直接投影公开metadata。所有inert query先登记再read，结构校验后逆序close，cleanup成功才返回结果。
- [x] 同步throw/nullpublisher/Mono.empty/异步error沿同一路径清理；cleanup失败保留CatalogAttempt，关门/请求一次终止；抽取原fail-stop关门通知而不改历史Apply outcome。shutdown在一般driver/store cleanup前观察失败owner屏障。
- [x] 断言target bytes/revision/store.saves和旧operation结果不变；cleanup成功读失败不关闭mutation gate；双失败对外cleanup优先。用任务4的Engine命令取得GREEN，再跑原engine全部测试。

## 任务 6：作者制品与同版公开消费者

执行划分：6a 负责 Fibra 作者/模板/生成夹具及仓外未知 Java 包链；6b 负责隔离 Tela client/Java 制品及组合反例。Engine/SPI 已冻结并通过双审，两者不共享源码 owner；隔离私有合同的设计增量复审通过后，可独立推进 6b 源码和测试编写。共享 Maven producer repository 的构建/测试必须由根线程串行分配，6a/6b 均各自冻结后过 spec→quality，整体发行仍在任务7核验；不能用并行准备跳过任何设计或实现门。

- [x] 只更新实际可执行Java包的descriptor，definitionId必须核对其真实PluginDefinition.builder/name实现；contract-only仍无name。作者生成模板用同一生成definition名称，不按packageId猜。新digest不覆盖原选中包。
- [x] 修改 `fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/EngineConsumerTest.java`和 `LifecycleConsumerTest.java`：真实未知包三个ID各异，安装选包→公开目录→使用目录完整引用经原CAS创建；disabled可发现；缺必需名称的非法制品与合法contract-only空集合区分。
- [x] 正式fixture `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/verification/external/ExternalFixtureRuntimeDriver.java`实现动态SPI并补对应test；仓外 `runtime-provider-application/src/test/java/verification/distribution/RuntimeProviderConsumerTest.java`覆盖同版公开provider接口。
- [x] 搜索所有 `implements RuntimeDriver`、匿名driver、`new JavaFacetDescriptor`及 `entrypoint:`/reader `LOCATION`/`JarEntry`字符串生成夹具，生产provider、外部fixture、测试替身及生成模板一次迁移。使用同版公开制品重编消费者，记录producer/consumer JAR+POM SHA与来源。增加旧无名称格式的负例，不建立旧二进制运行门。
- [x] `scripts/verify-distribution.sh`自身producer没有显式本地repo，运行时MVN指向本次临时wrapper。wrapper先注入producer repository，脚本随后显式consumerrepo参数仍优先；不得运行脚本裸MVN使deploy覆盖全局SNAPSHOT。consumer仍只从其专属临时远程仓消费正式制品。原脚本已完整通过，实际字节与来源证据见交付记录。

```bash
# 写到本次临时目录内的wrapper，其环境由调用者提供
#!/usr/bin/env bash
set -euo pipefail
exec "$FIBRA_GOAL_MVN" -Dmaven.repo.local="$FIBRA_GOAL_PRODUCER_M2" "$@"
```

- [x] 获隔离Tela授权后，从只读脏checkout建立可恢复隔离副本，保留当前未提交消费者事实；在副本适配client目录SPI及原Artifact/Driver/Hub共享reader的文件owner，四份实际可执行Java包及合成可执行fixture补必需definitionId，同步公开Fibra依赖来源和必要发行组装核对。目录读取零renderer attach/import/create；文件cleanup失败保留candidate/Unit及driver同owner索引，stop ACK后的文件门保留drain可读。管理链所需agent/model包必须能经过新Java reader和prepare，不能仅改client SPI声称真实Tela可消费。说明client所选导出在真实加载验证，未声称全集匹配；同步HTTP读不承诺立即Engine fail-stop。任务6b当前51项真实制品消费及最终spec/quality均通过，完整binding准入/取得/回调/关闭已复核；最终四Java真实制品与新Node制品的51项消费及实际ZIP字节映射已通过，file remote/空消费者与完整发行由任务7关闭，不恢复P3。
- [x] 交付根包catalog/API/SPI示例、错误、revision/CAS、作者/runtime统一升级说明、prepare/加载差异文档；不增加管理UI或full-stack装配schema。

## 任务 7：签名、完整验证、独立审查与交付

- [x] 公共授权后才生成签名：原ApiSignatureBaselineTest的 `fibra.updateApiBaseline=true`会更新全部模块，生成后逐文件核实际差异只能属于已批准engine、Java及Node查询合同（Node按实际javap核验）；无关差异先查来源，不接受统一刷新。
- [x] 执行完整验证而不只跑定向测试：原完整44模块1129项、架构边界及client包门均通过，实际终态与输入见交付记录。

```bash
"$fibra_goal_mvn" -Dmaven.repo.local="$fibra_goal_repository" clean verify
scripts/verify-architecture-boundaries.sh
scripts/verify-client-packages.sh
```

- [x] 隔离wrapper环境下执行 `scripts/verify-distribution.sh`及适用 `scripts/verify-reproducible-release.sh`，记录原ZIP/JAR/POM、消费来源和producer实际diff。原脚本均已通过，115制品三次可复现，实际file remote/空消费者来源核对一致。分发脚本里的打包skipTests不是测试资格；完整reactor通过是前置。
- [x] 重大资源路径由独立审查核实际diff及RED/GREEN证据；主线程逐项核候选DC-01至DC-10与目标交付，不接受只有testcount的报告。Fibra集成源码/公共合同、任务6a/6b、最终Node实施双审及整体交付证据审查均PASS B0/I0/M0，DC-01～DC-10闭合；原NOT READY报告保留历史。
- [x] 所有代码/制品/消费者证据齐备，本期目标完成；设计通过、孤立单元测试或静态声明成功均不够。Tela P3仍不恢复。commit/push/发布只在用户另行要求后执行。

## 需求覆盖和实施停止点

### 已批准增量：既有 Node runtime 进程组关闭修复

Node runtime 原本就是通用能力。本增量扩大本期实施范围，修复所有POSIX Node插件共用的关闭路径；不引入Tela政策。用户已明确批准原supervisor在原配置时限内将EPERM保持为尚未取得消失证明，只有ESRCH证明无组，持续未知到期仍失败并保留资源。候选及边界依据`/private/tmp/definition-catalog-node-termination-architecture-evaluation.md`（SHA`d46e59062794a1a677e52ce7e8e283a0efaf554ca9e36af865defd3e33792e0e`）。此前两次原失败的具体探测/信号操作仍未捕获，不把候选反例冒称原现场。

范围：生产只修改`fibra-runtime-node/src/main/resources/com/sstlfsj/fibra/runtime/node/node-process-supervisor.mjs`的范围观察、信号结果及原deadline内结算；测试限定原`NodeProcessUnitTest`/`NodeSidecarTest`及必要私有测试夹具；权威Node生命周期文档由根线程更新。无新API/SPI、依赖、版本、JSON-RPC/终态文件协议、第二owner或整次close重试。

- [x] N1：先沿原NodeProcessUnit.launch/materialize/close及未修改的production supervisor资源构造稳定EPERM反例。test launcher只向本fixture的supervisor注入系统结果，限定own PGID，不传播给payload；闸门释放后调用真实系统检查，不伪造ESRCH。同launcher无故障真实child为正控制，记录source/resource、原生日志/XML及native exit；测试helper与production资源不能混为同一事实源。有效正控制1/0/0/0、行为RED1/1/0/0及33归档字节已核；首轮canonical夹具失败不计产品RED，原两次operation未知。首条修复前完成RED并停止生产修改，完整设计独审见Node关闭规格。
- [x] N2：稳定RED后独审完整观察/信号/EOF→TERM→KILL/原配置deadline/终态缓存/资源保留设计。确认probe和signal的EPERM都覆盖；永久UNKNOWN/PRESENT到期失败，其他错误及传输/状态写失败不被后续ESRCH洗掉；不加强数值PGID身份或离组后代保证。最终设计PASS B0/I0/M1，原请求资源blanket I1已按原请求终态/session/payload lease独立合同关闭；M1归档数已改33。仅设计门通过，原预算不扩大，NLC矩阵GREEN仍须N3/N4。
- [x] N3：原supervisor同owner修复及最终108项Node回归通过；query/TERM/KILL的UNKNOWN→真实ABSENT、永久UNKNOWN/PRESENT、其他errno、独立错误、重复/并发close、现场保留均有证据。最终spec和fresh quality均PASS B0/I0/M0，fixture保留原出生身份及启动失败清理；根线程核实际diff/资源bytes/RED/GREEN。完整发行门仍归N4。
- [x] N4：原生完整clean verify、原distribution与reproducible门均通过，file remote/空消费者及producer/consumer真实JAR/POM/ZIP身份一致。新Node资源与最终四个Java真实制品共同进入最后51项隔离Tela消费；实际测试classpath27、Fibra解包24、Tela运行制品26分别核对，不沿用旧制品SHA证明新组合。最终独审PASS B0/I0/M0，本期全部目标要求闭合，不恢复P3。

DC-01/10对应任务6/7；DC-02/04对应任务2/3/4/5；DC-03/08/09对应任务5；DC-05对应任务3/4/6；DC-06/07对应任务1/3/5。公共合同只在任务2/3/5/7，制品修改在任务3/6，跨仓隔离修改仅任务6且需明确授权。

用户已批准Fibra实施及隔离Tela消费验证，并追加批准既有Node runtime进程组关闭修复。任务1～7及N1～N4均已完成：原完整1129项、原发行与空消费者、115制品三次可复现、最终四Java制品及新Node制品的隔离Tela51项消费均通过，最终独立审查PASS B0/I0/M0。本期目标完成，交付事实与未验证边界见[交付记录](delivery.md)；历史两次完整失败及原operation未知保留在progress，不宣称已定位原现场，不恢复Tela P3。
