# plugin-project-archetype Specification

## Purpose
TBD - created by archiving change publish-plugin-archetype. Update Purpose after archive.
## Requirements
### Requirement: 用户可从已发布 Archetype 生成独立插件项目

系统 SHALL 发布 `com.sstlfsj:fibra-plugin-archetype`，接受 groupId、artifactId、version、package、pluginId 和 Fibra version，生成不依赖 Fibra源码仓库的 Maven 多模块项目。

#### Scenario: 命令行生成
- **WHEN** 用户以非交互 `mvn archetype:generate` 提供全部必填属性
- **THEN** 生成根、`plugin-api`、`plugin-impl`、`config`、`deployment` 和 README，项目可直接执行 `mvn verify`

#### Scenario: 检查独立性
- **WHEN** 在只包含已安装 Fibra 制品的隔离本地仓库构建生成项目
- **THEN** 构建不读取 Fibra parent、`${revision}`、reactor 输出、systemPath 或源码仓库脚本

### Requirement: 生成项目遵守标准插件边界

生成项目 SHALL 使用 provided scope 消费 Fibra、PF4J 和共享 contract，并只把插件私有运行库放入 `lib/`。生成的 `plugin.properties` MUST NOT 含 `Plugin-Class`。

#### Scenario: 构建标准插件包
- **WHEN** 执行生成项目的 verify
- **THEN** 产出包含 `plugin.properties` 与排序后 `lib/*.jar` 的标准 ZIP，通过 package inspector 并可由 `FibraEngine` 装载

### Requirement: Archetype 自身持续验证生成结果

Archetype SHALL 在自身 verify 生命周期使用 Maven Archetype Plugin integration-test 生成样例并执行样例 verify。

#### Scenario: 模板漂移
- **WHEN** 模板 POM、源码、assembly 或 metadata 的修改导致生成项目不能编译或打包
- **THEN** archetype 模块 verify 失败，不允许发布失效模板
