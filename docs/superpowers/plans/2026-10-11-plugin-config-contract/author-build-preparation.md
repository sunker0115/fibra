# Task 5 正式作者构建准备（只读，2026-10-12）

基于 `/Users/sunke/dev/ai-project/fibra` 当前 `codex/plugin-config-contract` 工作树。未修改仓库、未运行 Maven、未改 Maven cache、未 commit/push。主线程正在修改 Engine，以下 API 是本次读取时的现状，合并 Task 5 前需复核签名。用户“不用兼容代码”是直接约束。

检查依据：用户级 AGENTS 指令；仓库及祖先路径未发现额外 AGENTS.md；已读 spec §4–5、Task 5、author-schema-inventory。Nowledge MCP thread_search 查询 `fibra plugin-config-contract Task5 author-schema single source archetype` 返回空；本报告不以历史记忆推断合同。所有插件能力来自本机精确版本官方发布 JAR 的 `META-INF/maven/plugin.xml`、POM 和 `javap`，无网络查询。

## 1. 结论与最小路线

**不需要先引入新构建插件。** 现有 Assembly 3.8.0 支持单文件 `destName`、`filtered=false`、`dir` 输出和 `inlineDescriptors`，可以把唯一 `src/plugin/fibra-config.json` 复制为 `target/classes/META-INF/fibra/config.json`。现有 distribution Exec 3.6.3 可在正式 `main.jar` 复制后调用一个固定资源提取脚本。模板继续使用已经依赖的 Assembly；package 根可以从同一源复制，与最终 JAR 强制字节比较。规范特意要求 distribution 从正式 JAR 提取；不要求模板再引入 JAR 解包插件。

这是一条已核能力、尚未运行 Maven 证明的建议路线。要先用隔离构建验证以下细节：Assembly 的输出目录合并、强制覆盖、执行顺序、Velocity 和三种 shade 输出。不能把本报告当成 Task 5 验收通过。

## 2. 已核 POM 链和 execution order

- 12 个正式插件直接继承根 `pom.xml`（三级目录模块 `<relativePath>../../../pom.xml</relativePath>`）。`fibra-plugins/pom.xml:13` 明示只是 aggregator、不做 parent；族聚合 POM 同样不是插件 parent。不能把 execution 放 aggregator 后宣称生效。
- 根 `pom.xml` 管理 Assembly 3.8.0、Resources 3.5.0、Dependency 3.11.0、Exec 3.6.3、Archetype 3.4.1、Shade 3.6.2；全局 Enforcer requirePluginVersions。
- 根 `process-resources` 已有 flatten 和 LICENSE/NOTICE 复制。新增合同派生应在 `process-resources`，随后默认 compile/test/package。JAR 打包取 build output，三个 shade 插件在 package 处理最终主 JAR。
- 三个 shade 模块是 `fibra-plugins/fibra-plugins-fs/fibra-fs-local/pom.xml:62`、`.../fibra-tool-fs-search/pom.xml:67`、`fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local/pom.xml:58`。前两种 JNA shade 的 filters 仅针对 JNA；search shade 含 Jackson relocation、ServicesResourceTransformer、ApacheNoticeResourceTransformer。现有规则不主动转换 config，但必须检查最终主 JAR，不能只检查 target/classes 或 original-*.jar。
- `fibra-distribution/pom.xml` 当前 prepare-package 顺序：`reset-distribution-staging` 清空 stage → `stage-distribution-layout` 复制资源/过滤 manifest 版本 → Dependency `stage-host-runtime` → `stage-formal-plugin-packages`（12 个精确 artifactItem，统一 destFileName=main.jar）→ Exec `stage-target-platform-runtime`。package 执行 `assemble-binary-distribution`，verify 执行脚本。
- 新增提取应放现有 Exec 块、prepare-package，排在 Dependency 复制之后，建议在 stage-target-platform-runtime 前；不能放在 resources 阶段或从相邻模块 target/classes 取资源。
- distribution 的 stage 每次 prepare-package 都清空，因此若使用 Dependency unpack，必须显式处理旧 marker 阻止重新解包的情况。直接从 stage 中的精确 main.jar 读取没有此 marker 窗口。

