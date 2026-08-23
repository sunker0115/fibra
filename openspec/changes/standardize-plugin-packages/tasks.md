## 1. 版本与规格基线

- [ ] 1.1 将 reactor revision 切换为 `0.3.0-SNAPSHOT`，提交独立开发起点
- [ ] 1.2 按本 change 对照并维护 superpowers 设计、OpenSpec specs 和实现计划

## 2. 目录包与入口类型

- [ ] 2.1 以 TDD 实现标准 ZIP/目录/properties/lib/摘要校验和稳定阶段错误
- [ ] 2.2 以 TDD 用目录 manager/loader 替换直接 JAR manager/loader
- [ ] 2.3 以 TDD 实现主 JAR自身索引判定、contract-only 与 executable 门禁

## 3. 完整依赖图

- [ ] 3.1 以 TDD 实现 prospective 全图装载、必需依赖和 SemVer 范围校验
- [ ] 3.2 以 TDD 补齐 optional edge 版本校验、旧/新 dependent 闭包和 ClassLoader 隔离

## 4. 批量事务

- [ ] 4.1 以 TDD 实现事务目录、原子 journal 和 loader 构造期崩溃恢复
- [ ] 4.2 以 TDD 实现 `applyArtifacts` 的目录交换、运行态顺序恢复和全部 entry 重建
- [ ] 4.3 以 TDD 实现正式 apply 失败回滚、`ROLLBACK` cause/suppressed 和诊断保留

## 5. Watcher 与配置协作

- [ ] 5.1 以 TDD 把 Watcher 改为 ZIP严格升级并删除旧 JAR candidate 语义
- [ ] 5.2 验证 config reconcile 与 artifact apply 共享同一独占锁和配置工厂重建边界

## 6. 真实示例与仓库外验收

- [ ] 6.1 新增 contract-only 示例包，改造 provider/consumer 为 contract 二进制依赖和 Fibra 服务依赖
- [ ] 6.2 改造示例 Host、Maven 打包和集成测试为标准 ZIP、批量升级和失败恢复
- [ ] 6.3 改造仓库外独立工程与脚本，验证 Host classpath隔离、私有依赖和完整多插件图

## 7. 文档、API 与收口

- [ ] 7.1 删除旧直接 JAR API/测试/文档语义，更新全部公开签名、README、API 和发布文档
- [ ] 7.2 执行模块测试、全 reactor verify、可复现构建和仓库外验证
- [ ] 7.3 完成代码审查、跨文档一致性与设计可行性复核，归档 OpenSpec change

实现步骤、精确文件、测试命令与提交边界由 `docs/superpowers/plans/2026-08-23-fibra-plugin-package-transaction.md` 作为唯一实施细节权威源。
