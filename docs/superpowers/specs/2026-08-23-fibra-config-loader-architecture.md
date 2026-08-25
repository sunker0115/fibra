# Fibra 配置装载架构

日期：2026-08-23
状态：`0.3.0` 历史实现契约，已被 `0.4.0` Engine 架构取代

> 本文只用于追溯 `0.3.0` 配置 loader 的历史行为。其中 watcher、宿主编排、关闭顺序和模块数量均不是 `0.4.0` 当前契约，不得据此实现或使用。当前权威源是 [Fibra Engine 架构](./2026-08-24-fibra-engine-architecture.md) 和 [`docs/api/README.md`](../../api/README.md)。

> 本文是 `fibra-loader-config` 0.3.0 的历史实现契约。制品格式、`applyArtifacts(List<Path>)` 和逻辑事务门以当时的[插件制品与事务更新设计](./2026-08-23-fibra-plugin-package-transaction-design.md)为准；本文只记录历史配置树、typed config、reconcile 与制品事务的协作边界。

## 1. 目标与真源

`fibra-loader-config` 是 Fibra 的框架中立动态组合层。它负责把 YAML/JSON 配置转换为插件条目树，并以可回滚事务把目标树同步到 `fibra-loader-pf4j`。它不是 Spring Boot 配置绑定工具，也不是只减少几行启动代码的便捷封装。

行为真源固定为 DeepSeek Harness 提交 `141eb6fef83422698aef7a981029e843e8161534` 中：

- `vendor/loader/src/config/entry.ts`、`group.ts`、`tree.ts`、`isolate.ts`；
- `vendor/include/src/index.ts`；
- `vendor/hmr/src/index.ts` 中配置文件刷新部分。

Java 实现必须保留稳定条目身份、多实例、分组作用域、禁用继承、依赖注入、隔离、拦截、patch 顺序、串行刷新、失败回滚和最后成功快照。Node 模块缓存和 JavaScript 字节码 HMR 不属于本模块；标准插件包更新由 PF4J loader 负责。

## 2. 制品/实例边界

`fibra-loader-pf4j` 已把 PF4J 制品与 Fibra 运行实例拆开，不保留原“一制品一 Fibra”入口或兼容分支。

身份含义固定为：

| 身份 | 唯一性 | 生命周期 |
|---|---|---|
| `pluginId` | 每个已装载 PF4J 制品唯一 | load/stop/unload/apply、properties 依赖、ClassLoader |
| `entryId` | 每棵配置树中的完整路径唯一 | mount/update/unmount、配置、Fibra、作用域 |

一个 `pluginId` 可以创建任意多个 `entryId`；一个 `entryId` 在任一时刻只对应一个 `pluginId` 和一个 Fibra。`plugin.properties` 的 PF4J 依赖只描述制品和 ClassLoader，Fibra `require`/配置 `inject` 只描述运行期服务就绪，二者不得合并或在 YAML 中重复声明。

PF4J 扩展点直接改为工厂语义：

```java
public interface FibraPluginEntrypoint<C> extends ExtensionPoint {
    Class<C> configType();

    PluginDescriptor<C> descriptor(String entryId);

    Plugin<C> create(String entryId);
}
```

PF4J 的 `ExtensionWrapper` 会缓存扩展实例，因此 mount 与制品事务恢复必须只借助自身索引发现入口类，再调用其无参构造器创建一次性入口并执行 `descriptor(entryId)`、`create(entryId)`。update 同样创建一次性入口，但只读取当前 `configType`，随后更新已有 Fibra，不创建 descriptor 或插件回调。不得跨多个 entry 或同一 entry 的不同生命周期复用可变入口对象。无配置插件使用 `Void.class`，仅接受 `null`。

`fibra-loader-pf4j` 的公开运行实例操作固定为：

```java
Class<?> configType(String pluginId);

Fibra mount(PluginInstanceSpec spec);

Fibra update(String entryId, Object config);

Fibra updateWithFactory(String entryId, PluginConfigFactory configFactory);

void unmount(String entryId);

List<String> entryIds();

Optional<Fibra> fibra(String entryId);
```

