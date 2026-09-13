# Fibra 发布与构建基线

## 发布边界

vNext 当前发布二十七个职责明确的 Maven 制品：

- 基础层：`fibra-api`、`fibra-core`、`fibra-config`、`fibra-artifact`；
- 托管与扩展层：`fibra-engine`、`fibra-bridge`、`fibra-runtime-java`、`fibra-runtime-node`、`fibra-registry`；
- 宿主与集成层：`fibra-cli-api`、`fibra-cli`、`fibra-spring`、`fibra-spring-boot-starter`；
- 开发工具：`fibra-plugin-archetype`。
- 正式插件产品：`fibra-tool-api`、`fibra-fs`、`fibra-fs-local`、`fibra-tool-fs`、
  `fibra-tool-fs-search`、`fibra-subprocess`、`fibra-subprocess-local`、`fibra-shell`、
  `fibra-shell-local`、`fibra-tool-shell`、`fibra-storage`、`fibra-storage-json`、
  `fibra-tool-storage`。

根工程、`fibra-plugins` 及其分类聚合模块、`fibra-plugins-acceptance`、`fibra-example`、
`fibra-parity-tests`、`fibra-benchmarks` 和 `verification` 均不发布。每个发布模块显式设置
`maven.deploy.skip=false`，生成主 JAR、sources JAR、Javadoc JAR 和 Flatten Maven POM。

## 权威门禁

环境固定为 Java 21 和 Maven 3.9.9：

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

真实插件组合与仓库外 Engine 消费还需要 Node.js、Bash 和 ripgrep。当前插件 Maven JAR 不捆绑这些
可执行文件；CI 从 `microsoft/ripgrep-prebuilt` 固定下载 ripgrep 15.0.1 并校验 SHA-256，宿主直接嵌入时
须把实际可执行路径写入对应插件配置。最终 ZIP 发行包按目标平台携带 sidecar，并继续注入同一配置。

全量 `verify` 覆盖 Scope/插件/资源所有权、配置与不可变制品保存、完整部署目标与崩溃恢复、ChangeSet
变更编排、真实 Java JAR/ClassSpace、真实 Node sidecar、运行域内 ContributionDirectory 与
PublishedRuntime、PluginRegistry、Spring Boot、archetype、公共 API 和 JMH 编译。

可复现脚本对二十七个发布模块的主 JAR、sources、Javadoc 与展开 POM 逐字节比较。分发脚本把这些制品
部署到临时文件仓库，再从空 Maven 本地仓库构建仓库外的 core、真实多插件公开 API 调用、Engine、
Spring Boot 和 archetype 消费场景。仓外 Java 插件以 `provided` 方式消费 `fibra-cli-api`，分发脚本从
ZIP 启动器执行动态命令与 help，再停用插件并验证命令消失。

日常逻辑修改使用已有依赖缓存执行 `mvn -o verify`。archetype 集成测试同样复用当前本地仓库，先暂存本次构建的 Fibra API，再生成并验证真实插件；不为每轮验证另建空依赖仓库。空仓外部消费只在最终分发门禁统一执行。

## 插件制品

Maven 发布物与安装单元不同：当前 27 个发布模块中，12 个动态插件仍发布 Java JAR，并作为各自安装包
的主 payload；source/Javadoc 附件不进入安装包。实际插件安装单元统一为目录包，根
`plugin.properties` 必须且只能声明 `formatVersion=1`、`runtime` 和包内相对 `payload`，不复制
插件标识、版本、依赖或入口。payload 必须存在且不等于包根，包内不得有符号链接。

Java 包以 `lib/main.jar` 等主 JAR 为 payload，`lib/` 可携带同一安装单元的私有依赖 JAR；主 JAR 内的
`META-INF/fibra/plugin.yaml` 是 `id`、`version`、`requires`、`entrypoint` 的唯一真源。宿主可见契约以 `provided` 方式依赖
`fibra-api`、`fibra-bridge` 与 `fibra-tool-api`；command 插件另以 `provided` 方式依赖 `fibra-cli-api`，
动态 provider/consumer 还以 `provided` 方式依赖对应的
`fibra-fs`、`fibra-subprocess`、`fibra-shell` 或 `fibra-storage` contract，并在 manifest 中声明精确版本边。
当前正式插件中的 JNA、Jackson 等实现依赖仍作为对应插件的 `optional` 私有着色内容发布，不能传递到
宿主或动态 contract。主 JAR 和私有 JAR 均不得用非空 manifest `Class-Path` 隐式扩展装载路径。

Node 包声明 `runtime=node`，payload 指向包含 `fibra-plugin.yaml` 和 JavaScript 入口的包内目录；
内部 manifest 是插件标识、版本、入口、协议及贡献声明的唯一真源。Java/Node runtime 均不保留
裸 JAR 或旧 Node 目录 fallback。`ArtifactStore` 复制完整包，装载和重启从受管包解析 payload。
插件市场、下载、签名、信任和上传鉴权属于宿主策略，不属于 Fibra 制品协议。

正式 CLI Maven 制品、profile 选择、本地安装命令与 F1 组合边界已实现；Maven 薄 JAR 不等于终端发行包。
ZIP 已包含启动器、宿主依赖、标准插件目录包、默认 profile 和目标平台 sidecar，并由仓库外解压运行门禁
验证。F2–F4 的交互与终端能力不得由该发行结果冒充。

## Maven Central

公开发布使用根 POM 的 `central-release` profile。正式标签必须为 `v<major>.<minor>.<patch>`，标签版本与根 POM 的非 `SNAPSHOT` revision 完全一致，且标签提交位于 `main` 历史中。

发布命令只选择二十七个发布模块：

```bash
mvn clean deploy -Pcentral-release \
  -pl fibra-api,fibra-core,fibra-config,fibra-artifact,fibra-engine,fibra-bridge,fibra-runtime-java,fibra-runtime-node,fibra-registry,fibra-cli-api,fibra-cli,fibra-spring,fibra-spring-boot-starter,fibra-plugin-archetype,fibra-plugins/fibra-tool-api,fibra-plugins/fibra-plugins-fs/fibra-fs,fibra-plugins/fibra-plugins-fs/fibra-fs-local,fibra-plugins/fibra-plugins-fs/fibra-tool-fs,fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local,fibra-plugins/fibra-plugins-shell/fibra-shell,fibra-plugins/fibra-plugins-shell/fibra-shell-local,fibra-plugins/fibra-plugins-shell/fibra-tool-shell,fibra-plugins/fibra-plugins-storage/fibra-storage,fibra-plugins/fibra-plugins-storage/fibra-storage-json,fibra-plugins/fibra-plugins-storage/fibra-tool-storage \
  -am -DskipTests -Darchetype.test.skip=true
```

`central-release` 保持 `autoPublish=false`，上传后必须在 Central Portal 人工核对 GAV、POM 元数据、附件和签名。仓库不保存发布凭据。
