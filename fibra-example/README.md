# Fibra 可运行示例

本目录只保存可运行的完整场景，不是用户插件模板。创建独立插件工程必须使用发布 `artifact` `com.sstlfsj:fibra-plugin-archetype`；模板生成、构建和真实 Engine 装载由 archetype 模块自己的集成测试保证。

```text
engine/
  contract-plugin    共享契约包
  provider-plugin    v1/v2/broken provider artifact
  consumer-plugin    v1/v2 consumer artifact
  application        多插件联合部署、升级、降级和失败回滚

spring-boot/
  application-api    Spring Boot application 与插件共享的 SPI
  provider-plugin    提供 SPI 实现的标准插件包
  application        starter 接入、deployment 上传与服务调用
```

Engine 场景中的 v2 和 broken `artifact` 是事务验收输入，不代表每个用户插件都必须采用 provider/consumer 分类。provider 和 consumer 只是该场景的业务角色；插件的二进制依赖由 PF4J 图表达，运行期服务等待由 Fibra 配置和 `ServiceKey` 表达。

运行纯 Java Engine 场景：

```bash
mvn -pl fibra-example/engine/application -am verify
```

运行 Spring Boot 场景：

```bash
mvn -pl fibra-example/spring-boot/application -am verify
```
