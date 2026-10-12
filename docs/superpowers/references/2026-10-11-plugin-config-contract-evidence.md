# 通用插件配置合同：来源与适用边界

2026-10-11设计资格证据；2026-10-12用户已批准对应公共API、依赖及制品范围，实施状态见[推进计划](../plans/2026-10-11-plugin-config-contract/task_plan.md)。本证据不代表生产实现或发行验收通过。Fibra基线 `0a674176b8b762b4e77044bcba7f051bf06c41d9`；调查时分支 `codex/plugin-config-contract`，当时生产源码未改。

## 1. 问题归属与现有链

`fibra-config` 已有严格 JSON/YAML 读取、include/patch 编译、不可变 raw graph、上下文及条件求值；不是空白配置系统。`ConfigDocumentReader` 使用 duplicate/trailing token 检查与 BigDecimal/BigInteger；其读取入口要求 root array，不能将 schema 文档伪装成 desired graph。

`DesiredEvaluation` 计算实际 effective LiteralValue。`LiteralValue`（fibra-api/value）递归不可变，所有 number 用 BigDecimal；Java runtime 的类型绑定和 `PluginDefinition.ConfigValidator` 是后续执行逻辑。新增缺口是 definition 的静态结构合同及所有 Engine 管理入口共享的结构门，不搬走 runtime 的绑定和资源职责。

`FibraEngine.describeDefinitions` 通过同一个 `EngineCommandLoop.submit` 读取当前 durable selection；`EngineCommandLoop` 使用 concatMap 串行执行。Apply 内调用并等待该公开方法既可能自等待，又会读到升级前 selection。新部署检查必须消费已捕获的候选精确 record，内部复用 reader，不递归调用公开查询。

当前 `BuiltInPluginPackage` 由作者直接传 packageDigest；`BuiltInFacet` 只有 definitionIds，没有 schema。只把 schema 加进进程内 fingerprint 不能阻止重启后同旧 revision 使用新合同。候选需使 schema 内容进入框架计算的 builtin packageDigest，仍保留唯一 selection/packageRevision。

本次先读取的三个仓外报告是调查线索，不是权威规格：`/private/tmp/tela-p3-config-contract-owner-review.md`、`tela-p3-author-contract-public-seams.md`、`tela-p3-config-schema-library-evidence.md`。结论逐项复核，正式资格见本次设计与后续测试账本。

## 2. OSGi、VS Code 与 JSON Schema

以下为 2026-10-11 重新读取的一手官方规范，非名称目录旧调查的来源补写。

