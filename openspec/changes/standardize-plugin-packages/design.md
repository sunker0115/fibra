## Context

本变更的完整架构、包结构、公开数据结构、流程和开源对比以 [`docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md`](../../../docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md) 为权威源。本文件只记录 OpenSpec 实施级决定，不重复字段定义。

当前实现使用 `FibraJarPluginManager`、直接 JAR Manifest 和 `reloadArtifact(Path)`。它能在单包运行失败后恢复，但只有关闭旧依赖闭包后才验证新图；多个插件必须分次更新；文件恢复依赖内存状态，进程崩溃时没有 journal。

PF4J 3.13.0 已提供目录 `lib/`、properties 描述、SemVer、依赖解析和每插件 ClassLoader，但其默认 ZIP repository 会先解压、批量装载是 best-effort。它的扩展 finder 还会沿依赖查找资源并吞掉部分类加载失败，不能直接作为 Fibra 自身入口判定。

## Goals / Non-Goals

**Goals:**

- 直接建立不含旧模型兼容代码的 `0.3.0` 唯一制品协议。
- 在当前运行态和安装目录改变前验证完整 prospective 图。
- 让单包和多包使用同一批量事务算法。
- 在运行中失败和目录交换期间进程崩溃后恢复确定安装图。
- 支持 contract-only、私有依赖、SemVer 范围和一制品多 entry。
- 保持 `fibra-core`、PF4J 制品层、Fibra 服务层和宿主框架边界清晰。

**Non-Goals:**

- 不支持直接 JAR、旧 API 转发、同 ID 多活版本或自动依赖下载。
- 不建立不可信插件安全沙箱或远程市场协议。
- 不把 Spring/Spring AI、Hasor、Solon 或 Java DeepSeek Harness 业务放入生产模块。
- 不新增通用 loader SPI 和生产 Maven 模块。

## Decisions

### D1：使用显式目录包，不使用 PF4J 默认 ZIP repository

候选 ZIP由 `PluginPackageInspector` 复制到同文件系统事务区并安全解压，安装目录只由 Fibra 的事务步骤替换。活动 `FibraDirectoryPluginManager` 只看直接子目录，忽略隐藏事务目录，不隐式展开 ZIP。

原因：必须在任何安装目录变化前完成结构和全图校验，也必须让单包与多包共享同一 apply 路径。

### D2：`plugin.properties` 是唯一描述真源

`FibraDirectoryPluginManager` 只配置 `PropertiesPluginDescriptorFinder`，主 JAR Manifest 中的 PF4J 描述不参与选择。`plugin.class` 和 `plugin.requires` 非空都在校验期拒绝。

原因：避免 properties、Manifest 和 PF4J Plugin 子类形成三个身份/生命周期来源；当前没有真实 system version 输入，接受 `plugin.requires` 会制造未执行的兼容承诺。

### D3：主 JAR固定命名，入口只读主 JAR自身索引

每包恰好一个 `lib/<id>-<version>.jar`。预检直接读取该 JAR 的 `META-INF/extensions.idx`，使用目标插件 ClassLoader、`Class.forName(..., false, ...)` 校验入口类型和定义 ClassLoader，不调用 PF4J 扩展对象 API。

原因：PF4J 的依赖感知 finder 可能继承依赖索引并忽略损坏类，无法稳定区分 contract-only 和损坏 executable。

### D4：临时 manager 验证完整 prospective 图

`PluginGraphPreflight` 以候选替换同 ID 当前目录，向临时 `FibraDirectoryPluginManager` 显式装载全量唯一路径。PF4J 解析必需依赖、循环和版本；Fibra 额外验证实际存在的 optional edge 版本和自身入口。临时 manager 不实例化业务入口，并在返回前关闭全部 ClassLoader。

原因：局部候选验证不能发现现有 dependent 的版本范围被破坏；只有完整图能决定一个批次是否可安装。

### D5：受影响闭包取旧图与新图依赖方并集

