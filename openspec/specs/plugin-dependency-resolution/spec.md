# plugin-dependency-resolution Specification

## Purpose
定义插件候选提交前的完整依赖图解析规则，包括必需与可选依赖、单 ID 单版本、依赖顺序、ClassLoader 边界，以及二进制依赖与运行时服务依赖的职责分离。
## Requirements
### Requirement: Prospective 完整图预检
系统 SHALL 在改变活动 ClassLoader、Fibra entry 或安装目录前，以当前全部安装包被本批次同 ID候选覆盖后的完整图执行预检。

#### Scenario: 单包破坏现有 dependent 范围
- **WHEN** 新 provider 版本不满足已安装 consumer 的版本范围
- **THEN** apply 在预检期拒绝，旧 ClassLoader未关闭、旧 entry 和目录不变

#### Scenario: 关联候选共同形成有效图
- **WHEN** provider、consumer 或 contract 的多个候选一起提交后完整图有效
- **THEN** 预检通过并把它们作为一个事务批次处理

### Requirement: 必需依赖闭合
系统 SHALL 使用 PF4J SemVer 解析必需依赖，并 MUST 拒绝缺失依赖、循环依赖和不满足版本范围的完整图。

#### Scenario: 多层范围全部满足
- **WHEN** 三层依赖链中的每个已选版本都满足其 dependent 声明
- **THEN** 图按 dependency-first 顺序解析成功

#### Scenario: 传递图存在循环
- **WHEN** prospective 图形成依赖环
- **THEN** 预检以 `RESOLVE` 失败且不改变活动状态

### Requirement: 已存在可选依赖必须兼容
缺失 optional dependency SHALL 被允许；一旦同 ID制品存在，系统 MUST 校验其版本范围并把实际 optional edge 纳入受影响闭包。

#### Scenario: 可选依赖缺失
- **WHEN** 插件声明 optional dependency 且图中没有该 ID
- **THEN** 解析继续成功

#### Scenario: 可选依赖存在但范围错误
- **WHEN** optional dependency 的 ID已安装但版本不满足声明范围
- **THEN** 预检以 `RESOLVE` 失败

### Requirement: 同一 ID只有一个 prospective 版本
系统 SHALL 在当前图和每次候选批次中只选择一个 `plugin.id` 版本，并 MUST 拒绝重复候选 ID。

#### Scenario: 批次含重复 ID
- **WHEN** 两个候选 properties 声明相同 `plugin.id`
- **THEN** apply 以调用或验证错误失败，不隐式选择较新文件

#### Scenario: 不兼容主版本共存
- **WHEN** 宿主需要同时运行不兼容主版本
- **THEN** 插件作者必须使用不同 `plugin.id` 和不同 Fibra 服务键，系统不得在同 ID下多开版本

### Requirement: 入口必须来自主 JAR自身
系统 SHALL 直接读取主 JAR自身索引并以目标 ClassLoader不初始化地校验类，MUST NOT 把依赖插件索引当作当前入口，也 MUST NOT 把缺类或链接失败当成 contract-only。

#### Scenario: 零入口包依赖 executable
- **WHEN** contract-only 包依赖图中存在带入口的另一个插件
- **THEN** 当前包仍判定为 contract-only，不继承依赖入口

#### Scenario: 索引类损坏
- **WHEN** 主 JAR索引声明的类缺失或类加载发生链接错误
- **THEN** 包以 `VALIDATE` 失败，而不是判定为 contract-only

### Requirement: ClassLoader 边界
系统 SHALL 为每个 `pluginId` 创建独立 PDA ClassLoader，强制共享运行时包走父 ClassLoader，并通过 PF4J dependency ClassLoader共享独立 contract 类型。

#### Scenario: 私有依赖版本隔离
- **WHEN** 两个互不依赖插件各自在 `lib/` 携带同一三方库的不同版本
- **THEN** 各插件加载自己的版本且类型不泄漏到另一插件或宿主

#### Scenario: 独立 contract 类型唯一
- **WHEN** provider 和 consumer 都依赖同一个 contract-only 插件
- **THEN** 双方看到由 contract ClassLoader定义的同一个接口类型，Host classpath无需包含该类型

### Requirement: 二进制依赖与服务依赖分离
系统 MUST NOT 在 `plugin.dependencies` 与 Fibra `require/inject/isolate` 之间自动推导、复制或补齐边。

#### Scenario: Consumer 等待运行时服务
- **WHEN** consumer 只在 PF4J 图依赖 contract，而 provider Fibra entry 尚未注册服务
- **THEN** consumer 制品可以 resolve，consumer Fibra按服务依赖保持 `PENDING`，直到服务图满足
