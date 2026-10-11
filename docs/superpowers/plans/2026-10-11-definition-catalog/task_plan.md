# Definition 目录能力推进计划

日期：2026-10-11。本期授权目标已完成：Fibra目录、Java取得修复、既有Node runtime关闭修复、公开制品及隔离Tela消费均通过完整验证与最终独立审查。原阻断和失败保留历史，不恢复Tela P3，不commit/push。交付、证据与未验证边界见[交付记录](delivery.md)。

## 目标与边界

用户新增约束：Fibra不限于Tela，只有经过证据确认的通用能力缺口或通用缺陷才能改Fibra。本期目录和Java取得修复分别覆盖所有runtime的包发现与所有Java插件的资源安全；产品组合及政策不进入上游。独立审查须核对此边界，不以Tela消费测试通过替代通用性证明。

在同一 Fibra Engine、精确 target selection、原 runtime owner 下，为所有插件提供 definition 名称目录；补齐 Java metadata 取得失败的资源归属。最终交付公开制品供 Tela 消费。设计门通过不代表实现、发行或 Tela P3 完成。

已确认：Fibra 负责通用包/target/依赖/资源生命周期和查询入口；插件/runtime 负责私有 definition/config；Tela 负责产品组合、政策与业务。Tela 当前工作树只读；隔离副本允许 client SPI、四份可执行 Java 描述符和必要测试/发行引用迁移。不自动 commit/push/merge。

## 阶段与验收

1. 源码及契约核对（complete）：核基线、Java/Node/client/built-in、精确依赖与关闭；仓外探针复现三条取得失败。
2. 最终架构评估（complete，主线程明确推荐）：用户要求从最终架构评估、不预设简化，最新说明自己不确定选型。主线程承担选型判断，推荐四语义、声明级目录、显式装配意图与D38；不再等待用户替代架构判断。metadata路线只保留比较依据，不同时交付两套来源。
3. 独立设计审查（complete，仅设计门）：原目录、模型说明、最终架构基础评估及DSH/§8推荐增量均完成独审；声明级合同、无兼容直接迁移、最终形态/Tela影响及实施计划增量独审均B0/I0/M0，报告绑定hash见progress。设计审查不能替用户批准具体公共合同或代替实现测试。
4. 具体合同设计及批准（complete）：用户明确选择“批准 Fibra 实施及隔离 Tela 消费验证（推荐）”。批准候选§8及实施计划范围：root-only目录、必需SPI、Java名称声明与公开descriptor、D38、公开制品和隔离Tela验证；无兼容代码。组合schema及P3恢复不在授权范围。
5. 内核实现与模块测试（complete）：D38、目录值/错误及句柄接口、Java/Node静态查询、Engine目录与九个必需SPI实现均通过独立spec/quality门。Engine145、Java67，Node目录前段92项及最终生命周期108项原生回归通过；纯built-in投影、prepare集合核对、target不变、取消/关闭和cleanup失败屏障已有证据。最终Node修复仍在原supervisor owner和原预算内，无Tela特判或新生命周期框架。
6. 公开制品与消费者验证（complete）：正式Java作者、模板、provider及未知包公开管理链迁移并独审通过。原完整44模块1129项、公开签名、架构/client包门、原distribution/file remote/空消费者及原reproducible-release均通过；115制品三次字节/size/mode一致。最终四个Java包及新Node制品在隔离Tela原五类51项消费通过，完整TC01～08和原13项回归保留；最终SPEC/quality及整体证据独审均PASS B0/I0/M0，DC-01～DC-10和N1～N4闭合。原Tela471项、隔离199项源输入未改；最终70项Fibra输入在审查后仅两份设计首段更新状态，生产/测试/公共合同未漂移。采用私有Maven仓，不覆盖全局同SNAPSHOT。原两次失败及未定位的operation作为历史保留，不以临时观测背书本次交付。

用户补充询问最终插件形态及Tela影响，最终评估§9已给目标包/类型/启用组合/逻辑client与实际执行的实例例子及Tela调整矩阵。未知全栈包的组合schema、实例归属和完整管理验收是独立后续合同；不是本期目录的已实现能力，不改变P3恢复门。

## 停止条件

- 新公共合同、制品协议、依赖或跨仓修改未经批准：停在可审阅设计；继续不依赖批准的取证/审查。
- 资源失败没有可达 owner 或依赖先被释放：设计不得通过。
- 同一问题两次失败：记录证据并上报，不硬试。
- 不创建或恢复 Tela P3 目标；总目标不能因阶段门通过而 complete。

## 当前事实与错误

- Fibra 初始干净，HEAD `7a7136c449a0a399ab4c12cd00c5ae54ffae84b7`，已建立 `codex/definition-catalog-design`。
- 可见祖先与仓根没有 AGENTS.md；用户消息中的规范有效。
- Nowledge thread_search `Tela Fibra definition catalog P3-D38` 返回空；不据此否认文件中的交接事实。
- 实施测试包含有意保留的RED，实际失败与GREEN记录见progress；若输出截断，按更小范围重新读取。
- 仓外探针首轮缺 snakeyaml-engine，按本地 POM 补齐后取得三个基线故障结果；不计产品测试失败/通过。报告尚未生成时读取一次返回文件不存在，待 agent 完成后已成功读取。
- Codex 定位中旧目录 `plugins/src` 和 `core/src/mcp_connection_manager.rs` 不存在，改由 rg --files 找到 `core-plugins/src`、`codex-mcp/src/connection_manager/`，未反复硬试。
- 原目录设计独审绑定 SHA-256 `9678a81ab5edb16b3424c6d79825b3ffc5e0cae04871d90e7ec00a5cc2f70b8c`，报告 `/private/tmp/fibra-definition-catalog-design-review.md`；后续评估及各次增量身份见progress。D38及目录实现已双审；公开签名按授权仅更新Engine、Java、Node三份并经原测试验证，POM未修改。