12 个包/模块和精确 definition 名称以现有 `author-schema-inventory.md §2` 为准。四个 API 包 `fibra-fs/fibra-subprocess/fibra-shell/fibra-storage` 的 descriptor 为 `{}`，合同必须含 `facets.main={}`，不能写 type:null 假 definition。

## 3. 本地插件能力证据与建议配置

读取位置为 `/Users/sunke/.m2/repository/org/apache/maven/plugins/<artifact>/<version>/<artifact>-<version>.jar`。

| 能力 | 已证实 | 判断 |
|---|---|---|
| Resources 3.5.0 copy-resources | 参数含 resources/outputDirectory/fileNameFiltering；无 destName 或 filename mapper | 不能直接将固定名称 fibra-config.json 改名 config.json；fileNameFiltering 是插值，不是任意 rename。不要臆造配置 |
| Assembly 3.8.0 single | inlineDescriptors、finalName、outputDirectory、appendAssemblyId、attach、ignoreDirFormatExtensions 均存在；FileItemAssemblyPhase 明确读取 getDestName/isFiltered | 已有插件足够完成单文件非过滤复制重命名 |
| Assembly 的 Plexus Archiver 4.10.4 | DirectoryArchiver.execute 逐项复制，未删除既有输出目录；copyFile 默认按源/目标 mtime 判 up-to-date，setForced 可跳过这个判断 | 可以写入现有 classes 目录，但建议 archiverConfig forced=true，防止同时间戳修改被跳过；需构建实证 |
| Dependency 3.11.0 unpack | fileMappers 存在，artifactItems 解析正式 Maven artifact，markersDirectory 默认 target/dependency-maven-plugin-markers | 是可行替代，但不直接读已 staged main.jar，且再次列 12 个 artifactItems；若采用须比较它解析的 JAR 与 main.jar 原始字节并解决 marker |
| Antrun 3.2.0 | 本机 cache 有，不在项目版本管理或正式构建链 | 不是首选；cache 存在不等于已批准新 build 依赖 |

建议的单模块 execution 形状（待 Maven 实证，不是已编辑代码）：

```xml
<execution>
  <id>stage-plugin-configuration</id>
  <phase>process-resources</phase>
  <goals><goal>single</goal></goals>
  <configuration>
    <attach>false</attach>
    <appendAssemblyId>false</appendAssemblyId>
    <outputDirectory>${project.build.directory}</outputDirectory>
    <finalName>classes</finalName>
    <archiverConfig><forced>true</forced></archiverConfig>
    <inlineDescriptors><inlineDescriptor>
      <id>plugin-configuration</id>
      <formats><format>dir</format></formats>
      <includeBaseDirectory>false</includeBaseDirectory>
      <files><file>
        <source>${project.basedir}/src/plugin/fibra-config.json</source>
        <outputDirectory>META-INF/fibra</outputDirectory>
        <destName>config.json</destName>
        <filtered>false</filtered>
      </file></files>
    </inlineDescriptor></inlineDescriptors>
  </configuration>
</execution>
```

这里 `classes` 依赖当前全部目标模块的默认输出目录；若主线程需要支持任意 build.outputDirectory，需在隔离 probe 验证 `outputDirectory=${project.build.outputDirectory}, finalName=.`，不要直接假定其 Maven/interpolator 行为。配置放 execution 内，不能让 finalName/classes/attach=false 污染模板 package-plugin 或 distribution ZIP execution。

去重方式由主线程决定：最直接是在 12 个叶 POM 显式启用同一窄执行；若要收敛复制协议，选根目录单一 assembly descriptor，并由每个叶模块显式引用/启用。不要改 parent 关系、不要建新 Maven 模块/通用装配 DSL、不要向根 build/plugins 放无条件 execution。根 pluginManagement 已有 Assembly，若新增有 phase/goals 的 execution，所有已使用 Assembly 的 unrelated 模块都会继承，尤其 distribution/archetype，须避免。

distribution 提取脚本只接收 stage 根：逐固定正式包读取 `plugins/<id>/lib/main.jar`，恰好一个普通 `META-INF/fibra/config.json` entry，缺失/重复/目录均失败，将原始 bytes 写到 `plugins/<id>/fibra-config.json`，不 parse-reserialize、不过滤、不从其它位置 fallback。可用现有 shell/JDK 工具组合；若用 Python zipfile，需在 Maven 构建层明确新增 Python 运行要求（全仓 verify-distribution 已要求 Python，但当前 distribution prepare-package 本身未要求）。更少新增运行要求的是现有 shell + `jar tf` 精确计数和固定 entry 解包/`cmp`；具体命令仍需对空格路径和临时目录做验证。

