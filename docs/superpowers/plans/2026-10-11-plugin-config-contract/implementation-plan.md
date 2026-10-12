# 通用插件实例配置合同 Implementation Plan

> **For agentic workers:** 使用 `subagent-driven-development` 分任务实施，先合同符合性审查，再代码质量审查；用户禁止自动commit/push，覆盖skill默认提交步骤。

**Goal:** 从正式插件制品无执行发现配置合同，在Engine原保存链统一校验effective输入，完成真实Java/Node/client消费与文档。

**Architecture:** artifact只提供绑定精确包的固定资源owner；config提供纯文档解析、profile准入和批量校验。Engine串行编排候选读取/关闭/检查，不改变runtime业务和资源owner。规则与实例值分离，保留单一target/CAS。

**Tech Stack:** Java21、Maven3.9.9、Jackson3.1.5、networknt3.0.8、Reactor（仅已有Engine层）、现有npm客户端制品。

## 验证命令环境

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

测试依赖使用本任务独立本地仓路径 `/private/tmp/fibra-config-contract-m2`；不混用历史同坐标SNAPSHOT。缺依赖按真实错误补齐精确制品，不偷换版本或削弱测试。每个任务保留RED、GREEN的命令/退出码/报告路径，连续两次同原因失败先诊断。

## Task 1：config纯合同与schema执行

状态：2026-10-12完成本任务。config94+api2通过；合同R3和质量R2均B/I/M=0。RED/GREEN、独立反例和输入摘要归档于`../../references/plugin-config-contract/implementation/task1-config/`。Engine、发行和真实消费者的完成结果分别见Task4–7。

Files：新增 `fibra-config/src/main/java/com/sstlfsj/fibra/config/` 下 ConfigSchemaSource、DefinitionConfigDocument、ConfigSchema、ConfigSchemaException、ConfigViolation、ConfigValidationInput、ConfigBatchFailure；package-private的解析、预算/profile准入与库adapter按职责拆分。修改fibra-config/pom.xml，按现有根依赖管理约定登记networknt。测试位于相同test package的DefinitionConfigDocumentTest、ConfigSchemaTest、ConfigSchemaBudgetTest。

- [x] 先增加缺合同API的可执行失败断言，再补接口与最小行为，逐行为RED/GREEN；新API尚不存在时用反射断言缺方法/类作bootstrap RED，随后切换静态调用测试。
- [x] 文档严格解码、全局/单schema预算隔离；非法envelope全局拒绝，dormant sibling语义按需。
- [x] 2020-12白名单及有限组合、typed map、本地DAG；meta-schema固定内建白名单，外部URI拒绝；精确数字与不转换、不补默认。
- [x] 所有执行词/节点/文本/数值/展开路径纳入饱和预算；config拥有命令总预算，先全量接纳后编译和执行；动态键诊断截到静态父路径。
- [x] 单项与batch共享唯一实现，无第三方异常或networknt Error诊断对象逸出；不将JVM致命错误吞成配置不匹配。实际断言使用以下公共合同：

```java
var source = new ConfigSchemaSource("fibra-config-1", (LiteralValue.ObjectValue)
    LiteralValue.of(Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "string")));
assertTrue(ConfigSchema.compile(source).validate(LiteralValue.of("ok")).isEmpty());
assertEquals("VALUE_MISMATCH", ConfigSchema.compile(source)
    .validate(LiteralValue.of(7)).orElseThrow().code());
assertEquals(1, ConfigSchema.validateAll(List.of(
    new ConfigValidationInput(source, LiteralValue.of("ok")),
    new ConfigValidationInput(source, LiteralValue.of(7)))).orElseThrow().inputIndex());
```

Run：`$MVN -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-config -am test`；GREEN要求原config求值/读取回归与新profile/safety/batch反例通过。对应CC03/04/06/09/10。

## Task 2：artifact固定资源及身份

状态：2026-10-12完成本任务。artifact88项通过；合同R1和质量R1均B/I/M=0，最终源输入已绑定。证据在`../../references/plugin-config-contract/implementation/task2-artifact/`；Engine登记与兄弟清理由Task4验证。

Files：PluginPackage.java、PluginPackageRecord.java、PluginPackageStore.java；新增PluginConfigurationResource.java和package-private固定资源实现；PluginPackageTest、PluginPackageStoreTest及新增PluginConfigurationResourceTest。

