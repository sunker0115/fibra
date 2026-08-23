# Fibra 插件制品与事务更新设计

日期：2026-08-23  
状态：`0.3.0` 已确认设计，作为实现与验收的权威输入  
OpenSpec：[`standardize-plugin-packages`](../../../openspec/changes/standardize-plugin-packages/)

## 1. 目标与边界

`0.3.0` 把 `fibra-loader-pf4j` 从“插件根目录直接放 JAR、单 JAR 事后回滚”重构为“标准目录安装包、ZIP 候选、完整依赖图预检、批量事务替换”。这是开发阶段的直接替换，不提供旧目录识别、旧 API 转发、弃用期或兼容适配器。

本次冻结以下长期边界：

- `fibra-core` 继续只实现 Cordis 等价的 Context/Fibra 生命周期，不感知 PF4J；
- PF4J 只管理制品描述、版本约束、二进制依赖图、制品状态和每制品 ClassLoader；
- Fibra 是业务插件、服务、事件、effect、配置和运行实例生命周期的唯一运行时；
- 一个 PF4J `pluginId` 对应一个已安装版本和一个 ClassLoader，一个制品可以创建多个 Fibra `entryId`；
- Spring、Spring AI、Hasor、Solon 和具体 Harness 业务均不进入内核或 PF4J loader；
- 候选包的下载、签名信任、市场仓库与发布编排由宿主或外部制品系统负责，Fibra 只接收本地候选 ZIP。

不新增通用 loader SPI，不拆新的生产 Maven 模块。当前只有 PF4J 一个制品实现，提前抽象会增加没有第二个消费者的契约。

## 2. 开源参照与方案选择

### 2.1 候选方案

| 方案 | 架构层取舍 | 业务层取舍 | 结论 |
|---|---|---|---|
| 继续直接 JAR | 文件少，但描述、主制品和私有依赖无法形成稳定边界；批量更新只能在破坏运行态后验证 | 简单示例容易，真实插件一旦有私有依赖、契约包或多插件联动更新就失真 | 拒绝 |
| 直接使用 PF4J 默认 ZIP repository | 复用自动解压和默认扫描，但解压会先改变插件目录，批量装载是 best-effort，不能先验证 prospective 全图 | 部署简单，但失败可能留下半批次目录或部分加载状态 | 拒绝 |
| Fibra 显式包协议 + PF4J 目录 loader | 由 Fibra 固化包结构、完整图预检、事务日志和恢复；PF4J 继续承担成熟的描述、SemVer、依赖和 ClassLoader | 单包和多包更新语义一致，可准确拒绝不兼容更新，也能让相关候选一次升级 | 采用 |

### 2.2 吸收与不吸收

- PF4J 3.15.0：吸收 `plugin.properties`、标准 `lib/` 目录、SemVer 范围、依赖图、每插件 ClassLoader 和父委派定制点；不采用默认 best-effort 批量装载、隐式 ZIP 展开、扩展对象缓存和默认扩展索引判定。
- Apache Commons Compress 1.28.0：只使用 ZIP 中央目录和 `ZipArchiveEntry.isUnixSymlink()` 完成符号链接/条目类型检查；JDK 21 `ZipEntry` 不暴露 Unix mode，禁止自己解析 ZIP external attributes。解压后的路径规范化、目标越界检查和目录协议仍由 Fibra 执行。
- gj.spring.pf4j：吸收“一插件一目录、版本化主 JAR、私有 `lib/`、卸载释放资源”；不吸收目录内隐式选择最新 JAR、Manifest `Class-Path`、失败后继续和 Spring 子容器。
- Spring Plugin：只吸收按调用查询当前运行状态、不维护第二份长期对象缓存的思想，不引入依赖。
- Cordis HMR：吸收依赖闭包、先加载验证、失败恢复旧实例的原则；包版本仍由 Java 制品层表达。
- DeepSeek Harness：只吸收 provider-neutral contract seam 按独立演进需要拆包的经验，不把其业务目录或插件命名变成 Fibra 通用标准。
- IDEA 与 VS Code：吸收唯一插件 ID、单活版本、专用 ClassLoader、依赖门禁和更新前兼容性校验；版本范围由 PF4J 表达，不退化为只有依赖 ID。

可回溯源码：

