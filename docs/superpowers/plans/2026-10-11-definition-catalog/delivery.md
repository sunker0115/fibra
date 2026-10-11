# Definition 目录能力交付记录

2026-10-11。本期授权目标已完成：实现、完整构建、发行/消费者验证及最终独立证据复核均通过。没有 commit、push、外部发布或 Tela P3 恢复。

最终独立审查为 PASS，B0/I0/M0，DC-01～DC-10及本期 Node/Tela 消费资格均闭合。[最终审查报告](/private/tmp/catalog-final-delivery-evidence-review.md) SHA256 为 `69ee173bcfae21d744fe10f8d0de296f6ff9fe3c3264356efef8bd8303dc4827`。原 NOT READY 报告及失败记录保留；最终通过依据是新输入和实际完整执行。审查后仅更新阶段状态和两份设计文档首段，不改变生产、测试、公共合同或制品。

本期交付三项通用能力：已选 root 包的声明级 definition 目录、Java 取得失败的资源归属修复、既有 Node runtime 的进程范围关闭修复。Fibra 没有加入 Tela 的业务政策、renderer、产品组合或旁路入口。最终插件模型与后续全栈装配边界见[最终架构评估](../../specs/2026-10-11-plugin-model-target-evaluation.md)。

目录由 `FibraEngine.describeDefinitions(pluginId, expectedTargetRevision)` 在原 command lane 读取精确 selection；动态 runtime 实现必需 `RuntimeDefinitionQuery` SPI，built-in 使用已有声明。查询不启动插件、不改变 target，也不证明部署成功；Java 声明名称在 prepare 中与真实定义核对。所有作者/provider/消费者按同版合同直接迁移，没有兼容分支。合同、错误、revision/CAS 与资源门见[目录规格](../../specs/2026-10-11-definition-catalog-design.md)和[公开 API](../../../api/README.md)。

Node 保留原 supervisor 唯一 owner 与原 EOF→TERM→KILL 预算。EPERM 为 UNKNOWN；真实同组 ESRCH 为不可逆 ABSENT，之后停止使用旧 PGID；持续 UNKNOWN/PRESENT 到期仍失败并保留资源。原请求终态、输出排空、session 与 payload lease 分开结算。采用/拒绝的成熟源码依据见[参照](../../references/2026-10-11-node-lifecycle-baselines.md)及[关闭合同](../../specs/2026-10-11-node-managed-range-close-design.md)。

## 验证事实

| 门 | 最终证据 |
| --- | --- |
| 完整 reactor | 原 `clean verify`，44 模块、157 有效 XML、1129/0/0/0；native session51000 exit0/chunka40d04。归档 `/private/tmp/definition-catalog-full-reactor-node-fixed-evidence`，manifest `c5e81dbd987853556c360fd1ddfcaf48a085fed9fd8c2c084711d19fc63c16f0` |
| Node 实施 | 最终108项三类回归及真实 RED/GREEN；最终 SPEC/quality 均 B0/I0/M0。测试资源与 JAR 内 supervisor 都是 `d93e6c69090f18ee87eda8f2dc39855b221401b419283c01fe84e1e3207456c5`；不是沿用旧失败资源 |
| 发行与空消费者 | 原 `verify-distribution.sh`，native57053 exit0/chunkb15240；30 正式模块、115 制品记录，55 对实际 JAR/POM 从 file remote 到空仓消费者字节与 origin 一致。7 份 XML 共11/0/0/0；生成插件、仓外 CLI 和真实 TTY 通过。958项归档 manifest `74b3a949b44331a56c906db85bcf510766707ec133e79192a19e936c9b58e940` |
| 可复现发行 | 原 `verify-reproducible-release.sh`，native26420 exit0/chunk2f4aff；两次 clean package 和一次已有 JAR 再打包，各115制品的 SHA/size/mode 一致，亦与上述发行相同。121项归档 manifest `b13899c2634490e8e5e345b5c9ec270b899aa0086b41ee5549d6ae2b6c3b8c35` |
| 隔离 Tela 消费 | 最终真实四个 Java JAR 的原五类51/0/0/0，native8126 exit0/chunkf3f550；原打包 native16689 exit0/chunkf24527。91项归档 `/private/tmp/tela-catalog-node-final-current-evidence`，manifest `9f8ffa5199a18005a65b421d9475d91f6baee9b0653403f17f972693f24556a4` |
| 制品与工作树保护 | 实际测试 classpath27个 Fibra JAR；最终 Tela ZIP 的 Boot host14与正式插件12，共26个，与新 producer54个JAR/POM对应。测试 classpath额外的 Fibra CLI 主JAR不进入产品宿主。原Tela471份源文件、隔离199项输入最终均无漂移 |

最终 Node JAR 为 `4b971ec19a06209e3457c2d2f3ad61efb68dc913b73c9d9789c419bace2b7fe3`。新 producer54项相较旧有界消费只改变此 Node JAR；最终真实 Java 四包与最终 Tela ZIP payload 同字节。Tela agent 再打包曾改变 ZIP 容器字节但内部全部文件内容一致，因此重新冻结最终四包并再次执行51项消费，不以旧 JAR/XML证明新输入。

采集 staging 的两次 metadata 断言失败源于范围误判：先只按 `fibra-*.jar` 漏掉插件的 `main.jar`，再把测试 classpath27误作解包24的数量。随后只读核对原 assembly、实际 Fibra ZIP 与 Tela Boot JAR，修正采集口径；不是 Maven/产品测试失败，没有修改产品源码、弱化断言或改变发行结构。旧输入、旧测试与原两次完整构建失败均保留历史；没有声称定位了历史 EPERM 的具体 syscall。

## 验证边界

实际运行平台为 macOS；Linux/Windows 和 Desktop E2E 未执行。原 `exec.skip` 仅隔离 Desktop/npm 执行，不是 Desktop 验收。打包阶段的 `skipTests` 不是测试资格，上述完整1129与最终51均来自独立实际测试终态。Node 仍保留数值 PGID及既有 Windows较弱边界，不承诺离组后代、OS强身份或持续权限拒绝下的清理成功。

Tela 原工作树保持只读，适配与消费证据保留在 `/private/tmp/tela-definition-consumer.f6x_d0l_/tela`。本期关闭 Fibra 目录/D38及批准的通用 Node 修复；全栈组合 schema、产品政策与 Tela P3 均为独立后续范围。
