# 配置合同文档一致性审查 R1

结论：完整阅读指定 7 份文档并核对当前源码后，发现 1 处 A 类文档同步问题；B/C/D/E 未发现独立、需要处理的配置合同问题。仅写本报告，未修改项目文件，未运行 Maven，未访问 Tela 主树。

范围：`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`、`docs/api/plugin-configuration.md`、`README.md`、`docs/api/README.md`、`docs/release.md`、`docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md`、`docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md`。任务 5 的作者模板、正式制品及仓外消费尚待验收是已明确的阶段事实，不作为缺陷。

先执行了一次定向 Nowledge 搜索（Fibra plugin configuration contract）。结果主要是旧部署/发行背景，没有当前配置合同的可用决定；以下结论完全依据当前工作树，不引用历史记忆作为现状证据。

文档职责：配置专题规格拥有字段、预算、错误与精确失败投影；API 文档负责使用步骤和消费边界；README 为能力概述/索引；release 负责制品及发布资格；vNext/Client Foundation 保留总体 owner、依赖和生命周期。没有发现本次新增内容形成第二套实例事实源或不恰当扩大产品边界。

## A. 前后矛盾

### A1. Client Foundation 的固定关闭流程未同步读取 owner 失败的前置屏障

严重度：P2。此处需要改文档，无需新架构决定或实现变更。

`docs/superpowers/specs/2026-09-15-fibra-client-foundation-architecture.md:795-798` 原文：

````text
```text
关闭新准入，排空已接纳的 command，并发布 SHUTDOWN / CLOSING
→ generations 封准入、drain、stop、retire
→ runtime drivers 逆序关闭
````

`docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md:275-277` 原文：

```text
| Reconcile重建/runtime requestRecompile | Operation(operationId) | RECONCILE/PLANNING/FAILED，durableRevision，NOT_APPLICABLE |

部署普通拒绝的failure stage为PLANNING，cleanup失败为RELEASING；operationStage始终PLANNING且targetRevision与本次operation一致，不虚构candidate attemptId。部署cleanup使用原failStop(fact)先把operation置FAILED并关闭准入；query沿Engine失败投影，不把之前成功Apply改成FAILED。BOOTSTRAP显式BLOCKED。失败attempt保留，shutdown在新建SHUTDOWN operation前观察同一缓存close终态，失败时不覆盖首个失败事实/operation，不释放其driver/store依赖；兄弟清理继续。read+close双失败仅组合固定安全code，close优先。上述五条路径均需字段级反例，不能只断言抛异常。
```

`fibra-engine/src/main/java/com/sstlfsj/fibra/engine/FibraEngine.java:1170-1173` 原文：

```java
    private Mono<Void> shutdown() {
        if (readAttempt != null) {
            return readAttempt.closed.then(loop.call(this::shutdown));
        }
```

问题：§10 将 SHUTDOWN/CLOSING 写成排空后无条件发生的第一步；新合同及实现中，读取资源的缓存关闭失败会在建立 SHUTDOWN 前结束关闭，保留原 operation/failure 和 driver/store。消费架构流程图的宿主实现者可能误以为每次 close 都必须覆盖旧 operation 为 SHUTDOWN，正好违背本次明确要求。

最小修正：在该流程图发布 SHUTDOWN/CLOSING 之前加入“观察 Engine 持有的读取 attempt 的缓存 close 终态”步骤，并在紧邻正文明确：失败保持既有 fail-stop、首个 operation/failure 和相关依赖，返回同一失败；只有读取屏障成功后才进入图中正常关闭流程。指向配置专题 §7 的详细投影即可，不复制五路径矩阵。

三项核查结果：

- 内部断言核查：搜索该架构全文的 `读取`、`清理失败`、`SHUTDOWN`、`首个`。§4 已写清读取失败保留资源，§10 尚未写出该前置屏障；因此不是“全文完全缺失读取失败语义”，而是后面的无条件流程与已有约束冲突。
- 引用回读：重新读取上述真实行号，并核对 `FibraEngine.java:1177-1182`，`beginOperation(SHUTDOWN)` 确实位于读取屏障之后。
- 同文档交叉核查：`2026-09-15-fibra-client-foundation-architecture.md:266-268` 明确普通查询不改历史 operation、清理失败保留资源与首个失败事实；§10 815-817 的例外只列 candidate/retirement/unit，未覆盖读取 owner。已有同文档段落不能消除该固定流程的误导。

补充静态证据：`EngineConfigurationContractTest.java:167-194` 的 `queryDoubleFailureRetainsOnlyFailedOwnersAndDoesNotRewriteSuccessfulOperation` 明确断言重复 close 返回相同错误、driver/store 关闭计数为 0、operation 保持原值。这里只核对测试源码，未宣称本轮执行通过。

## B. 语义不清

未发现独立问题。raw/effective 与 schema `$ref`、null/缺字段/空 object、package gate 与 local/祖先停用、普通查询失败与部署失败、安全错误投影与受权管理 view 均已区分。

## C. 缺失

未发现独立问题。预检读取 owner 的关闭分支已归 A1，不重复列缺失。任务 5 尚待完成的作者消费者/可执行示例资格未列为文档缺陷。

## D. 可调整

无本范围内必要建议。未把历史章节的非配置问题、泛化的措辞润色或尚未交付状态列入本轮。

## E. 内容定位错位

未发现问题。专题规格承载完整预算/错误表，API 文档以链接引用完整界限，release 承载包根/JAR 同源及发布资格，架构文档保留 owner 与生命周期约束，分层合理。

## 已核对的实现边界

- `ConfigSchema` 单项与批量签名、`DefinitionConfigDocument` 严格 envelope、`ConfigSchemaSource` 中立构造预算、公开诊断枚举与文档吻合。
- `PluginConfigurationResource` 固定资源读取、缺失信号、重复 read/close 和先 close 后 read 合同与专题规格吻合。
- `BuiltInFacet.configSchemas` 与 `BuiltInPluginPackage.implementationDigest` 的公开入口、派生最终 digest 与直接迁移描述吻合。
- `FibraEngine.describeConfiguration` 的冷 Mono、revision/selection/runtime/name/source 顺序、返回纯 source 与查询错误分类吻合。
- `deploy` 在 `planner.inputs` 后完成全部 active 输入的读取/真实关闭/批量校验，之后才创建 candidate；no-op 及健康 reconcile 例外在专题规格中已明示，不构成绕门矛盾。

修正 A1 后应重新完整扫描指定文档；当前报告不是“零修改收敛”结论。
