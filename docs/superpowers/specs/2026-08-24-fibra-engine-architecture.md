# Fibra Engine 最终架构

日期：2026-08-24

状态：架构已确认，作为 loader 重构、`fibra-engine`、Spring 适配、插件 archetype 和发布验收的共同上游权威源

## 1. 目标

Fibra 最终由框架中立内核、机制型 loader、托管 engine 和框架适配四层组成。`fibra-engine` 把插件制品、配置树、运行实例、期望状态、持续收敛和联合部署事务组成一个长期运行的插件引擎；Spring、Spring Boot、CLI、Web、Solon 或纯 Java 宿主只能适配或消费该能力，不得重新实现运行时协调。

本设计冻结以下原则：

- `fibra-api`、`fibra-core` 和 Cordis 对等语义不因 engine 改变；
- loader 只负责各自资源的读取、校验、计划和事务执行，不负责宿主监听策略；
- 文件 watcher 只产生 dirty signal，不直接调用 loader 变更方法；
- 所有自动变更由一个 level-triggered reconcile controller 串行收敛；
- plugin 与 config 强耦合升级通过显式 deployment package 建立事务边界，不按文件事件时间猜测批次；
- Spring 只管理 engine 的容器生命周期，不成为 engine 的实现依赖；
- 开发阶段直接删除错误 watcher API 和一步式内部实现，不提供兼容转发。

## 2. 最终模块图

```text
fibra-api
  ↑
fibra-core
  ↑
fibra-pf4j-api
  ↑
fibra-loader-pf4j
  ↑
fibra-loader-config
  ↑
fibra-engine
  ↑
fibra-spring
  ↑
fibra-spring-boot-autoconfigure
  ↑
fibra-spring-boot-starter

fibra-plugin-archetype  --只在生成项目中依赖已发布 fibra-pf4j-api，不进入运行时依赖链
```

九个运行时制品加一个开发工具制品，共十个可发布制品。示例、parity、benchmark 和 verification 模块不发布。

## 3. 原有模块的最终责任

### 3.1 `fibra-api` 与 `fibra-core`

保持当前公开契约和 Cordis 71 用例语义。`fibra-core` 继续只提供 root `Context` 和插件实例生命周期，不知道 PF4J、配置文件、watch service、deployment package、Spring 或宿主进程。

### 3.2 `fibra-pf4j-api`

继续只定义标准插件入口。插件实现编译时只依赖该模块和自己的 contract；PF4J、Fibra API与共享 contract 均由宿主或独立 contract 插件提供，不复制进插件私有 `lib/`。

### 3.3 `fibra-loader-pf4j`

保留标准插件包检查、依赖图、候选 ClassLoader、安装根、mount/unmount 和制品崩溃恢复。删除直接执行业务变更的 `FibraPluginWatcher` 公共 API。

一步式 `applyArtifacts` 只作为单资源事务的便捷入口，底层唯一实现拆为：

```text
inspect → plan → prepare → commit → complete
                           ↘ rollback
```

`prepare` 完成全部可失败的读取、摘要、依赖、候选类型和磁盘准备，不关闭旧 ClassLoader；`commit` 只执行已准备的确定性切换；`complete` 在上层 readiness 成功后释放 previous 数据。联合部署期间不得创建独立顶层 journal。

### 3.4 `fibra-loader-config`

保留 YAML 读取、include、patch、限制、entryId 解析、typed config 物化和运行实例 reconcile。删除 `watch(...)` 与 `FibraConfigWatcher` 公共 API。

配置处理拆为：

```text
resolve source tree
  → plan against current or candidate plugin catalog
  → prepare reversible runtime/file changes
  → commit
  → complete or rollback
```

候选配置计划必须能使用 `fibra-loader-pf4j` 提供的只读候选插件目录和目标 `configType`，保证 plugin/config 联合变更在拆除旧运行态前完成类型校验。

## 4. `fibra-engine`

### 4.1 公共入口

模块公共入口为终止性、`AutoCloseable` 的 `FibraEngine`：

```java
public final class FibraEngine implements AutoCloseable {
    public static Builder builder(Path installedRoot, Path configLocation);

    public void start();
    public void requestReconcile();
    public FibraDeploymentResult applyDeployment(Path packagePath);
    public FibraEngineStatus status();

    public Context root();
    public FibraPluginLoader pluginLoader();
    public FibraConfigLoader configLoader();
    public boolean isRunning();
    public void close();
}
```

builder 使用命名方法配置 artifact/config watch、required entries、readiness、root close 和周期 resync；不提供长位置参数构造器。所有集合在 build 时防御性复制，所有路径归一化并在创建任何 root、loader、watch service 或线程前整体校验。

### 4.2 所有权

每个 engine 独占一个 root、一个 plugin loader、一个 config loader、两个可选 watch source、一个 reconcile worker 和一个 deployment journal root。用户可读取 loader 执行显式管理，但显式管理与 reconcile 仍经过同一 engine 操作门；不得绕过 engine 直接关闭 loader。

正常关闭顺序固定为：

```text
停止接收 reconcile/deployment
  → 关闭 artifact source
  → 关闭 config source
  → 等待或取消尚未开始的 reconcile
  → config loader
  → plugin loader
  → root.closeAsync()
```

