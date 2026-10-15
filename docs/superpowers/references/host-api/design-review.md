# 整体公共 API 设计独立审查

基线 `5b2d2176`，2026-10-15；两个独立分析/审查 agent：contract_analysis、public_api_audit。
本文件归档结论，不把静态审查当运行资格。

## 发现与修订

首次 contract_analysis：B0/I2/M1；public_api_audit：B0/I2/M2。
统一修订：禁止受管 defining loader/hidden Class；public ABI/隐藏 permits 边界明确；public record 完整签名及
IllegalArgumentException 错误合同；provider 冻结配置身份原值传给 driver/WiringKey；补 nested/permits 明确校验及
JDK 反射无法证明残缺 permits 制品的窗口；补真实来源和源码摘要。为支持通用分层，采用 provider 导出并集与 facet
显式导入并集的同一 ABI 校验算法，不添加 resolver 或自动传递导入。

contract_analysis 最终复审 B0/I0/M0，放行测试先行实施；绑定规格 SHA256
`f57237421a6d701ff757aabda3e280c26c7c6667aa1cdafd03ec92cbb93f140b`，source-inputs SHA256
`5b45fb53dc00441428244e29df730d0a29eeec7200348954a158692a6097816c`。
public_api_audit 设计本体 B0/I0/M0，附属来源 M1 为将 ModelResponse 的 record 误写成 record/enum；
已按实际 ModelFailure.Category、ToolExecutionResult.Status 更正，不改变设计。剩余 B0/I0/M0。

## 放行范围

仅允许依照整体设计在同一 API 体系内进行正式 RED/GREEN、公开签名/文档及仓外制品验证。
无新增 Engine/服务/贡献注册体系；不改消费者产品源。正式实现、关闭、制品和仓外消费仍须分别独审与留证。

## 声明格式增量审查

实际 RED 发现 Jackson YAML object key 会将数字标量转为字段名字符串；固定3.1.5源码已核。
未发布候选的 hostApis 改成唯一 id/version 对象列表，避免第二 parser/原文扫描或新增依赖；
descriptor 仅将严格声明规范化为不可变 map。保留所有拒绝断言，不接受旧候选 map 格式。
contract_analysis 复审 B0/I0/M0；规格摘要 `6cc2e29b514c31baf409e538c3aad3f2882eca08fb3fb00deee3eb3bea8c92fd`。
静态门不代替 manifest-list-red/后续GREEN的运行证据。
