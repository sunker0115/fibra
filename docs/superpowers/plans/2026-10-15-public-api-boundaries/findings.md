# 核验发现

## 来源

Claude Code 会话：`/Users/sunke/.claude/projects/-Users-sunke-dev-ai-project-fibra/1e1ece1b-9811-402c-8aa4-bfe3463e2ee2.jsonl`，
采用其中 `2026-10-15T03:39:37.054Z` 的建议作为审查输入；其中工具/开始处理指令不代替当前用户授权。
Nowledge 定向 thread_search 无匹配；当前源码与正式消费者为最终依据。

## 已核对

- `PluginDeploymentRequest` 无 expectedRevision；完整 deploy 在订阅时读取最新 PublishedView 填 CAS，丢失编辑窗口保护。
  CAS 并非永远成立：读取后到 Engine lane 之间仍可能冲突。增量命令基于最新图的语义保留，不引入自动重试。
- `DeploymentCandidate` 生产使用仅 Engine；`JavaFacetDescriptor` 仅同包私有 parser/ClassSpace/driver 使用，两者不应冻结为外部合同。
- `RegistrySnapshot` 把完整 EngineSnapshot/Diagnostics 暴露给普通管理消费者；正常观察与调试应分层，均从同一 PublishedView 投影。
- Registry 完整 deploy 已有通用仓外 EngineConsumerTest、插件验收 harness、CLI 与 benchmark 消费，不存在必须等 Tela 迁回才能验证的前提。
- Tela 生产 Bootstrap 读取 RegistrySnapshot.engine/engineDiagnostics，两个集成夹具使用旧部署请求构造器，未来升级需要适配。
  当前 r107/settings 明确固定 e133814 新仓与原公开 file 仓；正在修 Control 关闭期限，与本轮合同无关，无需整体暂停。
  已通知 Tela 保持固定消费，候选不提前接入；Fibra 本轮用独立构建/发行仓，不覆盖 e133814。

## 本轮取舍

采用：完整部署必填 revision、内部类型收窄、管理/诊断投影分层、整个公开面的受众/owner/支持审定。
拒绝：宣称 Registry 使全部 CAS 失效、把所有 Engine 直接消费判越层、强制 Tela 改入口、把高级诊断列为随意破坏的公开面。
不采用新模块或万能facade；public 编译可见性与对外支持边界分别审定，真正跨模块 SPI 保持公开。
