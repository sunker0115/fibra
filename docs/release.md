# Fibra 发布与构建基线

## 发布边界

vNext 发布十二个职责明确的 Maven 制品：

- 基础层：`fibra-api`、`fibra-core`、`fibra-config`、`fibra-artifact`；
- 托管与扩展层：`fibra-engine`、`fibra-bridge`、`fibra-runtime-java`、`fibra-runtime-node`、`fibra-registry`；
- 集成层：`fibra-spring`、`fibra-spring-boot-starter`；
- 开发工具：`fibra-plugin-archetype`。

根工程、`fibra-example`、`fibra-parity-tests`、`fibra-benchmarks` 和 `verification` 不发布。每个发布模块显式设置 `maven.deploy.skip=false`，生成主 JAR、sources JAR、Javadoc JAR 和 Flatten Maven POM。

## 权威门禁

环境固定为 Java 21 和 Maven 3.9.9：

```bash
mvn clean verify
scripts/verify-reproducible-release.sh
scripts/verify-distribution.sh
```

全量 `verify` 覆盖 Scope/插件/资源所有权、配置与不可变制品保存、完整部署目标与崩溃恢复、ChangeSet
变更编排、真实 Java JAR/ClassSpace、真实 Node sidecar、运行域内 ContributionDirectory 与
PublishedRuntime、PluginRegistry、Spring Boot、archetype、公共 API 和 JMH 编译。

可复现脚本对十二个发布模块的主 JAR、sources、Javadoc 与展开 POM逐字节比较。分发脚本把这些制品部署到临时文件仓库，再从空 Maven 本地仓库构建仓库外的 core、Engine、Spring Boot 和 archetype 消费场景。

日常逻辑修改使用已有依赖缓存执行 `mvn -o verify`。archetype 集成测试同样复用当前本地仓库，先暂存本次构建的 Fibra API，再生成并验证真实插件；不为每轮验证另建空依赖仓库。空仓外部消费只在最终分发门禁统一执行。

## 插件制品

Java 插件是带 `META-INF/fibra/plugin.yaml` 的普通 JAR，只以 `provided` 方式依赖 `fibra-api`。Node 插件是带 `fibra-plugin.yaml` 的目录制品。插件市场、下载、签名、信任和上传鉴权属于宿主策略，不属于 Fibra 制品协议。

## Maven Central

公开发布使用根 POM 的 `central-release` profile。正式标签必须为 `v<major>.<minor>.<patch>`，标签版本与根 POM 的非 `SNAPSHOT` revision 完全一致，且标签提交位于 `main` 历史中。

发布命令只选择十二个发布模块：

```bash
mvn clean deploy -Pcentral-release \
  -pl fibra-api,fibra-core,fibra-config,fibra-artifact,fibra-engine,fibra-bridge,fibra-runtime-java,fibra-runtime-node,fibra-registry,fibra-spring,fibra-spring-boot-starter,fibra-plugin-archetype \
  -am -DskipTests -Darchetype.test.skip=true
```

`central-release` 保持 `autoPublish=false`，上传后必须在 Central Portal 人工核对 GAV、POM 元数据、附件和签名。仓库不保存发布凭据。
