## ADDED Requirements

### Requirement: 自动配置实现与依赖入口分离

系统 SHALL 以 `fibra-spring-boot-autoconfigure` 承载全部 Spring 自动配置代码和注册资源，并以 `fibra-spring-boot-starter` 作为无生产代码的用户依赖入口。starter MUST NOT 自己包含自动配置类、其他生产 class 或 `AutoConfiguration.imports`。

#### Scenario: 用户引入 starter
- **WHEN** Spring Boot 宿主只声明 `com.sstlfsj:fibra-spring-boot-starter`
- **THEN** Maven 传递解析得到 autoconfigure、两个 Fibra loader 和自动配置所需 Spring 类型，Boot 能发现唯一 Fibra 自动配置入口

#### Scenario: 检查 starter 主 JAR
- **WHEN** 构建发布版 `fibra-spring-boot-starter` 主 JAR
- **THEN** JAR不含 `.class`、`AutoConfiguration.imports` 或业务资源，只保留 Maven 制品必要元数据

#### Scenario: 检查 autoconfigure 主 JAR
- **WHEN** 构建发布版 `fibra-spring-boot-autoconfigure` 主 JAR
- **THEN** JAR包含 Fibra 自动配置实现、唯一 `AutoConfiguration.imports` 和生成的配置元数据

### Requirement: Spring 依赖不进入中立制品

五个中立生产制品的编译和运行依赖图 MUST NOT 出现 Spring、Spring Boot、Spring Shell 或 Spring AI。根父 POM MUST NOT import Spring BOM 或声明 Spring 依赖；Spring Boot 版本与 BOM SHALL 只由 autoconfigure 模块管理。

#### Scenario: 验证中立模块依赖树
- **WHEN** 对五个中立生产模块解析 compile/runtime 依赖树
- **THEN** 结果不存在 `org.springframework*` 或 Spring AI/Shell 坐标

#### Scenario: 验证父 POM
- **WHEN** 检查根父 POM dependencyManagement 和 dependencies
- **THEN** 不存在 Spring BOM import 或 Spring 依赖，只有 Fibra 内部 Spring 模块的当前 reactor 版本管理可以位于父 POM

### Requirement: Spring 适配以七制品发布

系统 SHALL 把 autoconfigure 和 starter 都作为可发布模块，使生产制品总数为七；两个模块均须生成主 JAR、sources JAR、Javadoc JAR和展开后的发布 POM，并进入可复现构建验收。

#### Scenario: 完整发布构建
- **WHEN** 执行 reactor 发布制品门禁
- **THEN** 七个生产模块全部具有要求的四类制品，聚合根、示例和验证模块仍跳过 deploy

#### Scenario: 连续可复现构建
- **WHEN** 从同一源码和固定构建环境连续构建两次七个生产模块
- **THEN** 对应主 JAR、sources JAR、Javadoc JAR和发布 POM逐字节一致

### Requirement: 只冻结宿主需要的 Spring 公共 API

autoconfigure SHALL 只把 `FibraAutoConfiguration` 类名、设计文档第 4 节定义的 `FibraProperties` 数据结构和 `FibraServiceBridge` 作为 Java 公共签名；lifecycle、bean 方法和 watcher 协作类型 MUST NOT 进入公开签名基线。starter MUST NOT 有 Java 公共签名基线。

#### Scenario: 生成 autoconfigure 签名
- **WHEN** 对 autoconfigure 生成公开和 protected 签名基线
- **THEN** 基线只包含自动配置入口类、properties 类型和 service bridge，不包含 `FibraLifecycle` 或自动配置 bean 方法

#### Scenario: 检查旧签名
- **WHEN** 全仓搜索 `0.3.1` 的公共 `FibraLifecycle` 构造器和 starter Java 签名基线
- **THEN** 生产 API、当前 API 文档和验收中均不存在这些旧契约
