# 正式作者配置 inventory（只读，2026-10-12）

仓库：`/Users/sunke/dev/ai-project/fibra`。只读当前源码，未修改仓库、未跑 Maven、未生成生产 schema。下列路径均相对此仓库；使用 `M` 的证据引用应与包表中的模块路径拼接。当前规范为 `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md` §4–5。旧 Nowledge 搜索结果属于早期部署计划，未作为当前实现证据。

## 1. 总结与必须显式处理的行为差异

- distribution 的 12 个 formal 包：4 个 API 包的 Java descriptor 为 `{}`，其 `main` 必须是零 definition 的空映射；8 个执行 definition，名称不能从 artifactId 推导。
- `tool-fs` 整项 null 合法，会在 validator 中生成默认对象；其所有字段 null 也合法。搜索插件的 `limits`/`timing` 可缺失或 null，各内部字段同样可缺失或 null。其余有配置的 definition 均没有整项 null 的有效运行语义。
- 所有 Java 动态插件共用 `JavaClassSpace.java:57–63` 的默认 `new YAMLMapper()`；`pom.xml:96` 固定 Jackson 3.1.5。该版本 `FAIL_ON_UNKNOWN_PROPERTIES` 默认 **false**，因此当前 record 配置忽略 unknown，不能写成“原来就拒绝 unknown”。新 `additionalProperties:false` 是已批准结构门的收紧，应该写入验证与兼容说明。
- Jackson 也有类型转换。该版本 `ACCEPT_FLOAT_AS_INT=true`；新整数/string 结构规则不应复制绑定层宽松转换，也不能声称是既有完全等价校验。
- `Void` definition 真实业务无配置；当前 Jackson `Void` deserializer 会吞掉任意非 null 输入并返回 null。新 `type:null` 是显式作者合同，拒绝此前被静默丢弃的 `{}`/任意值。不能为了复制 Jackson 意外宽容写全开放 schema。
- fs-local/storage-json 未设 `.validator`，`PluginDefinition.validate` 对 null 原样返回，start 后才解引用失败。不能把“prepare 未拒绝 null”解释成 nullable 配置。
- Node content-sanitizer 当前允许不少非规范形状（见 §4）；尤其整项 null **不合法**，`configure(config={})` 的默认参数只接住 undefined，接不住 null。正常 Engine 发送显式 null，不会把它变成 `{}`。
- 真实声明字段结构全部可由 profile 表达。非 blank、路径有效性/权限、可执行性及跨 provider 的延迟限制不是静态结构保证，留在业务边界。主线程已确认：严格字段门的主动收紧已获批准，无需重复授权；此 inventory 标明旧行为供迁移说明，不能写成新旧等价。

## 2. 12 包名称与实际装配

所有包的 facet 名称来自 `fibra-distribution/src/main/distribution/plugins/<包>/fibra-package.yaml:5`，均为 `main`；`:7` 为 java，`:9` payload 为 `lib/main.jar`。

每个 `M/src/main/resources/META-INF/fibra/plugin.yaml` 是 definition 名称静态真源。非空包该文件 `:1` 为真实 entrypoint，`:2` 为下表 definition。API 包文件 `:1` 仅 `{}`，无 entrypoint/definition。

