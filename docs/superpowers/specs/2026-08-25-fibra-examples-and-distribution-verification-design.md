# Fibra 示例与分发验收设计

状态：已确认，作为 `0.4.0-SNAPSHOT` 示例目录和仓库外分发验收的唯一专题权威源，可直接实施。

## 1. 目标

Fibra 固定三条互不替代的使用与验收边界：

1. `fibra-plugin-archetype` 是用户创建标准插件工程的唯一模板；
2. `fibra-example` 保存可运行的 Engine 与 Spring Boot 完整场景；
3. `verification/distribution` 从隔离 Maven 仓库消费发布制品，证明分发结果可被仓库外项目使用。

本次只重构非发布示例、非发布验收 fixture、脚本和文档，不修改 Fibra 公开 API、运行时语义、插件包协议、deployment 协议、Spring 属性或十制品发布边界。不提供旧目录、旧 artifactId、旧类名、旧脚本名或旧成功标记的兼容入口。

## 2. 开源参照与取舍

Maven Archetype 官方使用 `archetype:integration-test` 生成并构建独立项目；PF4J 将 application、共享 API 和多个 demo plugin 作为场景示例保存；Spring Boot 将可编译、可测试的 sample 与依赖入口分开维护。Fibra 吸收三点：模板必须由官方 Archetype 流程生成，示例必须真实运行，发布制品必须在 reactor 外独立消费。

Fibra 不把三者合并。把 archetype 输出提交为 example 会产生第二个模板真源；用 archetype 生成 verification 会让模板和验收共享同一种错误；把 verification 加入 reactor 会失去仓库外坐标消费证明。

## 3. 最终目录与命名

### 3.1 用户模板

`fibra-plugin-archetype` 保持现有四模块输出：

```text
generated-plugin/
├── plugin-api/
├── plugin-impl/
├── config/
└── deployment/
```

生成结果只存在于构建临时目录，不复制进 `fibra-example`。archetype 模块内的官方 integration-test 负责模板结构和构建，Failsafe 负责真实 `FibraEngine` 装载。

### 3.2 仓内示例

```text
fibra-example/
├── README.md
├── pom.xml
├── engine/
│   ├── contract-plugin/
│   ├── provider-plugin/
│   ├── consumer-plugin/
│   └── application/
└── spring-boot/
    ├── application-api/
    ├── provider-plugin/
    └── application/
```

artifactId 固定为：

- `fibra-example-engine-contract-plugin`；
- `fibra-example-engine-provider-plugin`；
- `fibra-example-engine-consumer-plugin`；
- `fibra-example-engine-application`；
- `fibra-example-spring-boot-api`；
- `fibra-example-spring-boot-plugin`；
- `fibra-example-spring-boot-application`。

模块名、Java 类名、包名、系统属性和日志成功标记中不再使用 `host`。架构 prose 可以使用“宿主应用”描述角色，但它不是制品或模块名称。

### 3.3 仓库外分发验收

```text
verification/
└── distribution/
    ├── README.md
    ├── pom.xml
    ├── settings.xml
    ├── build/
    ├── core-application/
    ├── contract-plugin/
    ├── provider-plugin/
    ├── consumer-plugin/
    ├── engine-application/
    └── spring-boot-application/
```

根 artifactId 固定为 `fibra-distribution-verification`。它不加入 Fibra reactor、不继承 `com.sstlfsj:fibra`、不引用工作树 `target/classes`、`systemPath`、绝对路径或符号链接。脚本固定为 `scripts/verify-distribution.sh`。

## 4. 示例能力不变量

目录重构后必须保留以下全部能力：

1. Engine application 只依赖 `fibra-engine`，不把 contract/provider/consumer 类型放入应用 classpath；
2. contract-only 插件拥有共享 `Greeting` 类型且没有 executable entrypoint；
3. provider 与 consumer 只二进制依赖 contract，consumer 不二进制依赖 provider；
4. v1、v2 标准包完成联合安装、升级、降级、再次升级；
5. broken provider deployment 失败后，磁盘版本、配置、entry 和服务值保持 v2；
6. 独立 Java 进程可执行并输出 v1、v2 服务结果；
7. Spring Boot application 只通过 `fibra-spring-boot-starter` 取得 Fibra 运行时；
8. Spring Boot 场景继续覆盖上传暂存、显式 apply、服务调用和状态查询；
9. 示例插件不进入 Spring BeanFactory，Spring controller 只属于 application。

