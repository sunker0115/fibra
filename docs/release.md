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