| 包 | 模块 M | main definition | 真实 entrypoint / 名称行 | distribution copy 行 |
|---|---|---|---|---|
| fibra-fs | fibra-plugins/fibra-plugins-fs/fibra-fs | 无 | descriptor :1 `{}` | pom.xml:125 |
| fibra-fs-local | fibra-plugins/fibra-plugins-fs/fibra-fs-local | fs-local | `M/src/main/java/com/sstlfsj/fibra/plugins/fs/local/LocalFileSystemEntrypoint.java:13` | pom.xml:126 |
| fibra-tool-fs | fibra-plugins/fibra-plugins-fs/fibra-tool-fs | tool-fs | `M/src/main/java/com/sstlfsj/fibra/plugins/fs/tool/FileToolEntrypoint.java:21` | pom.xml:127 |
| fibra-tool-fs-search | fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search | tool-fs-search | `M/src/main/java/com/sstlfsj/fibra/plugins/fs/search/ToolFsSearchEntrypoint.java:22` | pom.xml:128 |
| fibra-subprocess | fibra-plugins/fibra-plugins-subprocess/fibra-subprocess | 无 | descriptor :1 `{}` | pom.xml:129 |
| fibra-subprocess-local | fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local | fibra-subprocess-local | `M/src/main/java/com/sstlfsj/fibra/plugins/subprocess/local/SubprocessLocalEntrypoint.java:13` | pom.xml:130 |
| fibra-shell | fibra-plugins/fibra-plugins-shell/fibra-shell | 无 | descriptor :1 `{}` | pom.xml:131 |
| fibra-shell-local | fibra-plugins/fibra-plugins-shell/fibra-shell-local | fibra-shell-local | `M/src/main/java/com/sstlfsj/fibra/plugins/shell/local/ShellLocalEntrypoint.java:14` | pom.xml:132 |
| fibra-tool-shell | fibra-plugins/fibra-plugins-shell/fibra-tool-shell | fibra-tool-shell | `M/src/main/java/com/sstlfsj/fibra/plugins/shell/tool/ShellToolEntrypoint.java:16` | pom.xml:133 |
| fibra-storage | fibra-plugins/fibra-plugins-storage/fibra-storage | 无 | descriptor :1 `{}` | pom.xml:134 |
| fibra-storage-json | fibra-plugins/fibra-plugins-storage/fibra-storage-json | storage-json | `M/src/main/java/com/sstlfsj/fibra/plugins/storage/json/JsonStorageEntrypoint.java:13` | pom.xml:135 |
| fibra-tool-storage | fibra-plugins/fibra-plugins-storage/fibra-tool-storage | tool-storage | `M/src/main/java/com/sstlfsj/fibra/plugins/storage/tool/StorageToolEntrypoint.java:42` | pom.xml:136 |

上述 copy 行统一指 `fibra-distribution/pom.xml`。包清单还有该文件 :31–42 的 reactor dependencies。

### source → JAR → package 根落实点

当前：`M/src/main/resources/META-INF/fibra/plugin.yaml` → 插件 JAR → distribution 按真实 dependency artifact 拷贝到 `<stage>/plugins/<包>/lib/main.jar`（`fibra-distribution/pom.xml:120–139`）。根 manifest 由 `src/main/distribution` 在 prepare-package 复制、过滤版本（同文件 :70–100）；最终 ZIP 包含整个 stage（`fibra-distribution/src/assembly/bin.xml:9–18`）。现有链上没有 config 资源复制/提取。

新增实现落点应是每包唯一 `M/src/plugin/fibra-config.json`，非过滤字节复制/重命名至该模块 build output 的 `META-INF/fibra/config.json` 后进入 JAR；distribution **从上述正式 dependency JAR 提取** 到 `<stage>/plugins/<包>/fibra-config.json`，不能再手写一份 distribution schema，也不能从邻模块 target/classes 拼装。构建资格应逐包比较 source/JAR entry/根文件三者原始字节并用实际静态 reader 比较完整名称集合。

模块 parent 并不是 `fibra-plugins/pom.xml`（该文件 :13 明示只是 aggregator）。若共用 resources execution，需选真实 root parent 或每正式包显式启用，不能在 aggregator 加 execution 后声称继承生效；不能把新资源执行散播到所有 unrelated 模块。

三种 shade 包仍须保留作者同源资源：

- fs-local：`M/pom.xml:62–94`，package shade，artifactSet 仅 JNA，过滤器作用于 JNA；作者资源不会匹配这些 excludes。
- subprocess-local：`M/pom.xml:58–90`，package shade，JNA 同类路径。
- tool-fs-search：`M/pom.xml:67–119`，shade Jackson 私有包并 relocation，资源 transformer 为 services/notice；不要让 config 被过滤/合并/从依赖覆写，构建后以 JAR 字节检查证明。
- 其余九个正式包无本地 shade execution，常规 JAR 资源链。

archetype 配套位置：`fibra-plugin-archetype/src/main/resources/archetype-resources/pom.xml:27–50` 目前只有 compiler/assembly；`.../src/assembly/plugin.xml:8–18` 目前只复制 manifest 与 `lib/plugin.jar`。未来 schema source 与 JAR entry、包根也必须同源，不能只让发行仓内 12 包通过。archetype resource metadata 要包含新增源文件。

