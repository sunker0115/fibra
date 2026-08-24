## ADDED Requirements

### Requirement: Deployment Package 显式绑定插件与配置

系统 SHALL 使用包含 `deployment.properties`、`checksums.sha256`、`plugins/*.zip` 和 `config/` 根的标准 ZIP 表达一次联合发布。plugin package 与 deployment package MUST 保持不同身份和格式。

#### Scenario: 校验合法 deployment
- **WHEN** package 的身份、版本、配置根、插件列表、SHA-256、路径和嵌套 plugin ZIP 全部合法
- **THEN** 预检生成按字典序稳定的不可变 deployment 候选，不修改安装目录或运行态

#### Scenario: 映射 deployment 配置树
- **WHEN** package 配置根及全部 include 都位于 `config/` 且通过预检
- **THEN** 系统按相对路径把它们映射到宿主 `configLocation` 所在配置树，并为每个目标文件准备可恢复备份

#### Scenario: 拒绝越界配置引用
- **WHEN** package 配置使用绝对 include、`..` 越界、符号链接或其它离开 `config/` 的引用
- **THEN** 系统在修改配置文件或运行态前拒绝整个 package

#### Scenario: 拒绝不安全 ZIP
- **WHEN** package 含路径穿越、绝对路径、符号链接、重复条目、未知顶层条目、超限内容或摘要不匹配
- **THEN** 系统在创建候选 ClassLoader 和拆除运行态前拒绝整个 package

### Requirement: 松散事件不得被猜测为联合事务

系统 MUST NOT 根据 artifact/config 文件事件的时间、目录或名称接近程度自动创建 deployment。

#### Scenario: 互不兼容的松散变更
- **WHEN** 松散 plugin 与 config 各自都不能在当前运行态独立通过预检
- **THEN** engine 保留最后成功状态并分别报告失败，不把它们猜成可原子提交的批次
