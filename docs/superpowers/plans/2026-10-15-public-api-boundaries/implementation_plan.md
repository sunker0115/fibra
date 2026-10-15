# Fibra API 稳定边界实施计划

> 使用 subagent-driven-development：主线程拥有合同与集成，独立任务按文件边界实施，规格/质量分别审查。
> 用户已授权建议成立后开始；本轮不自动commit/push，不修改Tela。

**Goal:** 完整部署保留调用者CAS，所有公开类型有明确受众与owner，普通管理与高级诊断分层并冻结演进规则。
**Architecture:** Registry仍仅适配Engine；所有读取从同一PublishedView投影；真正runtime SPI保持公开，私有实现收回。
**Tech Stack:** JDK21、Maven3.9.9、原Jackson/Reactor/JUnit与现有签名门；无新增依赖或模块。

设计见[规格](../../specs/2026-10-15-public-api-boundaries-design.md)。活动状态只见[task_plan](task_plan.md)。

## 1. 设计与完整审定

- [ ] 阅读全部19份Java公开签名与2份npm基线，写 `docs/api/public-api-inventory.md`，分组覆盖受众/owner/支持边界及精确内部例外。
- [ ] 独立审查规格与实际Engine/Registry/consumer，B/I/M清零；已授权的收口不用重复请求同范围许可。

## 2. Registry CAS与读合同（有界实现任务）

文件：Registry的PluginDeploymentRequest/PluginRegistry/RegistrySnapshot、新增RegistryDiagnostics，PluginRegistryTest；
CliHost、EngineTransactionBenchmark、EngineConsumerTest、PluginAcceptanceHarness的请求构造点及读取测试。

- [ ] 用现有真实Fixture/Store写R1→R2→旧R1完整deploy断言RED；新expectedRevision接口缺失的编译RED单独记录，不能当行为RED。
  可先通过反射适配新构造签名与旧构造，固定请求编辑基线，再调用真实Engine，验证缺陷不是无关fixture错误。
- [ ] 实现唯一四参数请求 `new PluginDeploymentRequest(expectedRevision,selections,graph,context)`；负revision拒绝，完整deploy原样转交值。
  原三参数构造删除，无latest兼容路径；增量mutate/replace语义不改。
- [ ] 增加匹配revision成功、陈旧同内容拒绝、同revision同内容no-op、延后订阅与重复订阅不重捕revision、失败审计NOT_SAVED/目标不变测试；
  原失败保存、UNCERTAIN、增量fresh图与资源关闭断言保留并迁移。
  成功保存态根据请求expectedRevision与结果targetRevision判定；覆盖before仍R1但排队执行到R2的同内容no-op，不能误报SAVED。
- [ ] 普通snapshot按规格9字段+Builder替换；state/gates/target/observed从同一次PublishedView投影；get/list/watch沿同一函数。
  新RegistryDiagnostics三字段一次捕获；不存在engine/current等原普通访问器。
- [ ] 先有反射形状/同revision投影/已有失败与watch测试RED，再实现；诊断仅查询不触发部署或资源取得。
- [ ] 全仓请求构造点传调用方同次读取的target revision（无target为0）；CLI在读profile/prepareInstall前捕获，不能在提交前偷换成latest。
- [ ] 迁移Registry已有测试对engine/诊断的读取，普通字段用snapshot，具体attempt树用diagnostics；按相同viewRevision比较。

## 3. 收窄内部型（主线程）

- [ ] 在JavaPackageArchitectureTest或新的PublicApiBoundaryTest先写10个类型非public的失败断言，正式SPI/provider/API类保持public。
  只反射Class元数据，不要求错误地从别的包编译引用将被收窄的类。
- [ ] 10类型仅变更可见性：Engine的DeploymentCandidate/PluginCatalog/PluginCatalogEntry/DesiredBindingException/UnknownRuntimeException，
  JavaRuntimeDriver/JavaFacetDescriptor，NodeRuntimeDriver/NodeFacetDescriptor/NodeEndpointManifest。record构造与访问器随所属类收窄；不改加载算法或关闭顺序。
- [ ] 编译跨模块消费者与原runtime测试，证明接口RuntimeDriver仍可消费；不把运行时SPI和实现类型一并隐藏。

## 4. 集成、基线和权威文档

- [ ] 主线程核对实际diff/失败日志/既有测试，无新增模块/依赖/协议/持久格式。
- [ ] 用原ApiSignatureBaselineTest生成并核对差异，仅engine、registry、runtime-java、runtime-node有预期变化；其余模块必须不漂移。
- [ ] 更新docs/api入口、整体规格、vNext相关正文、Client Foundation相关正文、CHANGELOG，清理当前过时正文而非堆叠补丁说明。
- [ ] 独立规格和代码质量审查，修正并复核至B/I/M清零。

## 5. 验收与独立交付

- [ ] 定向、全量clean verify、architecture与client-public-packages；原Node/子进程环境限制按实际证据诊断，不弱化断言。
- [ ] 独立仓build-m2排除旧com/sstlfsj，构建/发布到本轮专属public-repository，不碰e133814已有公开仓与Tela m2。
- [ ] 仓外新consumer只从新POM/JAR/ZIP解析：完整CAS冲突/无变化、管理与诊断、公开SPI及原Host类型共享；逐文件来源/摘要绑定。
- [ ] 原可复现发行门（包装比较与正式测试分开留证），源码/原生命令/退出/报告/制品摘要归档。
- [ ] 核对Tela固定旧批次仍可继续，输出精确新合同及需要未来适配的调用点；不自动给其它会话发新指令或修改Tela。

定向命令（先按mvn-env设置JDK21）：

```sh
$MVN --batch-mode --no-transfer-progress -o \
  -Dmaven.repo.local=/private/tmp/fibra-api-boundaries-evidence-20261015/build-m2 \
  -pl fibra-registry -am test -Dtest=PluginRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

所有正式命令用本轮证据目录run.py记录；连续两次相同问题失败停止盲试并记录诊断。只有实际全部验收完成才关闭目标。
