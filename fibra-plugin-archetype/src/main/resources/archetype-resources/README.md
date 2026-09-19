# ${artifactId}

这是一个独立的 Fibra Java 插件工程。

```bash
mvn verify
```

`mvn package` 和 `mvn verify` 都会自动生成正式可安装目录：

```text
target/${artifactId}-${version}-plugin/
├── fibra-package.yaml
└── lib/
    └── plugin.jar
```

将整个目录作为 package 来源，通过 `PluginPackageStore` 发布，再由
`fibra-registry` 选择具体 package revision 进入完整部署目标。目录名随 Maven 的
`project.build.finalName` 变化；根 `fibra-package.yaml` 是逻辑 package 的唯一身份源，
声明插件 id、版本、facet、runtime、执行目标、payload、依赖和能力。
主 JAR 内的 `META-INF/fibra/plugin.yaml` 只声明 Java facet 的 entrypoint。
正常 Maven 制品 `target/${artifactId}-${version}.jar` 保留，内容与目录中的 `lib/plugin.jar` 完全相同；
安装目录不会额外打包 `fibra-api` 或动态 contract，也不生成插件 ZIP。

这里的“独立工程”指一个插件制品及其安装目录，不限制仓库只能有一个 Maven 模块。一个产品由公共契约、provider、
consumer 等多个制品组成时，可以像 Fibra 正式插件一样使用聚合 POM 管理多个独立插件工程；聚合模块本身不部署。

生成项目默认只依赖宿主提供的 `fibra-api`。需要动态契约时，把对应 contract artifact 以 `provided` scope
加入 POM，并在 `fibra-package.yaml` 的 facet `dependencies` 中按 `pluginId` + `facetId`
声明运行时依赖；不要把 `fibra-api`、工具 API 或动态 contract class 打进插件 JAR。
provider 用 `PluginDefinition.provide(...)` 发布服务，consumer 用 `PluginDefinition.require(...)`
声明服务依赖，运行时再通过 package facet 依赖图与 realm 将两者装配起来。
