# 仓库外消费方模板

本目录保存 `scripts/verify-external-consumer.sh` 使用的独立 Maven 工程模板。它不属于 Fibra reactor，不继承 Fibra parent，也不能依赖 Fibra 源码或本地编译目录。

工程固定包含四个模块：

- `core-app`：与插件链独立，只验证普通 Java 应用通过坐标消费 `fibra-core`；
- `provider-plugin`：拥有 `Greeting` 契约并提供服务；
- `consumer-plugin`：编译期以 `provided` scope 依赖 provider，运行时通过 PF4J `Plugin-Dependencies` 使用 provider ClassLoader 中的同一契约；
- `host`：只依赖 `fibra-loader-pf4j`，不依赖两个插件；脚本在运行前把两个插件 JAR 放入临时插件目录。

Host 会依次验证两个插件加载与启动、停止 provider 时级联停止 consumer、从 consumer 重启时自动恢复 provider，以及卸载 provider 时级联卸载 consumer。只有全部状态、服务注册和释放断言都成立，才会输出 `EXTERNAL_MULTI_PLUGIN_CONSUMER_OK`。

不要修改版本哨兵，也不要在本目录直接执行 Maven。请始终从 Fibra 仓库根目录执行：

```bash
scripts/verify-external-consumer.sh
```

脚本会从根 `pom.xml` 读取当前 Java 编译版本、Fibra、PF4J、SLF4J 和 Maven 插件版本，把本目录复制到系统临时目录，再向复制后的独立工程传入版本。完整验收范围、隔离边界和结论含义见 `docs/release.md` 的“仓库外消费验收”；模块与 ClassLoader 关系图见 `docs/superpowers/specs/2026-08-22-fibra-external-multi-plugin-verification-design.md`。
