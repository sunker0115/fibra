# 第三方软件说明

Fibra 的内核语义参考 `@cordisjs/core`，其 MIT 许可证原文随仓库和发布 JAR 保存在 `LICENSES/Cordis-MIT.txt`。

运行时使用 Reactor Core、Reactive Streams 与 SLF4J API。配置、Java manifest 和 Node manifest 使用 Jackson 3、Jackson YAML 及其传递依赖 SnakeYAML Engine。Spring 适配使用 Spring Framework 与 Spring Boot。本地文件系统和子进程插件私有打包 JNA，分别用于调用 Windows 的文件安全、原子替换及 Job Object API；Fibra 按 JNA 提供的 Apache License 2.0 选项使用和再分发。上述组件按各自许可证分发。

测试使用 JUnit 与 Reactor Test。性能基准使用 JMH；`fibra-benchmarks` 不发布，也不进入任何运行时制品。

具体版本由根 `pom.xml` 的 properties 与 dependencyManagement 统一锁定。
