# Fibra 发布与构建基线

## 发布边界

远程 Maven 仓库只接收以下四个生产制品：

- `com.sstlfsj:fibra-api`；
- `com.sstlfsj:fibra-core`；
- `com.sstlfsj:fibra-pf4j-api`；
- `com.sstlfsj:fibra-loader-pf4j`。

根 `fibra` 只负责聚合、版本和构建策略；两个插件示例、示例宿主及 `fibra-parity-tests` 只负责验证。它们允许安装到本地 Maven 仓库以支持 reactor 开发，但统一跳过远程 deploy，不形成可消费产品坐标。

四个生产模块使用 Flatten Maven Plugin 的 `oss` 模式生成自包含发布 POM。发布 POM不保留未发布的根 parent，不包含 `${revision}` 或 `${project.version}`，所有消费依赖都展开为明确版本。因此消费者不需要第五个 `com.sstlfsj:fibra` 父 POM。

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

四个生产模块每次 `package` 都附加 sources JAR 与 Javadoc JAR。`ReleaseArtifactBaselineTest` 继续检查主 JAR、sources JAR、Javadoc JAR、自包含 POM、Java 21 class major version、测试 class 隔离和 deploy 模块边界。公开 API 仍由 `ApiSignatureBaselineTest` 与四份 `javap -protected` 基线冻结。

## 可复现构建

根 POM固定 `project.build.outputTimestamp`，Javadoc 固定编码、locale 并移除生成时间。验证脚本先保存已通过完整 `verify` 的四模块产物，再只重建生产依赖图，并逐字节比较主 JAR、sources JAR、Javadoc JAR 和发布 POM：

```bash
scripts/verify-reproducible-release.sh
```

脚本中的 `-DskipTests` 只用于第二次产物重建；第一次权威构建必须先完整执行 `mvn clean verify`，不得用它替代测试门禁。CI按同一顺序执行。

## 仓库外消费验收

`verification/external-consumer` 是纳入版本控制的独立 Maven 工程模板，不是 Fibra reactor 模块，也不继承 `com.sstlfsj:fibra`。不得把它加入根 POM 的 `<modules>`。它包含三个职责互不替代的模块：

- `core-app` 在 Fibra 制品中只直接依赖 `com.sstlfsj:fibra-core`，另以 runtime scope 使用 `slf4j-simple`，验证运行时创建、服务注册、读取和释放；
- `plugin` 在 Fibra 制品中只直接依赖 `com.sstlfsj:fibra-pf4j-api`，另以 provided scope 使用 PF4J 编译支持，生成瘦插件 JAR、PF4J 扩展索引和固定 Manifest；
- `host` 在 Fibra 制品中只直接依赖 `com.sstlfsj:fibra-loader-pf4j`，另以 runtime scope 使用 `slf4j-simple`；它不把 `plugin` 声明为 Maven 依赖，而是在运行时从插件目录加载其 JAR 并验证服务注册与卸载。

唯一入口是：

```bash
scripts/verify-external-consumer.sh
```

不要直接修改或单独构建模板 POM。模板中的 `fibra.version`、`fibra.repository.url`、PF4J、SLF4J 及 Maven 插件版本使用不可解析的 `REQUIRED_BY_VERIFY_SCRIPT` 哨兵。脚本从根 POM 读取当前 `revision` 和统一版本，通过 Maven `-D` 参数传入；模板自身不复制 Fibra 版本真源。

脚本完整执行以下黑盒边界：

1. 使用空的用户与全局 Maven settings 和独立临时本地仓库，只构建四个生产模块，并把它们 deploy 到临时文件仓库；
2. 检查临时仓库只有四个约定模块，且每个模块都具有发布 POM、主 JAR、sources JAR 和 Javadoc JAR；
3. 把模板复制到系统临时目录，拒绝符号链接、仓库绝对路径、`target/classes`、`target/test-classes` 和 `systemPath`；
4. 使用第二个从空目录开始的 Maven 本地仓库构建独立项目，分别检查四个 Fibra 坐标的主 JAR 和 POM 都来源于临时仓库，并逐字节比较本地解析制品与临时远端制品；
5. 以两个独立 `java -jar` 进程运行内核应用和插件宿主，不通过 Maven `exec:java` 或 Fibra 仓库 classpath 运行；
6. 检查插件是瘦 JAR，包含唯一入口、`META-INF/extensions.idx` 和 PF4J Manifest，不内嵌 Fibra、PF4J、Reactor 或 SLF4J 类；同时检查宿主 JAR 不包含插件入口类。

该门禁通过只证明“当前工作树生成的四个发布制品，可由另一个 Java 21 Maven 项目仅通过坐标直接编译和运行”。它不表示这些坐标已经存在于 Maven Central 或任何公共仓库，也不替代公开发布前置条件。两个空本地仓库需要从 Maven Central 下载第三方依赖和构建插件，因此首次执行必须具备网络访问能力。

CI 固定按以下顺序执行：完整 `mvn clean verify`、可复现制品比较、仓库外消费验收。仓库外脚本生产制品时使用的 `-DskipTests` 不能替代前面的完整测试门禁。

## 部署

发布仓库由调用方通过 Maven settings、`distributionManagement` 或 `altDeploymentRepository` 提供。执行全 reactor deploy 时，根与验证模块会明确跳过，只有四个生产模块上传：

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

这些信息确定后统一写入根 POM，由四个生产模块的扁平 POM保留；不得使用虚假 URL、临时开发者或推测的许可证通过公共仓库校验。
