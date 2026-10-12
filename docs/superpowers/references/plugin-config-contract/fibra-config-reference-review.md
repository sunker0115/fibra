# 配置合同参考证据独立复核

2026-10-11。审查对象：`docs/superpowers/references/2026-10-11-plugin-config-contract-evidence.md` 第 3–5 节。只读，未修改 Fibra/Pi/OpenCode/DSH，未运行外部项目测试。Nowledge 针对搜索 `Fibra OpenCode Pi 配置 schema scope 资源 owner` 返回 0 条；结论来自当前本机固定源码。

## 结论与问题

**B0 / I0 / M2。** 工具参数、产品配置与插件实例 options 的主要归属判断准确；没有发现可推翻推荐方向的重要问题。两项小问题应在证据定稿前修正。

### M1：两处引用范围未覆盖所述关键行为

- 证据文档第 62 行写 OpenCode `plugin/src/tool.ts:54–64`，链接 `#L54`，但固定文件总共 54 行。`tool` 的 Zod shape 泛型和 args 位于 **45–48** 行，`tool.schema = z` 位于 **52** 行，`z` import 在 **1** 行。建议改链接/标签为 `tool.ts:45–52`（必要时另列 `:1`）。事实本身正确，当前定位不能让读者直接核实。
- 第 36 行 DSH `fiber.ts:733–750` 的范围包含 inactive 延期与 `_resolveConfig`，真正 `return this.restart()` 在 **751** 行。建议改为 **736–752**，覆盖方法与完整分支。这同属引用精度问题，不是行为结论错误。

### M2：V2 options 形状应写清“对象值 unknown”，避免被读成任意 JSON 值

- 证据文档第 59 行“V2 package + 可选 unknown options”不够精确。
- `packages/core/src/config/plugin.ts:5–8` 是 `options: Schema.Record(Schema.String, Schema.Unknown).pipe(Schema.optional)`，即**可选字符串键对象，字段值 unknown**，不是可选任意 scalar/list/null。
- SDK `packages/plugin/src/v2/options.ts:1` 是 `Readonly<Record<string, any>>`，外部 loader `packages/core/src/config/plugin/external.ts:40,54,85` 传对象或 `{}`；V1 同样是 Record，不是全形状实例 schema。
- 建议第 59 行改成“V2 package + 可选开放 options 对象（字段值 unknown）”。不影响“尚非跨 runtime 静态必填/类型合同”的结论。

## 已核实且成立的主要内容

- DSH `fiber.ts:50–61`：Config 可选，执行 `~standard.validate`，thenable 拒绝，issues 抛 ValidationError，否则返回 value。`:736–752` inactive 延期，因为可能访问注入服务；ACTIVE 先 `_resolveConfig` 再 waterfall/restart。文档已明确此服务依赖配置 resolution 不等于 Fibra 纯求值，未混淆。
- Pi `extensions/loader.ts:559–589,613–631`：jiti import、取得 factory、执行 factory(api)，成功 commit、失败 discard。补读 `:244–267,521–546`：只暂存部分 runtime changes 和订阅，discard 收回已跟踪订阅。文档目前只取“注册失败归原 owner 收口”原则，并未声称它回滚插件任意外部副作用，因此成立；今后不得扩写为全事务回滚。
- Pi `extensions/types.ts:572–586` + loader `:289–300`：ToolDefinition.parameters 为 TypeBox，注册检查是 schema 描述本身为 JS object，不能据此说完整 schema 已在注册时验证；当前文档措辞未越界。
- Pi `ai/utils/validation.ts:317–349`：structuredClone、normalizeOptionalNulls、Value.Convert、无 TypeBox marker 的 JSON coercion 分支；错误包含原始 toolCall.arguments。文档仅用于否定直接复制这些转换/回显语义，成立。注意未验证 `Value.Convert` 忽略返回值后的每种运行时转换行为，不能从“调用”外推所有参数必然转换。
- Pi SettingsSchema `:206–446` 是产品字段/默认注解且 root additionalProperties=true；settings-manager `:13–26,375–379,395–453,596–625` 从 schema 文件仅 import type，JSON.parse 后迁移，withLock 内重读/局部字段合并。文档没有把 Schema 的存在冒称为读取执行校验，准确。
- OpenCode V1 `plugin/src/index.ts:68–81`、`core/src/v1/config/plugin.ts:5–8` 确为 string 或 [string,Record]；`opencode/src/plugin/index.ts:114–124,219–252` 顺序执行 server(input,options)，然后 config hooks(cfg)，失败日志后继续，非 Fibra 保存前原子结构门。
- OpenCode V2 `core/src/config/plugin/external.ts:15–29,73–89`：安装/解析 import 后 decode default export 的 id+effect/setup，Promise adapter 可用，options ?? {}，逐项 ignoreCause，外部发现工作 forkScoped。当前文档没有把模块 API 形状校验误说成 options schema 校验。
- OpenCode `opencode/src/config/parse.ts:8–29,35–60`：JSONC 失败包含完整 text，Effect decode 明确 onExcessProperty=ignore。当前文档把它定位产品 parse helper，未扩成所有产品节点都拒绝/忽略何种键的全局结论。
- `opencode/test/plugin/loader-shared.test.ts:812–849` 确实只证明测试意图为 tuple options 到 server；文档明确未运行，准确。

