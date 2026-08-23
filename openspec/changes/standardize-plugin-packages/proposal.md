## Why

当前 `fibra-loader-pf4j` 只接受插件根目录直接 JAR，并在关闭旧运行态后才知道替换制品及其完整依赖图能否成立。这种模型无法准确承载私有依赖、独立契约制品和多插件版本联动更新，也无法在多个目录替换期间的进程崩溃后恢复确定状态。

`0.3.0` 需要在开发阶段直接冻结长期制品边界，避免未来的 Spring AI、Java DeepSeek Harness 或其他宿主反向迫使核心再次重构。

## What Changes

- **BREAKING**：插件安装形态从直接 JAR改为 `plugins/<plugin-id>/plugin.properties + lib/*.jar` 标准目录，候选改为含唯一顶层插件目录的 ZIP。
- **BREAKING**：删除 `loadArtifact(Path)` 与 `reloadArtifact(Path)`，新增 `applyArtifacts(List<Path>)` 作为单包和批量安装、升级、降级的唯一入口，不提供兼容转发。
- 使用 `plugin.properties` 作为唯一描述真源，禁止 `plugin.class`、`plugin.requires`、共享运行时类内嵌和同版本不同内容重发。
- 区分 contract-only 与 executable 制品；contract-only 可被依赖但不可创建 Fibra entry，executable 必须恰好有一个自身入口且可创建多个 entry。
- 在触碰当前 ClassLoader、运行实例和安装目录前，对候选覆盖后的完整 prospective 图执行结构、依赖、版本、optional edge、入口和 ClassLoader 预检。
- 单包不兼容更新直接拒绝；provider、consumer、contract 等相关候选可在同一 `applyArtifacts` 批次中事务更新。
- 增加持久事务 journal，使运行中失败和目录交换期间的进程崩溃都能恢复旧安装图；恢复不完整时稳定报告 `ROLLBACK`。
- Watcher 只自动应用已安装 ID 的严格更高版本；多插件联动更新必须由部署协调器显式批量调用，不能依赖文件到达时序。
- 示例和仓库外验证增加独立 contract-only 插件，改用真实 ZIP 包，证明 PF4J 二进制依赖图与 Fibra 服务依赖图相互独立。

## Capabilities

### New Capabilities

- `plugin-package-format`: 定义唯一安装目录、候选 ZIP、描述属性、`lib/`、摘要、入口类型和契约归属。
- `plugin-dependency-resolution`: 定义完整 prospective 图、SemVer/optional 依赖、自身入口校验和 ClassLoader 边界。
- `plugin-update-transaction`: 定义批量 apply、受影响闭包、运行态恢复、失败回滚、崩溃恢复、Watcher 和稳定错误。

### Modified Capabilities

无。仓库此前没有 OpenSpec 稳定规格；当前 `0.2.0` 行为只存在于实现和设计文档中，本次三个规格直接建立 `0.3.0` 稳定契约。

## Impact

- 生产代码：`fibra-loader-pf4j` 重构 JAR manager/loader、校验、更新事务、错误与 Watcher；`fibra-loader-config` 只适配新的制品操作，不引入 PF4J 或 Spring 新边界。
- 公开 API：`fibra-loader-pf4j` 签名发生明确破坏性变化；`fibra-api`、`fibra-core` 和 `fibra-pf4j-api` 核心语义不变。
- 示例与验收：新增非发布 contract-only 示例模块，provider/consumer 包装、宿主、仓库外工程和脚本全部改用标准 ZIP。
- 文档：PF4J 架构、配置 loader、公共 API、README、发布说明和公开签名基线整体同步，删除旧直接 JAR语义。
- 依赖：继续使用 PF4J 3.13.0、SLF4J 和 JDK 21，不新增生产运行时依赖。

