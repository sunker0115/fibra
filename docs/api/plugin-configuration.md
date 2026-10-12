# 插件实例配置合同

实现状态：配置合同、正式作者包、发行装配、仓外消费及隔离 Tela 适配已完成；最终签名、全量门禁、可复现发行与合同/质量独审均通过。隔离 Tela 验证不包含 Electron 实机或 OpenAI 联网；用户已决定本次保留 Jackson 3.1.5 并记录既有告警，升级另行处理，详见[最终交付记录](../superpowers/plans/2026-10-11-plugin-config-contract/closeout.md)。

规则由插件作者提供，实例值仍保存在原 `DeploymentTarget` 中。宿主通过 Engine 查询规则、提交完整部署目标；不需要维护另一份配置库，也不要求使用者手写 schema。

## 使用变化

| 场景 | 原来 | 新合同 |
| --- | --- | --- |
| 填写实例配置 | 按插件说明填写原配置 | 继续填写同一份配置，可先查询该 definition 的字段规则 |
| 拼错字段或填错类型 | 可能到 Java 绑定或运行时才发现，部分未知字段被忽略 | 作者规则明确拒绝时，在创建候选运行资源和保存目标之前拒绝 |
| 表达式和上下文 | 由既有 config 模块求值 | 求值后校验 effective 值，保留原表达式与上下文 |
| 默认值 | 由 runtime/插件处理 | 仍由原 owner 处理；schema 不补默认值或转换类型 |
| 更换插件版本 | 选择精确包 revision | 用候选 revision 的规则重验当前值；不自动迁移或删改配置 |
| 路径、服务、进程是否可用 | 在业务与运行阶段检查 | 继续在该阶段检查；结构通过不代表能够启动 |

正式插件的封闭对象规则会拒绝未知字段，这是有意收紧。原 Java 绑定默认忽略未知字段、部分数字转换及 `Void` 配置静默丢弃的行为，不是新结构门的兼容保证。

按新合同直接迁移作者包、宿主调用和实例配置，不提供旧API别名、双格式解析或缺合同时的回退路径。

使用者的 profile 仍采用现有形状。例如，同一 definition 的两个实例：

```yaml
- id: project-files
  plugin: {id: fibra-fs-local, facet: main, definition: fs-local}
  config:
    root: {$ref: /fibra/workspaceRoot}
    spillDirectory: .fibra-spill
  realm: {fibra.fs: files}
- id: archive-files
  plugin: {id: fibra-fs-local, facet: main, definition: fs-local}
  enabled: false
  config:
    root: /data/archive
    spillDirectory: .fibra-spill
  realm: {fibra.fs: archive}
```

假定原 context 提供 `/fibra/workspaceRoot=/data/project-a`，第一项校验的是求值后的字符串路径，第二项保持停用。两者共享下方作者规则，不需要各写一份 schema。schema 文档中的 `$ref` 与实例配置中的 `$ref` 属于不同文档和解释阶段。

## 作者提供一份规则

动态包根固定文件为 `fibra-config.json`。`facets` 下的键必须对应已有 facet 和 definition 名称；名称仍来自 runtime 的静态目录，配置文档不创建名称。

以下是 `main/fs-local` 的结构示例：

```json
{
  "format": 1,
  "profile": "fibra-config-1",
  "facets": {
    "main": {
      "fs-local": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {
          "root": { "type": "string", "minLength": 1 },
          "spillDirectory": { "type": "string", "minLength": 1 }
        },
        "required": ["root", "spillDirectory"],
        "additionalProperties": false
      }
    }
  }
}
```

`minLength: 1` 只检查长度，不能代替非空白、路径约束或目录可用性检查。API 型 facet 没有 definition 时写 `"main": {}`；有一个无配置 definition 时，为它声明 `type: "null"`，两者含义不同。

Java 作者源为 `src/plugin/fibra-config.json`，构建生成 JAR 内 `META-INF/fibra/config.json`；发行装配从该正式 JAR 提取原字节到包根。Node/client 包也提供根文件。Engine 只读包根，不从 JAR 猜测或回退。发布资格检查完整名称集合及全部 schema；运行时按实际使用的 schema 校验。