## 3. 八个执行 definition 的字段规则

以下建议都不写 schema default；runtime 现有默认值只在本表说明，权威仍在 Config 构造器/validator。记录类型 integer 上界是对应 Java 字段可绑定上界；nullable 的 minimum/maximum 仅作用数字。建议封闭 object 的 unknown 收紧见 §1。

### fs-local

源码目录：`fibra-plugins/fibra-plugins-fs/fibra-fs-local/src/main/java/com/sstlfsj/fibra/plugins/fs/local/`。

- `LocalFileSystemConfig.java:7–13,20–23`：object，`root`、`spillDirectory` 均必填非 null string；无默认；constructor 拒绝 blank。静态可用 string/minLength:1/required，不能用 minLength 冒充非 blank。
- spillDirectory 必须不是 absolute 且不含 `..`（:11–12），属路径业务语义；profile 禁 regex，不要模拟为 minLength。
- `LocalFileSystemEntrypoint.java:13–23` 无 validator；null 会绕过 prepare，start :15–18 解引用。因此 schema 根必须 object，不是 nullable。
- `LocalFileSystem.java:75–88` 将 root 绝对化/normalize，检查目录存在并取 real path；后续文件访问还有 confinement、符号链接及发布语义。schema 不承诺资源可用。

### tool-fs

源码目录：`fibra-plugins/fibra-plugins-fs/fibra-tool-fs/src/main/java/com/sstlfsj/fibra/plugins/fs/tool/`。

- `FileToolEntrypoint.java:40` 对整项 null 使用 `new FileToolConfig()`，故根 object|null，object 无 required。
- `FileToolConfig.java:4–9,15–33`：`readLimit`、`readMaxLineLength` 为 Integer，可缺/null，非 null >=1、<=2147483647；默认均 2000。
- 同文件 `readMaxBytes`、`readSourceMaxBytes` 为 Long，可缺/null，非 null >=1、<=9223372036854775807；默认分别 51200、10485760。
- 所有字段都应 integer|null，不应只 optional integer；默认值不进入 schema。
- Tool input/output schema（`FileToolEntrypoint.java:46–104`）描述 read/write/edit 调用参数，不是实例 config，不可复制充当本合同。

### tool-fs-search

源码目录：`fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search/src/main/java/com/sstlfsj/fibra/plugins/fs/search/`。

- `ToolFsSearchEntrypoint.java:37–42` 明确拒绝整项 null；根 object。
- `SearchPluginConfig.java:3–13`：必填 string `rgExecutable`、`workdir`，null/blank 拒绝，无默认；静态 minLength:1。
- 可选 `limits`、`timing` 均 object|null，null/缺省分别调用 `SearchLimits.defaults()`、`SearchTiming.defaults()`（:12–13），不是必填 nested object。
- `SearchLimits.java:3–22,34–39`：5 个字段均 Integer|null、可缺、正数 1..2147483647：`globMaxResults` 默认100、`grepMaxMatches` 250、`grepMaxLineBytes` 2000、`rawOutputMaxBytes` 20000000、`stderrMaxBytes` 65536。字段 default 权威常量 :6–10；字段名与应用位置 :3–5,13–22。
- `SearchTiming.java:3–10,17–31`：`timeoutMillis`、`graceMillis` 均 Long|null、可缺，非 null 1..2147483647。默认30000/3000（:5–6）。不得使用 Long.MAX_VALUE，业务已经限制有限计时器上界。
- nested object 都应显式 properties 和 additionalProperties；无需 required。全结构 profile 可表达。
- `ToolFsSearchEntrypoint.java:48–55` 的 regular/executable/directory 检查在 start 保留，字符串校验不保证这些。

### fibra-subprocess-local

源码目录：`fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local/src/main/java/com/sstlfsj/fibra/plugins/subprocess/local/`。

- `SubprocessLocalEntrypoint.java:17` 明确 null 拒绝；根 object。
- `SubprocessLocalConfig.java:4–8` 必填 string `nodeExecutable`、无默认、非 null/非 blank；静态 minLength:1。
- `LocalSubprocess.java:36–44` 再验证非 blank；实际 spawn/平台隔离/外部 node 程序能力属于运行时资源边界，不能由 schema 宣称满足。

### fibra-shell-local