| 参照 | 核实的机制 | 本项目取舍与未解决窗口 |
|---|---|---|
| [OSGi Compendium 8 Metatype §105.1/5](https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.metatype.html) | 作者携带描述，服务统一访问；也允许动态 provider | 最小改造：采用作者声明/统一发现/上层呈现分工；静态资源才能满足不执行插件代码，拒绝动态 metadata provider、XML 与另一服务体系 |
| [Configuration Admin §104.1.2–3](https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.cm.html) | 以 PID 维护配置数据库并向 managed services/factories 供给实际值 | 只采用“描述与实际值分工”原则；实际值在 Fibra 已由 DeploymentTarget 唯一持久化，拒绝复制其配置数据库/更新通道 |
| [VS Code contributes.configuration](https://code.visualstudio.com/api/references/contribution-points#contributes.configuration) | manifest 描述服务 Settings/JSON 编辑，扩展经 getConfiguration 读实际值 | 最小改造：描述发现与显式读值区分；不复制全局 settings/UI 平台，不据此推导 Fibra save/CAS/资源保证 |
| [JSON Schema annotations](https://json-schema.org/understanding-json-schema/reference/annotations) | default 是注解；readOnly/writeOnly 表达消费语义 | 直接采用 default 不自动注值；读写授权及秘密管理另归产品，不把 schema 当 ACL |
| [Standard Schema](https://standardschema.dev/) | TypeScript validate/transform 接口，成功结果可返回转换后的 value | 参考接口分工，不能当作跨 Java/Node 的无执行静态 JSON 合同；Fibra 纯结构门不采用其转换返回值 |

名称目录先前参照是 PF4J 索引、ServiceLoader 发现/实例化分离和 OSGi revision/wiring，见 [目录证据](2026-10-11-definition-catalog-evidence.md)。Metatype/Configuration Admin 为本次配置合同新增参照，不倒写目录历史。

## 3. DSH/Cordis 固定源码

本机 `/Users/sunke/dev/ai-project/deepseek-harness` 的 HEAD 已核为 `c291e7961a515f6d7af9304e7fd1d257929aef26`。

- [fiber.ts:49–61](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/fiber.ts#L49)：Config 可选，缺少时返回原值；调用 `Config['~standard'].validate`，拒绝 Promise，成功可返回归一化 value。
- 同文件 [736–752](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/fiber.ts#L736)：inactive 更新延期配置 resolution，原因是它可能访问注入服务；活动时先 resolve 再 restart。
- 采用：验证时点必须服从真实生命周期，有效停用不能凭假开关来验证；业务归一化由 runtime 明确承担。
- 不采用：可选执行代码验证不能满足 Fibra 的强制静态描述/跨 runtime 保存前结构门；DSH 配置可访问服务的前提也不等于 Fibra 纯 effective 求值。Fibra 的 target/CAS、精确候选和失败关闭仍须本项目验收。

## 4. Pi：区分扩展、产品设置和工具参数

按用户要求重新读取本机 `/Users/sunke/dev/ai-project/pi`，HEAD `f1b2e77f5b13b2a199b1052cb79c235451afe7d7`，origin `earendil-works/pi`；只有未跟踪 `.codegraph/`，未修改。以下是这个固定版本及列出路径的结论，不泛称所有版本都没有配置能力。

| 固定源码 | 已核事实 | 取舍 |
|---|---|---|
| [extensions/loader.ts:560–630](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/extensions/loader.ts#L560) | jiti import 后执行 factory(api)，成功 commit，失败 discard | 采用注册失败应由原 owner 收口；拒绝把执行 factory 获得的工具描述冒充无执行的实例配置发现 |
| [extensions/types.ts:572–586](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/extensions/types.ts#L572)、loader.ts:289–302 | 工具 parameters 是 TypeBox schema；注册入口检查参数描述是 object | 参数合同属于工具调用，不是 extension instance 的跨 runtime 静态配置规范 |
| [ai/utils/validation.ts:317–351](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/ai/src/utils/validation.ts#L317) | clone 参数后处理 optional null、Value.Convert 和 JSON schema coercion；失败文本附 Received arguments | 拒绝复制到纯配置门：会转换输入且回显原值；成熟项目的选择不构成 Fibra 精确数值/秘密安全依据 |
| [settings-schema.ts:206–446](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/settings-schema.ts#L206) | 产品 SettingsSchema，root additionalProperties=true，含 default 注解 | 它是产品设置描述，不是每个未知插件的权威 definition schema；开放字段是其政策，不能替 Fibra 决定 |
| [settings-schema.ts:5–25](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/settings-schema.ts#L5) | 部分计数显式限制JS安全整数；timeout为number或disabled；modelOverrides为动态键到结构对象 | 采用这些真实场景检查通用profile是否够用；不把所有Fibra数字静默缩到JS Number，也不将产品设置等同插件合同 |
| [settings-manager.ts:374–453、597–625](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/settings-manager.ts#L374) | 读取 JSON.parse 后迁移；持久写在锁内重读，只合并本次修改字段；从 settings-schema 只 import type | 采用避免覆盖他人写入的原则；Fibra 对应既有完整 target CAS，不复制另一设置仓或字段合并状态；不能仅见 Schema 常量即声称全部读取执行 schema 验证 |

## 5. OpenCode：开放 options、产品 schema 和生命周期

本机 `/Users/sunke/dev/ai-project/opencode`，HEAD `388406238bd5ca15564a762840a2362c3a45bd9c`，origin `anomalyco/opencode`；只有未跟踪 `.codegraph/`，未修改。同时核了当前树中 V1 与 V2 路径，不能混为一套接口。

| 固定源码 | 已核事实 | 取舍 |
|---|---|---|
| [plugin/src/index.ts:68–81](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/plugin/src/index.ts#L68)、core/src/v1/config/plugin.ts | V1 PluginOptions 为 Record<string,unknown>，配置接受 string 或 [string,options]；入口接收 options | 采用作者/插件拥有字段含义；开放传递不满足 Fibra 强制静态类型/必填合同，不能称已有等价方案 |
| [core/src/config/plugin.ts](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/core/src/config/plugin.ts)、[plugin/external.ts:38–95](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/core/src/config/plugin/external.ts#L38) | V2 package + 可选开放 options 对象（字符串键，字段值 unknown）；先解析/安装/import，再核 id/effect/setup，把 options 或空对象交给 effect；外部加载逐项 ignoreCause | 可参考输入/模块职责分离；不复制 npm 安装、动态执行来发现配置、空对象默认与忽略失败策略到 Fibra 保存门 |
| [opencode/src/plugin/index.ts:114–124、219–252](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/opencode/src/plugin/index.ts#L114) | V1 按顺序初始化插件传 options；随后 config hook 获得产品 cfg；失败日志后继续 | 采用显式时序；这不是“保存前全局结构验证且失败 target 不变”的证据，Fibra 保留自己更严格的失败分类 |
| [opencode/src/config/parse.ts](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/opencode/src/config/parse.ts) | 产品配置由 Effect Schema decode，onExcessProperty=ignore；JSONC 语法错误可附完整源文本 | 参考统一产品解析入口；拒绝 silent unknown 丢弃及全文错误回显作为通用 definition 配置政策 |
| [plugin/src/tool.ts:45–52](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/plugin/src/tool.ts#L45) | tool args 是 Zod shape，tool.schema=z | 工具参数不等于插件实例配置，不复制 Node 校验库到 Java 宿主 |
| [loader-shared.test.ts:812–849](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/opencode/test/plugin/loader-shared.test.ts#L812) | 测试明确 tuple options 传到 server plugin | 读取的测试是行为意图证据；本轮未运行 OpenCode 测试，不声称其回归通过 |
| [core/src/config/mcp.ts:15–47](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/core/src/config/mcp.ts#L15)、[provider.ts:7–70](https://github.com/anomalyco/opencode/blob/388406238bd5ca15564a762840a2362c3a45bd9c/packages/core/src/config/provider.ts#L7) | environment/headers为string-map、model为动态键对象；MCP为local/remote tagged union，OAuth为false或对象 | 本次profile必须评估类型化字典和有限变体，不能只拿文件插件例子证明通用性；采用输入形状需求，保留Fibra的静态/候选/保存语义 |

## 6. 综合决定依据

推荐仍是扩展现有 Fibra 配置链：作者的静态 schema → config 模块的纯结构检查 → runtime 的绑定/业务规则。Pi/OpenCode 支持职责区分与单入口思想，却没有在上述路径提供可直接移植的“静态、精确 revision、跨 runtime、target CAS 前”完整合同。Fibra 特有部分是与既有求值/候选/保存/恢复/资源 owner 的组合，不是新造通用 JSON Schema 算法。

对于库遇到的问题，优先查固定源码、官方规范和 upstream issue；成熟库可复用，但 profile、数值、安全错误和工作预算必须由真实反例证明。禁止仅因参考项目存在同名 schema 就复制它的转换、默认或错误回显行为。

OpenCode的同Scope Promise/Effect适配（core/src/plugin/promise.ts:15–32）与先登记finalizer再reload（core/src/state.ts:89–121）支持原owner原则。但core/src/plugin.ts:54–65、91–94先删索引并ignore关闭错误，不可移植为Fibra失败资源保留机制。详见[独立来源复核](plugin-config-contract/fibra-config-reference-review.md)，两处行号和options形状小修已回填。

## 7. 本次独立调查和有界探针归档

- [完整配置链与错误投影](plugin-config-contract/fibra-config-chain-audit.md)
- [制品、builtin与消费者](plugin-config-contract/fibra-config-artifact-audit.md)
- [networknt资格补充](plugin-config-contract/fibra-config-schema-qualification.md)：旧证据1334项重核匹配；multipleOf反例失败探针原退出保留；独立受限profile探针10断言通过。不是正式发行或全profile通过。
- [探针源与原始回执](plugin-config-contract/probe/)，[本次归档摘要](plugin-config-contract/evidence-sha256.json)。历史探针原临时根仍在报告中；正式实现需以可重复测试替代临时环境依赖。
- 初版独审：[架构/行业场景](plugin-config-contract/review/architecture-r1.md)、[schema及有限分支探针](plugin-config-contract/review/schema-r1.md)、[生命周期](plugin-config-contract/review/lifecycle-r1.md)。初版未通过，修订后须按新摘要复审，不能沿用初版hash宣布关闭。
- 新有限字典/分支探针28断言、编译/执行nativeExit=0；anyOf宽8深4仍可产生4096个错误，oneOf快停也仍执行内部失败分支。记录的是局部行为及错误聚合风险，不是CPU/内存上界或正式制品资格。
- 修订及最终定点复审：[架构](plugin-config-contract/review/architecture-r2.md)、[schema](plugin-config-contract/review/schema-r2.md)、[生命周期](plugin-config-contract/review/lifecycle-r2.md)，最终均B0/I0/M0，绑定规格 `3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`。此处只证明设计审查；后续材料性批准已记录到推进计划，实施/发行另行验收。
- 行业形状探针另18断言通过，覆盖local/remote、false/object、number/disabled、oneOf零/多/唯一匹配及allOf封闭对象行为；原生编译/执行均0，源码和回执已归档。未运行正式Fibra实现/制品/消费者验收。
