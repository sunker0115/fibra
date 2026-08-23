# Fibra 仓库外多插件与用户模板验收设计

本文定义 `verification/external-consumer` 在 0.3.0 的唯一职责：它同时是用户可复制的独立插件工程模板和 `scripts/verify-external-consumer.sh` 实际构建的黑盒输入。不得另建一份 Maven Archetype 或未被同一脚本持续验收的脚手架。

## 1. 隔离边界

独立工程不加入 Fibra reactor、不继承 Fibra parent、不引用 Fibra 源码、工作树 `target/classes`、`systemPath` 或符号链接。构建只通过五个公开 Maven 坐标消费 Fibra。脚本先把当前工作树的五个生产制品部署到临时文件仓库，再从另一个空本地仓库构建模板；解析后的主 JAR 和 POM 必须逐字节等于临时远端制品，并带 Maven 来源追踪记录。

模板固定为五个模块：

```text
core-app                         普通 Java 坐标消费，不参与插件链

contract-plugin                 contract-only，拥有 Greeting
       ↑                 ↑
       │ PF4J 二进制依赖  │ PF4J 二进制依赖
provider-plugin          consumer-plugin
       │                 │
       └── Fibra 服务 ───┘

host                            开发验收宿主，只依赖 fibra-loader-config
```

`provider`、`consumer` 是本示例的业务角色，不是 Fibra 通用插件类别。PF4J 图只包含两条到 contract 的二进制依赖；consumer 不二进制依赖 provider。配置树用 `inject` 与 isolate 表达 consumer 等待哪个 provider 服务，因此二进制图与服务图保持正交。

## 2. 模板可用性

根 POM 必须给出可解析形态的真实默认值，不得保留 `REQUIRED_BY_VERIFY_SCRIPT` 等哨兵。0.3.0 发布后，用户在模板根执行 `mvn verify` 即生成标准 ZIP；开发期仓库脚本只在临时副本上以 `-D` 覆盖 `fibra.version`、临时仓库 URL 和与根 POM 统一的工具版本，不改模板源文件。

README 必须明确区分：

- 最小 executable：单插件模块、唯一入口、properties 与 assembly；
- 可选 contract-only：仅在跨插件共享类型或独立版本演进时需要；
- 可选 consumer：展示多插件依赖和 Fibra 服务等待；
- 开发 Host/core-app：用于验证，不是插件发布物。

## 3. 标准包与 ClassLoader 门禁

contract/provider/consumer 都生成 v1、v2 标准 ZIP。每份 ZIP 必须只有一个 `plugin.id` 顶层目录，包含根 `plugin.properties`、`lib/<id>-<version>.jar` 和可选私有依赖；不得用直接 JAR 或 Manifest 提供插件身份。

脚本逐包检查：

1. 根目录、ID、版本和固定主 JAR 命名一致；
2. contract 主 JAR 只拥有 `Greeting` 且没有入口索引；
3. provider/consumer 主 JAR 各自只有自身入口索引，不复制 `Greeting`；
4. 三份主 JAR 不内嵌 Fibra、PF4J、Reactive Streams、Reactor 或 SLF4J；
5. provider/consumer 的 properties 只声明 compatible contract 范围，不声明彼此依赖；
6. provider 的 `lib/` 携带 Commons Text 私有依赖，consumer 包不携带，并在 consumer 入口内实际确认该类不可见；
7. Host shaded JAR 不包含 contract/provider/consumer 类型。

provider 与 consumer 都能调用 `Greeting` 本身即证明它们从 contract dependency ClassLoader 得到同一个接口类型；若各自复制 contract，服务读取会在真实进程暴露类型不等或链接错误。

## 4. 运行验收

脚本把三份 v1 ZIP 解压为已安装目录，复制模板自己的 YAML，再启动独立 Host JAR。YAML 按 consumer-first 顺序声明两个 provider、两个 consumer，分别使用 isolate `one/two`；验收覆盖：

- consumer 在 provider 到位前稳定等待，最终四个 entry 全部 `ACTIVE`；
- 两组服务不串线；
- config-only 更新保持 provider Fibra uid；
- 失败配置更新保持上一 snapshot 对象、配置文件字节和服务值；
- consumer 运行时看不到 provider 私有 Commons Text；
- Host 用一次 `applyArtifacts(List.of(contractV2, providerV2, consumerV2))` 完成关联升级；
- v1 的 contract 范围与 v2 不兼容，只有三候选共同提交后的 prospective 图有效；
- 更新后四个 entry 用新 ClassLoader 重建，服务值保持，三个安装目录版本均为 `2.0.0`。

只有普通 core 进程输出 `EXTERNAL_CORE_CONSUMER_OK`，插件 Host 输出 `EXTERNAL_CONFIG_LOADER_CONSUMER_OK`，且所有静态包检查通过，脚本才输出最终成功标记。

## 5. 结论边界

该门禁证明当前五个生产制品能被另一个 Java 21 Maven 工程仅通过坐标消费，并能以标准 ZIP 运行完整多插件图。它不表示坐标已经发布到 Maven Central，不验证恶意插件隔离，也不替代全 reactor 测试和可复现构建。首次使用空 Maven 仓库下载第三方依赖需要网络；CI 顺序固定为全量 `clean verify`、可复现比较、仓库外脚本。
