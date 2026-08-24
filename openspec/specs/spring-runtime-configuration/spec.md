# spring-runtime-configuration Specification

## Purpose
TBD - created by archiving change standardize-spring-runtime-integration. Update Purpose after archive.
## Requirements
### Requirement: Boot 属性一对一映射 Engine Builder

系统 SHALL 只接受权威设计定义的不可变 `fibra.engine`、`fibra.artifacts`、`fibra.config`、`fibra.startup` 和 `fibra.shutdown` 属性，并把每个值一对一映射到 `FibraEngine.Builder`。默认值只有属性数据结构一个真源。

#### Scenario: 只提供必填属性
- **WHEN** 宿主只配置 installed root 和 config location
- **THEN** source 默认关闭、required entries 为空、resync 为 30 秒、重试从 250 毫秒有界增长到 30 秒，其余参数使用权威默认值并构建一个 engine

#### Scenario: 使用旧属性
- **WHEN** 宿主只提供 `fibra.plugins-root`、`fibra.config-location`、`fibra.watcher.*` 或其他旧键
- **THEN** 新必填属性校验失败，不读取旧值或记录兼容警告后继续

### Requirement: Engine 创建前完整校验

自动配置 SHALL 在创建 root、loader、watch service或线程前校验路径、条件必填、entry唯一性和所有 Duration范围，并校验 retry max 不小于 initial；失败 MUST 指明完整属性键和值。

#### Scenario: 路径或条件无效
- **WHEN** 安装根、配置文件、启用 source所需目录或 Duration无效
- **THEN** ApplicationContext 在创建 `FibraEngine` 前失败且不创建目录或运行资源

#### Scenario: Required entry 无效
- **WHEN** required entries 含空白或重复 entryId
- **THEN** 创建 engine 前失败并指明 `fibra.startup.required-entries`

### Requirement: 通用配置不包含上传暂存

`FibraProperties` MUST NOT 包含 staging/upload/download/marketplace 路径。Web 示例 SHALL 使用 `example.fibra.staging-root`。

#### Scenario: 非 Web 宿主启动
- **WHEN** CLI 或其他 Boot 宿主使用 starter
- **THEN** 不配置上传目录也可创建 engine

### Requirement: 配置元数据准确且无旧键

autoconfigure SHALL 生成全部当前字段的类型、默认值、条件和 entryId说明，不得包含旧扁平字段。

#### Scenario: 检查元数据
- **WHEN** 读取 autoconfigure JAR配置元数据
- **THEN** 当前字段完整、旧键和 staging 均不存在
