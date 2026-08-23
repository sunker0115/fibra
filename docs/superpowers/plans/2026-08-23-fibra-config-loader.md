# Fibra `0.2.0` 配置装载实施计划

日期：2026-08-23
状态：已完成；实现、API 冻结、五制品发布门禁和仓库外验收均已落地

架构真源：[Fibra 配置装载架构](../specs/2026-08-23-fibra-config-loader-architecture.md)。每一阶段都以测试先行，禁止先加兼容层再补真实模型。

## 阶段 1：PF4J 制品与运行实例解耦

1. 在 `fibra-pf4j-api` 先写/更新 API 基线预期，把 `FibraPluginEntrypoint` 改为 `configType/descriptor/create` 工厂。
2. 在 loader 测试夹具中让同一制品以两个 `entryId`、两个配置 mount，先得到失败测试。
3. 新增 `PluginInstanceSpec`，把 loader 内部索引改成 `entryId -> runtime` 和 `pluginId -> entryIds`。
4. 将公开制品方法统一改名为 artifact 语义，删除无配置 `startPlugin` 路径。
5. 重写 stop/unload/reload，使其快照并恢复每个受影响制品的全部实例。
6. 更新真实 provider/consumer/example/仓库外 fixture，验证依赖制品与运行实例不混淆。

成功标准：同一 JAR 两个 entry 同时 ACTIVE 且配置/服务隔离；reload 后两者均恢复；失败 reload 后旧 JAR 和两个旧实例均恢复。

## 阶段 2：Core 声明式名称能力

1. 先写 name-only `require/isolate` 的 parity 测试和 API 基线预期。
2. 增加字符串 isolate 重载，不触碰 service type registry。
3. 增加字符串 dependency 存储和解析；typed dependency 仍执行原类型校验。
4. 验证字符串依赖先 PENDING，真实 typed provider 到位后 ACTIVE，最后一个 binding 撤销后 ClassLoader 类型仍可释放。

成功标准：配置按名称表达作用域和依赖，不创建 `ServiceKey<Object>`，原 71 项 core 对应用例与现有增强测试全部通过。

## 阶段 3：配置领域模型、解析和 patch

1. 创建 `fibra-loader-config` 与发布 POM，先写 YAML/JSON、错误阶段和限制测试。
2. 引入 Jackson `3.1.5`，实现不暴露 Jackson 的不可变领域模型。
3. 实现插件/group/include 三种节点、完整 ID、include 环和继承展开。
4. 实现纯函数 patch，引入 warning 类型，覆盖深复制和有序命中。
5. 用插件 `configType()` 完成 typed config 转换并验证 ClassLoader 引用不进入 snapshot。

成功标准：同一语义 YAML/JSON 生成相等 snapshot；所有非法输入在运行态变更前按准确 stage 失败。

## 阶段 4：事务 reconcile 与文件操作

1. 先写 config-only update、替换、删除、disabled、PENDING/FAILED 测试。
2. 实现不可变候选、diff、确定性 apply journal 和逆序 rollback。
3. 写第 N 项失败、rollback 二次失败和失败后下一次成功测试。
4. 实现 create/update/remove 与原子写回，验证只读文件和写入失败不改变目标。

成功标准：只有运行态完全成功才提交 snapshot/摘要/文件；任一失败后旧运行态保持可用且错误链完整。

## 阶段 5：配置 watcher 与 JAR 更新串行化

1. 先写 create/modify/delete、burst 合并、close 等待和失败通知测试。
2. 实现 dirty + single runner watcher，不复制 reconcile 逻辑。
3. 将 config transaction 和 artifact reload 统一到 loader 事务协调器。
4. 增加 config refresh 与 JAR reload 并发测试。

成功标准：两类更新不存在交叉中间态，watcher 失败可观测且不破坏后续刷新。

## 阶段 6：API、文档与发布闭环

1. 生成并审查 `fibra-loader-config` public/protected `javap` 基线，更新其余破坏性 API 基线。
2. 把发布门禁和脚本从四个生产制品改为五个。
3. 将仓库外 host 改为真实 YAML、多 entry、配置更新/回滚验收；host 仍不把插件放入 classpath。
4. 更新 README、API 文档、发布说明、开源基线和已有 PF4J 架构文档，删除“一制品一 Fibra”等废弃描述。
5. 执行 `mvn clean verify`、仓库外消费和逐字节可复现门禁；完成独立代码审查后再进入 `0.2.0` 发布判断。

成功标准：五个生产制品可独立消费，文档无“已实现/未来能力”歧义，所有发布门禁通过。
