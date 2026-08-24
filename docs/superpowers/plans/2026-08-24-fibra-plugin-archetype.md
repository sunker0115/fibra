# Fibra Plugin Archetype 实施计划

状态：已依据 `2026-08-24-fibra-engine-architecture.md` 与 OpenSpec `publish-plugin-archetype` 冻结，可直接实施。

## 目标

发布 `com.sstlfsj:fibra-plugin-archetype`。它生成不继承 Fibra parent 的独立 Maven 多模块工程，包含 `plugin-api`、`plugin-impl`、`config`、`deployment` 和 README；生成后一次 `mvn verify` 同时产出标准插件包与 deployment 包。

## 实施顺序

1. 在根 reactor、properties、pluginManagement 和 dependencyManagement 中登记 archetype，保持所有版本单一真源。
2. 建立 Maven Archetype 3.4.1 metadata，冻结 `groupId`、`artifactId`、`version`、`package`、`pluginId`、`fibraVersion` 六个输入。
3. 生成独立根 POM；Fibra/PF4J/Maven 插件版本集中在 properties，Fibra模块集中在 dependencyManagement。
4. `plugin-api` 生成共享服务契约和 contract-only 标准 ZIP；`plugin-impl` 生成 typed config、entrypoint、无 `Plugin-Class` 描述符和标准 ZIP。
5. `config` 保存可直接加载的配置；`deployment` 使用 Maven/Ant 标准任务复制两个 ZIP、生成 SHA-256 清单并打包联合 deployment，不手写 Java ZIP组件。
6. 使用 archetype 官方 `integration-test` 生成项目并执行 `verify`；另由仓库外门禁检查独立性、包结构和 engine 装载。
7. 更新十制品发布门禁、API/README/release/插件作者文档，执行全 reactor、外部消费与可复现构建。

## 验收命令

```bash
$MVN -pl fibra-plugin-archetype -am clean verify
scripts/verify-external-consumer.sh
scripts/verify-reproducible-release.sh
$MVN clean verify
```

## 禁止项

- 生成项目不得继承 `com.sstlfsj:fibra`、使用 `${revision}`、`systemPath` 或 Fibra 仓库源码路径。
- Fibra、PF4J 和共享 contract 不得复制进 executable 插件 `lib/`。
- `plugin.properties` 不得声明 `Plugin-Class`。
- archetype 不进入任何运行时依赖链。