- [x] 失败测试先证明find(R1)后改变资源不会作为R1返回、手造record不具新能力、缺失/非regular不同。
- [x] 原digest同chunk派生资源SHA/长度/存在性，不改变fibra-content-v1；两snapshot一致后私有随record，toBuilder仅内容身份不变保留。
- [x] 创建无I/O，read有界且stream立即归owner；read/close串行真实终态；close失败保留且不重试；成功清除资源；不引入config/Reactor依赖。

```java
PluginConfigurationResource resource = record.configurationResource();
byte[] bytes = resource.read();
resource.close();
assertArrayEquals(expected, bytes);
```

同夹具还须文件替换、read/close双失败、并发close等待read、close先发生、兄弟清理的反例；不能只检查上述成功。Run：`$MVN -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-artifact -am test`。对应CC02/07。

## Task 3：builtin元数据与唯一内容身份

状态：2026-10-12完成。合同与质量独审均B/I/M=0；合同审查独立20项通过。builder输入与最终摘要已直接迁移；核心reactor560项、HostRestartRecoveryTest真实独立进程2项通过，benchmark编译通过。原私有definition不一致仍断言真实Java prepare现场。证据归档至implementation/task3-builtin。

Files：BuiltInFacet.java、BuiltInPluginPackage.java；DeploymentTargetCompiler.java（身份引用）；BuiltInPluginPackageTest、JavaBuiltInRecoveryTest及真实builder调用点。

已核当前builder消费还包括EngineTransactionBenchmark、RuntimeDriverEngineTest、EngineAttemptStateModelTest、HostRestartRecoveryProcess、JavaRuntimeDriverTest、PluginRegistryTest、FibraServiceBridgeTest、FibraCliTest；只迁移builder输入，不机械替换最终身份读取。新增schema预检后还须补齐真实启动测试夹具的作者合同，不能跳过旧生命周期断言。

- [x] RED验证只改schema、facetId、profile会改变最终packageDigest，而map/集合顺序不改变。
- [x] 新增configSchemas，输入改implementationDigest；摘要包含已冻结全部字段，未知profile/坏schema不在provider构造阶段语义编译。

```java
var facet = BuiltInFacet.builder(facetId, runtimeId, target)
    .definitionIds(Set.of("main")).configSchemas(Map.of("main", source)).build();
var builtin = BuiltInPluginPackage.builder().pluginId(pluginId).version("1")
    .implementationDigest(implementationDigest).facets(List.of(facet)).build();
```

新增合同只限已批准部分，不将所有packageDigest读取机械替换。完整旧target恢复BLOCKED及原CAS修复在Task4验证。Run：`$MVN -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine,fibra-runtime-java -am test`。对应CC01/02/05/06。

## Task 4：Engine共同读取/预检与查询

状态：2026-10-12完成。合同和质量独审均B0/I0/M0；最终核心reactor516项、独立合同审查112项通过。入口220项（含真实重启2项）早于最后一行同步关闭栈修复，未冒充最终全入口复跑；Node沙箱失败以原样权限恢复49项验证。证据归档至implementation/task4-engine。正式作者、发行与消费者的完成结果见Task5–7。

Files：FibraEngine.java、DeploymentTargetCompiler.java及现CatalogAttempt相关内部实现；新增deployment/DefinitionConfigContract.java、ConfigContractException.java及有界内部协作者；扩RuntimeDriverEngineTest，新增EngineConfigurationContractTest。

- [x] 先写未知definition/缺合同/字段非法均在candidate create/prepare之前失败、query不执行插件、候选R2不同于当前R1的失败测试。
- [x] 查询实现 `describeConfiguration(PluginDefinitionRef,long)`，原lane/fence；内部预检直接消费捕获records，不在lane中调用公开query。
- [x] 统一name/config读取父owner，真实close后才交结果；部署与query分开投影FailureFact，保留失败句柄、driver/store及首错。
- [x] 五条失败发起路径、raw/ref/if/literal、disabled/when/package gate、恢复/启用/升级、no-op与健康reconcile逐项按设计表断言。

```java
var contract = engine.describeConfiguration(definition, expectedRevision).block();
assertEquals(expectedRevision, contract.targetRevision());
assertEquals(selectedRevision, contract.packageRevision());
assertEquals(0, candidatePrepareCount.get());
```

Run：`$MVN -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-engine -am test`；再跑Registry/CLI/Spring入口回归。对应CC01–08/10。

## Task 5：正式作者包、模板和发行装配

状态：2026-10-12完成。合同R2和质量R1独审均B0/I0/M0；原1059项作者测试逐class对齐日志和XML，修复后内部定向11、仓外14项通过，原发行ZIP/CLI/TTY/关闭门通过。12正式包source/JAR/root/ZIP字节链一致；最终54文件输入、24仓外源与27实际依赖/插件JAR身份已独立核验。正式消费者增量和隔离Tela仍属于Task6。

