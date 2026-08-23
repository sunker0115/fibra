# Fibra 配置驱动宿主示例

本示例在 Fibra 的生产制品中只直接依赖 `fibra-loader-config`，通过 [`config/fibra.yaml`](config/fibra.yaml) 创建真实 provider 和 consumer 插件实例。consumer 在配置中声明 `example.provider.greeting` 依赖，因此即使配置顺序位于 provider 之前，也会先保持 `PENDING`，等 provider 提供服务后再进入 `ACTIVE`。

先在仓库根目录执行：

```bash
mvn clean verify
```

该命令会构建宿主可执行 JAR，并把四个验收插件制品放入 `fibra-example-host/target/plugin-artifacts`。运行示例时，插件目录必须只安装 provider v1 和 consumer v1；provider v2 作为更新候选保留在目录外：

```bash
example_work="$(mktemp -d)"
mkdir "$example_work/plugins"
cp fibra-example-host/target/plugin-artifacts/fibra-example-provider-v1.jar \
  "$example_work/plugins/fibra-example-provider.jar"
cp fibra-example-host/target/plugin-artifacts/fibra-example-consumer-v1.jar \
  "$example_work/plugins/fibra-example-consumer.jar"
java -jar fibra-example-host/target/fibra-example-host-all.jar \
  "$example_work/plugins" \
  fibra-example-host/config/fibra.yaml \
  fibra-example-host/target/plugin-artifacts/fibra-example-provider-v2.jar
```

进程先从 YAML 装载 v1 插件树，再把 provider 原子更新到 v2。成功日志必须同时出现 `consumer->provider-1.0.0` 和 `consumer->provider-2.0.0`。