若 Assembly probe 失败，Antrun 可用 copy tofile（overwrite=true/filtering=false）解决作者 rename；它需要新根 property/pluginManagement、12 叶 activation、模板独立固定版本和冷 cache/release 验证，会扩大构建依赖面但不引入运行时 JAR。现有 Assembly 能力尚无失败证据，暂不建议引入。

## 4. archetype 与 Node 的精确落点

archetype 修改路径（均在 `fibra-plugin-archetype/src/main/resources/`）：

- `archetype-resources/src/plugin/fibra-config.json`：生成项目唯一作者源。模板现有 definition 是 `${pluginId}`，`PluginConfig` 只有 message，整项 null 在 entrypoint.validator 生成默认对象；schema 应允许 null 或带必填 message 的封闭 object，message string/minLength=1，不声明 default。空对象不是有效 message 配置。
- `archetype-resources/pom.xml`：现有 compiler/assembly；给 Assembly 增加独立 process-resources execution，保留 package execution 配置。
- `archetype-resources/src/assembly/plugin.xml`：增加同源 JSON 到根 `fibra-config.json`，filtered=false；保留 manifest 和 `lib/plugin.jar`。
- `META-INF/maven/archetype-metadata.xml`：源 JSON 含 `${pluginId}`，不能整文件 filtered=false 后遗留占位符。需明确 Velocity 字面量写法保护 `$schema`、`$defs`、`$ref` 以及字符串中的 `#/$defs/...`（例如已核版本支持的 literal block/转义方式），并用真实生成测试证明；本次未执行 Velocity，具体转义尚待 probe。
- `archetype-resources/README.md`：作者 source/JAR/root 链、结构与业务 owner、发布 QA 的可执行步骤。

archetype 验证现状：`src/test/resources/projects/basic/goal.txt` 为 `verify`；`verify.groovy` 目前精确断言安装根仅 manifest/lib，需更新为包含 fibra-config.json，并比较生成源/JAR entry/根三份 bytes，确认 `$schema/$ref` 原文保留、不含模板 escape；`src/test/java/.../FibraPluginArchetypeIT.java` 目前只通过 JavaRuntime 实际启动，需补静态发布资格、missing/orphan/空facet/坏schema、关闭失败测试。POM pre-integration-test 编译 Java IT、install-file 准备 API；Archetype 打包生命周期生成 fixture；Failsafe integration-test/verify。测试应跑 `-pl fibra-plugin-archetype -am verify`，不要跳 archetype IT。

不能要求“原始 archetype 模板 bytes == 生成源”——`${pluginId}` 必须展开；应核生成源中 JSON Schema 关键字与引用原文，再比较生成源/JAR/root bytes。可在生成资格 fixture 中含合法 `$ref` 或加带 `$ref` 的生成输入反例，防止只测 `$schema`。

Node：`fibra-example/content-sanitizer/node-plugin` 本来就是完整源 package，直接新增根 `fibra-config.json` 是单源，不加 Maven/JAR/fallback。copy 整包后再由正式 provider/store 资格；更新 `fibra-example/content-sanitizer/README.md` 和 spring-host IT。当前 `spring-host/pom.xml:61` 只把源包路径传入 IT。schema 精确字段边界见 inventory §4：根 object，不 nullable；replacement string|null；rules array|null，items enum 三规则，允许空/重复，unknown 拒绝。真实 Node spawn/默认语义继续用原 IT 验证，不能用静态资格替代运行证明。

## 5. 正式产物发布资格：可落地的 API 路径

这是普通 QA/示例 helper，不新增生产 Registry 或资格持久状态。可放现有 acceptance 测试、模板 IT、仓外 `engine-application`；核心 config 只提供纯校验，不知道安装路径。