## 宿主先查规则，再提交原部署命令

安装并将包选入 target 后，用名称目录选择 definition，再查询该精确 selection 的规则：

```java
var ref = new PluginDefinitionRef("fibra-fs-local", "main", "fs-local");
var contract = engine.describeConfiguration(ref, expectedTargetRevision).block();

// 将此 entry 纳入调用方准备的完整 desired graph。
var entry = DesiredInputEntry.builder("workspace-files", ref)
    .config(LiteralValue.of(Map.of(
        "root", "/srv/workspace",
        "spillDirectory", ".spill")))
    .build();

engine.submit(ApplyDeployment.builder(fullDesiredGraph)
    .expectedRevision(contract.targetRevision())
    .selections(fullSelections)
    .configContext(fullConfigContext)
    .build()).block();
```

这里 `fullDesiredGraph` 必须包含 `entry` 及仍需保留的其他节点；`fullSelections`、`fullConfigContext` 也必须完整。查询不创建实例、不启用包、不写 target。查完之后 target 发生变化，原 CAS 会拒绝提交；调用方重新读取完整事实并作出选择。

上面是接入片段，依赖宿主已创建的 Engine 和完整目标。可执行的安装、查询、非法输入拒绝、合法启动及关闭流程见仓外消费者的 [LifecycleConsumerTest](../../fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/LifecycleConsumerTest.java)；作者全包资格见 [AuthorConfigurationConsumerTest](../../fibra-distribution/src/test/consumers/engine-application/src/test/java/verification/distribution/AuthorConfigurationConsumerTest.java)。从仓库根执行 `bash scripts/verify-distribution.sh` 会发布本轮制品，并复制消费者到仓外实际运行；所需工具与制品来源见[仓外验收说明](../../fibra-distribution/src/test/consumers/README.md)。该完整发行检查用于作者交付，不需要使用者在每次提交配置前运行。

可查询 disabled 的已选择包，也可查询 RUNNING/BLOCKED target。revision 过期、未选择包、包不可用、未知 definition、合同缺失或无效分别返回稳定分类。普通查询失败不改原成功 operation；查询清理失败会关闭 Engine 准入并保留失败资源。

`DefinitionConfigContract` 返回四项：`targetRevision` 是本次查询捕获的目标版本，供原 CAS 使用；`packageRevision` 是被选择包的精确内容身份；`definition` 是请求的完整引用；`source` 是该 definition 的 profile 与 schema。结果不带当前实例值或运行资源句柄。

查询的 `ConfigContractException.code()` 按以下边界分类：

| Code | 含义 |
| --- | --- |
| `ENGINE_UNAVAILABLE` | Engine 尚未运行、已停止准入、FAIL_STOP 或已关闭 |
| `STALE_TARGET` | 请求 revision 与捕获的 durable target 不同 |
| `NOT_SELECTED` | revision 匹配，但目标没有选择该包 |
| `PACKAGE_UNAVAILABLE` | 精确包缺失、损坏、身份不符或动态/内建声明冲突 |
| `UNSUPPORTED_RUNTIME` | 包声明的 runtime 没有对应 driver |
| `UNKNOWN_DEFINITION` | facet 或 definition 不在真实静态名称目录中 |
| `MISSING_CONTRACT` | 摘要时根文件缺失，或文档没有对应规则 |
| `INVALID_CONTRACT` | 文档、profile、schema 或预算不满足合同 |
| `READ_FAILED` | 真实读取失败或 SPI 返回非法结果 |
| `CLEANUP_FAILED` | 真实关闭失败；优先于读取错误并保留失败 owner |

null 引用与负 revision 在方法调用时拒绝。业务查询是冷 `Mono`：不订阅则不入队，重复订阅各自读取；取消观察不会取消已接纳的读取与关闭。错误分类是公开合同，不应解析异常文本来驱动流程。

