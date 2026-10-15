# Fibra 公共 API 整体架构与 Java 合同类型身份

日期：2026-10-15。基线：`5b2d2176`。状态：整体设计及声明格式已独审，本地实现、公开制品和仓外消费资格已通过；
源码及证据随本批本地提交归档，未发布远端 release。精确证据见[交付记录](../references/host-api/delivery.md)。

## 1. 目标、受众与唯一体系

Fibra 对外提供一套按职责分层的 API。分层服务不同受众，不为不同产品建立平行框架，不通过一个万能 facade
遮蔽资源所有权。现有模块仍是发布/依赖边界；不为本次缺口改名或整体重组模块。
本文统一公共 API 的导航、owner 和演进要求；具体语义仍由相应专项规格负责。已交付签名以
[`docs/api`](../../api/README.md) 的编译基线监控可见性；支持受众与精确内部协作例外以
[公开审定清单](../../api/public-api-inventory.md)和[稳定边界](2026-10-15-public-api-boundaries-design.md)为准。
候选签名必须通过同批制品验证后才成为当前支持合同，不能把所有public类型自动冻结为SDK。

| 层次/受众 | 已有正式模块与入口 | 唯一职责 | 不允许的旁路 |
|---|---|---|---|
| 插件作者与进程内内核 | `fibra-api` 的 `PluginDefinition`、`PluginEntrypoint`、`Context`、`ServiceKey`、`ScopeView`；`fibra-core` 的 `FibraRuntime/RuntimeDomain` | 定义插件、作用域、服务、事件、effect 及资源树；可独立嵌入 | core 不读取 package/target，不依赖 Engine、runtime 或 Spring |
| 不可变制品与配置 | `fibra-artifact` 的 package/store/install transaction；`fibra-config` 的 desired/value/schema | 制品事实、精确 revision、配置表达与求值 | 不执行插件、不另造生命周期；runtime 私有声明不复制根 package 的身份/依赖图 |
| 托管宿主控制 | `fibra-engine` 的 `FibraEngine`、完整 command、只读 observation | 唯一 target CAS、command lane、编译与 generation 协调 | 不能直接操作 driver/unit 或发行第二控制 facade |
| 能力发布与调用 | `fibra-bridge` 的 kind/codec/registrar/admission；Engine 的 `PublishedRuntime` | bridge 持注册/调用租约；托管调用由唯一 PublishedRuntime 校验 view/registration fence | 宿主不持私有 directory/handler，不旁路 fence 调用 |
| 执行器作者 | Engine `.runtime` 的 provider/driver/candidate/generation/unit SPI | 在统一 Engine 编排下执行一个 runtime 的 slice | provider 不持 Host scope/driver；runtime 不保存 target、不互相依赖 |
| 标准 Java/Node 执行器 | `fibra-runtime-java`、`fibra-runtime-node` | 解释私有 payload、物化定义、持加载器/sidecar 和静态 lease | Engine 不加 Java/Node/产品分支；类型共享不下放启停权 |
| 管理和交互适配 | Registry、CLI、Spring | 管理命令翻译、交互会话、容器适配 | 不增加第二 target/恢复/服务注入通道；借用对象不重复关闭 |
| 外部 execution/客户端 | Java `fibra-client-protocol`、npm `client-api/client-protocol` | 与统一生命周期、发布和调用一致的 wire 值/codec 与作者 SPI | 不引入 Java Class 身份到跨进程，不拥有产品 renderer/transport/会话事实 |

内核独立嵌入和 Engine 托管是同一内核的不同组合深度。选择 Engine 后，由它独占该运行域的管理与关闭，
不能同时从 core 直接挂载或停止同一组托管 units。Registry 是管理用例适配，不是另一 Engine；
CLI 和 Spring 不能建立各自的共享 API 或服务发现机制。外部 provider 与产品 composition root 使用相同正式制品。

### 1.1 五种身份不能混为一个注册表

| 合同 | 表达的事实 | owner/变更窗口 |
|---|---|---|
| `ServiceKey<T>` 与 Host service binding | 在 realm 中提供哪个服务对象 | 原内核服务树；HostServiceRegistry 在启动时冻结，宿主拥有对象 |
| `ContributionKind<D,I,O>` | 一类贡献的 descriptor、输入、输出及可选 codec | 原 Bridge；local 依赖真实 Class/kind 身份，remote 依赖原 codec |
| Java API 类型合同（本次缺口） | 哪些受信 Class 是同一 release 的稳定签名词汇 | Host 发行生命周期；仅 Java provider 解释，不注册服务或贡献 |
| package/facet/definition/desired entry | 部署什么不可变制品以及创建哪个实例 | 原 artifact/config/Engine，沿完整目标与 CAS |
| unit/view/registration/session fence | 当前允许哪个实例/注册接纳一次操作 | 原 Engine/Bridge/外部 execution 协议；不能用类或包版本代替 |