示例 README 必须明确：创建用户插件只能使用 archetype；Engine 场景是多插件依赖与事务部署示例；Spring Boot 场景是应用接入示例；v2/broken 制品是验收输入，不是模板结构。

## 5. 分发验收能力不变量

`scripts/verify-distribution.sh` 必须在临时目录完成：

1. 把十个生产制品部署到空的临时远端文件仓库；
2. 检查每个制品只有一个 POM、主 JAR、sources JAR 和 Javadoc JAR；
3. 使用另一个空本地仓库构建 `verification/distribution`；
4. 验证六个框架中立制品来自临时远端仓库且字节一致；
5. 运行 core application；
6. 检查 contract/provider/consumer 的 v1/v2 包结构、依赖范围、共享契约和私有依赖隔离；
7. 运行 engine application，覆盖 consumer-first、isolate、config-only 更新、失败配置恢复、完整关联升级和不完整升级拒绝；
8. 构建并启动只依赖 starter 的 Spring Boot application，确认 `FibraEngine`、root 和 bridge 自动装配且应用能正常关闭；
9. 从临时远端仓库调用已部署的 `fibra-plugin-archetype` 生成独立项目，再用同一个隔离仓库构建生成项目并检查标准 plugin/deployment ZIP；
10. 拒绝 fixture 或生成项目泄漏 Fibra parent、`${revision}`、reactor 输出、`systemPath` 或仓库绝对路径。

内部 archetype IT 继续验证模板细节和 Engine 装载；仓外 archetype smoke 只证明已分发 archetype 坐标及其生成项目可独立解析，不复制内部断言。

## 6. Spring Boot 分发验收

`spring-boot-application` 是最小非 Web 应用，不引入 `spring-web`，只直接依赖：

- `com.sstlfsj:fibra-spring-boot-starter`；
- Spring Boot 核心启动依赖；
- 运行期日志实现。

应用通过属性使用临时 installed root 和 config location，关闭两个 watcher。启动后必须从 Spring 容器取得 `FibraEngine`、root `Context` 和 `FibraServiceBridge`，确认 Engine 已运行，然后正常关闭。它不复制 example 的 Web deployment 场景。

## 7. 测试与发布边界

- `fibra-example` 继续参加默认 reactor，但全部模块跳过远程发布；
- `verification/distribution` 永远不进入默认 reactor，只由分发脚本运行；
- `fibra-plugin-archetype` 继续作为第十个发布制品；
- `fibra-parity-tests` 必须锁定新的 example module 集合、distribution 独立性、模块 parent 和依赖边界；
- 可复现发布脚本的十制品集合不变；
- 完整验收顺序固定为 `mvn clean verify`、`scripts/verify-reproducible-release.sh`、`scripts/verify-distribution.sh`。

## 8. 文档真源

本文是 example 与 distribution verification 的唯一专题权威源。上游 Engine 架构、根 README、发布文档和 API 文档只保存摘要与本文指针。旧文档中“`verification/external-consumer` 是用户模板”“external consumer 固定五模块”“只验证六个制品即可代表全部外部消费边界”等过时内容直接删除或整段重写，不保留历史兼容表述。

## 9. 完成标准

以下条件同时满足才算收尾：

- 仓库中不存在 `fibra-example-*host*`、`verification/external-consumer`、`verify-external-consumer.sh` 及对应 Java package/class/system property 引用；
- example 两个场景的原有断言全部保留并通过；
- distribution 原有静态检查与运行断言全部保留，并新增 Spring Boot 和 archetype 仓外消费；
- 根 reactor、可复现构建和 distribution 黑盒脚本全部通过；
- 文档不存在互相冲突的当前设计；
- 工作树只包含本设计可追溯的改动。
