# plugin-package-format Specification

## Purpose
TBD - created by archiving change standardize-plugin-packages. Update Purpose after archive.
## Requirements
### Requirement: 唯一安装目录格式
系统 SHALL 只把 `plugins/<plugin-id>/plugin.properties` 与 `plugins/<plugin-id>/lib/*.jar` 识别为已安装插件包，完整字段与路径规则见设计文档第 4 节。

#### Scenario: 标准目录被装载
- **WHEN** 插件根目录直接子目录名等于 `plugin.id`，且 properties 与 `lib/` 满足协议
- **THEN** `loadArtifacts()` 装载并解析该制品

#### Scenario: 直接 JAR被拒绝
- **WHEN** 插件根目录直接包含旧模型插件 JAR
- **THEN** `loadArtifacts()` 在创建该 JAR ClassLoader 前以 `VALIDATE` 失败，且不存在兼容装载路径

### Requirement: 唯一候选 ZIP格式
系统 SHALL 只接受包含一个顶层 `<plugin-id>/` 标准目录的 ZIP候选，并 SHALL 在解压时拒绝绝对路径、`..`、越界目标、符号链接、非普通文件/目录条目、多个顶层目录和非标准层级。

#### Scenario: 标准 ZIP进入预检
- **WHEN** ZIP只有一个名称等于 `plugin.id` 的顶层目录且目录内容有效
- **THEN** 系统复制候选后从内部事务区完成检查，外部 ZIP保持不变

#### Scenario: 路径穿越被拒绝
- **WHEN** ZIP条目试图写到内部事务区之外
- **THEN** apply 以 `VALIDATE` 失败，当前安装目录和运行态不变

### Requirement: Properties 是唯一描述真源
系统 SHALL 只从根 `plugin.properties` 读取 PF4J 制品描述，只允许设计文档第 4.2 节列出的描述键，并 MUST 拒绝非空 `plugin.class`、`plugin.requires` 和任何未列出的键；主 JAR Manifest 中的 PF4J 描述不得覆盖它。

#### Scenario: Plugin-Class 被拒绝
- **WHEN** properties 声明非空 `plugin.class`
- **THEN** 包在结构预检期失败且不会创建活动 ClassLoader

#### Scenario: Manifest 描述不参与身份
- **WHEN** 主 JAR Manifest 与 properties 声明不同的插件 ID或版本
- **THEN** 系统只使用 properties 身份，并按包校验规则处理主 JAR，而不建立第二描述源

### Requirement: 主 JAR与私有依赖边界
每个包 SHALL 恰好包含一个 `lib/<plugin-id>-<plugin-version>.jar` 主 JAR；其余 `lib` 直接子级只允许私有第三方 JAR。所有 JAR MUST NOT 内嵌设计文档第 4.3 节列出的共享运行时类。

#### Scenario: 私有依赖参与当前 ClassLoader
- **WHEN** 标准包的 `lib/` 含主 JAR和私有第三方 JAR
- **THEN** 目录 loader 把它们加入同一个插件 ClassLoader，且不使用 Manifest `Class-Path`

#### Scenario: 任一 JAR内嵌共享类
- **WHEN** 主 JAR或私有依赖 JAR包含 Fibra、PF4J、Reactive Streams、Reactor 或 SLF4J 共享类
- **THEN** 包以 `VALIDATE` 失败

### Requirement: 制品入口类型
系统 SHALL 仅根据主 JAR自身扩展索引把制品分类为 contract-only 或 executable；完整类型规则见设计文档第 5.1 节。

#### Scenario: Contract-only 被依赖
- **WHEN** 主 JAR没有自身 Fibra 入口且其他插件声明了对它的 PF4J 依赖
- **THEN** 它可以 load/resolve/start，但 `configType` 与 `mount` 明确拒绝

#### Scenario: Executable 创建多个 entry
- **WHEN** 主 JAR恰好有一个有效自身入口
- **THEN** 同一 `pluginId` 可以创建多个不同 `entryId`

#### Scenario: 多入口被拒绝
- **WHEN** 主 JAR索引含多个类或非 Fibra 扩展
- **THEN** 整个包在预检阶段失败

### Requirement: 同版本内容不可变
系统 SHALL 对解压后协议文件计算规范 SHA-256；同 ID同版本同摘要 SHALL 为 no-op，同 ID同版本不同摘要 MUST 被拒绝。

#### Scenario: 重复相同候选
- **WHEN** 候选与当前安装包 ID、版本和规范摘要均相同
- **THEN** apply 返回该批次结果但不关闭 ClassLoader、不重建 entry、不交换目录

#### Scenario: 同版本不同内容
- **WHEN** 候选与当前安装包 ID和版本相同但规范摘要不同
- **THEN** apply 以 `VALIDATE` 失败

#### Scenario: 同版本普通重建改变 JAR 字节
- **WHEN** 插件作者以相同版本重新构建且 JAR 时间戳、条目顺序或生成内容导致摘要变化
- **THEN** 系统仍按同版本不同内容拒绝；作者必须提升版本，或使用可复现构建得到原摘要

### Requirement: 重复业务契约是显式残留风险
系统 SHALL 扫描并拒绝设计文档第 4.3 节列出的共享运行时类，但 MUST NOT 以启发式包名猜测任意业务 contract；插件作者文档 SHALL 把跨插件 `ClassCastException`、`LinkageError` 和同限定名类型不相等指向重复携带 contract 的诊断路径。

#### Scenario: 两个插件各自携带相同业务接口
- **WHEN** provider 和 consumer 的私有 `lib/` 各自包含同限定名 contract 类型且没有独立 contract dependency
- **THEN** 仓库外 ClassLoader 验收必须暴露类型不相等，诊断文档要求将 contract 拆为独立插件或宿主公共 API，不增加运行期兼容桥

### Requirement: 契约归属不绑定 provider 角色
系统 SHALL 支持宿主公共 API、独立 contract-only 插件和 executable 内部契约三种归属，并 MUST NOT 根据 provider/consumer 业务角色改变安装层级。

#### Scenario: 中间插件同时消费和提供
- **WHEN** 一个插件依赖上游 contract并向下游注册服务
- **THEN** 它仍以单一 `pluginId` 扁平安装，层次只由依赖图和 Fibra Context/服务图表达

### Requirement: 可直接构建的仓库外插件模板
仓库 SHALL 维护一份不属于 Fibra reactor、也不继承 Fibra parent 的独立插件工程，同时作为用户模板与黑盒验收输入；不得另存一份未被同一验收构建的脚手架。

#### Scenario: 用户按模板构建标准包
- **WHEN** 用户在独立目录按模板 README 执行 `mvn verify`
- **THEN** 工程通过公开 Maven 坐标编译并产出 contract-only、executable 和依赖示例的标准 ZIP，不读取 Fibra 源码或工作树 classpath

#### Scenario: 开发版本隔离验收模板
- **WHEN** Fibra 仓库执行仓库外验证脚本
- **THEN** 脚本复制同一模板并只覆盖开发版本与临时仓库参数，在隔离 Maven 仓库中完成构建和运行，不修改模板源文件

