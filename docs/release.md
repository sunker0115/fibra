# Fibra 发布与构建基线

## 版本状态

`v0.4.0` 是当前 Git 正式版本，`v0.3.1` 是上一正式版本基线。正式标签必须指向 `main` 上根 POM `revision=0.4.0` 且全部门禁通过的提交，不得移动或覆盖。

文档不得把 Git tag、内部仓库部署和 Maven Central 发布混为一件事。当前仓库没有足够证据声明 `0.4.0` 坐标已存在于 Maven Central；Central 确认可解析前，使用者必须从 `v0.4.0` 源码本地安装或部署到明确的内部仓库。

## 发布边界

远程 Maven 仓库接收十个制品。

六个框架中立运行时制品：

- `com.sstlfsj:fibra-api`；
- `com.sstlfsj:fibra-core`；
- `com.sstlfsj:fibra-pf4j-api`；
- `com.sstlfsj:fibra-loader-pf4j`；
- `com.sstlfsj:fibra-loader-config`；
- `com.sstlfsj:fibra-engine`。

三个可选 Spring 运行时制品：

- `com.sstlfsj:fibra-spring`；
- `com.sstlfsj:fibra-spring-boot-autoconfigure`；
- `com.sstlfsj:fibra-spring-boot-starter`。

一个开发工具制品：

- `com.sstlfsj:fibra-plugin-archetype`。

因此是九个运行时制品加一个开发工具制品。根 `fibra`、`fibra-example`、`fibra-parity-tests`、`fibra-benchmarks` 和 `verification` 不远程发布。`fibra-benchmarks` 参加默认 reactor，但只用于编译、打包和人工性能测量，不进入发布或可复现构建集合。starter 是无生产 class 的依赖入口；archetype 主 JAR 保存生成模板，不是运行时库。

十个模块都使用 Flatten Maven Plugin 的 `oss` 模式生成自包含 POM。发布 POM 不保留根 parent、`${revision}` 或未展开的内部依赖版本，消费者不需要继承 `com.sstlfsj:fibra` 父 POM。版本仍只在根 `<revision>` 和统一 properties/dependencyManagement 中维护。

## 权威构建

环境固定为 Java 21 和 Maven 3.9.9：

```bash
mvn clean verify
```

Maven Enforcer 检查 Java/Maven 版本、依赖收敛和插件版本。完整 reactor 同时执行：

- Cordis Core 71 项逐条 Java 等价测试；
- 八个含 Java API 制品的 `javap -protected` 签名基线；
- PF4J 3.15.0 行为、插件包、依赖图、ClassLoader 和事务恢复；
- 配置树、typed config、文件写入和回滚；
- Engine source、reconcile、deployment、readiness、崩溃恢复和终止关闭；
- Spring 生命周期、属性、自动配置整体退让和示例黑盒；
- Maven Archetype 生成、生成项目 `verify`、标准包检查及真实 Engine 装载；
- JMH 基准代码编译与可运行基准 JAR 打包，但不执行性能测量；
- 十个发布制品的主 JAR、sources JAR、Javadoc JAR和展开 POM门禁。

无源码 starter 仍附加空 sources/Javadoc JAR，archetype 没有 Java 公共类时仍附加空 Javadoc JAR，以保持仓库发布附件集合一致；发布门禁同时确认 starter 没有 `.class`，并确认 archetype 包含 `META-INF/maven/archetype-metadata.xml`。

## 可复现构建

完整 `mvn clean verify` 成功后执行：

```bash
scripts/verify-reproducible-release.sh
```

脚本保存十个模块当前主 JAR、sources JAR、Javadoc JAR和展开 POM，再以相同版本和构建时间戳重建并逐字节比较。脚本第二次构建使用 `-DskipTests` 只为比较产物，不能替代前面的完整测试。

## 仓库外消费验收

```bash
scripts/verify-distribution.sh
```