源码目录：`fibra-plugins/fibra-plugins-shell/fibra-shell-local/src/main/java/com/sstlfsj/fibra/plugins/shell/local/`。

- `ShellLocalEntrypoint.java:18` 明确 null 拒绝；根 object。
- `ShellLocalConfig.java:3–6`：`bashExecutable` 必填 string 非 null/非 blank，`outputMaxBytes` 必填 int >0，`graceMillis` 必填 long >0；无默认。静态三项 required，字符串 minLength:1；数字区间分别 1..2147483647、1..9223372036854775807，不 nullable。
- 原配置 constructor 对 `graceMillis` 只有正数条件；`LocalShell.java:52–54` 构成 subprocess spec，真正 `LocalSubprocess.java:52–58` 在 spawn 又拒绝 grace 超2147483647。是否把此 provider组合运行限制提升为 shell作者字段上界需主线程显式判断；不能假称现 Config 已设此上界。首选结构只反映 Config 正数及 Long 范围，保留运行边界；若想提前拒绝超时器限制则需准确记录这是作者规则补全。

### fibra-tool-shell

`fibra-plugins/fibra-plugins-shell/fibra-tool-shell/src/main/java/com/sstlfsj/fibra/plugins/shell/tool/ShellToolEntrypoint.java:13–20`：`PluginDefinition<Void>`，无配置，无默认或字段。按批准合同 schema `type:null`。任意 object 都不是有意义实例配置。该文件 :23以后 command/workdir/timeoutMs 属于 tool request，不是 plugin config。

### storage-json

源码目录：`fibra-plugins/fibra-plugins-storage/fibra-storage-json/src/main/java/com/sstlfsj/fibra/plugins/storage/json/`。

- `JsonStorageConfig.java:6–17`：根 object，必填非 null/非 blank string `root`，无默认；静态 minLength:1。
- Path.of(root) 必须合法、absolute，属业务校验；不可用静态类型声称 absolute 或可写。
- `JsonStorageEntrypoint.java:13–22` 无 null validator；start :15 解引用 null 失败，不能根 nullable。
- start 创建 store/登记 effect（:15–18）；`JsonConfigStore.java:55–61` 初始读取/启动 writer，`:207` 起读取旧文档，`:292,316` 持久创建目录。内容/权限/资源关闭继续由 runtime owner 负责。

### tool-storage

`fibra-plugins/fibra-plugins-storage/fibra-tool-storage/src/main/java/com/sstlfsj/fibra/plugins/storage/tool/StorageToolEntrypoint.java:35–45`：`PluginDefinition<Void>`，无实例字段，根 `type:null`。`:48–78` 与 :103 起描述存储工具参数 key/value，**不是实例 config**。不能因为 ConfigStore 处理任意 LiteralValue，就给此 definition 开放 object 配置。

## 4. Node content-sanitizer

- 包根 `fibra-example/content-sanitizer/node-plugin/fibra-package.yaml:2,5,7,9`：plugin `content-sanitizer`，facet `main`，runtime node，payload `payload`。
- 真名称 `payload/fibra-plugin.yaml:2` definition `content-sanitizer`；`:3` entrypoint `index.mjs`。
- `payload/index.mjs:3–7` 规则值为 email / bearer-token / api-key。
- `:22–32` config 读取：replacement 若是 string 原样接受（包括空字符串）；否则使用 `[REDACTED]`。rules 若是 array，逐项必须被 supportedRules Map 接受，否则报错；非 array 使用所有三规则。空 array 合法、重复项合法且通过 Set 去重。无 required 字段，unknown 字段忽略。
- 适合保留明确 null 默认语义的作者规范形状是 object，replacement 可选 string|null，rules 可选 array|null（items 为 string enum三值）；代码 :23–26 确实把显式 null 解析成 runtime 默认，因此无需把 null 当错型收紧。`minItems` 不得设1，禁止 `uniqueItems` 且当前也不要求唯一。replacement 不应 minLength:1。不写 default。该结构本身 profile 足够表达。
- **材料差异**：旧实现 replacement=null/number/object 等都 fallback；rules=null/string/object 等也 fallback；完整 config 的 array/string/number/boolean（非 null primitive 有属性访问装箱行为）会走默认。完整 null 则访问 `.replacement` 直接 TypeError；undefined 才命中参数默认 `{}`。因此不能机械指定 nullable root。主线程确认已批准严格字段门；建议保留字段缺省/显式null的默认语义，但拒绝其它错误类型、非object根和未知字段，并明确这不是完全保留旧行为。
- `fibra-runtime-node/src/main/java/com/sstlfsj/fibra/runtime/node/NodeRuntimeDriver.java:399–400` 将 `prepared.config.toJava()` 作为 config 发送，null 不自动转换成 `{}`。示例宿主真实 object 输入在 `fibra-example/content-sanitizer/spring-host/src/main/java/com/sstlfsj/fibra/example/SanitizerPluginManager.java:60–61`。
- 同源文件可直接置于 `fibra-example/content-sanitizer/node-plugin/fibra-config.json`；这里已经是完整源 package，没有 JAR 阶段。现 `spring-host/pom.xml:61` 测试直接指向该 source package；正式外部 consumer 必须复制完整包再读取根合同，不能只测试源文件解析。
- `index.mjs:57` JSON.parse 使用 JS Number，现 config 无数值字段；9007199254740993 的 Node wire资格需要独立真实消费者，不能给 sanitizer 臆造数字字段以通过门禁。

