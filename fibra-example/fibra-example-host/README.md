# Fibra 标准插件包与配置驱动宿主示例

本示例只把 `fibra-loader-config` 放入宿主 classpath。`Greeting` 契约、provider 和 consumer 分别打成三个标准 ZIP，Host 不编译也不加载它们的类；PF4J 图负责共享 contract 二进制类型，Fibra 配置图负责 consumer 等待 provider 服务，两张图不合并。

三个包的关系如下：

```text
fibra-example-contract       contract-only，只拥有 Greeting 类型
          ↑             ↑
          │ PF4J        │ PF4J
          │ 二进制依赖   │ 二进制依赖
provider executable     consumer executable
          │             │
          └── Fibra 服务 ┘
```

在仓库根目录执行：

```bash
mvn -pl fibra-example-host -am verify
```

命令会生成 contract/provider/consumer 的 v1、v2 标准 ZIP，并运行真实独立进程。该进程先用一次 `applyArtifacts` 安装三份 v1 包，根据 [`config/fibra.yaml`](config/fibra.yaml) 创建 provider/consumer entry，再用一次三候选批量事务升级到 v2。验收还会提交一个启动失败的 provider v3，确认磁盘、PF4J 图、Fibra entry 和服务值全部恢复到 v2。

手工运行时传入空安装目录、配置文件、三份 v1 ZIP 和三份 v2 ZIP：

```bash
example_work="$(mktemp -d)"
mkdir "$example_work/plugins"
java -jar fibra-example-host/target/fibra-example-host-all.jar \
  "$example_work/plugins" \
  fibra-example-host/config/fibra.yaml \
  fibra-example-contract-plugin/target/fibra-example-contract-1.0.0.zip \
  fibra-example-provider-plugin/target/fibra-example-provider-1.0.0.zip \
  fibra-example-consumer-plugin/target/fibra-example-consumer-1.0.0.zip \
  fibra-example-contract-plugin/target/fibra-example-contract-2.0.0.zip \
  fibra-example-provider-plugin/target/fibra-example-provider-2.0.0.zip \
  fibra-example-consumer-plugin/target/fibra-example-consumer-2.0.0.zip
```

成功日志必须依次包含 `consumer->provider-1.0.0` 和 `consumer->provider-2.0.0`，最终安装目录中的三个 `plugin.properties` 版本都必须是 `2.0.0`。
