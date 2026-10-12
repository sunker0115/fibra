# ${artifactId}

这是一个独立的 Fibra Java 插件工程。

```bash
mvn verify
```

`mvn package` 和 `mvn verify` 都会自动生成正式可安装目录：

```text
target/${artifactId}-${version}-plugin/
├── fibra-config.json
├── fibra-package.yaml
└── lib/
    └── plugin.jar
```

将整个目录作为 package 来源，通过 `PluginPackageStore` 发布，再由
`fibra-registry` 选择具体 package revision 进入完整部署目标。目录名随 Maven 的
`project.build.finalName` 变化；根 `fibra-package.yaml` 是逻辑 package 的唯一身份源，
声明插件 id、版本、facet、runtime、执行目标、payload、依赖和能力。
主 JAR 内的 `META-INF/fibra/plugin.yaml` 同时声明 Java facet 的 `entrypoint` 和 `definitionId`。
生成的名称为 `${pluginId}`，与 `FibraPluginEntrypoint.definition()` 完全相同。目录查询只读取声明，实际 prepare
在 bind 前核真实名称；旧缺名称格式没有回退。升级 runtime 时重新打包插件，通过原完整 target/CAS 选择新 digest。
正常 Maven 制品 `target/${artifactId}-${version}.jar` 保留，内容与目录中的 `lib/plugin.jar` 完全相同；
安装目录不会额外打包 `fibra-api` 或动态 contract，也不生成插件 ZIP。

这里的“独立工程”指一个插件制品及其安装目录，不限制仓库只能有一个 Maven 模块。一个产品由公共契约、provider、
consumer 等多个制品组成时，可以像 Fibra 正式插件一样使用聚合 POM 管理多个独立插件工程；聚合模块本身不部署。

生成项目默认只依赖宿主提供的 `fibra-api`。需要动态契约时，把对应 contract artifact 以 `provided` scope
加入 POM，并在 `fibra-package.yaml` 的 facet `dependencies` 中按 `pluginId` + `facetId`
声明运行时依赖；不要把 `fibra-api`、工具 API 或动态 contract class 打进插件 JAR。
provider 用 `PluginDefinition.provide(...)` 发布服务，consumer 用 `PluginDefinition.require(...)`
声明服务依赖，运行时再通过 package facet 依赖图与 realm 将两者装配起来。

配置合同的唯一作者源是 `src/plugin/fibra-config.json`。构建将它原字节复制到主 JAR 的
`META-INF/fibra/config.json` 和安装目录根；不能维护三份规则。`format:1`、`profile:fibra-config-1`
下按 facet/definition 声明 schema。本模板允许整项 null（由运行时生成默认配置），或者带必填、非空
`message` 的封闭 object；schema 不填默认值，未知字段被结构门拒绝，业务验证继续归 entrypoint。

发布前运行 `mvn verify` 并比较生成源、最终 JAR 固定 entry、安装根三份原始字节。宿主资格还应安装完整包，
选择停用 revision，以实际提交后的 target revision 调用 `engine.describeDefinitions(pluginId, revision)`，
精确比较全部 facet 和 definition 集合，再对完整 source 集合（包括空集）执行 `ConfigSchema.checkSources`。
缺失、孤儿、额外空 facet 及停用 definition 的坏 schema 都必须失败；运行时按需查询不能替代发布资格。
通过 store 已核 record 的 `configurationResource()` 读取后必须真实关闭，再解析文档；关闭失败须保留句柄、
Engine 和 store 依赖，转宿主终止流程，不得在 finally 中继续关 Engine。Fibra 仓内 archetype 集成门以同一作者 QA
helper 验证此生命周期，并保留实际 Java runtime 启动测试。