## 5. 绑定层证据与建议验证

整项 config 的缺省/显式null/空object汇总（缺省经 Engine 得到 LiteralValue null，不能当成 `{}`）：

| definition | 缺省 / 显式 null | `{}` | 合法字段内显式 null |
|---|---|---|---|
| fs-local | prepare 放过但 start 失败；静态拒绝 | 构造器因 root 缺失失败 | root/spillDirectory 都拒绝 |
| tool-fs | validator 返回默认配置；静态允许 | 全字段默认，合法 | 四字段都默认，静态允许 |
| tool-fs-search | validator 拒绝 | 必填路径缺失，失败 | 两路径拒绝；limits/timing及其各字段默认 |
| fibra-subprocess-local | validator 拒绝 | nodeExecutable 缺失，失败 | nodeExecutable 拒绝 |
| fibra-shell-local | validator 拒绝 | 必填字段缺失，失败 | 三字段全部拒绝 |
| fibra-tool-shell | 合法无配置 | 旧Jackson丢弃后null；新结构门拒绝 | 无字段 |
| storage-json | prepare 放过但 start 失败；静态拒绝 | root 缺失，失败 | root 拒绝 |
| tool-storage | 合法无配置 | 旧Jackson丢弃后null；新结构门拒绝 | 无字段 |
| content-sanitizer | Engine发送null，Node属性访问失败；静态拒绝 | 两字段runtime默认，合法 | replacement/rules 都default fallback，建议静态允许null |

四个 API 包不适用 config 矩阵，因为它们不存在 definition，更不存在空配置定义。

仓内：`fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaDefinitionEntry.java:24–26` LiteralValue.toJava → binder → definition.prepare；`JavaClassSpace.java:57–63` 默认 YAMLMapper；`fibra-api/src/main/java/com/sstlfsj/fibra/PluginDefinition.java:59–68` null 不做类型检查，没有 validator 时原样返回。

本机已缓存精确 dependency 源：`/Users/sunke/.m2/repository/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5-sources.jar`：

- `tools/jackson/databind/DeserializationFeature.java:148–150` unknown 默认忽略，`:159–161` primitive null 默认失败，`:460–463` float→int 默认允许。
- `tools/jackson/databind/deser/jdk/JDKMiscDeserializers.java:61–62` Void 使用 NullifyingDeserializer；`tools/jackson/databind/deser/std/NullifyingDeserializer.java:29–45` 跳过全部值并返回 null。

主线程实现验收至少应覆盖：四API空集合；八definition精确名称；Void显式null与object拒绝；tool-fs整项null及每个字段null/省略/0；搜索嵌套null及内部null默认，计时器边界；fs/storage整项null提前拒绝；所有正式record unknown提前拒绝；Node空rules/重复rules/空replacement、完整null、未知rule，以及对错型fallback新旧差异的明确决定。最终仍需 source/JAR/root 原始字节一致、禁运行entrypoint的名称/合同资格与正式JAR仓外真实consumer验证。本 inventory 只完成源码核对，未宣称这些运行测试已经通过。