`PluginInstanceSpec` 使用 builder，字段为 `entryId`、`pluginId`、`parentContext`、`PluginConfigFactory` 和按服务名声明的 `requirements`。配置层工厂只捕获不可变原始配置；每次 mount、update 或制品事务恢复时，它根据当前入口的 `configType` 新建 Jackson mapper 并重新转换，绝不捕获插件 `Class<?>`、typed config 或旧 ClassLoader。`mount` 自动启动目标 PF4J 制品及其 `plugin.dependencies`，但不会为 contract-only 或其他依赖制品虚构 Fibra 实例。配置树负责创建所有 entry，Fibra 服务依赖负责把尚未满足的 consumer 稳定在 `PENDING`。

公开制品操作统一为 `loadArtifacts/applyArtifacts/stopArtifact/unloadArtifact/artifactIds`。没有公开单候选安装入口，也没有公开 `startPlugin`：单包用 `applyArtifacts(List.of(candidate))`，制品启动是 mount 的内部前置动作，宿主不能创建没有配置身份的 Fibra。

制品事务必须快照受影响制品及旧/新图依赖方的全部 `PluginInstanceSpec`，依赖方优先卸载，制品依赖顺序重新装载，再按原 entry 顺序用配置工厂重新物化 typed config 并恢复全部实例。恢复失败时依据持久 journal 回滚安装目录、PF4J 状态和全部旧实例；不能只恢复每个制品的一个 Fibra，也不能把旧 ClassLoader 创建的 typed config 传给新入口。

## 3. Core 的声明式最小增强

配置文件按服务名声明 `inject` 和 `isolate`，不能制造 `ServiceKey<Object>`，否则会把 `Object.class` 写入同名服务类型声明并与真实插件类型冲突。

`fibra-api` 增加：

```java
Context isolate(String serviceName);

Context isolate(String serviceName, Object label);

PluginDescriptor.Builder<C> require(String serviceName);

PluginDescriptor.Builder<C> require(String serviceName, Object intercept);

void require(String serviceName);

void require(String serviceName, Object intercept);
```

字符串依赖只参与名称、isolate token 和 ACTIVE provider 检查，不声明或缓存 `Class<?>`。插件代码继续优先使用类型化 `ServiceKey<T>`；实际 `get/provide` 仍执行现有严格类型校验。同一 descriptor 同时以 typed 和 name-only 形式声明同名依赖时，构建阶段直接拒绝。

## 4. 模块与依赖

最终生产依赖方向为：

```text
fibra-loader-config
  -> fibra-loader-pf4j
  -> fibra-api
  -> Jackson Databind + Jackson YAML
  -> SLF4J API

fibra-loader-pf4j -> fibra-core + fibra-pf4j-api + PF4J
fibra-pf4j-api    -> fibra-api + PF4J
fibra-core        -> fibra-api + Reactor Core + SLF4J API
```

`fibra-loader-config` 不依赖 Spring、Spring Boot、Spring AI、Hasor 或 Solon。Jackson 固定使用 `3.1.5` LTS：

```text
tools.jackson.core:jackson-databind
tools.jackson.dataformat:jackson-dataformat-yaml
```

Jackson 只存在于实现和发布依赖中，任何 public/protected 签名不得出现 Jackson 类型。YAML/JSON 都转换为不可变的 Java `Map/List/String/Number/Boolean/null` 图；传给调用者或插件前必须深复制/冻结，patch 不得反向污染解析缓存或上一份成功快照。

## 5. 配置文件模型

配置文件扩展名只接受 `.yaml`、`.yml`、`.json`，顶层必须是数组。每个节点恰好属于一种类型。

### 5.1 插件节点

```yaml
- id: llm-primary
  name: harness-llm-deepseek
  disabled: false
  inject:
    settings: null
    telemetry:
      level: basic
  intercept:
    logger:
      level: INFO
  isolate:
    llm: primary
    cache: true
  config:
    model: deepseek-chat
```