## OpenCode V2 Scope/生命周期的可借鉴机制与边界

固定 commit 为 `388406238bd5ca15564a762840a2362c3a45bd9c`。以下可补充到参考证据，但不应提升为 Fibra 文件关闭资格。

1. **同一 Scope 包含 Promise/Effect 两种适配入口。** `packages/core/src/plugin/promise.ts:15–32,90` 捕获当前 Scope/context，Promise 注册用 `Scope.provide(scope)`，返回 registration.dispose 只观察既有注册的 dispose。可直接采用的结构原则是：适配层不拥有第二份资源或关闭权。Fibra 对应 Engine attempt 观察 runtime/artifact owner 的 close，不能复制底层句柄。
2. **先登记 finalizer，再执行可能失败的应用。** `packages/core/src/state.ts:89–121` 在 uninterruptible 段构造 transform 与 dispose、登记 transform、Scope.addFinalizer，然后 reload。dispose 通过 semaphore 和 active flag 防重复。可借鉴“取得/登记不可留下取消窗口、再执行后续可失败工作”。Fibra 已有 inert owner→登记→read 的接缝，无需引入 Effect/另一 Scope 系统。
3. **该实现的失败终态不等于文件关闭失败保留。** 同 `state.ts:95–107` 在 materialize 前就 active=false 并移除 transform；若后续 materialize 失败，不会保留待重试的旧 registration。这里对象是可重建投影，不是必须保留的 stream/JarFile 关闭现场；不能照搬 active=false-before-close。
4. **插件 Scope 替换/移除明确忽略清理错误。** `packages/core/src/plugin.ts:54–65` 从 active 删除旧 scope，然后 `Scope.close(existing,...).pipe(Effect.ignore)`，再建新 child、提供其 scope 执行 effect、失败时 close child；`:91–94` remove 同样先删除、ignore close；`:128–132` shutdown 先 active.clear 再关父 scope。它支持父子归属和失败装载清理的原则，但不证明 Fibra 的“关闭失败保留 owner+依赖、fail-stop、禁止新代覆盖”要求。由于外层源码主动清掉索引并忽略错误，不应以此为文件 owner 的直接可移植实现。
5. **文件 API 是抽象接口，不是失败句柄的证据。** `packages/plugin/src/v2/effect/filesystem.ts:4–16` 返回 byte[]/列表等 Effect；`core/src/fs-util.ts:31–46,53–66` 使用 Effect FileSystem，readFileStringSafe 对缺失/权限错误返回 undefined。本轮没有研究其第三方平台底层内部 close，不从该抽象推断句柄保留或取消语义。尤其权限错误当 undefined 不是 Fibra 缺失合同的合适默认。

最小建议：只引用 1、2 的同 owner 适配和先登记 finalizer 思想；明确拒绝 3、4 的失败终态。固定 artifact 文件 reader 仍须本项目测试“read/close 各一次、观察取消不取消真实取得、关闭失败保留、兄弟清理继续、父依赖保留”，不要由 OpenCode Scope 名称推导已满足。

## 固定版本与读取清单

实时核对三个 HEAD，与证据文档一致；Pi/OpenCode origin 一致，三者均仅未跟踪 `.codegraph/`。

- DSH `c291e7961a515f6d7af9304e7fd1d257929aef26`：`vendor/cordis/src/fiber.ts`。
- Pi `f1b2e77f5b13b2a199b1052cb79c235451afe7d7`：`packages/coding-agent/src/core/extensions/{loader,types}.ts`、`packages/ai/src/utils/validation.ts`、`packages/coding-agent/src/core/{settings-schema,settings-manager}.ts`。
- OpenCode `388406238bd5ca15564a762840a2362c3a45bd9c`：`packages/plugin/src/{index,tool}.ts`、`packages/plugin/src/v2/options.ts`、`packages/plugin/src/v2/effect/filesystem.ts`、`packages/core/src/v1/config/plugin.ts`、`packages/core/src/config/plugin.ts`、`packages/core/src/config/plugin/external.ts`、`packages/opencode/src/plugin/index.ts`、`packages/opencode/src/config/parse.ts`、`packages/opencode/test/plugin/loader-shared.test.ts`、`packages/core/src/plugin.ts`、`packages/core/src/plugin/{promise,internal}.ts`、`packages/core/src/state.ts`、`packages/core/src/fs-util.ts`。
