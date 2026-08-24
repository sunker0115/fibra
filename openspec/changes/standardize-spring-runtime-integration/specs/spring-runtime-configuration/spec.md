## ADDED Requirements

### Requirement: 使用唯一四段属性模型

系统 SHALL 只接受设计文档第 4 节定义的 `fibra.artifacts`、`fibra.config`、`fibra.startup` 和 `fibra.shutdown` 不可变属性结构，并使用该节定义的默认值和字段类型。系统 MUST NOT 绑定或解释 `0.3.1` 的扁平属性作为兼容输入。

#### Scenario: 只提供必填属性
- **WHEN** 宿主只配置 installed root 和 config location
- **THEN** 两类 watcher 默认关闭、required entries 默认为空、readiness 与 root close timeout 使用设计文档定义的默认值

#### Scenario: 分别启用两个 watcher
- **WHEN** 宿主分别设置 artifact watch 和 config watch 的 enabled/debounce
- **THEN** 两组值独立绑定，启用或修改其中一组不改变另一组

#### Scenario: 使用旧属性
- **WHEN** 宿主只提供 `fibra.plugins-root`、`fibra.config-location`、`fibra.watcher.*` 或其他已删除属性
- **THEN** 默认托管运行时因新必填属性缺失而启动失败，不读取旧值、不记录兼容警告后继续

### Requirement: 在资源创建前完整校验属性

自动配置 SHALL 在创建 root Context、loader、watch service、scheduler 或 watcher 线程前校验完整属性图。路径、条件必填、唯一性和 Duration 范围以设计文档第 4 节为准；每个失败 MUST 指明完整属性键和无效值。

#### Scenario: 安装根不存在
- **WHEN** `fibra.artifacts.installed-root` 不存在或不是目录
- **THEN** ApplicationContext 在创建 Fibra root 和 loader 前失败，错误指明该完整属性键，starter 不创建该路径

#### Scenario: 初始配置不存在
- **WHEN** `fibra.config.location` 不存在或不是普通文件
- **THEN** ApplicationContext 在创建 Fibra运行资源前失败，starter 不等待 watcher 将来创建文件

#### Scenario: artifact watch 缺少 incoming root
- **WHEN** artifact watch 已启用但 incoming root 缺失、不是目录或未配置
- **THEN** ApplicationContext fail-fast，错误指明 `fibra.artifacts.incoming-root`

#### Scenario: artifact watch 关闭且 incoming root 未配置
- **WHEN** artifact watch 关闭且 incoming root 未配置
- **THEN** 属性校验通过，不创建 artifact watcher

#### Scenario: required entry 无效
- **WHEN** required entries 含空白值或重复 entryId
- **THEN** ApplicationContext 在装载插件前失败，错误指明 `fibra.startup.required-entries`

#### Scenario: Duration 无效
- **WHEN** 任一 watcher debounce、readiness timeout 或 root close timeout 不大于零
- **THEN** ApplicationContext 在创建 Fibra运行资源前失败并指明对应完整属性键

### Requirement: 通用配置不包含上传暂存策略

`FibraProperties` MUST NOT 包含 staging/upload/download/marketplace 路径。上传暂存 SHALL 由具体宿主的独立属性定义；Spring Web 示例使用 `example.fibra.staging-root`。

#### Scenario: Web 示例上传候选
- **WHEN** Web 示例接收 ZIP并暂存
- **THEN** 控制器从示例专属属性读取 staging root，不从 `FibraProperties` 获取上传路径

#### Scenario: 非 Web 宿主使用 starter
- **WHEN** CLI 或其他 Spring Boot 宿主启用 Fibra starter
- **THEN** 无需配置任何上传暂存目录即可完成运行时启动

### Requirement: 配置元数据准确描述公共契约

autoconfigure 构建 SHALL 生成覆盖全部 `fibra.*` 字段的 Spring Boot 配置元数据，说明条件必填、默认值和 entryId 语义，不得包含已删除旧属性。

#### Scenario: 检查配置元数据
- **WHEN** 读取 autoconfigure JAR中的配置元数据
- **THEN** 每个当前字段都有名称、类型和说明，required entries 明确标为 entryId，旧扁平字段和 staging root 不存在
