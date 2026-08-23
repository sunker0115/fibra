# 仓库外消费方模板

本目录保存 `scripts/verify-external-consumer.sh` 使用的独立 Maven 工程模板。它不属于 Fibra reactor，不继承 Fibra parent，也不能依赖 Fibra 源码或本地编译目录。

工程固定包含四个模块：

- `core-app`：与插件链独立，只验证普通 Java 应用通过坐标消费 `fibra-core`；
- `provider-plugin`：拥有 `Greeting` 契约并提供服务；
- `consumer-plugin`：编译期以 `provided` scope 依赖 provider，运行时通过 PF4J `Plugin-Dependencies` 使用 provider ClassLoader 中的同一契约；
- `host`：只依赖 `fibra-loader-config`，不依赖两个插件；脚本在运行前把两个插件 JAR 放入临时插件目录，并生成真实 YAML 配置树。

Host 会从 YAML 创建两个 provider 实例和两个 consumer 实例，验证 consumer 先声明依赖仍能在 provider 到位后激活、config-only 更新保持 provider Fibra 身份，以及失败更新同时保留上一份运行态、snapshot 和配置文件。只有全部断言成立，才会输出 `EXTERNAL_CONFIG_LOADER_CONSUMER_OK`。

不要修改版本哨兵，也不要在本目录直接执行 Maven。请始终从 Fibra 仓库根目录执行：

```bash
scripts/verify-external-consumer.sh
```

脚本会从根 `pom.xml` 读取当前 Java 编译版本、Fibra、PF4J、SLF4J 和 Maven 插件版本，把本目录复制到系统临时目录，再向复制后的独立工程传入版本。完整验收范围、隔离边界和结论含义见 `docs/release.md` 的“仓库外消费验收”；模块与 ClassLoader 关系图见 `docs/superpowers/specs/2026-08-22-fibra-external-multi-plugin-verification-design.md`。