- `id`、`name` 必填且非空；`name` 精确等于已装载 `plugin.properties` 中的 `plugin.id`。
- `config` 可以是任意 JSON 值；缺省为 `null`。
- `inject` 接受字符串数组或“服务名到 intercept 值”的对象，规范化为有序 map。
- `intercept` 是“服务名到配置值”的对象。
- `isolate` 是“服务名到 `true` 或非空字符串标签”的对象。`true` 表示该完整 entry 私有 token；字符串表示同一配置 loader 内同标签、同服务名共享 token。

### 5.2 分组节点

```yaml
- id: agents
  group: true
  disabled: false
  isolate:
    agent: true
  config:
    - id: main
      name: harness-agent
```

分组节点要求 `group: true`，禁止 `name`、`include`、`inject`，`config` 必须是子节点数组。分组自身始终保留一个内建 Fibra 作为父作用域；`disabled: true` 不停止分组 Fibra，只使全部后代不 mount。分组的 isolate/intercept 由后代继承。

### 5.3 include 节点

```yaml
- id: base
  include: ./base.yml
  patches:
    - id: persistence
      config:
        root: ./.sessions
```

include 节点要求 `id`、`include`，禁止 `name`、`group`、`config`、`inject`。路径相对包含它的文件解析，必须规范化为真实绝对路径；同一 include 栈再次出现相同真实路径即报环。include 作为内建分组 Fibra存在，子 entry 的完整 ID 以 include ID 为前缀。patch 只作用于被 include 文件内部，不跨越下一级 include 边界。

### 5.4 完整 ID

同一组内 raw `id` 必须唯一；完整 ID 用 `:` 连接祖先 ID，例如 `agents:main`。任意层 raw ID 禁止包含 `:`，整棵运行树的完整 ID 必须唯一。所有 resolve/update/remove、日志和异常只使用完整 ID。

未知字段、重复 YAML/JSON key、错误字段类型、空 ID/name、非法节点组合和超出限制均在触碰运行态前失败。默认限制固定为：文件 4 MiB、嵌套深度 100、字符串 1 MiB、单文件 entry 10,000；这些值作为 builder 参数可向下收紧或显式放大，不从系统属性隐式读取。

## 6. 环境值与表达式

`fibra-loader-config` 只接受 YAML/JSON 字面值，不执行 `!!js`、JEXL、SpEL、脚本引擎、反射调用或任意类构造。原 DeepSeek Harness 的 `!!js` 是 Node 宿主的代码执行能力，不是插件生命周期本身；照搬会把不受控代码执行和宿主对象图暴露给通用 loader。

Java DeepSeek Harness 必须在调用 loader 前，把 Spring Boot 静态配置、环境、命令行、当前目录和启动服务事实转换成显式 `FibraConfigPatch`。因此能力映射为：

```text
原文件字面值              -> Fibra YAML/JSON 字面值
process.env / cwd          -> Spring Boot 静态配置生成的 patch
ctx.xxx 启动事实           -> Harness 启动协调器生成的 patch
--profile / bundle overlay -> 有序 FibraConfigPatch 列表
```

patch 的最终结果会进入配置快照、错误报告和 dump，运行态不存在隐藏表达式。业务插件内部的动态默认值由其 typed config validator 负责。该替换保留可配置结果和 profile 组合能力，同时利用 Java 的类型化宿主配置增强可审计性；不得把 `application.yml` 直接当插件树，也不得让 Spring 类型进入本模块 API。

## 7. Patch 语义

`FibraConfigPatch` 有两种互斥形态：

- insert：无 `id` 时向当前文件根组尾部插入；有 `id` 时目标必须是当前文件中的 group，向其子列表尾部插入。
- override：必须有 `id`，可选 `name` 作为防误配断言，其余出现的字段整体替换目标字段；显式 `null` 删除可选字段，不能修改 `id`。

