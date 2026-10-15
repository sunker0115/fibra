# Fibra 统一公共 API 设计与交付

## 目标与边界

统一现有 Fibra 公共 API 的角色、层次、唯一入口与资源所有权，在同一体系内补齐宿主与动态 Java facet 的合同类型身份。
用户已授权通用上游设计、验证及必要实现，并要求整体规划、不得形成多套 API 或产品特判。
实施与验收在 `/private/tmp/fibra-host-api-20261015` 的 `codex/host-api-contract` 完成；基线 `5b2d2176`。
用户随后要求切回当前目录并提交，活动工作树为 `/Users/sunke/dev/ai-project/fibra`，仍使用同一分支；原目录保留 detached 备份。
不改 Tela、本批仅按用户要求本地提交、不自动 push、不改变默认 Maven 仓库中的 Fibra 制品、不删除用户数据。

## 执行与验收门

- [x] 核验主树/隔离树、公开签名、权威架构、装配/加载/关闭和真实消费者。
- [x] 初次核验 Tela 批准前静态提案 SHA256 `128d50c8fd39d553c130ac624fe7df839faae452079144f823394be7403519b7`；后续当前字节 `6f82a59ba0e4d63501f44ff80974d6cbfb0b0e64e0c7f2cd7d53dff4a21ebfb6` 顶部已写批准状态，§6 上游要求仍一致。未归档原副本，不声称已验证全文差异只含状态。
- [x] Nowledge MCP thread_search `Fibra ClassLoader host API shared contract Tela` 返回空；当前源码及制品作为依据。
- [x] 整体 API 有界分析：受众/层次/唯一 owner、禁止边、三路线取舍、变更面与演进规则。
- [x] 权威设计与独立设计/资格审查，B/I/M 全部清零后才写生产代码。
- [x] 失败测试：显式合同、完整 ABI inventory、导入版本、重复/间接导入、类型身份及私有类隔离。
- [x] 最小实现：沿原 provider/driver/loader/index，保持原 Engine/Bridge/Scope/fence/lease owner。
- [x] 真实 Engine 双 provider、typed 调用、旧 fence、停用/替换、在途 drain、两次prepare失败与loader回收/活跃对照。
- [x] 同批公开签名、文档一致性、runtime/engine/整仓受影响回归和独立代码审查。
- [x] 独立 Maven 仓及发行目录构建公开 POM/JAR/ZIP；仓外仅用公开制品验证，绑定源码/命令/退出/摘要。
- [x] 报告已证范围、未证边界及消费者接入合同；不宣称 Tela/P3 完成。

## 停止条件

发现第二控制面、业务事实源、未归属资源、产品特判或超出已批准持久/公开合同影响时，先修设计。
同一问题连续两次失败，停止硬试，记录已试方案、错误与下一取证方向。
静态通过不代替运行通过；清理失败不得关闭仍被依赖的 owner 或假报释放成功。

## 当前状态

本地实现与发行资格完成。整体设计/格式增量、inventory规格、整体代码质量及最终证据独审均B0/I0/M0；
生产改动仅Java runtime层，公开签名仅该模块增量；现行三份文档同步同一体系，不增模块/依赖/容器/resolver。
验收后已将同字节源码带入当前目录的 `codex/host-api-contract`，main 引用未变；本批本地提交，不 push。
所有新制品只写本任务独立仓/发行目录，验收后仅同步工作树与提交状态文案。
结果、原生命令、精确制品和未验证边界见[交付证据](../../references/host-api/delivery.md)。

## 实际验证与问题记录

- 证据根 `/private/tmp/fibra-host-api-evidence-20261015`，各命令由run.py记录原生argv/源码摘要/退出。
- baseline-loader通过；inventory类型缺失编译RED单列，不冒充运行失败。imports空值与nonpublic baseline两组真实RED已修复，inventory19项GREEN。
- host-api-first-runtime：测试包漏dependencies/capabilities，随后暴露缺fibra-config；均是夹具问题，未进入预期逻辑。补齐并收紧负例要求实际Java LOAD失败，防无关错误假绿。
- 数字YAML键被字符串化的真实RED促使未发布候选格式增量审查；唯一list格式通过，不接受原候选map，不增加parser。
- engine-qualified-fixture通过27项；host-api-reclamation通过4个真实Engine场景，包含回收/失败重复/活跃peer。
- runtime-engine-regression通过；signature-generation成功，baseline变化仅fibra-runtime-java。
- 架构边界与固定Node22.14.0/pnpm11.19.0的client-public-packages通过；离线依赖缓存不全及全局store沙箱不可写已留证，改用本任务独立store按原frozen lock补齐，未改版本/锁文件。
- full-verified-publication在NodeProcessUnitTest有16个夹具identity文件缺失：源码payload依赖/bin/ps，受限环境明确拒绝ps。原生单项node-native-environment通过，未修改Node源码/断言；full-native-publication同源全量clean deploy原生0，1254 tests无失败/错误/跳过。
- 公开Maven与SPI两组仓外consumer各4项通过；16个Fibra POM/JAR字节与发布来源逐项一致。最新两处test收紧已由仓外夹具覆盖，生产源码与正式全量输入一致。
- 原Model API JAR及provider class字节保持不变，仅仓外元数据/导入调整；26根lib JAR的真实ZIP调用通过双provider/两轮typed模型调用、替换、停用及重复关闭。
- release-reproducibility原脚本原生0，三次包装比较一致；内置skipTests仅用于包装比较，不抵扣完整正式测试。最终ZIP与交付副本一致。
