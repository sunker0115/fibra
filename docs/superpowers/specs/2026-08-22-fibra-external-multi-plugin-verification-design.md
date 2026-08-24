# Fibra 仓库外多插件验收设计

状态：已按 `0.4.0-SNAPSHOT` Engine 架构更新。本文只定义 `verification/external-consumer` 黑盒 fixture；用户插件脚手架的唯一入口是 `fibra-plugin-archetype`。

## 1. 隔离边界

独立工程不加入 Fibra reactor、不继承 Fibra parent、不引用 Fibra 源码、工作树 `target/classes`、`systemPath` 或符号链接。脚本先把十个可发布制品部署到临时文件仓库，再以另一个空本地仓库构建 fixture；fixture 实际解析六个框架中立制品，主 JAR和 POM必须逐字节等于临时远端制品并带 Maven来源记录。

fixture 固定为五个模块：

```text
core-app                         普通 Java 坐标消费，与插件链无关

contract-plugin                 contract-only，拥有 Greeting
       ↑                 ↑
       │ PF4J 二进制依赖  │ PF4J 二进制依赖
provider-plugin          consumer-plugin
       │                 │
       └── Fibra 服务 ───┘

host                            只依赖 fibra-engine 的黑盒宿主
```

`provider`、`consumer` 是本 fixture 的业务角色，不是 Fibra 通用插件类别。PF4J 图只包含两条到 contract 的二进制依赖；consumer 不二进制依赖 provider。Fibra 配置图通过 `inject` 和 isolate 表达运行期服务等待，两张图保持正交。

## 2. 标准包与 ClassLoader 门禁

contract/provider/consumer 都生成 v1、v2 标准 ZIP。每份 ZIP 只有一个 `plugin.id` 顶层目录、根 `plugin.properties`、固定命名主 JAR 和可选私有依赖；不得使用直接 JAR、Manifest 身份或 `Plugin-Class`。

脚本逐包检查：

1. 根目录、ID、版本和主 JAR 命名一致；
2. contract 只拥有 `Greeting` 且没有入口索引；
3. provider/consumer 各自只有自身入口，不复制 `Greeting`；
4. 主 JAR 不内嵌 Fibra、PF4J、Reactive Streams、Reactor 或 SLF4J；
5. provider/consumer 只声明 contract 版本范围，不声明彼此依赖；
6. provider 私有携带 Commons Text，consumer 包和 Host classpath 都不含它；
7. Host shaded JAR 不包含 contract/provider/consumer 类型。

provider 与 consumer 都能调用 `Greeting` 证明它们从 contract dependency ClassLoader 获得同一个接口类型。出现同限定名类型不能转换、`ClassCastException` 或 `LinkageError` 时，首先检查是否把 contract 重复打入多个私有 `lib/`；不得增加反射兼容桥。

## 3. 运行验收

Host 使用 `FibraEngine` 初载 v1 插件目录和真实 YAML，验证：

- consumer-first 配置最终形成两个 provider 和两个 consumer 的四个 ACTIVE entry；
- 两组 isolate 服务不串线；
- config-only 更新保持 provider Fibra uid；
- 失败配置更新保持上一 snapshot、配置文件字节和服务值；
- consumer 看不到 provider 私有 Commons Text；
- 一次多制品变更完成 contract/provider/consumer v2 关联升级；
- v1 与 v2 contract 范围不兼容，单包不能误升级，完整候选图才能提交；
- 更新后四个 entry 用新 ClassLoader 重建且服务值保持。

只有普通 core 进程与插件 Host 都输出约定成功标记，且所有静态检查通过，脚本才成功。

## 4. 结论边界

该门禁证明当前十个制品可部署、六个框架中立制品可被另一个 Java 21 Maven 工程仅通过坐标消费，并能运行完整多插件图。它不表示坐标已发布到 Maven Central，不验证不可信代码沙箱，也不替代全 reactor、archetype 生成验收和可复现构建。CI顺序固定为完整 `mvn clean verify`、可复现比较、仓库外脚本。