patch 按传入顺序逐层应用，输入树和 patch 均不修改。前一个 patch 插入的 entry 必须立即加入本层索引，后一个 patch 可以命中。目标不存在、insert 目标不是 group、`name` 不匹配均产生结构化 warning 并跳过该 patch；格式错误和应用后树校验失败则终止候选。

每个文件的 patch 只作用于该文件：根文件按 builder `patches(...)` 的列表顺序应用；include 文件按包含它的 include 节点 `patches` 列表顺序应用。根 patch 不自动穿透 include 边界，include patch 也不作用于下一级 include 文件。调用方需要 profile/bundle 多层覆盖时，必须先按低优先级到高优先级合并成一个有序列表传给 builder。

## 8. 公开 API

公开包固定为 `com.sstlfsj.fibra.loader.config`，核心类型为：

```text
FibraConfigLoader
FibraConfigEntry
FibraConfigPatch
FibraConfigSnapshot
FibraConfigRuntimeEntry
FibraConfigException
FibraConfigErrorStage
FibraConfigWarning
FibraConfigWatcher
FibraConfigReloadFailure
```

构造与主要操作：

```java
FibraConfigLoader loader = FibraConfigLoader.builder(root, plugins, configPath)
    .patches(profilePatches)
    .warningSink(warningSink)
    .build();

FibraConfigSnapshot first = loader.load();
FibraConfigSnapshot next = loader.refresh();
FibraConfigSnapshot current = loader.snapshot();

Optional<FibraConfigRuntimeEntry> entry = loader.resolve("agents:main");
String id = loader.create(parentId, position, newEntry);
loader.update(entryId, patch, parentId, position);
loader.remove(entryId);

FibraConfigWatcher watcher = loader.watch(debounce, failureSink);
```

`load()` 只能成功一次；`refresh()` 展开后的 entry 树相等时返回同一 snapshot 对象，纯格式或注释变化不会产生新 snapshot。`create(parentId, position, entry)` 中 `parentId == null` 表示根，`position < 0` 表示尾部，超出尾部的非负位置截到尾部。`update(entryId, patch, parentId, position)` 只接受 target 等于 `entryId` 的 override patch，可同时修改并移动节点；`remove(entryId)` 删除节点。include 子节点写回其 include 文件；builder/include patch 插入且原始文件中不存在的合成节点没有可写源节点，update/remove 以 `VALIDATE` 拒绝。只读文件以 `WRITE` 拒绝。`close()` 先关闭 watcher 并等待在途刷新，再按依赖方优先和树的逆序卸载全部托管 entry；它不关闭调用方拥有的 PF4J loader 或 root Context。

## 9. 候选、diff 与事务

每次 load/refresh/update 固定执行：

1. 在逻辑事务门内读取全部涉及文件；若解析、patch 和继承展开后的 entry 树与当前 snapshot 相等，直接返回当前 snapshot。
2. 解析 YAML/JSON，深复制并按顺序应用 patch。
3. 校验全部文件、include 图、节点和完整 ID；对包括 disabled 后代在内的每个插件节点解析 artifact 引用并读取入口 `configType()`，使禁用不能掩盖拼错的插件或非法配置。
4. 对每个插件节点用一次性 Jackson mapper 把普通值转换为当前插件 ClassLoader 中的 config 类型，只做候选校验并立即释放 typed config；插件 class 不需要也不得依赖 Jackson 注解。实际 mount/update 由 `PluginConfigFactory` 再按当时的 `configType` 物化，保证制品更新后使用新 ClassLoader 类型。
5. 展开 disabled、父 Context、isolate、intercept 和 inject，生成不可变候选 snapshot。
6. 与最后成功 snapshot 按完整 `entryId` 比较。
7. `entryId/pluginId/父完整 ID/inject/isolate/intercept/节点类型` 均未变化而只有 config 变化时调用 `Fibra.update()`；其余变化执行替换。
8. 先按依赖方优先和子节点优先卸载删除、禁用和替换项，再按树顺序 mount 新增、启用和替换项。互不依赖的 sibling 也串行处理，以保证确定性和可逆 journal。
9. 等待每个受影响 Fibra 收敛。`PENDING` 是服务尚未到位的合法稳定状态；`FAILED` 或生命周期异常使事务失败。
10. 全部成功后一次性提交 snapshot；程序化修改随后才允许提交已暂存的文件。

