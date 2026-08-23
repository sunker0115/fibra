## 1. 版本与规格基线

- [x] 1.1 将 reactor revision 切换为 `0.3.0-SNAPSHOT`，提交独立开发起点
- [x] 1.2 按本 change 对照并维护 superpowers 设计、OpenSpec specs 和实现计划

## 2. 目录包与入口类型

- [x] 2.1 以 TDD 实现标准 ZIP/目录/properties/lib/摘要校验和稳定阶段错误
- [x] 2.2 以 TDD 用目录 manager/loader 替换直接 JAR manager/loader
- [x] 2.3 以 TDD 实现主 JAR自身索引判定、contract-only 与 executable 门禁
- [x] 2.4 用直接行为测试锁定 PF4J 3.15.0 的扩展 finder 类加载失败和 SemVer 范围

## 3. 完整依赖图

- [x] 3.1 以 TDD 实现 prospective 全图装载、必需依赖和 SemVer 范围校验
- [x] 3.2 以 TDD 补齐 optional edge 版本校验、旧/新 dependent 闭包和 ClassLoader 隔离
- [x] 3.3 用直接行为测试锁定 PF4J 3.15.0 不把 optional edge 纳入依赖图

## 4. 批量事务

- [x] 4.1 以 TDD 实现事务目录、原子 journal 和 loader 构造期崩溃恢复
- [x] 4.2 以 TDD 实现 `applyArtifacts` 的目录交换、运行态顺序恢复和全部 entry 重建
- [x] 4.3 以 TDD 实现正式 apply 失败回滚、`ROLLBACK` cause/suppressed 和诊断保留
- [x] 4.4 以 TDD 覆盖无 journal 预检垃圾、逐 ID 半交换组合、摘要不闭合拒启和 configType 变化回滚

## 5. Watcher 与配置协作

- [x] 5.1 以 TDD 把 Watcher 改为 ZIP严格升级并删除旧 JAR candidate 语义
- [x] 5.2 以 TDD 实现不跨 lifecycle 等待持锁的可重入逻辑事务门、报忙语义和 watcher dirty 重试
- [x] 5.3 验证 config reconcile 与 artifact apply 不交叉提交、身份快照查询不死锁和配置工厂重建边界

## 6. 真实示例与仓库外验收

- [x] 6.1 新增 contract-only 示例包，改造 provider/consumer 为 contract 二进制依赖和 Fibra 服务依赖
- [x] 6.2 改造示例 Host、Maven 打包和集成测试为标准 ZIP、批量升级和失败恢复
- [x] 6.3 改造仓库外独立工程为可直接构建且由同一脚本验收的用户模板，验证 Host classpath隔离、私有依赖和完整多插件图

## 7. 文档、API 与收口

- [x] 7.1 删除旧直接 JAR API/测试/文档语义，更新全部公开签名、README、API 和发布文档
- [x] 7.2 执行模块测试、全 reactor verify、可复现构建和仓库外验证
- [x] 7.3 完成代码审查、跨文档一致性与设计可行性复核，归档 OpenSpec change

实现步骤、精确文件、测试命令与提交边界由 `docs/superpowers/plans/2026-08-23-fibra-plugin-package-transaction.md` 作为唯一实施细节权威源。
