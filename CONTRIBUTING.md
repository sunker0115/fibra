# 贡献指南

## 开发环境

项目固定使用 JDK 21、Maven 3.9.9、`client/.node-version` 锁定的 Node.js 22.14.0 和 pnpm 11.19.0。
真实插件与发行验证还需要 Python 3、`unzip`、Bash 和 ripgrep 15.0.1。优先使用仓库已锁定的版本和本地依赖
缓存，不提交 IDE 元数据、构建产物、凭据或私有配置。

## 架构约束

- `fibra-core` 只实现生命周期、Scope、服务、事件和 effect，不感知 package、具体 runtime、Engine 或
  Spring；
- `fibra-artifact` 只负责 `PluginPackage`、不可变内容和事务存储，不执行插件；
- `fibra-engine` 是完整 `DeploymentTarget`、runtime 协调和事实发布的唯一 command lane；
- runtime 必须通过 `RuntimeProvider`、`RuntimeDriver`、candidate、generation、execution unit 这组公开 SPI
  接入，Engine 不增加 Java/Node/browser 类型分支；
- `fibra-registry` 只把管理用例翻译成完整 Engine command，不建立第二状态机；
- `fibra-bridge` 只负责贡献目录、调用准入、注册身份和排空，不负责安装或目标保存；
- package 根 `fibra-package.yaml` 是逻辑身份、版本和 facet 图的唯一真源；Java/Node payload descriptor 只
  描述各自 runtime 的本地入口；
- desired entry identity、package revision、unit target revision、runtime instance、operation id、view
  revision 和 registration identity 各有独立语义，不能合并或用名字猜测；
- 浏览器端执行器、Web 资源装载器、前端渲染框架绑定、连接实现和产品会话恢复必须留在产品仓；Fibra 的
  client 发布物只包含 API 与协议契约；
- 中立模块不得依赖 Spring 或产品类型；不引入兼容层、第二事务门、隐藏 fallback 或双 runtime 模型。

## 修改纪律

公开 API、package/target 格式、runtime SPI、wire 协议或模块边界变化，必须先更新权威设计，并在同一变更中
更新公共签名/declaration 基线、契约测试、独立消费者和活跃文档。删除旧能力时同步删除旧测试与文档，新增
能证明替代契约的测试；不能只删失败断言。

修复一类问题时全仓搜索平行路径。运行时与异步副作用不得把缺失观察事实解释为正常跳过；应检查实际
`PublishedView`、存储记录和诊断。日志统一使用 SLF4J，不使用 `System.out` 或 `System.err`。

## 提交前验证

逻辑改动至少运行对应模块测试。涉及发布边界、公共契约、package、runtime 或 client 协议时，完成以下门禁：

```bash
mvn --batch-mode --no-transfer-progress clean verify

corepack enable
corepack prepare pnpm@11.19.0 --activate
pnpm --dir client install --frozen-lockfile
scripts/verify-client-packages.sh
scripts/verify-reproducible-release.sh
scripts/verify-architecture-boundaries.sh
```

不要使用 `--no-verify`、`--force`、注释测试或放宽门禁来绕过失败。无法运行 UI 或端到端验证时，在 PR 中
明确列为未验证，不要用单元测试替代实际证据。本地开发和 PR 事件不运行空仓分发门；每次 push 由 GitHub
Actions 自动执行，正式 release workflow 也执行一次。

## Pull Request

PR 说明应包含问题、整体架构取舍、影响面、破坏性边界和实际验证结果。涉及发布契约时，明确列出 Maven/npm
制品、独立消费者和重启证据。安全漏洞请按 [SECURITY.md](SECURITY.md) 的私密渠道报告。
