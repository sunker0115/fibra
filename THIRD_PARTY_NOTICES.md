# 第三方说明

Fibra 的行为基线来自 DeepSeek Harness 内置 Cordis 4.0.1。Cordis 使用 MIT License；固定源码版本与文件摘要见 `docs/superpowers/references/2026-08-21-fibra-cordis-mapping.md`，原许可证位于对应真源的 `vendor/cordis/LICENSE`。

本工程运行时使用 Reactor Core、SLF4J API；制品装载使用 PF4J；配置装载使用 Jackson Databind、Jackson YAML 及其传递依赖 SnakeYAML Engine；示例宿主使用 SLF4J Simple。测试使用 JUnit、Reactor Test 与 Awaitility。具体版本由根 `pom.xml` 的 properties 和 dependencyManagement 固定。
