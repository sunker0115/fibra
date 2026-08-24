# Fibra Engine 部署宿主示例

本示例宿主只依赖 `fibra-engine`，不取得两个 loader。`Greeting` 契约、provider、consumer 和配置由一个 deployment ZIP 联合提交；制品与配置任一步失败都会回滚整个部署。

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

命令会生成 contract/provider/consumer 的 v1、v2 标准 ZIP，组装两个 deployment ZIP 并运行真实独立进程。进程通过 `FibraEngine.applyDeployment` 完成 v1 初装和 v2 关联升级。验收还会提交一个不兼容 deployment，确认磁盘、插件图、Fibra entry、配置和服务值全部恢复到 v2。

手工运行时传入空安装目录、初始配置文件和两个已按 deployment 契约打包的 ZIP：

```bash
example_work="$(mktemp -d)"
mkdir "$example_work/plugins"
java -jar fibra-example-host/target/fibra-example-host-all.jar \
  "$example_work/plugins" \
  "$example_work/fibra.yaml" \
  deployment-v1.zip \
  deployment-v2.zip
```

成功日志必须依次包含 `consumer->provider-1.0.0` 和 `consumer->provider-2.0.0`，最终安装目录中的三个 `plugin.properties` 版本都必须是 `2.0.0`。
