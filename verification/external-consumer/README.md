# 仓库外消费方模板

本目录保存 `scripts/verify-external-consumer.sh` 使用的独立 Maven 工程模板。它不属于 Fibra reactor，不继承 Fibra parent，也不能依赖 Fibra 源码或本地编译目录。

不要修改版本哨兵，也不要在本目录直接执行 Maven。请始终从 Fibra 仓库根目录执行：

```bash
scripts/verify-external-consumer.sh
```

脚本会从根 `pom.xml` 读取当前 Java 编译版本、Fibra、PF4J、SLF4J 和 Maven 插件版本，把本目录复制到系统临时目录，再向复制后的独立工程传入版本。完整验收范围、隔离边界和结论含义见 `docs/release.md` 的“仓库外消费验收”。
