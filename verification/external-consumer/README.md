# Fibra 仓库外消费验收 fixture

本目录是不加入 Fibra reactor、不继承 Fibra parent 的独立 Maven 黑盒验收工程，只由 `scripts/verify-external-consumer.sh` 复制、构建和运行。它负责证明发布制品可通过 Maven 坐标独立消费，不是用户项目模板；用户必须使用 `fibra-plugin-archetype` 生成标准插件工程。

模块关系：

```text
core-app                         普通 Java 消费示例，与插件链无关

contract-plugin                 contract-only：只拥有 Greeting
       ↑                 ↑
       │ PF4J 二进制依赖  │ PF4J 二进制依赖
provider-plugin          consumer-plugin
       │                 │
       └── Fibra 服务 ───┘

host                            仅用于本地端到端验证，不含上述三类
```

- 最小 executable 插件只需要一个插件模块、一个 `FibraPluginEntrypoint`、`src/main/plugin/plugin.properties` 和标准 ZIP assembly；可直接从 `provider-plugin` 精简。
- 只有跨多个插件共享类型或需要独立版本演进时才需要 `contract-plugin`。contract-only 包没有 `META-INF/extensions.idx`，可以被依赖但不能创建 Fibra entry。
- `consumer-plugin` 是多插件依赖示例，不是所有插件的必需结构。它只在 PF4J 图依赖 contract，在 Fibra 配置中等待 provider 服务，不二进制依赖 provider。
- `host` 和 `core-app` 都不是插件发布物。Host 只依赖 `fibra-engine`；插件类、contract 类型和插件私有库均不进入 Host classpath。

## 构建标准 ZIP

根 POM 固定 Java 21、当前开发版本 `0.4.0-SNAPSHOT`、PF4J 3.15.0 和 Maven 插件版本。当前快照没有默认公共仓库来源，不能在本目录直接执行 `mvn verify` 作为有效验收；本仓库开发者必须从 Fibra 仓库根目录执行：

```bash
scripts/verify-external-consumer.sh
```

脚本先把当前十个发布制品部署到临时文件仓库，再在临时副本上通过 `-D` 注入当前开发版本和临时仓库地址，不修改本目录源文件。正式版本发布后，本 fixture 仍由该脚本验收；它不承担用户使用说明。

每个插件模块生成 v1、v2 两份包，例如：

```text
contract-plugin/target/external-contract-plugin-1.0.0.zip
provider-plugin/target/external-provider-plugin-1.0.0.zip
consumer-plugin/target/external-consumer-plugin-1.0.0.zip
```

ZIP 内必须只有一个与 `plugin.id` 相同的顶层目录，根下只有 `plugin.properties` 和 `lib/`；主 JAR 必须严格命名为 `<plugin.id>-<plugin.version>.jar`。Fibra、PF4J、Reactor、Reactive Streams、SLF4J 和独立 contract 依赖都用 `provided`，不得复制进 `lib/`。普通私有三方库使用默认 runtime scope，由 assembly 放入当前插件自己的 `lib/`。

`provider-plugin` 故意私有携带 Commons Text；`consumer-plugin` 在运行时确认它不可见。若跨插件服务出现同限定名类型不能转换、`ClassCastException` 或 `LinkageError`，应先检查是否把 contract 重复打进了多个 `lib/`，正确修复是拆出独立 contract-only 插件或放入宿主公共 API，不能增加反射兼容桥。

## 脚本运行的完整图

脚本完成独立构建后安装三份 v1 ZIP，再把三份 v2 ZIP 组合成一个 deployment 包提交给 Engine。以下命令只用于解释脚本的最终进程形态，不能代替脚本前面的隔离仓库与结构门禁：

```bash
work="$(mktemp -d)"
template_root="$(pwd)"
mkdir "$work/plugins"
for zip in \
  contract-plugin/target/external-contract-plugin-1.0.0.zip \
  provider-plugin/target/external-provider-plugin-1.0.0.zip \
  consumer-plugin/target/external-consumer-plugin-1.0.0.zip; do
  (cd "$work/plugins" && jar xf "$template_root/$zip")
done
cp host/config/fibra.yaml "$work/fibra.yaml"
java -jar host/target/external-host-all.jar \
  "$work/plugins" "$work/fibra.yaml" \
  "$work/external-deployment-2.0.0.zip"
```

Host 通过 `FibraEngine.start()` 从安装根和 YAML 创建两个 provider entry、两个 consumer entry，验证服务等待和 isolate，再调用一次 `applyDeployment(...)` 关联更新 contract/provider/consumer。只有三份安装目录都成为 `2.0.0`、四个 entry 重新激活且服务结果不变，才输出 `EXTERNAL_ENGINE_CONSUMER_OK`。