类型合同只是服务/贡献正确使用类型的先决条件。拥有合同不等于拥有服务、贡献、权限或启停能力；
Host service 继续经 `HostServiceRegistry` 进入原 `RuntimeDomain`，动态贡献继续经原 registrar 发布。
不新增 API registry、API plugin、service locator、调用 gateway、容器或第二恢复状态。

## 2. 问题归属与有界模式分析

本节是基线 `5b2d2176` 的问题分析；交付后的当前合同见 §4/§5。
该基线默认 Java loader：已加载 → 平台实际解析 → 固定宿主共享基线实际解析 → 本制品 → 声明的动态依赖。
`JavaClassIndex` 使用相同平台/parent 判定，并跳过实际父共享类；ClassSpace 物化入口，driver 的
StaticWiring/StaticLease/AcquisitionOwner 负责成功、部分取得与失败清理。Engine 只通过统一 SPI 协调。
共享业务 API 缺口因此归 Java runtime 的类型解析，不能放进 core 服务注册、Engine 业务规则或 Spring exporter。

基线核查来源：

- `JavaRuntimeProvider` 当时只收 built-ins；`JavaBuiltInPackage` 是内建定义及 metadata，不是 class export。
- `PluginClassLoader.loadClass` 按依赖 loader 递归解析；未导入 facet 可能间接借到依赖的 Host API，必须阻断。
- `JavaClassIndex.validate` 在重复检查前跳过 parent 类；简单增加共享前缀会漏掉插件携带的影子 API。
- `ContributionDirectory.acquireEntry` 对 kind 使用对象身份；复制同字节 JAR 不能代替共享定义类。
- `HostServiceRegistry` 已有统一宿主服务装配；不得再借类型共享创建第二注入通道。
- Java descriptor/源码异常沿 MANIFEST/LOAD/CLOSE 分类；不另造错误体系或吞 LinkageError。
- 现有 driver 测试提供真实 JAR、失败 close owner、lease/refcount 和 loader 回收证据；新增路径复用其风格。

真实消费者要求包括 sealed input/output、嵌套 record、enum、泛型容器、builder、descriptor 与静态 kind。
消费者特定名字只用于来源记录和仓外验证，不进入 Fibra 实现和正式示例。两个不相关的验证 API 必须可独立导出和导入。
模式分析、真实来源位置及固定制品摘要见[资格输入](../references/host-api/qualification.md)。

## 3. 路线比较与一手参照

| 路线 | 收益与维护/关闭成本 | 决定 |
|---|---|---|
| 精确 `Class<?>` inventory | 直接绑定 Host 实际 Class；同包实现不自动暴露；nested/签名引用清单需显式维护和校验；不创建/关闭 loader | 采用；符合窄合同和 Host 发行 owner |
| 精确 package 集合＋anchor loader | 省清单；需定义包枚举、split package、JAR/module/自定义 loader 与新增类规则；同包新类自动扩大暴露面 | 拒绝当前路线；消费者 API 包内确有非合同入口，且没有需要包级动态发现的消费者 |
| 全 classpath/前缀、OSGi resolver 或新共享容器 | 前者扩大不受控可见性，后者引入版本解析与新 owner/生命周期；都超过当前精确 release 需求 | 拒绝；不作为兜底 |

直接采用 JDK 的定义 loader/平台可见性规则；最小改造现有 Fibra loader/index；Host 精确 release 的声明/失败语义
是 Fibra 自己的合同。PF4J 的插件 loader/父委派与 OSGi 的显式导入导出只作原则参照，不采用其容器、
扩展发现或完整 resolver，也不声称获得这些项目的全部保证。

