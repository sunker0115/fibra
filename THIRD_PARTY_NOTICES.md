# 第三方软件说明

Fibra 的内核语义参考 `@cordisjs/core`，其 MIT 许可证原文随仓库和发布 JAR 保存在 `LICENSES/Cordis-MIT.txt`。

运行时使用 Reactor Core、Reactive Streams 与 SLF4J API。配置、Java manifest 和 Node manifest 使用 Jackson 3、Jackson YAML 及其传递依赖 SnakeYAML Engine。Spring 适配使用 Spring Framework 与 Spring Boot。本地文件系统和子进程插件私有打包 JNA，分别用于调用 Windows 的文件安全、原子替换及 Job Object API；Fibra 按 JNA 提供的 Apache License 2.0 选项使用和再分发。上述组件按各自许可证分发。

插件配置结构校验使用 networknt JSON Schema Validator 及其传递依赖 itu，二者均按 Apache License 2.0 分发。Fibra 的配置 profile 在调用校验器前限制可用关键词和输入预算；支持范围不等于校验器的完整 JSON Schema 能力。

JSON Schema Validator 的上游 NOTICE：

```text
JSON Schema Validator
Copyright (c) 2016 and onwards Network New Technologies Inc.

// ------------------------------------------------------------------
// NOTICE file corresponding to the section 4d of The Apache License,
// Version 2.0, in this case for
// ------------------------------------------------------------------

Apache Commons Validator
Copyright 2001-2023 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

Internet Time Utility (itu) 发布源码中的版权声明：

```text
Copyright (C) 2017 Morten Haraldsen (ethlo)
Copyright (C) 2017 - 2024 Morten Haraldsen (ethlo)
Copyright (C) 2017 - 2025 Morten Haraldsen @ethlo
Licensed under the Apache License, Version 2.0.
```

测试使用 JUnit 与 Reactor Test。性能基准使用 JMH；`fibra-benchmarks` 不发布，也不进入任何运行时制品。

具体版本由根 `pom.xml` 的 properties 与 dependencyManagement 统一锁定。
