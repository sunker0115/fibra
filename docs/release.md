# Fibra 发布与构建基线

## 发布边界

远程 Maven 仓库接收以下六个可发布制品，分两类：

五个中立内核/loader 制品（仅依赖 Reactor + SLF4J，父 POM 保持 Spring-free）：

- `com.sstlfsj:fibra-api`；
- `com.sstlfsj:fibra-core`；
- `com.sstlfsj:fibra-pf4j-api`；
- `com.sstlfsj:fibra-loader-pf4j`；
- `com.sstlfsj:fibra-loader-config`。

一个可选 Spring 适配制品（自管 Spring Boot BOM，Spring 只在该模块内部，不进内核也不进父 POM）：

- `com.sstlfsj:fibra-spring-boot-starter`。

根 `fibra` 只负责聚合、版本和构建策略；contract/provider/consumer 三个插件示例、示例宿主及 `fibra-parity-tests` 只负责验证。它们允许安装到本地 Maven 仓库以支持 reactor 开发，但统一跳过远程 deploy，不形成可消费产品坐标。

六个可发布模块使用 Flatten Maven Plugin 的 `oss` 模式生成自包含发布 POM。发布 POM不保留未发布的根 parent，不包含 `${revision}` 或 `${project.version}`，所有消费依赖都展开为明确版本。因此消费者不需要 `com.sstlfsj:fibra` 父 POM。

## 权威构建

构建环境固定为 Java 21 和 Maven 3.9.9：

```bash
mvn clean verify
```

Maven Enforcer 在每个模块检查：

- 构建 JDK必须为 Java 21；
- Maven 版本必须为 `3.9.9`；
- 依赖图必须收敛；
- clean、verify、install、deploy 生命周期使用的插件必须有明确版本。

六个可发布模块每次 `package` 都附加 sources JAR 与 Javadoc JAR。`ReleaseArtifactBaselineTest` 检查主 JAR、sources JAR、Javadoc JAR、自包含 POM、Java 21 class major version、测试 class 隔离和 deploy 模块边界。公开 API 由 `ApiSignatureBaselineTest` 与六份 `javap -protected` 基线冻结（含 `fibra-spring-boot-starter`）。

## 可复现构建

根 POM固定 `project.build.outputTimestamp`，Javadoc 固定编码、locale 并移除生成时间。验证脚本先保存已通过完整 `verify` 的六个可发布制品（五个中立内核/loader + `fibra-spring-boot-starter`）产物，再只重建这些依赖图，并逐字节比较主 JAR、sources JAR、Javadoc JAR 和发布 POM：

```bash
scripts/verify-reproducible-release.sh
```

脚本中的 `-DskipTests` 只用于第二次产物重建；第一次权威构建必须先完整执行 `mvn clean verify`，不得用它替代测试门禁。CI按同一顺序执行。

## 仓库外消费验收

`verification/external-consumer` 是纳入版本控制的独立 Maven 工程模板，不是 Fibra reactor 模块，也不继承 `com.sstlfsj:fibra`。不得把它加入根 POM 的 `<modules>`。它同时是唯一用户插件模板和黑盒验收输入，固定包含五个模块：

- `core-app` 只依赖 `fibra-core`，验证普通 Java 坐标消费；
- `contract-plugin` 以 provided scope 依赖 `fibra-api`，拥有 `Greeting`，生成无入口索引的 contract-only v1/v2 标准 ZIP；
- `provider-plugin` 以 provided scope 依赖 contract、`fibra-pf4j-api` 和 PF4J，生成 executable v1/v2 标准 ZIP，并把 Commons Text 作为私有 runtime JAR 放入自身 `lib/`；
- `consumer-plugin` 只以 provided scope 依赖 contract、`fibra-pf4j-api` 和 PF4J，不依赖 provider；运行时通过 Fibra 服务读取 `Greeting`，并确认 provider 私有 Commons Text 不可见；
- `host` 只依赖 `fibra-loader-config` 和 runtime `slf4j-simple`，不依赖任何插件或 contract 类型，仅用于开发端到端验证。

provider/consumer 都在 PF4J 图依赖 contract，consumer 在 Fibra 配置图等待 provider 服务；两张图互不替代。Host 与三份插件包之间没有 Maven 依赖，只有目录装载和候选 ZIP 提交关系。完整关系图见[仓库外多插件依赖验收设计](superpowers/specs/2026-08-22-fibra-external-multi-plugin-verification-design.md)。