1. 从最终主 JAR 构造完整测试包：manifest 仍由测试装配 owner 提供；从该 JAR 读取 config entry 原 bytes 到根。仓内源字节比对只在仓内 acceptance 做，仓外 consumer 不读取源码树。
2. 构造 `PluginPackageStore`、`DeploymentTargetStore`，成功构造 Engine 后由 Engine 接管；实际注册 JavaRuntimeProvider，Node 使用真实 NodeRuntimeProvider。安装完整包，用全量 disabled selection + 空 desired graph 提交。获取提交后的实际 targetRevision，不能硬编码 0/1。
3. 调用一次 `engine.describeDefinitions(pluginId, expectedTargetRevision)` 返回整包全部 facets（真实 API 是按 package 查询，不是逐 facet overload）。检查返回 pluginId/packageRevision 与精确 installed selection 相同，逐 facet 取 `facetId().value()` / `definitionIds()`；不靠 artifactId 推导名称，不执行 entrypoint。
4. 用 `store.find(pluginId, packageRevision).orElseThrow()` 获得 verified record；public Builder 创建的纯 metadata record 不能调用 configurationResource。外层 owner 持有 `PluginConfigurationResource`，调用 read，真实 close 成功后才能释放 handle 引用。之后 `DefinitionConfigDocument.parse(bytes)`。
5. 先 `document.facets().keySet()` 与 catalog 的完整 facet 集合精确相等；每个 facet 下 schema definition keySet 与真 definition 集合精确相等。任意缺 facet、missing definition、orphan definition、额外空 facet 都失败；零 definition facet 必须显式保留空映射。
6. 按稳定 facet/definition 顺序展平完整包全部 ConfigSchemaSource，调用 `ConfigSchema.checkSources(allSources)` 并要求 Optional.empty。对零 definition 包仍调用空集合；对禁用的坏 schema 也必须失败，不能只调用 describeConfiguration 某一个 name 当整包资格。
7. 资格成功后正常关闭 Engine，断言 store 生命周期沿 owner 关闭。另跑原执行 IT 验证配置的成功/最近非法/default/null/unknown 行为。

新增断言至少包括：source == final JAR entry == stage root == ZIP root；JAR exact entry 唯一；四 API 空集合、八执行 definition 精确集合；反例 missing schema、orphan schema、缺/多空facet、inactive 坏 schema；新源 bytes 在不 clean 且相同 mtime 场景重新构建后不遗留旧合同；三种 shade 都取最终产物；模板与 Node 同门。不要写文本 grep 就宣称 ConfigSchema.checkSources 真正执行。

现有复用点：

- `fibra-plugins/fibra-plugins-acceptance/fibra-plugins-acceptance-host/src/test/java/com/sstlfsj/fibra/plugins/acceptance/PluginAcceptanceHarness.java:104` 的 installRequest 当前只 copy JAR + 写 manifest，需从同一个 source JAR 提取合同；`FormalPluginArtifactsIT.java` 已枚举全 12 包，可增加静态资格。
- `fibra-distribution/src/test/consumers/engine-application/pom.xml` 已按精确版本 copy 12 主 JAR 到独立 consumer 的 target/plugin-artifacts，排除宿主 classpath。`EngineConsumerTest.java` 同样 installRequest 手写完整包，必须补 JAR 提取合同；新作者消费者可放此现有模块，无需新生产依赖。
- `scripts/verify-distribution.sh` 会部署正式模块到临时 file repository、复制 consumer 源到仓外、使用独立 consumer-m2，再生成独立 archetype 项目并 verify；适合接入真实正式 JAR QA。末段手工组装 external CLI 包（只 copy main.jar/写 manifest）也必须提取 java-plugin JAR 合同。
- `fibra-distribution/src/test/scripts/verify-archive.sh` 已遍历 12 包/校验 main.jar；增加每包根/JAR byte 比较。此脚本可验证字节，完整 ConfigSchema 发布资格须调用上述实际 Java consumer，不能由 shell JSON 解析替代。

## 6. 关闭失败沿 owner 保留

不能复制现有部分 consumer 的 `try (store; targets; engine)` 结构：Engine 成功构造后重复独立关闭 store/targets 不符合本合同，资源 close 失败时更会切断依赖。

作者 QA 需要一个有界 owner 持有 engine/store/targets 和当前 config handle：

- 读成功 + close 成功：清空 handle；parse/check；成功或普通读/parse/check 失败最终都由 Engine.close 关闭其接管对象。
- read 失败 + close 成功：保留原错误作为 QA 失败，清空 handle，正常 Engine close。
- close 失败（不论 read 是否成功）：保留 handle、Engine、store/targets 的强引用；QA 明确失败并交宿主终止路径，禁止 finally 无条件 Engine/store close；禁止 retry 物理 close（当前接口明确重复调用不会重试物理关闭）。
- Engine 构造前失败：只由仍持有的构造 owner 关闭尚未移交资源；构造内部已接管部分遵循实际构造回滚，不能重复假定 ownership。