事务开始时记录每项反向操作。失败时严格逆序执行 journal：撤销新实例、恢复旧上下文和旧原始配置工厂、重新 mount 旧实例，并等待全部恢复实例收敛。回滚成功时调用者收到原始阶段异常，当前 snapshot 保持不变；只要任一恢复动作失败，最终异常 stage 必须为 `ROLLBACK`，原始应用异常作为 cause，全部恢复失败按发生顺序直接挂在该 `ROLLBACK` 异常的 suppressed 列表，调用者不必穿透 cause 才能发现恢复不完整。

配置事务、程序化修改、配置 watcher 回调和 PF4J `applyArtifacts` 必须使用同一个 `FibraPluginLoader` 逻辑事务门。所有者线程可重入，其他线程竞争立即报 busy；门不得在跨 lifecycle Scheduler 阻塞等待时持有物理锁，前一次失败不能毒化后续操作。

跨模块协调入口固定为 `FibraPluginLoader.runExclusive(Supplier<T>)` 和 `runExclusive(Runnable)`。它们在 loader 关闭检查后登记当前所有者与重入深度；config loader 的一次完整事务只进入一次该入口，回调内调用 mount/update/unmount 直接重入。其他线程收到 `FibraPluginLoaderBusyException` 后由 watcher 保持 dirty 并重试；同步调用方自行决定重试策略。该 API 不暴露锁对象、tryLock、超时或手工 begin/commit，事务提交和回滚仍由调用方的一次回调完整拥有。`artifactIds()/entryIds()` 读取最后成功提交的不可变身份快照，不进入事务门。

## 10. 错误、日志与 watcher

`FibraConfigErrorStage` 固定为：

```text
READ, PARSE, VALIDATE, RESOLVE, CONVERT, DISPOSE, APPLY, WRITE, ROLLBACK
```

`FibraConfigException` 必须携带 stage、文件绝对路径（若适用）、完整 entry ID（若适用）、pluginId（若适用）和 cause。不得只返回拼接字符串，也不得吞掉回滚错误。

patch 跳过使用 `FibraConfigWarning`；文件刷新失败使用调用方显式提供的 `failureSink`，同时通过 SLF4J 记录。禁止 `System.out/System.err`。监听器合并 `CREATE/MODIFY/DELETE` 事件，采用 dirty 标记加单运行任务；删除当前配置文件视为 READ 失败并保留最后成功运行态。resolver 同时记录最后成功配置路径和本次失败候选尝试访问的路径。watcher 除目录事件外还以有界周期比较被监听路径的存在状态，并在失败候选更新路径集合后检查文件是否已经可用；即使新增 include 的父目录最初也不存在，或目录事件与监听集合更新交叉，文件随后创建仍会自行恢复，不要求再次修改根文件。每轮候选后重新计算目录注册并注销已不再引用的目录。`FibraConfigWatcher.close()` 必须停止接收新事件并等待正在执行的 refresh 和 failure callback 完成；config loader 先原子进入 `closing` 并摘除 watcher，才能在协调锁外执行 close/join，`watch()` 在 `closing/closed` 状态一律拒绝。

程序化 create/update/remove 在触碰运行态前，先把待写 YAML/JSON 重新解析并执行与文件 refresh 相同的文件大小、嵌套深度、字符串长度、entry 数量和结构校验，公共写 API 不能绕过 builder 限制。随后使用两阶段写回：先为每个涉及文件在同目录创建随机临时文件、写入并 `force`；再执行运行态事务；运行态成功后才对每个目标执行 `ATOMIC_MOVE + REPLACE_EXISTING` 并提交 snapshot。若运行态失败则删除临时文件；若某次 rename 失败，则用原始字节原子恢复此前已经替换的文件，再把运行态回滚到旧 snapshot。单个文件替换具有文件系统原子性；多个文件没有跨文件系统调用的一次性原子 rename，因而外部绕过 loader 的并发文件读取者可能短暂观察到部分新文件。loader 自身不会提交部分 snapshot，并会恢复全部仍可恢复的文件；如果文件恢复本身失败，最终报告 `ROLLBACK` 并把各恢复失败直接放入 suppressed，不能承诺外部文件全部不变。不支持原子移动的文件系统直接报告 `WRITE`，不能退化为原地覆盖。外部 refresh 从不写文件。