脚本使用独立临时 Maven 本地仓库完成以下边界：

1. 把十个发布制品 deploy 到临时文件仓库，并检查每个制品恰好具有 POM、主 JAR、sources JAR 和 Javadoc JAR；
2. 复制 `verification/distribution` 到仓库外临时目录，拒绝符号链接、Fibra 仓库绝对路径、reactor `target/classes` 和 `systemPath`；
3. 独立工程从临时仓库解析它实际使用的九个运行时制品，并核对 Maven 来源记录和制品字节；
4. 构建并运行 `core-application`、contract/provider/consumer 标准插件包、只依赖 `fibra-engine` 的 `engine-application`，以及只直接依赖 starter 与 Spring Boot 的非 Web application；
5. 检查 contract 类型唯一、provider 私有依赖隔离、`Plugin-Class` 禁止、application 不含插件或 contract 类型；
6. 以独立 `java -jar` 进程验证 consumer-first、多个 entry、等待依赖、isolate、配置更新与失败恢复、不完整关联升级拒绝和三包完整关联升级；
7. 从临时远端仓库调用已部署的 `fibra-plugin-archetype`，构建生成的独立项目并检查标准 plugin/deployment ZIP。

这个 fixture 是仓库外黑盒验收，不承担用户模板责任；用户插件项目只能由 `fibra-plugin-archetype` 生成。脚本通过只证明当前工作树制品可以从 Maven 坐标独立消费，不表示坐标已发布到公共仓库。目录职责和完整断言见[示例与分发验收设计](superpowers/specs/2026-08-25-fibra-examples-and-distribution-verification-design.md)。

## 插件 Archetype 发布与使用

从源码使用时先在 `v0.4.0` 执行 `mvn install`，再以 `com.sstlfsj:fibra-plugin-archetype:0.4.0` 生成项目。`archetypeVersion` 与生成项目的 `fibraVersion` 必须使用同一个正式版本。

生成项目固定为独立四模块结构：

```text
generated-plugin/
  pom.xml
  plugin-api/       # contract-only 标准包
  plugin-impl/      # 唯一 Fibra 入口与 typed config
  config/           # fibra.yaml
  deployment/       # 插件 ZIP + 配置 + SHA-256
```

生成项目不继承 Fibra parent，不引用 `${revision}`、reactor 输出或仓库脚本。直接执行 `mvn verify` 即生成标准 plugin ZIP 和 deployment ZIP。archetype 自身的 `verify` 使用官方 integration-test 生成并构建样例，并由 `FibraEngine` 实际安装该 deployment。

## 运行时部署布局

标准插件安装目录：

```text
<installed-root>/
  <plugin.id>/
    plugin.properties
    lib/
      <plugin.id>-<version>.jar
      [私有 runtime 依赖.jar]
  .fibra-engine/                 # Engine journal、revision 和事务数据
```

插件候选 ZIP 顶层必须是唯一 `<plugin.id>/`，安装目录与 ZIP 内结构同构。contract-only 包只保存共享类型且没有 Fibra 入口；executable 包恰好包含一个 `FibraPluginEntrypoint`。Fibra、PF4J、Reactor、SLF4J 和共享 contract 不得复制进插件私有 `lib/`。

纯 Java 宿主的典型布局：

```text
<deploy-root>/
  app.jar
  config/fibra.yaml
  run/plugins/                   # installed-root
  run/incoming/                  # 可选 artifact source
  deployments/                  # 宿主接收的显式 deployment 包
```

Spring Boot Web 示例的布局：

```text
<deploy-root>/
  app.jar
  application.yml
  run/plugins/                   # fibra.artifacts.installed-root
  run/incoming/                  # fibra.artifacts.incoming-root
  run/fibra.yaml                 # fibra.config.location
  run/staging/                   # example.fibra.staging-root，仅示例上传策略
```