关闭反例可向 QA 的测试 seam 注入 close-failing PluginConfigurationResource，同时真实包名称仍由 provider 读取。断言读/close次数、原始失败与close失败保留、handle仍被owner持有、store/Engine依赖未继续关闭、宿主终止信号已发出；不能仅 assertThrows 后让局部变量掉出作用域。正常示例与反例应验证同一个 QA close owner 实现。

## 7. 主线程需明确的少数选择/未验证窗口

1. 采用现有 Assembly 的最小路线并执行隔离 probe，还是有确证阻碍后批准 Antrun；当前没有必要新增 build 插件的证据。
2. 12 叶 POM 显式执行与根共享 descriptor 的组织方式；不改变 parent、不散播 unrelated 模块 execution。是否接受固定 classes 路径，或 probe 确认通用 outputDirectory 写法。
3. 模板 `${pluginId}` 与 `$schema/$ref` 的 Velocity 转义必须以真实 3.4.1 生成结果闭环，本次只定位了风险，未运行生成器。
4. distribution 脚本选择 JDK/shell 或显式 Python 构建依赖；不得暗中扩大发行构建环境要求。
5. QA 关闭失败的宿主终止 owner 要落在可观察且保留强引用的实际 helper 中，不能只留 README 伪代码。

本报告未跑任何测试；未证明正式 JAR、发行 ZIP、模板和 Node 当前已含合同。以上是最小实施路线和可执行验收入口，不是 runtime 兼容或 fallback 方案。

## 8. 主线程实施选择与隔离构建证据

先采用现有 Assembly/Exec。优先在根 pluginManagement 的具名 execution 中仅管理配置，不绑定 phase/goals；12个叶插件按同一id显式绑定process-resources/single，避免其它Assembly消费者被自动执行，同时让复制协议只有一个配置owner。模板独立消费不能依赖Fibra parent，须包含自己的等价配置。先用实际effective POM与隔离构建验证合并语义、任意outputDirectory、目录不被清空及同mtime更新；若现有插件确有阻碍，先报告具体证据再调整，不直接添加新依赖。

发行提取优先使用构建已要求的shell/JDK，从stage精确main.jar抽取固定entry；不额外要求Python参与prepare-package。对唯一entry、路径含空格、原字节及重复执行补验证。全包资格放测试/作者示例的有界owner，保留失败句柄与其Engine依赖，不增加生产资格框架。

隔离项目`/private/tmp/fibra-config-assembly.er5wzdhx`已实际执行两次process-resources和一次jar，三个native exit均0。根pluginManagement execution仅配置、active叶显式绑定、idle叶仅声明插件：只有active生成合同；`outputDirectory=${project.build.outputDirectory}`及`finalName=.`支持包含空格的自定义输出目录，保留既有资源。作者源同长度、同mtime改写后forced=true仍更新；最终JAR唯一固定entry与作者源及classes逐字节一致。JAR SHA256为`94ea1e89363dfe3a51057bda778f288b9e0b3fdb914e7303480eb84e05bd5cff`。证据及精确POM归档于`../../references/plugin-config-contract/implementation/assembly-probe/`。此结果关闭复制/合并语义疑问，尚不证明正式12包、shade或Velocity生成。

共享QA关闭owner限测试源码：acceptance、archetype和Node宿主测试通过现有compiler显式testSourceRoots共编译一个专用测试helper，保留默认测试源；不得增加生产模块/依赖或生成工程的仓库路径依赖。正式JAR/sourcesJAR排除该helper作为发行验收项。此组织选择避免三份生命周期规则，尚待实际构建和独审。

Task5合同R1修复后，唯一共享QA源码最终位于`fibra-distribution/src/test/consumers/engine-application/src/author-qa/java`，随现有仓外消费者源码复制；外部POM相对本项目编译，三个仓内测试共编译同一源。这样全包发布资格可直接在仅依赖正式JAR的仓外环境运行，不发布QA生产库。合同R2已通过，质量审查未完成。