Files：consumer-impact.md列出的12个正式插件各src/plugin/fibra-config.json和pom.xml；fibra-distribution/pom.xml；fibra-plugin-archetype模板pom/assembly/metadata/README及生成测试；content-sanitizer Node包及README。

- [x] 先对正式JAR/ZIP缺固定合同、模板缺schema、作者源字节不一致写失败验证；每包按真实descriptor和Config代码定规则，不把API型插件一律当null。
- [x] 作者源单份，经构建派生JAR META-INF/fibra/config.json；发行从精确JAR提到包根。Maven/Velocity不得变形$schema/$ref；无runtime fallback。
- [x] 作者QA枚举全部真名称、比对missing/orphan/空facet、checkSources全量资格，关闭失败保持owner。

```java
assertArrayEquals(authorSource, jarResource);
assertArrayEquals(jarResource, packageRootResource);
assertTrue(ConfigSchema.checkSources(allSources).isEmpty());
```

Run：原archetype IT、plugins acceptance、distribution verify；以当前POM真实profile/参数执行，不跳测试。对应CC01/03/11/12。

## Task 6：正式消费者及隔离Tela

状态：2026-10-12完成。合同R2和质量R2均B0/I0/M0，质量I1/I2经四文件窄修关闭。最终仓外19项、隔离Tela Java34项、干净Host单方法、完整npm29及真实Java wire到renderer28项通过；3个Fibra消费者输入与最终482项Tela输入已锁定。未修改Tela主树；未验证Electron实机、OpenAI联网或完整Tela发行矩阵。

只读覆盖核对见[消费者验收差距](consumer-acceptance-gap.md)。复用已有R1/R2、owner五路径和状态机证据；增量聚焦并发CAS、Engine到真实Java validator一次、实际出口安全投影、Node数值边界、产品client实际执行和关闭，不重建验收框架。

Files：fibra-distribution/src/test/consumers下Java作者、Engine/provider/CLI/Spring消费者；Tela范围只限consumer-impact.md，在新的任务副本锁定完整输入与当前正式依赖后运行。

- [x] 正式Maven制品独立仓外consumer；Java静态发现无执行、Node真实spawn/config、client真实import/create/关闭。
- [x] 新版规则拒绝旧值保留原target，错误DTO/日志secret canary，真实Node数字表示与client tagged decimal分别记录。
- [x] 验证隔离Tela 4个Java包与client合同来源/JAR/包根、Host API消费及renderer真实模块；不改主树、不新增UI/产品组合/wire。

```java
assertEquals(before, engine.published().current());
```

before是拒绝前取得的原PublishedView，普通query失败不得修改它；Apply失败按原operation公开投影分别断言旧target和新的失败operation。每项实际命令、制品SHA、nativeExit、断言覆盖写入验证账本；Desktop实机未运行就明确缺失，不以Host通过替代。对应CC03/05/08/10/11。

## Task 7：文档、签名与终审

状态：2026-10-12完成。最终完整clean verify为1228项/171份testsuite报告、零失败/错误/跳过；原可复现发行、六阶段Java→TS、最终ZIP/POM仓外解析及真实CLI、架构门均通过。最终合同/质量独审均B0/I0/M0，源码及制品身份闭合；详见[最终交付记录](closeout.md)。用户已决定本次保留Jackson3.1.5并明确记录既有告警，升级另行处理，不表示告警已消除或授权发布。

执行顺序和证据保护见[最终门准备](final-preparation.md)。该准备报告的两项OSV结果只代表早期范围；后续完整发行清点已发现既有Jackson告警，以[依赖资格](dependency-qualification.md)的当前结论和用户决策为准。

Files：README.md、docs/api/README.md、新plugin-configuration.md、config/artifact/engine公共签名、vNext/ClientFoundation相关权威节、release/THIRD_PARTY_NOTICES、作者模板/例子说明。

- [x] 新旧使用对照、作者与host可运行例子、支持语言/默认/null/unknown/停用/升级/保存状态和读取安全边界与实现一致。
- [x] 原签名/架构/发行/npm检查通过；依赖树、许可证、JAR/ZIP内容及真实consumer证据绑定本次字节，安全告警单独保留。
- [x] 逐CC01–12双向审计实际源码/断言/正式产物，独立合同审查后代码质量审查均通过；没有commit/push。
- [x] 已授权配置合同的全部目标证据闭合；用户决定记录既有Jackson告警并将升级另行处理，技术门通过不表示告警消除或授权发布。
