## Why

仓库中的 example 和 external-consumer 用于项目自身验收，不是用户可直接生成的插件工程。用户若需要手工复制 POM、assembly、plugin.properties、contract scope 和部署布局，极易把 Fibra/PF4J/contract 复制进私有 `lib/` 或生成无法装载的 ZIP。

## What Changes

- 新增并发布 `fibra-plugin-archetype`，使用 Maven 官方 `maven-archetype` packaging。
- 生成完全脱离 Fibra reactor、不继承 Fibra parent 的多模块插件项目。
- 生成项目集中管理 Fibra 版本，使用标准 contract + implementation 分层、Maven Assembly 和标准 plugin ZIP。
- 生成项目包含配置与 deployment package 示例，并可直接执行 `mvn verify`。
- archetype 构建通过官方 integration-test 生成并验证项目；仓库外门禁再使用隔离仓库和已发布 Fibra 制品完成装载。
- 可发布制品在 engine 与 Spring change 完成后的九个基础上增加为十个。

## Capabilities

### New Capabilities

- `plugin-project-archetype`：定义生成命令、输入属性、项目结构、依赖 scope、打包、测试和仓库外验收。

## Impact

- 新增 `fibra-plugin-archetype` 可发布开发工具模块。
- 更新根 reactor、dependencyManagement、发布门禁、README、release 和插件作者文档。
- external-consumer 继续作为黑盒验收，不与 archetype 生成源码混用。