`staging-root` 不属于通用 Fibra 属性。上传、下载、鉴权、签名和市场是宿主策略；Engine 只接受本地候选目录与显式 deployment 路径。纯 Java 与 Spring 宿主都由同一个 `FibraEngine` 管理插件、配置、source、reconcile、事务和关闭。

## 部署命令

内部仓库由调用方通过 Maven settings、`distributionManagement` 或 `altDeploymentRepository` 提供：

```bash
mvn deploy -DaltDeploymentRepository=release::https://repo.example.invalid/maven
```

示例 URL 只展示命令形态，不是项目默认仓库。仓库中不保存凭据，也不静默回退到其他目标。

## Maven Central 发布

公开发布使用 Central Publisher Portal 和根 POM 的 `central-release` profile。该 profile 才启用 `central-publishing-maven-plugin` 与 GPG 签名；普通 `verify`、可复现构建和临时文件仓库分发验收不加载 Central 发布扩展。

GitHub Actions 的 `发布到 Maven Central` 工作流只允许维护者手工输入已有正式标签。工作流先在无发布凭据的任务中验证：

- 标签格式为 `v<major>.<minor>.<patch>`；
- 标签版本与根 POM 的非 `SNAPSHOT` `revision` 完全一致；
- 标签提交位于 `main` 历史中；
- 全 reactor、可复现构建和仓库外分发三道门禁全部通过。

验证任务输出不可变提交 SHA，发布任务只重新检出该 SHA，并绑定受保护的 GitHub Environment `central`。该环境保存四个 Secret：

- `CENTRAL_USERNAME`：Central Portal user token 用户名；
- `CENTRAL_PASSWORD`：Central Portal user token 密码；
- `MAVEN_GPG_KEY`：ASCII armored OpenPGP 私钥；
- `MAVEN_GPG_PASSPHRASE`：私钥口令。

凭据只进入发布任务的最终 Maven 步骤，不进入 Pull Request、普通 CI 或三道验证脚本。发布任务执行：

```bash
mvn --batch-mode --no-transfer-progress clean deploy -Pcentral-release \
  -pl fibra-api,fibra-core,fibra-pf4j-api,fibra-loader-pf4j,fibra-loader-config,fibra-engine,fibra-spring,fibra-spring-boot-autoconfigure,fibra-spring-boot-starter,fibra-plugin-archetype \
  -am -DskipTests -Darchetype.test.skip=true
```

`central-release` 固定 `autoPublish=false`。因此工作流成功只表示签名制品已上传并通过 Portal 校验，不表示已经公开；项目所有者必须在 Central Portal 核对十个 GAV、POM 元数据、附件和签名后人工发布。Central 上的同一 GAV 不能覆盖或重传，错误版本只能通过后续修复版本处理。

正式发布顺序固定为：将 `revision` 改为非 `SNAPSHOT` 版本并完成门禁，合并到 `main`，创建同版本标签，手工触发 Central 工作流，在 Portal 人工发布，确认 Central 可解析后再创建或公告 GitHub Release。发布完成后，开发分支进入下一个 `SNAPSHOT` 版本。

## 对外公开发布前置条件

仓库已经提交 Apache-2.0 根许可证、项目/开发者/SCM 元数据、贡献指南、安全策略、签名插件和 Central Portal 工作流。首次公开发布前仍必须由项目所有者完成以下外部设置：

- 在 Central Portal 验证 `com.sstlfsj` namespace 发布所有权；
- 创建并保护 GitHub Environment `central`，配置人工审批和四个发布 Secret；
- 生成发布专用 OpenPGP 主密钥并把公钥分发到公共 key server；
- 在 GitHub 启用私密漏洞报告、Dependabot alerts、secret scanning 和 push protection；
- 保护 `main` 与正式版本标签，要求 CI 门禁通过；
- 非 `SNAPSHOT` 的 `revision`；
- 完整门禁通过后的 `main` 合并和正式 tag。

未确认的信息不得用虚假占位元数据绕过公共仓库校验。
