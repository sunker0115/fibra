# spring-adapter-packaging Specification

## Purpose
定义 Spring Framework 适配层、Spring Boot 自动配置与 starter 的发布边界，使框架中立运行时不引入 Spring 依赖，并以最小稳定 API 提供可独立消费的 Spring 集成。
## Requirements
### Requirement: 通用 Spring、自动配置和依赖入口分离

系统 SHALL 以 `fibra-spring` 承载 Spring Framework 接缝，以 `fibra-spring-boot-autoconfigure` 承载 Boot 属性与自动配置，以 `fibra-spring-boot-starter` 作为无生产代码依赖入口。

#### Scenario: 用户引入 starter
- **WHEN** Spring Boot 宿主只声明 `com.sstlfsj:fibra-spring-boot-starter`
- **THEN** Maven 传递解析得到 autoconfigure、spring adapter、engine 和其运行依赖，Boot 发现唯一 Fibra 自动配置入口

#### Scenario: 检查 starter
- **WHEN** 构建 starter 主 JAR
- **THEN** JAR不含 `.class`、`AutoConfiguration.imports` 或业务资源

#### Scenario: 检查两个代码模块
- **WHEN** 构建 spring 与 autoconfigure 主 JAR
- **THEN** spring 不含 Boot 类型或 imports，autoconfigure 含唯一 imports 和配置元数据

### Requirement: Spring 依赖不进入框架中立 `artifact`

六个框架中立运行时 `artifact` `fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config`、`fibra-engine` 的 compile/runtime 依赖图 MUST NOT 出现 Spring家族坐标；根父 POM MUST NOT import Spring BOM或声明 Spring依赖。

#### Scenario: 验证依赖树和父 POM
- **WHEN** 执行中立依赖门禁
- **THEN** 六个模块无 Spring，根 POM只有内部 Spring模块的当前版本管理，Boot BOM只在 autoconfigure

### Requirement: Spring change 完成后有九个运行时 `artifact`

系统 SHALL 发布六个框架中立运行时 `artifact`、`fibra-spring`、autoconfigure 和 starter。九个模块均生成项目要求的主 `artifact`、辅助 `artifact` 和展开 POM并进入可复现构建。

#### Scenario: 完整运行时发布构建
- **WHEN** 执行运行时 `artifact` 门禁
- **THEN** 九个模块 `artifact` 完整，聚合根、示例和验证模块仍跳过 deploy

### Requirement: 分模块冻结最小 Spring API

`fibra-spring` SHALL 只冻结 `FibraSpringLifecycle` 和 `FibraServiceBridge`；autoconfigure SHALL 只冻结 `FibraAutoConfiguration` 类名和 `FibraProperties` 数据结构；starter MUST NOT 有 Java签名基线。

#### Scenario: 生成签名
- **WHEN** 生成两个代码模块的 javap 基线
- **THEN** 不含 loader/source/coordinator、自动配置 bean 方法或旧 `FibraLifecycle`
