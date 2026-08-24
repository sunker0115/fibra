## 1. 版本与 Loader 事务边界

- [x] 1.1 将 reactor revision 从已发布 `0.3.1` 切换为 `0.4.0-SNAPSHOT`
- [x] 1.2 以 TDD 把 artifact 变更重构为 plan/prepare/commit/complete/rollback 唯一实现
- [x] 1.3 以 TDD 提供候选插件目录和目标 configType 的只读预检视图
- [x] 1.4 以 TDD 把 config 变更重构为 resolve/plan/prepare/commit/complete/rollback 唯一实现
- [ ] 1.5 删除两个 loader 的 watcher 公共 API并同步 API签名，不提供兼容代码

## 2. Engine 生命周期与 Reconcile

- [x] 2.1 新增可发布 `fibra-engine` 及不可变 builder、状态和唯一资源所有权
- [x] 2.2 以 TDD 实现双 source、去重队列、执行期 dirty 和周期 resync
- [x] 2.3 以 TDD 实现启动初载、总 readiness、失败回滚和终止性关闭
- [ ] 2.4 以 TDD 实现最后成功 revision、结构化失败和有界退避重试

## 3. Deployment Package 与联合事务

- [ ] 3.1 以 TDD 实现 deployment ZIP、properties、SHA-256 和安全边界校验
- [ ] 3.2 以 TDD 实现唯一 engine journal 与两个 loader 参与者的 prepare/commit/complete/rollback
- [ ] 3.3 以 TDD 覆盖每个崩溃点恢复、主异常、suppressed 顺序和拒绝猜测恢复
- [ ] 3.4 以真实多插件和 typed config 验证联合升级、降级及不兼容回滚

## 4. 示例、API 与发布

- [ ] 4.1 把纯 Java example 和 external managed host 改为只使用 `FibraEngine`
- [ ] 4.2 冻结 engine 公共 API，更新两个 loader 签名和七制品发布门禁
- [ ] 4.3 执行 Cordis parity、loader、engine、全 reactor、外部消费和可复现构建
- [ ] 4.4 更新架构、API、release 和 README，完成审查并归档 change

精确文件、测试命令、TDD 顺序和提交边界由 `docs/superpowers/plans/2026-08-24-fibra-engine.md` 作为唯一实施细节权威源；计划通过人工闸门前不得修改生产代码。