一手来源（2026-10-15 核）：[JDK21 ClassLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)、
[PF4J class loading](https://pf4j.org/doc/class-loading.html)、
[OSGi Core module layer](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html)。
参照不解决恶意同 JVM 代码的反射、任意 I/O 或线程行为；ClassLoader 是类型隔离，不是安全沙箱。

## 4. 同一 provider 装配面的精确合同

只在 `com.sstlfsj.fibra.runtime.java` 增加不可变值 `JavaApiContract`：

```java
new JavaApiContract("example.document", "1", List.of(
    DocumentApi.class, DocumentDescriptor.class, DocumentRequest.class, DocumentResult.class));
new JavaRuntimeProvider(builtIns, apiContracts);
```

完整最小公共签名为 `public record JavaApiContract(String id, String version, List<Class<?>> classes)`，
提供规范构造器与同名三个访问器；classes 返回防御复制的不可变 list，不增加公开 registry 或 builder。
字段为非空白 `id`、非空白精确 `version` 和非空清单，名字按 binary name 唯一。
只收 public、非数组/primitive/anonymous/local/hidden 的具名类型；public nested 的 public enclosing 链也须显式导入。
禁止任何由 Fibra 受管 `PluginClassLoader` 定义的 Class 进入 Host inventory，不能把动态 facet 类型提升成发行类型。
自有 Host 发行 loader 可用，仍由 Host 关闭。不允许覆盖平台或现有固定共享基线，或多个合同占有同一 binary name。
同一 provider 的合同 id 唯一，不做多版本选择；配置在 provider 创建时完全冻结。

既有单参数 provider 构造器保留，仅委托到 `apiContracts=List.of()` 的同一实现；不是兼容模式、第二配置源或
新 builder。新建时不创建 loader、Scope、服务或 driver；Host 在 Engine 全部依赖释放后才可关闭其发行 API loader。
provider 仍是可顺序复用的无 Host 状态 factory，每次 create 只创建全新 driver；driver 借用冻结的 Class inventory。
非法 id/version/list、类型资格、重复 owner 或 ABI 解析失败统一为 composition 阶段 `IllegalArgumentException`，
解析失败保留 cause；不引入新的公共异常层次。record 只验证本值形状，跨类型闭包由 provider 验证。

### 4.1 签名闭包校验

inventory 显式列出所有非基线公开合同类型，包含实际可解析的 public nested、public sealed permitted、enum、record 及 builder。
校验只核对、不自动增加导出：类的 enclosing/层次/泛型边界、public/protected 方法/构造器/字段的类型、
参数、返回、throws 和泛型签名引用必须来自显式 inventory 并集或实际同身份的现有共享基线。
显式检查 public/protected member nested 和 public permitted，而非只靠方法签名间接遇到它们；
protected 非 public nested 与非 public hierarchy/signature 类型不满足 public inventory 条件，明确拒绝。
nonpublic permitted 实现可留在 Host 定义 loader，不扫描其私有实现树；若实际进入公共签名，仍按上述规则拒绝。
反射 `getPermittedSubclasses()` 会忽略不可解析项，只核 Host 实际可解析的 public permitted，不证明 classfile
全部 permits 项的发行完整性；Host 完整发行与仓外真实消费者负责该窗口，不自写 classfile parser。
数组退到 component；ParameterizedType owner/args、Wildcard/TypeVariable bounds、GenericArray 均递归，visited 防环。
不运行初始化器，不读取静态 kind/ServiceKey 的字段值，不扫描方法体、私有实现或任意注解图。
JDK 的 `getDeclaredFields/Methods/Constructors/Classes` 在枚举元数据时可能先链接 private descriptor 引用；
Host 必须完整发行这些元数据所需类型，缺失时 composition 以 IllegalArgumentException 保留链接 cause 拒绝，
即使该类部分公开方法本身可调用。可链接的 private 类型不要求导出，也不递归其实现图；
这里不承诺对残缺 Host 制品作 public-only 反射链接，不为规避该窗口增加字节码 parser 或依赖。
全部导出并集的 public/protected ABI 未闭合、同名不同 Class、签名解析失败均在 composition root 直接拒绝。
允许 API 分层引用：公共 DTO 只归一个合同 owner。每 facet 在 id/version 匹配后对**显式导入并集**复用同一闭包校验；
引用另一个合同的类型就必须显式导入那个合同，缺少时 LOAD 拒绝并指明类型/owner。不自动传递、不维护 resolver。
仅有 A 合同但 A 引用 B 的 provider 配置失败；完整 A+B 的 provider 下，A-only facet 失败、A+B facet 成功。

Host 对导出清单是否只含业务合同负最终责任；运行时不能从一个 public Java 类推断它是不是 Store/实现。
本机制不自动导出 Spring、业务实现或 Host classpath。

### 4.2 每 facet 显式导入

`META-INF/fibra/plugin.yaml` 在原 entrypoint/definitionId 之外增加唯一可选字段：

```yaml
entrypoint: example.plugin.DocumentEntrypoint
definitionId: document
hostApis:
  - id: example.document
    version: '1'
```

hostApis 是显式 id/version 对象列表，缺省为空；每项只允许这两个必填、非空白字符串字段。
null、非 list、非 object 项、字段缺失/类型错误/未知/重复、重复 id 均 MANIFEST 拒绝；不支持范围、latest、自动选择。
contract-only JAR 可以仅有 hostApis，仍没有 definition。runtime私有解析值 `JavaFacetDescriptor` 将其规范化为不可变
id→version map；该Java类型不是作者/Host合同，格式作者只使用列表声明。只接受列表这一种声明格式。
采用列表与现有制品 dependency 声明风格一致，避免 YAML 的 JSON 对象模型将数字键隐式字符串化；
不增加第二 parser、原文扫描器或依赖。运行期 Map 是已验证声明的索引，不是另一配置来源。
这不是由解析器限制推导出的唯一设计：id→version map 对文本标识符也合理；这里选择对象列表是为了与
现有依赖声明的条目模型一致，并将名称和版本作为可独立校验的值。相比 PF4J/OSGi 的文本条目，不另建 DSL。
Pi 的 package.json、DSH 的 entry 列表/inject 对象都不提供相同的 Java ABI 合同；它们不是本格式的直接实现来源。
参照差异与本项目选择见[资格输入](../references/host-api/qualification.md#声明格式的参照边界)。
package 根依赖仍只表达动态 facet 图，不复制 Host API 信息到 package/target 或另加 host-api 插件节点。

候选 prepare 在物化该 facet 入口前核 Host id/版本并扫描自身有效类名；缺合同、版本不等、携带任意已导出
binary name（包括未导入合同、同字节、依赖 JAR、多 release 生效类）均 LOAD 失败，不允许本地覆盖。
每个被取得的动态依赖也执行自己的校验。静态目录 query 只读声明，不证明导入可用，也不加载类型。
probe/inspect 保持私有声明形状校验；运行资格仍由 prepare 决定，不把安装成功当运行通过。

这里的缺合同是**显式声明**的 id 在 Host 缺失，或导入并集的公开 ABI 未闭合；不承诺提前链接所有
方法体。完全省略所需导入的隐式依赖，可以直到 factory/start/后续执行才触发类型拒绝。
受控生命周期中的非 VM 致命 LinkageError 必须交付原失败并沿原 owner 清理，不能令调度等待者失去终态。
实际保存态按发生阶段区分，不能把迟发失败都投影为 LOAD/NOT_SAVED；见[失败设计](2026-10-15-lazy-linkage-lifecycle-design.md)。

### 4.3 解析、身份和隔离

对已登记 Host API binary name：当前 facet 已导入则直接返回 inventory 中的 Class；未导入则直接
ClassNotFoundException，不查询本地或依赖，也不自动传递导入。这样依赖 facet 的导入不会成为隐式 re-export。
其他类型保留原解析序列；原私有同名非 API 类仍各自隔离，动态 contract facet 的既有解析仍沿原依赖图。
Host、两个独立 provider unit 看到的 Class/descriptor/DTO 和它们声明的静态 kind 是同一对象身份。
调用仍沿原 PublishedRuntime，不读取 kind 字段、构造新 kind 或绕过 registration fence。

原 getResource/getResources、本地→依赖→parent 的资源与 SPI/TCCL 行为不扩展；类导入不是资源/反射隔离承诺。
接口内使用未在 public ABI 暴露的私有实现由其定义 loader 正常解析，不因此成为插件可直接加载的类型。

### 4.4 版本和编译指纹

精确 version 是 Host release 的合同声明，不是自动字节码兼容证明。Host 负责不可变发行和精确版本；
发行证据绑定 API JAR/POM、Fibra 同批制品及其 SHA256。API 内容变更必须变更版本、重建消费者并受控重启。
provider contractIdentity 包含语义版本及按 id/version/binary name 排序的清单摘要，进入原 Engine compiledFingerprint；
provider 只计算一次冻结身份，经 create 原值交给 driver，WiringKey 必须使用该同值，禁止继续使用旧静态常量代替配置身份。
不把 ClassLoader 地址、对象 identityHashCode 或业务 API 新事实写入 target，不增加热 API 更新、版本仲裁或状态机。
原静态 WiringKey 仅在不可变 driver 内复用，继续包括精确 payload、依赖 wiring 和 runtime 合同；不跨 Host 或 driver 缓存。

## 5. 生命周期与失败归属

| 对象/动作 | owner 和顺序 | 失败边界 |
|---|---|---|
| API Class 及其定义 loader | Host 产品发行；provider/driver 仅借用 | 不因 unit 停用、替换、candidate 失败或 Engine close 而关闭；Host 最后释放 |
| 插件 loader/静态依赖 lease | 原 driver StaticWiring/AcquisitionOwner | 取得即登记，部分失败先自身后依赖；close 失败保留同 owner 与必要依赖 |
| 执行 Scope 与 contribution | 原 unit/内核/Bridge | 封准入、真实在途 drain、stop 后才释 loader；API 相同不放松旧 view/registration |
| target、观察与恢复 | 原 Engine/store/唯一发布面 | prepare 拒绝不产生新调用入口或隐式修 target；错误沿已有阶段和诊断 |

两个 units 引用同 API 不互为 lifecycle dependency。停用/替换 A 不关闭 B 或 API；静态 facet 依赖关系仍照原图。
API 保持可达不等于旧 unit/registration 可用；不能用共享 API 来钉住旧实例或绕过排空。
资源关闭的验证分别说明文件句柄关闭、静态 lease 归零、loader 弱引用回收和活跃对照，不把其中一个冒充其他项。

## 6. 验收、发行和演进规则

先独立审整体层次/owner/合同/来源，B/I/M 清零后开始生产实现。新路径先有真实 RED，再实现 GREEN。

| 验证族 | 可证伪目标 |
|---|---|
| 整体 API 边界 | 无新增 Engine facade/registry/注入/控制入口；中立模块不依赖 Java 或产品；两种普通业务 API 可独立消费 |
| composition 合同 | 不可变、重复 id/name/异 loader 同名、受管/hidden 类型、基线冲突及非public基线签名、ABI nested/generic/builder 遗漏、隐藏 permitted 成功与隐藏 hierarchy 拒绝、private元数据缺类链接cause、初始化器不执行 |
| 私有声明 | 正常/无导入/contract-only，null/类型/空白/重复/未知字段和精确版本 |
| 静态准备 | 缺/错合同、已/未导入的 API 影子、间接导入、跨 API 显式并集闭包、依赖闭包拒绝；入口激活未发生，失败可重复观察和清理 |
| 正式 Engine typed 调用 | Host→动态模型成功；双 provider 同 DTO/descriptor/kind；原 fence/registration 旧引用拒绝 |
| 生命周期 | 停用/替换 A 后 B/API 存活；真实并发在途 drain；prepare/close 失败 owner、重复失败无重试/泄漏 |
| 制品和来源 | public signature、文档与实现一致；正式 runtime/engine/受影响整仓 verify；同批 POM/JAR/ZIP 及仓外公开 consumer |

不添加第三方依赖或更换现有版本。JDK21/Maven3.9.9，使用独立 Maven 本地仓及独立发行目录；可复制第三方缓存，
但不复用旧 Fibra 同坐标字节。consumer 不引用 reactor target/classes、不把 Fibra 源码加入产品 reactor。
发行身份须含源码基线+完整变更摘要、原生 argv/退出、测试报告与 JAR/POM/ZIP 摘要；无 commit 时明确为未提交源码。
所有正式门通过后才更新“当前已交付”说明。对外一个 release 同批升级；Java binary/source 变化同步签名、
文档、模板与独立消费者。不为维护旧快照制造第二行为分支。

后续增加 API 必须先写受众、owner、所在层、现有入口为何不足、失败/关闭和验证矩阵；只有真实消费者才新增能力。
本轮整体收口后，支持API/SPI/诊断/格式不随内部优化变化；重开必须满足稳定边界中的需求/场景或正确性、安全、生命周期证据门，
明确版本、迁移与同批消费者验证。
这不授权本次重做 CLI/Node/client API，不预建未来 contract resolver、跨语言统一 Class 模型、通用 API 容器或热版本层。

## 7. 权威文档落点

整体入口和各层唯一 owner 见本文与 [`docs/api/README`](../../api/README.md)。
[vNext §6.2](2026-09-07-fibra-vnext-architecture.md#62-java-runtime) 已同步加载/声明正文，
[Client Foundation §10](2026-09-15-fibra-client-foundation-architecture.md#10-composition-root-与关闭所有权)
已同步 Class 合同与 HostServiceRegistry 的关系，不保留互相冲突的当前设计。
活动进度仅见[本上游计划](../plans/2026-10-15-public-api/task_plan.md)，不复制消费者阶段计划。