唯一入口是：

```bash
scripts/verify-external-consumer.sh
```

模板 POM 具有 0.3.1 正式版、Maven Central、Java 21、PF4J 3.15.0 和固定 Maven 插件版本，0.3.1 发布后用户可在模板根直接执行 `mvn verify`。开发期脚本从根 POM 读取当前 `revision` 与统一工具版本，只在临时副本上通过 Maven `-D` 覆盖开发版本和临时仓库 URL，不修改模板源文件。

脚本完整执行以下黑盒边界：

1. 使用空的用户与全局 Maven settings 和独立临时本地仓库，只构建五个生产模块，并把它们 deploy 到临时文件仓库；
2. 检查临时仓库只有五个约定模块，且每个模块都具有发布 POM、主 JAR、sources JAR 和 Javadoc JAR；
3. 把模板复制到系统临时目录，拒绝符号链接、仓库绝对路径、`target/classes`、`target/test-classes` 和 `systemPath`；
4. 使用第二个从空目录开始的 Maven 本地仓库构建独立项目，分别检查五个 Fibra 坐标的主 JAR 和 POM 都来源于临时仓库，并逐字节比较本地解析制品与临时远端制品；
5. 检查 contract/provider/consumer 的 v1/v2 ZIP 都只有一个顶层目录、根 properties 与固定命名主 JAR；contract 只包含 `Greeting` 且没有入口，provider/consumer 各有唯一自身入口且不复制 contract；
6. 检查三份主 JAR 不内嵌 Fibra、PF4J、Reactor 或 SLF4J；provider/consumer 的 properties 只声明 contract 范围；provider 包必须携带一份 Commons Text 私有依赖，consumer 包和 Host classpath 都不得包含它；
7. 检查 Host JAR 不包含 contract、provider 或 consumer 类，证明 Host 未通过 shade 或编译依赖获得插件实现与契约；
8. 以两个独立 `java -jar` 进程运行内核应用和插件宿主，不通过 Maven `exec:java` 或 Fibra 仓库 classpath 运行；
9. Host 从三份 v1 ZIP 解压出的目录装载完整图，读取真实 YAML 创建 `provider-one/provider-two` 与 `consumer-one/consumer-two`，验证等待、isolate、config-only 身份保持和失败配置更新回滚；
10. Host 以一次显式 `applyArtifacts(List.of(contractV2, providerV2, consumerV2))` 完成版本范围发生变化的关联升级，确认三个安装目录成为 v2、四个 entry 重建并保持最后成功服务值；全部成立后才输出 `EXTERNAL_CONFIG_LOADER_CONSUMER_OK`。

该门禁通过只证明“当前工作树生成的五个发布制品，可由另一个 Java 21 Maven 项目仅通过坐标直接编译和运行”。它不表示这些坐标已经存在于 Maven Central 或任何公共仓库，也不替代公开发布前置条件。两个空本地仓库需要从 Maven Central 下载第三方依赖和构建插件，因此首次执行必须具备网络访问能力。

CI 固定按以下顺序执行：完整 `mvn clean verify`、可复现制品比较、仓库外消费验收。仓库外脚本生产制品时使用的 `-DskipTests` 不能替代前面的完整测试门禁。

## 部署

发布仓库由调用方通过 Maven settings、`distributionManagement` 或 `altDeploymentRepository` 提供。执行全 reactor deploy 时，根与验证模块会明确跳过，只有六个可发布模块（五个中立内核/loader + `fibra-spring-boot-starter`）上传：

```bash
mvn deploy -DaltDeploymentRepository=release::https://repo.example.invalid/maven
```

示例地址仅说明 Maven 参数形态，不是项目默认仓库。仓库中不保存凭据，也不提供静默回退目标。

## 对外公开发布前置条件

当前基线可以发布到已授权的内部 Maven 仓库，但不能把尚未确认的信息写成占位元数据。公开发布前必须由项目所有者确定并提交：

- Fibra 自身许可证及根 `LICENSE`；
- 实际项目 URL、Git remote、SCM 和 issue tracker；
- 开发者或组织信息；
- `com.sstlfsj` namespace 的发布所有权；
- 目标仓库、制品签名和发布凭据策略；
- 非 `SNAPSHOT` 的 `revision`。

这些信息确定后统一写入根 POM，由六个可发布模块的扁平 POM保留；不得使用虚假 URL、临时开发者或推测的许可证通过公共仓库校验。
