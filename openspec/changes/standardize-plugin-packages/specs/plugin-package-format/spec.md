## ADDED Requirements

### Requirement: 唯一安装目录格式
系统 SHALL 只把 `plugins/<plugin-id>/plugin.properties` 与 `plugins/<plugin-id>/lib/*.jar` 识别为已安装插件包，完整字段与路径规则见设计文档第 4 节。

#### Scenario: 标准目录被装载
- **WHEN** 插件根目录直接子目录名等于 `plugin.id`，且 properties 与 `lib/` 满足协议
- **THEN** `loadArtifacts()` 装载并解析该制品

#### Scenario: 直接 JAR被拒绝
- **WHEN** 插件根目录直接包含旧模型插件 JAR
- **THEN** `loadArtifacts()` 在创建该 JAR ClassLoader 前以 `VALIDATE` 失败，且不存在兼容装载路径

### Requirement: 唯一候选 ZIP格式
系统 SHALL 只接受包含一个顶层 `<plugin-id>/` 标准目录的 ZIP候选，并 SHALL 在解压时拒绝绝对路径、`..`、越界目标、符号链接、多个顶层目录和非标准层级。

#### Scenario: 标准 ZIP进入预检
- **WHEN** ZIP只有一个名称等于 `plugin.id` 的顶层目录且目录内容有效
- **THEN** 系统复制候选后从内部事务区完成检查，外部 ZIP保持不变

#### Scenario: 路径穿越被拒绝
- **WHEN** ZIP条目试图写到内部事务区之外
- **THEN** apply 以 `VALIDATE` 失败，当前安装目录和运行态不变

### Requirement: Properties 是唯一描述真源
系统 SHALL 只从根 `plugin.properties` 读取 PF4J 制品描述，并 MUST 拒绝非空 `plugin.class` 与 `plugin.requires`；主 JAR Manifest 中的 PF4J 描述不得覆盖它。

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

### Requirement: 契约归属不绑定 provider 角色
系统 SHALL 支持宿主公共 API、独立 contract-only 插件和 executable 内部契约三种归属，并 MUST NOT 根据 provider/consumer 业务角色改变安装层级。

#### Scenario: 中间插件同时消费和提供
- **WHEN** 一个插件依赖上游 contract并向下游注册服务
- **THEN** 它仍以单一 `pluginId` 扁平安装，层次只由依赖图和 Fibra Context/服务图表达