- PF4J：本机 `org.pf4j.DefaultPluginManager`、`DefaultPluginLoader`、`PropertiesPluginDescriptorFinder`、`DependencyResolver`，源码包位于 Maven 本地仓库的 `org/pf4j/pf4j/3.15.0/pf4j-3.15.0-sources.jar`；
- gj.spring.pf4j：`/Users/sunke/dev/ai-project/gj.spring.pf4j/src/gj-pf4j/src/main/java/gj/pf4j/GJJarPluginRepository.java` 与 `src/gj-plugin-demo/pom-parent.xml`；
- Cordis：`/Users/sunke/dev/ai-project/cordis/packages/hmr/src/index.ts`；
- DeepSeek Harness：`/Users/sunke/dev/ai-project/deepseek-harness/vendor/README.md` 及各 package manifest；
- Spring Plugin：`/Users/sunke/dev/ai-project/spring-plugin/README.markdown`。
- Apache Commons Compress：[`1.28.0` 官方发布页](https://commons.apache.org/proper/commons-compress/)和[官方安全报告](https://commons.apache.org/proper/commons-compress/security.html)。

## 3. 身份和公开数据结构

### 3.1 身份

| 名称 | 含义 | 稳定性 |
|---|---|---|
| `pluginId` | PF4J 制品、安装目录、版本节点和 ClassLoader 身份 | 同一时刻全局唯一，只允许一个安装版本 |
| `entryId` | 配置树中的 Fibra 运行实例身份 | 同一 loader 内唯一；更新制品后以同一 ID 重建 |
| candidate package | 外部提供的单插件 ZIP 候选 | 输入只读，Fibra 不移动、不删除 |
| installed package | `plugins/<pluginId>/` 标准目录 | loader 管理的当前单活版本 |

稳定引用是 `pluginId` 和 `entryId`，不是 `Fibra`、入口对象、插件对象或 ClassLoader 的 Java 对象身份。制品更新后，外部缓存的旧对象全部陈旧。

### 3.2 公开 API

`FibraPluginLoader` 保留运行实例和制品状态 API，删除直接 JAR API：

```java
public final class FibraPluginLoader implements AutoCloseable {
    public FibraPluginLoader(Context root, Path pluginsRoot);

    public List<String> loadArtifacts();
    public List<String> applyArtifacts(List<Path> candidatePackages);

    public Class<?> configType(String pluginId);
    public Fibra mount(PluginInstanceSpec spec);
    public Fibra update(String entryId, Object config);
    public Fibra updateWithFactory(String entryId, PluginConfigFactory configFactory);
    public void unmount(String entryId);

    public void stopArtifact(String pluginId);
    public boolean unloadArtifact(String pluginId);
    public List<String> artifactIds();
    public List<String> entryIds();
    public Optional<Fibra> fibra(String entryId);

    public <T> T runExclusive(Supplier<T> action);
    public void runExclusive(Runnable action);
    public void close();
}

public final class FibraPluginLoaderBusyException extends IllegalStateException {
    public FibraPluginLoaderBusyException(String message);
}
```

以下方法直接删除，不保留转发：

```java
loadArtifact(Path)
reloadArtifact(Path)
```

单包安装、升级或降级调用 `applyArtifacts(List.of(candidate))`；有关联版本变化时，把全部候选一次传给同一个 `applyArtifacts`。显式调用允许安装新 ID、升级和降级，但最终完整图必须有效。

构造器只恢复磁盘事务，不创建插件 ClassLoader。宿主必须先调用 `loadArtifacts()` 完成首次完整安装图校验和活动 manager 初始化，之后才能调用 `applyArtifacts`、mount 或其它运行态 API；初始化前调用以 `IllegalStateException` 拒绝且不创建事务。`unloadArtifact` 只卸载活动制品及其受影响运行实例，不删除标准安装目录；后续 `loadArtifacts()` 可以按当前完整安装图重新装载所有仍在磁盘但未活动的制品。`loadArtifacts()` 因而是可重复的完整图同步入口，不是旧单 JAR旁路。

`runExclusive` 和全部制品/entry 变更 API 共用一个可重入逻辑事务门，而不是在整个操作期间持有 Java `Lock`：

- 事务门只用短临界区登记当前调用线程、重入深度和已提交只读快照；文件操作、PF4J 调用、插件回调及任何 `Mono.block()` 期间均不得持有物理锁；
- 同一阻塞调用线程可以重入，供 `FibraConfigLoader` 在一次 reconcile 内调用 mount/update/unmount；其他线程在事务活动时立即抛 `FibraPluginLoaderBusyException`，不得排队等待持门线程；
- 同步管理 API 不得从 Reactor non-blocking 线程调用；这种调用同样立即抛 `FibraPluginLoaderBusyException`。Fibra root 的 lifecycle Scheduler 属于该范围，因此插件生命周期/effect 回调不能反向执行 loader 管理操作；
- `artifactIds()`、`entryIds()` 只读取上一次完整提交的不可变身份快照，不进入事务门；内部快照同时保存每个活动制品的版本供 watcher 比较，但不保存插件类。`configType()`、`fibra()` 和全部变更 API 依赖实时 ClassLoader/运行态，事务活动时按前述规则报忙；
- 同步宿主调用自行处理报忙；`FibraPluginWatcher` 和 `FibraConfigWatcher` 必须把报忙视为瞬时竞争，保留 dirty 状态并在当前事务释放后重新执行，不能丢失文件事件。

`close()` 只能由普通阻塞线程在事务门空闲时开始，并把完整停止/unload/关闭 ClassLoader 作为最后一个独占事务；活动事务期间（包括从 `runExclusive` 回调内）调用立即报忙且 loader 保持打开。宿主必须先关闭两个 watcher 并等待其在途任务结束，再关闭 config loader、plugin loader 和 root Context。

这个规则把 loader 事务门放在 Fibra lifecycle 调度之外：loader 可以发起并等待内核生命周期收敛，但内核回调不能等待或重入 loader 管理事务。不存在“持有 loader 物理锁等待 lifecycle、lifecycle 再等待 loader 锁”的锁环。

### 3.3 稳定异常

跨阶段失败使用：

```java
public enum FibraArtifactErrorStage {
    READ, VALIDATE, RESOLVE, DISPOSE, INSTALL, APPLY, ROLLBACK
}

public final class FibraArtifactException extends RuntimeException {
    public FibraArtifactErrorStage stage();
    public List<Path> packages();
    public List<String> artifactIds();
}
```

参数为空、列表重复路径、插件 ID 空白等调用错误仍使用 `IllegalArgumentException`；关闭后的调用等状态错误使用 `IllegalStateException`。`FibraArtifactException` 的原始失败放在 cause；恢复失败按发生顺序直接加入该异常的 suppressed。同步 API 不重复记录已传播异常，Watcher 和无法传播的后台清理失败使用 SLF4J。

## 4. 唯一插件包协议

### 4.1 安装和候选布局

安装目录固定为：

```text
plugins/
  <plugin-id>/
    plugin.properties
    lib/
      <plugin-id>-<plugin-version>.jar
      <private-dependency>.jar
```

候选目录由宿主决定，例如：

```text
staging/
  <candidate-name>.zip
```

ZIP 内必须恰好包含一个顶层目录，顶层目录名等于 `plugin.id`：

```text
<plugin-id>/
  plugin.properties
  lib/
    <plugin-id>-<plugin-version>.jar
    <private-dependency>.jar
```

不接受 ZIP 根直接放 `plugin.properties`、多个顶层目录、直接 JAR、嵌套插件目录或安装目录名与 `plugin.id` 不同。ZIP 文件名不承担身份语义。

### 4.2 `plugin.properties`

它是唯一制品描述真源，最小内容为：

```properties
plugin.id=example.greeting.provider
plugin.version=1.2.0
plugin.dependencies=example.greeting.contract@>=1.0.0 & <2.0.0
```

允许 PF4J 的 `plugin.description`、`plugin.provider` 和 `plugin.license`。`plugin.class` 必须不存在或为空；插件作者不得提供 PF4J `Plugin` 子类，避免在 Fibra 外建立第二套业务生命周期。`plugin.requires` 在 `0.3.0` 不接受，宿主/Fibra API 兼容性通过宿主公共 API 或显式 contract artifact 的版本依赖表达，不能写一个实际未校验的字段制造安全感。除 `plugin.id`、`plugin.version`、`plugin.dependencies` 及本段明确列出的三个可选描述字段外，其他键一律拒绝；业务配置只进入 `fibra-loader-config` 管理的独立配置文件，不得塞入制品描述。

`plugin.id` 只允许 ASCII 字母、数字、点、下划线和连字符，且首字符必须是字母或数字。`plugin.version` 必须是 PF4J `DefaultVersionManager` 可解析的 SemVer。依赖只使用 `plugin.dependencies`：

- `contract@>=1.0.0 & <2.0.0` 表示版本范围；
- `optional?@>=1.0.0 & <2.0.0` 表示可选依赖；
- 缺失的可选依赖不影响解析，已安装的可选依赖必须满足范围；
- 同一 ID 的不兼容主版本共存时必须使用不同 `plugin.id` 和不同 Fibra 服务键。

### 4.3 `lib/`

`lib/<plugin-id>-<plugin-version>.jar` 必须恰好一个，是主插件或主契约 JAR。其余直接子级 JAR 是该插件私有的第三方依赖。`lib/` 不允许子目录和非 JAR 文件。

所有 `lib/*.jar` 都不得包含：

```text
com/sstlfsj/fibra/
org/pf4j/
org/reactivestreams/
reactor/
org/slf4j/
```

`fibra-api`、`fibra-core`、`fibra-pf4j-api`、PF4J、Reactor、Reactive Streams 和 SLF4J 必须由宿主父 ClassLoader 提供。其他插件及 contract 插件也不得复制进当前 `lib/`；它们必须作为独立包安装，并通过 `plugin.dependencies` 共享同一个依赖 ClassLoader 类型。普通 JAR 内容无法可靠证明“私有三方库”还是“被复制的契约插件”，因此这一边界同时由 Maven scope、包构建规则和黑盒 ClassLoader 测试门禁。

不生成或使用 Manifest `Class-Path`；目录 loader 按排序后的相对路径把全部 `lib/*.jar` 加入当前插件 ClassLoader。

### 4.4 同版本内容

loader 对解压后的 `plugin.properties` 和排序后的 `lib/*.jar` 计算规范 SHA-256。输入顺序固定为 `plugin.properties` 在前，随后按使用 `/` 分隔的 UTF-8 相对路径字典序排列 `lib/*.jar`；每个文件依次写入 4 字节大端路径字节长度、路径 UTF-8 字节、8 字节大端文件长度和原始文件字节。摘要不包含绝对路径、ZIP 文件名、ZIP metadata 或解压后的文件时间：

- 同一 `plugin.id`、同一版本、同一规范摘要：该候选是 no-op；
- 同一 `plugin.id`、同一版本、不同规范摘要：拒绝，禁止同版本原地重发；
- 不同版本：进入正常 prospective 图预检。

### 4.5 发布约束与重复契约诊断

同版本不同摘要拒绝是刻意的制品不可变策略，不对普通 JAR 的非可复现构建做内容归一化。插件作者重新构建后只要字节发生变化就必须提升 `plugin.version`；需要重现同一版本时，构建必须固定时间戳、条目顺序和生成内容，使规范摘要逐字节一致。

普通 JAR 扫描无法可靠识别任意业务 contract 是否被复制进另一个插件的 `lib/`。若跨插件服务接缝出现同限定名类型无法转换、`ClassCastException`、`LinkageError` 或接口方法链接失败，首要诊断是检查 provider/consumer 是否各自携带了 contract 类；正确修复是把 contract 作为独立插件依赖或宿主公共 API，而不是增加 ClassLoader 强转、反射适配或兼容桥。该残留风险不通过启发式包名扫描掩盖。

## 5. 制品类型与契约归属

### 5.1 自身入口判定

入口只从主 JAR 自身的 `META-INF/extensions.idx` 读取。loader 不使用 PF4J `getExtensionClasses` 判定制品类型，因为 PF4J 的依赖感知资源查询可能读到依赖插件的索引，并且默认 finder 会吞掉部分 `ClassNotFoundException`、`NoClassDefFoundError`，从而把损坏的 executable 误判为 contract-only。

固定规则：

- 索引不存在或为空：`contract-only`；
- 索引恰好声明一个类，该类由目标插件自己的 ClassLoader 定义、实现 `FibraPluginEntrypoint`、具有 public 无参构造器：`executable`；
- 索引含非 Fibra 扩展、缺失类、链接失败、多个类或入口由依赖/父 ClassLoader 定义：候选无效。

预检只以 `Class.forName(name, false, pluginClassLoader)` 装载类型，不初始化类、不实例化入口，也不调用 `configType/descriptor/create`。业务入口真正启动仍可能失败，正式更新事务必须处理这种失败并回滚。

`contract-only` 可以 load、resolve、start、stop 和 unload，可以作为其他制品的 PF4J 依赖；`configType` 和 `mount` 必须以明确错误拒绝。`executable` 恰好有一个入口，但可以创建任意多个 `entryId`。

### 5.2 契约归属

跨 ClassLoader 类型只有三种合法归属：

1. 宿主公共 API：由父 ClassLoader 提供，适合真正属于宿主平台的稳定接口；
2. 独立 contract-only 插件：适合多个 provider、多方消费或需要独立版本演进的契约；
3. 单个 executable 内部契约：只适合不跨插件消费的简单插件。

“契约永远归 provider”不是 Fibra 规则。provider、consumer 只是相对于某条服务关系的角色，不是插件目录分类；同一个多层依赖插件可以同时消费上游服务并向下游提供服务。插件始终按 `pluginId` 扁平安装，层次只存在于 PF4J 依赖图和 Fibra Context/服务图中。

## 6. 完整图预检

`loadArtifacts()` 和 `applyArtifacts(...)` 使用同一个 `PluginGraphPreflight`。候选不能只校验自身或直接依赖，必须构造：

```text
prospective graph = 当前全部安装包 - 同 ID 旧包 + 本批次全部候选
```

预检顺序固定为：

1. 在 loader 逻辑事务门内把每个候选 ZIP复制到同文件系统的 `plugins/.fibra-preflight/<txid>/input/`，后续不再读取外部可变文件；
2. 用 Apache Commons Compress 读取 ZIP 中央目录和 Unix mode，再安全解压；拒绝绝对路径、`..`、目标越界、符号链接、非普通文件/目录条目、多个顶层目录和非标准层级；
3. 校验目录、`plugin.properties`、ID、SemVer、主 JAR、私有 JAR、共享类、同版本摘要和 `plugin.class`；
4. 以候选覆盖同 ID 当前包，形成唯一 ID 的 prospective 全图；
5. 使用临时 `FibraDirectoryPluginManager` 装载全图，校验缺失依赖、循环和版本范围；
6. 对实际存在的 optional dependency 额外检查版本范围，因为 PF4J 3.15.0 的 `DependencyResolver` 不把 optional edge 纳入依赖图；
7. 启动无操作 PF4J wrapper，并按上一节直接检查每个主 JAR 自身扩展索引；
8. 关闭临时 manager 的全部 ClassLoader；
9. 计算候选 ID 在旧图和 prospective 图中传递依赖方的并集。实际存在的 optional edge 也进入受影响闭包。

预检不调用任何业务入口。结构、依赖或入口预检失败时，当前安装目录、当前 ClassLoader、PF4J 状态和所有 Fibra entry 完全不动。

单独更新 provider 如果破坏现有 consumer 的版本范围，必须在此拒绝；provider、consumer、contract 候选一起提交且 prospective 全图有效时，可以作为一个批次更新。

## 7. 批量更新事务与崩溃恢复

### 7.1 事务目录

预检使用无 journal 的临时工作区：

```text
plugins/.fibra-preflight/<txid>/
  input/                 # 候选 ZIP不可变副本
  next/<plugin-id>/      # 已解压并验证的新目录
```

它不属于正式事务，不允许包含 `previous/`，也不允许改变安装目录或活动运行态。loader 构造时可直接清理全部预检工作区；预检期崩溃不会触发 `ROLLBACK` 拒绝启动。

每次非 no-op 更新在预检完成后创建正式事务目录：

```text
plugins/.fibra-transactions/<txid>/
  journal.properties
  input/                 # 候选 ZIP不可变副本
  next/<plugin-id>/      # 已验证的新目录
  previous/<plugin-id>/  # 被替换的旧目录
```

正式事务目录创建后，第一个持久动作必须是原子发布 `PREPARED` 的 `journal.properties`；随后才把预检工作区的 `input/`、`next/` 原子移入该目录。若进程只创建了空正式目录、尚未发布 journal，该目录可安全清理；无 journal 却存在 `previous/` 属于协议不可能状态，必须以 `ROLLBACK` 拒绝启动。

`journal.properties` 至少记录事务 ID、状态、按字典序排列的候选 ID、各 ID 更新前是否存在、旧规范摘要和新规范摘要。运行时回滚已经恢复旧目录和旧运行态、准备开始删除事务 payload 时，journal 额外原子记录 `cleanup.outcome=ROLLBACK`；该字段是清理意图证明，不是新的事务状态。journal 每次修改都先写同目录临时文件并 `FileChannel.force(true)`，再 `ATOMIC_MOVE + REPLACE_EXISTING`，最后 force 事务目录。每次插件目录 move 后同样 force 源父目录与目标父目录，再进入下一步。文件系统不支持原子 move 或目录 force 时以对应事务阶段失败，不退化为普通 move、复制覆盖或仅依赖进程内缓存。

状态只有：

```text
PREPARED -> INSTALLING -> APPLYING -> COMMITTED
```

### 7.2 正常更新

1. 预检通过后记录旧 PF4J started 状态、受影响制品全部 `PluginInstanceSpec` 和稳定 entry 顺序；快照只保存配置工厂，不保存旧 ClassLoader 创建的 typed config；
2. 创建正式事务目录并把 `PREPARED` journal 作为第一个持久动作原子发布，再把预检 `input/next` 移入正式事务目录；
3. 依赖方优先、子 entry 优先 dispose，随后 stop/unload 受影响制品并关闭 ClassLoader；
4. 写入 `INSTALLING`，对每个候选 ID 按字典序把旧目录原子 move 到 `previous/`，再把 `next/` 目录原子 move 到 `plugins/<pluginId>/`；
5. 写入 `APPLYING`，加载更新后的受影响制品，按依赖顺序恢复 started 状态，再按原 entry 顺序用配置工厂重新物化 typed config 并 mount；
6. 全部运行态恢复成功后写入 `COMMITTED`；
7. 更新已提交只读身份快照并清理事务目录。清理固定先删除 `previous/next/input` 和 journal 临时文件，最后删除 `journal.properties`，再删除空事务目录；不得先删 journal。外部候选 ZIP 始终保留。

多个目录没有一个文件系统级原子替换操作，因此不能把“逐个原子 rename”描述成“批次天然原子”。对外原子性由预检、逻辑事务门、反向恢复和持久 journal 共同提供。

### 7.3 运行中失败

任一步失败时：

1. 卸载本次创建的新 entry 和新 PF4J 运行态；
2. 按 journal 逆序把安装目录移回 `next/`，把 `previous/` 恢复到原 ID；新安装且原来不存在的 ID从安装目录撤回；
3. 重装旧依赖图、旧 started 状态和旧 entries；
4. 恢复成功后先原子写入 `cleanup.outcome=ROLLBACK`，再按 payload-first、journal-last 顺序清理并抛原阶段 `FibraArtifactException`；安装目录和运行态保持旧状态；
5. 恢复失败时抛 stage 为 `ROLLBACK` 的异常，原阶段异常作为 cause，恢复失败按发生顺序加入 suppressed，并保留事务目录供诊断和下次启动恢复。

### 7.4 进程崩溃后恢复

构造 `FibraPluginLoader` 时，在创建活动 PF4J manager 之前扫描 `.fibra-transactions`：

- 先清理 `.fibra-preflight`；空的无 journal 正式事务目录也可清理，无 journal 却存在 `previous/` 时拒绝启动；
- `COMMITTED`：每个 `plugins/<id>` 必须匹配 journal 的新摘要；全部匹配才保留新目录并清理 `previous/next/input`，任一不匹配都报告 `ROLLBACK`；
- `cleanup.outcome=ROLLBACK`：每个旧存在 ID 的安装目录必须匹配旧摘要，每个新安装 ID 必须不存在；全部匹配才继续删除可能残留的 payload 和 journal，任一不匹配都报告 `ROLLBACK`；
- `PREPARED`：安装目录尚未交换，旧 ID 必须仍匹配旧摘要、新安装 ID 必须不存在；满足时清理正式事务和残留预检目录，否则报告 `ROLLBACK`；
- `INSTALLING` 或 `APPLYING`：按候选安装顺序的逆序逐 ID 执行下述确定性恢复，全部恢复旧摘要后才清理事务；
- journal 损坏或任一目录/摘要组合不属于下述合法状态：构造失败并报告 `ROLLBACK`，不得猜测一个图继续启动。

逐 ID 恢复只允许以下组合，其中摘要均按第 4.4 节重新计算：

1. `oldExists=true` 且 `previous/<id>` 存在：`previous` 必须匹配旧摘要；新目录必须恰好位于一个位置——`plugins/<id>` 匹配新摘要且 `next/<id>` 不存在，或 `plugins/<id>` 不存在且 `next/<id>` 匹配新摘要。前一种先把新目录原子移回空的 `next/<id>`，然后两种情况都把 `previous/<id>` 原子恢复到安装目录；
2. `oldExists=true` 且 `previous/<id>` 不存在：`plugins/<id>` 必须匹配旧摘要且 `next/<id>` 必须匹配新摘要，表示该 ID 尚未开始交换；当前旧目录或待安装新目录缺失、摘要未知都无法闭合，必须报告 `ROLLBACK`；
3. `oldExists=false`：`previous/<id>` 必须不存在；新目录同样必须恰好位于一个位置——`plugins/<id>` 匹配新摘要且 `next/<id>` 不存在表示已经安装，反之表示尚未安装。已安装时把新目录原子移回空的 `next/<id>`，最终保持该 ID 不存在；其他组合报告 `ROLLBACK`。

`plugins/<id>` 与 `next/<id>` 同时存在或同时缺失、旧摘要/新摘要不匹配、journal 重复 ID 或候选顺序不规范都属于无法闭合，不允许覆盖或删除其中任一份来“尝试恢复”。

成功提交清理、成功回滚清理和构造期恢复清理都使用同一 journal-last 顺序。`COMMITTED` 证明新图，`cleanup.outcome=ROLLBACK` 证明旧图，因此 payload 已部分删除时仍能只依赖安装目录摘要重复完成清理。进程在清理期间再次退出时，要么 journal 仍在且可证明目标图并继续清理，要么只剩可安全删除的空无 journal 事务目录；协议不会自行产生“无 journal 但有 previous”的状态。

恢复完成后才允许 `loadArtifacts()` 创建 ClassLoader。该机制保证进程内失败和目录交换期间的进程崩溃都不会被静默接受为半批次安装图。

## 8. ClassLoader 与运行实例

`FibraDirectoryPluginLoader` 使用 PF4J `DefaultPluginClasspath` 的 `lib/` 约定，并创建当前 `FibraPluginClassLoader`。加载顺序仍为 PDA：插件自身、声明的插件依赖、宿主；Fibra、PF4J、Reactive Streams、Reactor 和 SLF4J 包强制由父 ClassLoader 加载。

更新候选 ID时，受影响集合是候选 ID加上旧图和新图中的传递依赖方并集。停止顺序为 dependent-first，重新装载和启动为 dependency-first。contract ClassLoader 更新同样会重建实际依赖方的 ClassLoader。

`PluginConfigFactory` 可能在候选校验后的正式 mount、失败恢复和配置 reconcile 中多次调用，必须可重复执行，只捕获普通不可变值，不得捕获插件 `Class<?>`、typed config、入口、插件对象或旧 ClassLoader。

升级、降级或失败恢复时，loader 总是把目标版本当前 ClassLoader 的 `configType` 传给同一个配置工厂重新物化。若配置字段不能转换、工厂拒绝该类型或入口 mount 失败，当前批次按 `APPLY` 失败并执行完整目录与运行态回滚；Fibra 不提供跨插件版本的配置迁移或兼容层。

`applyArtifacts` 的批次只包含插件候选，不包含配置文件事务。需要零停机改变配置 schema 时，现有不可变原始配置和配置工厂必须能分别为旧/新 `configType` 物化合法对象；完全不兼容的变更必须由宿主先通过 config reconcile 禁用/移除受影响 entry，再 apply 制品，最后写入新配置并重新启用。Fibra 不猜测字段映射，也不把配置与制品文件隐式拼成一个事务。

Spring Bean、静态缓存、ThreadLocal 或业务单例持有插件类都会阻止 ClassLoader 回收；插件对象不得进入 Spring BeanFactory。框架宿主只能持有 `FibraPluginLoader`/`FibraConfigLoader` 等父 ClassLoader 类型，并按 `entryId` 每次查询当前运行实例。

PF4J 二进制依赖和 Fibra 服务依赖继续完全分离：

- `plugin.dependencies` 决定类是否可见、制品能否 resolve；
- `PluginDescriptor.require`、配置 `inject/isolate` 决定 Fibra 何时 ACTIVE、服务落在哪个作用域；
- 两者不得互相推导或自动补齐。

## 9. Watcher

`FibraPluginWatcher` 只监听外部目录中原子发布的 `.zip` 候选，按 `pluginId` 去抖并调用 `applyArtifacts(List.of(candidate))`：

- 自动更新只接受已安装 ID 的严格更高版本；相同或更低版本忽略；
- 同一去抖窗口内同 ID 选择最高版本，同版本选择最后修改时间较新的文件；
- watcher 不删除、移动候选，不复制事务算法；
- 单包 prospective 图不兼容时失败并保留旧状态，通过 SLF4J 与 `lastFailure()` 暴露；
- 遇到 `FibraPluginLoaderBusyException` 时保留该 ID 的 dirty 候选，在活动事务退出后重新执行，不把瞬时竞争写入 `lastFailure()`；
- 多插件联动更新不能依赖文件到达时序，必须由部署协调器显式调用一次 `applyArtifacts(allCandidates)`；
- `close()` 停止接收事件并等待在途 apply 与 failure callback 完成。

`FibraPluginWatchFailure` 改为保存本次 package 路径与 cause，不保留 JAR candidate 兼容字段。

## 10. 示例与验收结构

仓内示例改成三类插件包，不按 provider/consumer 目录分类安装：

```text
fibra-example-contract-plugin  # contract-only，拥有 Greeting 类型
fibra-example-provider-plugin  # executable，依赖 contract artifact
fibra-example-consumer-plugin  # executable，依赖 contract artifact；运行时等待 provider 服务
```

provider 与 consumer 都依赖 contract，但 consumer 不因为使用服务而形成对 provider 的二进制依赖。这一结构用于证明 PF4J 图与 Fibra 服务图没有被混为一体。provider/consumer 仍只是示例中的业务角色，不是通用插件类别。

仓库外验证同步增加 contract-only 模块，并生成真实 ZIP：Host classpath 不包含任何插件或 contract 类型，只从插件目录加载；同一 provider 多 entry、consumer 服务等待、私有依赖隔离、版本范围、批量升级和失败恢复都必须由独立进程黑盒验证。

`verification/external-consumer` 同时是唯一用户插件工程模板和黑盒验收输入，不再维护第二份会漂移的脚手架。`0.3.0` 收口时它必须具备可直接执行的默认版本与 `mvn verify` 路径，产出 contract-only、executable 和多依赖示例的标准 ZIP；README 必须区分最小插件必需模块、可选 contract/consumer 和仅用于本地验证的 Host。验收脚本在复制后的工程上覆盖 Fibra 版本与临时仓库地址，不修改模板源文件。该工程始终不加入 Fibra reactor、不继承 Fibra parent，也不使用 Fibra 工作树 classpath；当前开发期不可解析版本哨兵必须在模板验收阶段删除。

## 11. 验收不变量

完成 `0.3.0` 必须由自动测试锁定：

1. 只接受标准目录安装包和标准 ZIP 候选，直接 JAR无任何兼容入口；
2. contract-only 可解析和被依赖，但不能 mount/configType；
3. executable 只能有一个自身入口，同制品可创建多个 entry；
4. 依赖插件入口不会被误认为当前包入口，索引缺类和链接失败不能被吞掉；
5. 必需/可选依赖的缺失、循环和版本范围全部以 prospective 完整图判断；
6. 单包不兼容更新在关闭旧 ClassLoader 前失败；相关多包候选可一次成功；
7. 三层依赖按 dependent-first 停止、dependency-first 恢复；
8. 成功更新关闭旧 ClassLoader并恢复全部 entry；
9. 业务入口失败恢复旧目录、旧制品状态、旧 entry 和旧服务；
10. 回滚失败的 stage、cause、suppressed 和事务目录完整；
11. 同版本同内容 no-op，同版本不同内容拒绝，显式合法降级成功，Watcher 忽略非升级候选；
12. 两个插件可各自携带同一三方库的不同版本，且类型只在各自 ClassLoader 可见；
13. config reconcile 与 artifact apply 共用同一串行边界；
14. 示例宿主和仓库外工程只使用真实 `plugin.properties + lib` 包；
15. 全部公开 API 签名、使用手册、架构文档和发布说明与实现一致。
16. loader 在等待 Fibra lifecycle 时不持有物理锁；lifecycle/Reactor non-blocking 回调管理重入立即报忙，身份快照查询不死锁；
17. 无 journal 预检垃圾可清理，`PREPARED/INSTALLING/APPLYING/COMMITTED` 的逐 ID 崩溃状态均按摘要确定恢复或拒绝；
18. PF4J 3.15.0 的 optional edge、扩展 finder 类加载失败和 SemVer 范围行为由直接测试锁定。
19. 仓库外插件模板可独立执行 `mvn verify` 并产出标准 ZIP，同一份模板由黑盒脚本在隔离仓库中实际构建，不存在未受验收的第二份脚手架。

## 12. 明确非目标

- 不支持不可信插件安全沙箱；
- 不支持同一 `pluginId` 多版本同时活动；
- 不在 Fibra 内下载依赖或访问插件市场；
- 不自动选择或更新依赖版本；
- 不提供跨主版本 ID 映射或服务键兼容层；
- 不让 PF4J `Plugin-Class`、Spring Bean 生命周期或其它 IoC 容器管理 Fibra 业务插件；
- 不以 Java DeepSeek Harness 的 agent/tool/provider/session 目录作为通用插件规范。