候选 ID、旧图传递 dependents、新图传递 dependents 的并集进入停止、重载和恢复。实际存在的 optional edge 也按真实二进制边处理。

原因：更新既可能破坏旧依赖，也可能建立新依赖；只看一个方向会留下使用旧 ClassLoader 的 dependent。

### D6：持久 journal 是批量原子语义的一部分

目录交换在 `plugins/.fibra-transactions/<txid>` 下保存不可变输入、新目录、旧目录和原子更新的 properties journal。状态为 `PREPARED -> INSTALLING -> APPLYING -> COMMITTED`。构造 loader 时先恢复所有未提交事务，再创建活动 manager。

原因：多个目录不存在单一原子 rename；只做内存回滚不能覆盖进程在两个 move 之间退出。

### D7：正式 apply 仍保留运行态反向恢复

预检后快照 started 状态、`PluginInstanceSpec` 和 entry 顺序，dependent-first dispose/unload，dependency-first reload/start，最后按旧顺序 remount。业务入口失败时先恢复目录，再恢复旧 ClassLoader 和 entries；恢复失败形成 `ROLLBACK` 主异常和 ordered suppressed。

原因：预检不能安全执行用户业务代码，正式启动失败仍是合法故障面。

### D8：PF4J 图与 Fibra 服务图不互推

独立 contract-only 插件承载跨插件类型；provider 和 consumer 都依赖 contract，但 consumer 不因运行时使用 provider 服务自动形成 provider 二进制依赖。配置层继续只声明 service name、isolate 和 runtime requirement。

原因：二进制可见性和服务就绪是两个生命周期，合并后会退化为静态依赖或错误 ClassLoader 类型。

### D9：Watcher 不隐式拼批次

Watcher 对单一 `pluginId` 去抖并执行 `applyArtifacts(List.of(candidate))`，只接受严格升级。需要一起更新的多个插件由宿主显式一次提交。

原因：根据文件到达时间猜测事务批次不可重复，也无法区分“等待下一文件”和“完整部署已到齐”。

## Risks / Trade-offs

- [临时全图 manager 增加短时 ClassLoader 和内存] → 不初始化/实例化业务入口，完成预检立即关闭。
- [普通 JAR无法自动证明私有依赖不是复制的契约插件] → 共享包扫描、Maven scope、标准构建和仓库外 ClassLoader 黑盒测试共同门禁。
- [业务或 Spring 持有插件类导致 Metaspace 泄漏] → 文档禁止插件进入 BeanFactory/静态缓存，并以关闭旧 ClassLoader和弱引用验收。
- [journal 或备份损坏无法自动判断正确图] → 构造阶段以 `ROLLBACK` 失败，不猜测、不继续加载半图。
- [更新会重建 Fibra Java 对象] → 公开文档冻结 `entryId` 为稳定身份，禁止缓存旧 `Fibra`/入口/插件对象。
- [严格主 JAR命名和同版本摘要拒绝提高打包要求] → 提供真实 Maven 示例和仓库外模板，换取确定性和可审计更新。

## Migration Plan

1. 在 `codex/0.3.0-development` 把 revision 改为 `0.3.0-SNAPSHOT`。
2. 先以失败测试冻结目录包、入口判定、完整图和事务 journal。
3. 用目录 manager/loader、inspector、preflight 和 transaction 替换 JAR manager/loader 及旧 reload 实现。
4. 删除旧直接 JAR测试、API 与文档，不保留兼容路径。
5. 改造 Watcher、配置 loader 调用点、三插件示例和仓库外独立工程。
6. 更新全部公开签名、架构和使用文档，执行完整 Maven、可复现构建和仓库外验证。
7. 验证完成后归档本 change，稳定规格进入 `openspec/specs/`。

回滚开发提交使用普通 Git revert；运行期包更新回滚由 D6/D7 的 journal 和运行态恢复完成。

## Open Questions

无。候选格式、API、入口判定、optional edge、批次边界、崩溃恢复和示例 contract 拆分均已确定。

