# Fibra 分发黑盒验收工程

本目录是不加入 Fibra reactor、也不继承 Fibra parent 的独立 Maven 工程。它只由仓库根目录的 `scripts/verify-distribution.sh` 复制到临时目录后构建和运行，用来证明 Fibra 发布 `artifact` 可以仅通过 Maven 坐标被仓库外项目消费。

本目录不是用户插件模板。用户必须使用发布 `artifact` `com.sstlfsj:fibra-plugin-archetype` 创建插件工程；分发脚本还会从临时远端仓库调用该 archetype，构建生成项目并检查标准插件包和 deployment 包。

```text
core-application          直接消费 fibra-core 的纯 Java 进程
contract-plugin           只拥有共享 Greeting 类型的 contract-only 包
provider-plugin           executable provider，携带私有 Commons Text
consumer-plugin           executable consumer，不复制 provider 私有依赖
engine-application        只消费 fibra-engine 的多插件联合部署进程
spring-boot-application   只直接消费 starter 和 Spring Boot 的非 Web 进程
```

`provider-plugin` 和 `consumer-plugin` 只以 `provided` scope 二进制依赖 `contract-plugin`；consumer 不依赖 provider。`engine-application` 不把 contract、provider 或 consumer 类放进自身 classpath。Spring Boot application 从容器取得自动装配的 `FibraEngine`、root `Context` 和 `FibraServiceBridge`，并验证 Spring 生命周期负责启动和关闭 Engine。

在 Fibra 仓库根目录执行：

```bash
scripts/verify-distribution.sh
```

脚本使用互相分离的临时远端仓库、生产者本地仓库和消费者本地仓库，完成十个发布 `artifact` 的附件检查、来源和字节核对、标准插件包结构检查、私有依赖隔离、双 isolate 服务图、配置失败恢复、缺失关联包时整批拒绝、v1/v2 关联升级、Spring Boot 自动装配，以及已部署 archetype 的仓库外生成与构建。成功日志必须包含：

```text
FIBRA_DISTRIBUTION_CORE_OK
FIBRA_DISTRIBUTION_ENGINE_OK
FIBRA_DISTRIBUTION_SPRING_BOOT_OK
FIBRA_DISTRIBUTION_ARCHETYPE_OK
```

脚本通过只代表当前工作树生成的 `artifact` 可被隔离工程消费，不代表这些坐标已经发布到公共 Maven 仓库。
