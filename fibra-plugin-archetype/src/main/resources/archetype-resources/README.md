# ${artifactId}

这是一个独立的 Fibra Java 插件工程。

```bash
mvn verify
```

构建产物 `target/${artifactId}-${version}.jar` 可通过 `fibra-registry` 安装到显式指定的 `java` runtime。
插件入口由 `META-INF/fibra/plugin.yaml` 唯一声明，不使用类扫描、PF4J descriptor 或 extension index。

这里的“独立工程”指一个可部署插件 JAR，不限制仓库只能有一个 Maven 模块。一个产品由公共契约、provider、
consumer 等多个制品组成时，可以像 Fibra 正式插件一样使用聚合 POM 管理多个独立插件工程；聚合模块本身不部署。

生成项目默认只依赖宿主提供的 `fibra-api`。需要动态契约时，把对应 contract artifact 以 `provided` scope
加入 POM，并在 `plugin.yaml` 的 `requires` 中使用同一精确版本声明；不要把 `fibra-api`、工具 API 或动态
contract class 打进插件 JAR。provider 用 `PluginDefinition.provide(...)` 发布服务，consumer 用
`PluginDefinition.require(...)` 声明服务依赖，运行时再通过 manifest 依赖图与 realm 将两者装配起来。
