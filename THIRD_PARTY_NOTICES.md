# 第三方说明

Fibra 的行为基线来自 DeepSeek Harness 内置 Cordis 4.0.1。Cordis 使用 MIT License；固定源码版本与文件摘要见 `docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md`，随本仓库保留的原许可证见 `LICENSES/Cordis-MIT.txt`。

本工程运行时使用 Reactor Core、SLF4J API；`artifact` 装载使用 PF4J，并使用 Apache Commons Compress 读取 ZIP 中央目录和 Unix 符号链接元数据。Commons Compress 的运行时传递依赖 Commons Codec、Commons IO 与 Commons Lang 同样由根 POM 锁定；上述 Apache Commons 组件均使用 Apache License 2.0。配置装载使用 Jackson Databind、Jackson YAML 及其传递依赖 SnakeYAML Engine；示例宿主使用 SLF4J Simple。可选适配 `artifact` `fibra-spring-boot-starter` 运行时依赖 Spring Boot AutoConfigure 及其传递的 Spring Framework 组件，均使用 Apache License 2.0；Spring 只存在于该 `artifact` 内，不进中立内核/loader `artifact`。测试使用 JUnit、Reactor Test 与 Awaitility；内核性能基准模块 `fibra-benchmarks` 使用 JMH（GPLv2 with Classpath Exception），参加默认 reactor，但不发布也不进任何分发 `artifact`。具体版本由根 `pom.xml` 的 properties 和 dependencyManagement 固定。