## 11. ClassLoader 与资源所有权

- PF4J loader 是插件 entry 对应 Fibra 的唯一运行时所有者；config loader 对插件 entry 只保存普通元数据，`resolve(entryId)` 每次动态查询 PF4J loader，不能复制缓存插件 Fibra、Context、插件回调、descriptor、typed config、配置类型或异常 cause。
- group/include 是 config loader 自己创建并拥有的内建作用域，因此托管表在它们存活期间保存对应 Fibra；不另存冗余 Context，访问时由 Fibra 获取。unmount 插件 entry 后 config loader 不保留其运行对象；PF4J loader 移除入口引用并等待 Fibra dispose，再 stop/unload ClassLoader。
- snapshot 只保存普通不可变配置值和字符串身份，不保存插件 `Class<?>` 或 typed config。
- Jackson mapper 可以全局复用，但不得注册插件 ClassLoader 模块、mixin 或 subtype 并长期缓存。每次转换完成后不得由 mapper 配置持有插件类。
- 动态插件不进入 Spring BeanFactory；配置 watcher 和插件 ZIP watcher 都由宿主生命周期协调器显式关闭。

## 12. 测试与发布门禁

实现必须先写失败测试，并覆盖：

1. YAML/JSON 等价、重复 key、未知字段、限制和错误 stage。
2. patch 深复制、root/group insert、同层后续 patch 命中、warning 和 name 防误配。
3. group/include、完整 ID、include 环、disabled 继承、local/shared isolate 和 intercept 继承。
4. name-only inject 不污染服务类型声明，provider 到位后 PENDING entry 自动 ACTIVE。
5. 同一 PF4J 制品同时 mount 多个 entry，config 类型转换和实例互不共享。
6. config-only update 保持 Fibra 身份；边界变化替换实例。
7. 第 N 项失败完整恢复旧 snapshot、配置、服务和值；多文件第 N 次 rename 失败恢复所有可恢复文件和旧运行态；文件恢复与运行态恢复同时失败时，全部失败直接位于最终 `ROLLBACK` 的 suppressed。
8. refresh 串行、失败后下一次可成功、外部运行态漂移可按同一 snapshot 自愈、watcher create/modify/delete/父目录不存在的缺失 include 恢复/close 等待在途回调/watch-close 并发互斥。
9. 标准包批量更新恢复受影响制品的全部 entry，插件私有 typed config 切换到新 ClassLoader，并与配置 refresh 不交叉。
10. 真实 contract/provider/consumer ZIP 的多实例、依赖 ClassLoader、配置更新和失败恢复黑盒测试。

远程发布面从四个扩展为五个正式制品：`fibra-api`、`fibra-core`、`fibra-pf4j-api`、`fibra-loader-pf4j`、`fibra-loader-config`。必须同步根 POM、dependencyManagement、发布基线、五份 `javap` API 基线、可复现脚本、仓库外消费脚本、README、发布文档和第三方依赖声明。仓库外验收必须由独立 Engine application 通过 Maven 坐标读取真实 YAML、创建同制品多 entry，并验证更新与回滚；仓内 parser 单测不能代替该门禁。

## 13. 明确非目标

- Spring/Hasor/Solon 容器集成；
- Spring Boot `application.yml` 自动绑定；
- Spring AI 模型、工具或 RAG 配置；
- JavaScript、JEXL、SpEL 或其它任意表达式执行；
- Node 模块缓存、源码级或字节码 HMR；
- 不可信插件沙箱；
- 同一 `pluginId` 多版本同时运行。
