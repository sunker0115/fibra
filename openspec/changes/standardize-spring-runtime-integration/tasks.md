## 1. 版本与模块边界

- [ ] 1.1 将 reactor revision 切换为 `0.4.0-SNAPSHOT`，保持 `v0.3.1` 不变
- [ ] 1.2 新增可发布 `fibra-spring-boot-autoconfigure`，把 starter 改为无生产代码依赖入口
- [ ] 1.3 统一内部 dependencyManagement、Spring 模块 BOM 和七制品发布门禁

## 2. 配置契约

- [ ] 2.1 以 TDD 实现四段不可变 `FibraProperties` 和默认值
- [ ] 2.2 以 TDD 实现创建任何 Fibra 资源前的完整属性校验和精确错误
- [ ] 2.3 删除旧属性、旧 getter/setter 和通用 staging 语义，不提供兼容代码

## 3. 自动配置与所有权

- [ ] 3.1 以 TDD 实现完整托管单元及宿主已有 `Context` 时整体退让
- [ ] 3.2 保持 `FibraServiceBridge` 显式 ServiceKey 桥接，禁止插件 bean 自动注入
- [ ] 3.3 验证 root、两个 loader 和 lifecycle 的唯一关闭所有权

## 4. 生命周期与 Watcher

- [ ] 4.1 以 TDD 实现 load、config reconcile、总 readiness deadline 和精确状态失败
- [ ] 4.2 以 TDD 实现 config watcher 后 artifact watcher 的延迟启动和运行期失败报告
- [ ] 4.3 以 TDD 覆盖每个启动阶段的完整反向回滚、watcher 构造失败自清理、主异常和 suppressed 顺序
- [ ] 4.4 以 TDD 实现幂等终止关闭、固定关闭顺序和 callback 恰好一次

## 5. 示例与真实验收

- [ ] 5.1 把 Web 示例 staging 配置迁到示例命名空间，并使用新 `fibra.*` 属性
- [ ] 5.2 用 Awaitility 4.3.0 验证 config watcher 自动 reconcile
- [ ] 5.3 用真实标准 ZIP 验证 artifact watcher 自动事务升级及并发最终收敛
- [ ] 5.4 验证关闭后无 watcher 线程、旧插件实例和旧 ClassLoader 强引用

## 6. API、文档与发布收口

- [ ] 6.1 重建 autoconfigure 公共签名基线，删除 starter Java 签名基线
- [ ] 6.2 更新 README、API、release、架构和开源参照中的模块、属性、制品与 watcher 语义
- [ ] 6.3 执行模块测试、全 reactor verify、依赖边界、七制品可复现构建和仓库外验证
- [ ] 6.4 完成代码审查、跨文档一致性和设计可行性复核，归档 OpenSpec change

本文件只记录验收进度。proposal 通过后，将由 `docs/superpowers/plans/2026-08-24-fibra-spring-runtime-integration.md` 固化精确文件、测试命令、TDD 顺序和提交边界；该文件创建前不存在实施细节权威源，不得进入生产实现。