## 纯校验与 Engine 门的区别

`fibra-config` 提供不依赖 artifact/runtime 的纯入口：

```java
var document = DefinitionConfigDocument.parse(documentBytes);
var source = document.facets().get("main").get("fs-local");
var violation = ConfigSchema.compile(source).validate(effectiveValue);
violation.ifPresent(value -> {
    throw new IllegalArgumentException(value.code());
});
```

`validate` 返回 `Optional<ConfigViolation>`：空值表示该实例结构通过，非空值必须由调用方处理；调用本身不会替宿主提交目标。

单项入口只承诺单实例预算。一次命令有多个实例时，使用 `ConfigSchema.validateAll(List<ConfigValidationInput>)`；它统一检查批量预算并按原输入顺序定位普通失败。`ConfigSchema.checkSources(...)` 用于作者发布资格，不需要实例值。集合超预算的 index 为 `-1`，其他失败指向原输入 index。

Engine 自动在原求值之后、任何 candidate create/prepare 之前完成全部 active 实例的静态名称与规则检查，再走原保存与生命周期流程。直接使用低层 `FibraRuntime`、`PluginDefinition.prepare` 或 runtime driver 的消费者，不会自动获得包感知预检，仍负责其原有类型绑定和资源合同。

## null、停用与升级

- 缺字段、显式 `null` 和空对象不同。是否允许缺字段由 `required` 决定；是否允许 `null` 由 `type` 决定。nullable 不会自动生成字段。
- local/祖先 disabled 或 `when=false` 沿原求值语义延期，尚未得到 effective 配置的实例不做 schema 校验。重新启用时重新检查。
- package gate=false 不新增一套求值短路：原 raw/context 求值仍可能失败，只是不执行该包的实例结构门与运行。
- 升级使用候选 R2 的规则。结构拒绝发生在保存前，原 target/current 保留；已安装的 R2 仍属于包历史。运行阶段失败和保存结果不确定继续遵循原 Engine 状态合同。
- builtin 的输入为 `implementationDigest`；最终 `packageDigest` 还覆盖 facet、名称和配置规则。规则改变会改变最终身份，旧 target（包括 disabled selection）不会自动迁移，需要通过原 CAS 显式选择新 revision。

保存状态仍沿原部署合同：新目标在结构门失败为 `NOT_SAVED`；已有 durable target 的恢复或重建为 `NOT_APPLICABLE`，恢复失败保持 `PRESENT + BLOCKED`，允许管理修复；保存后运行失败仍可能是 `SAVED`，写入结果不确定仍为 `UNCONFIRMED`。Registry 在 Apply 前可能已经发布新包，`NOT_SAVED` 只表示目标未保存，不表示包历史被回滚。

## profile 和诊断边界

`fibra-config-1` 是 JSON Schema 2020-12 的受限 profile。支持明确类型、nullable、对象字段与字典、单类数组、标量枚举、长度/数量/数值范围、本地无环 `$defs/$ref` 和有限组合。拒绝外部引用、正则/format、动态引用、默认值及未支持关键词；不能把通用 JSON Schema 文档直接当作已兼容。

固定文档最多 1 MiB。另有单 schema、effective 值、嵌套、节点、数值和批量工作预算；完整界限见[配置合同规格](../superpowers/specs/2026-10-11-plugin-config-contract-design.md#51-工作与数值预算)。预算超限可在库执行前拒绝，不代表 CPU、堆内存或耗时 SLA。

Java 侧以精确 `LiteralValue` 数字参与结构校验。该保证止于 Engine 的 effective 值；Node 的普通 `JSON.parse` 数字表示与 client tagged decimal 协议需分别处理，结构通过不会让 JavaScript Number 获得任意精度。

新诊断仅提供稳定 code、受控 keyword/pointer，不回显原值、未知动态键或第三方异常。既有 `EngineChangeException.view()` 仍是可能包含完整 target 的管理视图；宿主输出日志或外部错误 DTO 时应只选择安全诊断字段，不能序列化整份异常对象图。