### 4.3 Reconcile 模型

artifact/config source 只把同一个 engine key 放入有界去重队列。reconcile 不消费事件差量，每次重新读取完整安装目录、候选目录和配置依赖文件，比较 `desiredRevision` 与 `appliedRevision` 后生成新计划。

规则：

- 一个 engine 同时最多执行一个 reconcile 或 deployment；
- 多个文件事件合并为一次 dirty 状态；
- 执行期间再变 dirty，当前执行结束后至少再 reconcile 一次；
- 周期 resync 修复丢失的文件事件；
- 失败保留最后成功运行态和 `appliedRevision`，记录结构化 `lastFailure` 并按有界退避重试；
- 手工 deployment 的 revision 高于松散目录信号，执行期间只累计 dirty，不交叉提交。

### 4.4 双 Source

`ArtifactDirectorySource` 监听候选 ZIP目录，`ConfigFileSource` 监听当前配置快照解析出的根文件和 include 文件集合。两者只依赖 JDK `WatchService`，构造失败必须关闭已分配资源；source 关闭后不得再入队。

source 与 controller 是 engine 内部实现，不进入公共 API签名。底层 loader 不再持有 watch thread、scheduler 或 debounce 状态。

## 5. Deployment Package

plugin package 是可复用插件制品；deployment package 是某个宿主环境的一次原子发布，两者不得混为一个格式。

标准结构：

```text
deployment.zip
├── deployment.properties
├── checksums.sha256
├── plugins/
│   └── *.zip
└── config/
    └── fibra.yaml
```

`deployment.properties` 至少包含 deployment id、version、配置根相对路径和按字典序排列的插件相对路径。摘要固定使用 SHA-256；禁止 MD5。ZIP 路径、大小、条目数、重复项、符号链接、未知顶层条目和摘要全部在预检期校验。

### 5.1 唯一总事务

engine 为联合部署创建唯一持久 journal。artifact/config loader 以参与者身份加入：

```text
PREPARING
  → PREPARED
  → COMMITTING
  → VERIFYING readiness
  → COMMITTED
  → complete participants
```

任一步失败按 config 后 artifact 的逆序 rollback，原异常为主异常，恢复异常按发生顺序 suppressed。崩溃恢复只读取 engine journal；参与者不得在同一联合部署中创建互相矛盾的顶层 journal。

新状态在 readiness 成功前不得成为对外 committed revision。恢复无法证明前态或后态完整时拒绝启动并报告 ROLLBACK，不猜测一个插件图继续运行。

## 6. Spring 边界

`fibra-spring` 只提供 `FibraSpringLifecycle` 和 `FibraServiceBridge`。lifecycle 把 Spring `start/stop` 委托给同一个 `FibraEngine`，不复制 load、watch、readiness、rollback 或 close 算法。

`fibra-spring-boot-autoconfigure` 只负责不可变属性、完整校验、条件退让、engine builder 映射和配置元数据。starter 是无生产 class 的依赖入口。

## 7. 插件 Archetype

`fibra-plugin-archetype` 使用 Maven `maven-archetype` packaging，生成一个不继承 Fibra 父 POM的独立多模块插件项目：

```text
generated-plugin/
├── pom.xml
├── plugin-api/
├── plugin-impl/
├── config/
├── deployment/
└── README.md
```

生成项目必须直接执行 `mvn verify`，产出标准 plugin ZIP 和包含该插件的 deployment ZIP。模板使用 Maven Assembly，不手写 ZIP组件；依赖版本集中在生成项目根 properties/dependencyManagement，内部模块使用 `${project.version}`。

archetype 自身使用 Maven 官方 `archetype:integration-test` 在构建期生成并验证项目。额外的仓库外验证使用隔离本地仓库安装十个制品，再生成、构建并由 `fibra-engine` 装载产物。

## 8. 测试与发布

- Cordis 71 用例和 core API 基线保持通过；
- 两个 loader 分别覆盖单参与者事务、崩溃恢复和候选视图；
- engine 覆盖事件去重、执行期 dirty、resync、失败退避、联合事务、readiness、关闭和 ClassLoader 回收；
- Spring 只验证委托、属性、自动配置和所有权；
- 纯 Java example 改用 `FibraEngine`，Spring example 只引入 starter；
- external consumer 验证 core、插件、engine 和 Spring Boot 四种外部消费边界；
- 十个可发布制品生成主制品、发布 POM及项目要求的辅助制品，并进入可复现构建；
- archetype 生成项目不得引用 reactor、`${revision}`、`target/classes` 或 Fibra 父 POM。

## 9. 范围外

- Spring Shell 命令注册、Spring AI 和具体 agent 协议；
- 远程插件市场、自动下载依赖、签名信任策略和沙箱；
- 每插件 Spring Context 或插件 bean 自动注入；
- 根据时间接近程度把两个松散文件事件猜成同一 deployment；
- 对旧 watcher API、旧 Spring 属性或错误模块边界提供兼容代码。

## 10. Open Questions

无。模块名称、依赖方向、watch source、reconcile、联合事务、Spring 接缝、archetype、测试和发布边界均已确定。
