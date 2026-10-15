# Fibra 统一公共 API 实施计划

> 按 subagent-driven-development 分工；主线程负责合同、集成和最终证据。用户授权不自动 commit/push。

**Goal:** 在一套分层公开 API 中补齐通用 Java 合同身份，沿既有 Engine 和调用入口运行。
**Architecture:** Java provider 冻结 Class inventory；每 facet 显式导入版本及完整 ABI 并集；不增控制/服务/调用面。
**Tech Stack:** JDK21、Maven3.9.9、现有 Jackson/Reactor/JUnit；无新增依赖。

设计门见 [design-review](../../references/host-api/design-review.md)，活动验收见 [task_plan](task_plan.md)。

## 1. 不可变合同与同一闭包算法（独立实现边界）

文件：新增 `fibra-runtime-java/src/main/java/com/sstlfsj/fibra/runtime/java/JavaApiContract.java`、
包私有 `JavaApiContracts.java`、`JavaApiSignatures.java`（必要时合并内部 helper），测试 `JavaApiContractTest.java`。

- [x] 先写失败测试：形状/不可变、同名异 Class、基线/managed/hidden、nested/generic/throws/permits、无初始化、
  导出并集及导入并集、顺序无关指纹。新类型未存在的编译 RED 单独记为接口缺口，不冒充运行断言 RED。
- [x] 最小实现公共 record；内部冻结 registry 只作链接配置，不持服务/Scope/driver。
- [x] 统一内部接缝：`JavaApiContracts(List<JavaApiContract>)`，`identity()`，`names()`，
  `bind(ArtifactId, Map<String,String>) -> Map<String,Class<?>>`；同算法检验全导出与显式导入。
- [x] 正式运行并记录测试；主线程 review diff/测试后再扩展回归。

## 2. 原 Java provider/descriptor/loader/index 集成（主线程）

文件：`JavaRuntimeProvider`、`JavaRuntimeDriver`、`JavaFacetDescriptor/Reader`、`PluginClassLoader`、`JavaClassIndex`。

- [x] 先测 hostApis 严格 id/version list（规范化 map）、缺/错导入、无导入/间接导入、影子MR及普通私有类隔离，运行确认失败。
- [x] provider 构造冻结同一 JavaApiContracts；driver 取得同一配置和 identity，WiringKey 原值使用。
- [x] descriptor canonical 三字段，原两字段构造委托空map；仅一种解析语义。
- [x] prepare 先读/核当前 facet imports 与 class index，再物化该 facet；每依赖同样校验；保留原取得和失败 owner。
- [x] loader 在任何fallback前核全局保留名及当前导入；index在parent跳过前拒绝保留名。
- [x] 定向测试全部成功；原 Java runtime/engine 回归无退化。

## 3. 完整 Engine 与公共消费（独立测试边界）

文件：新增 `fibra-runtime-java` 的独立 `verification.hostapi` 包及中立 fixture；仓外 consumer 原样复制该公开 API 测试。

- [x] 先固定默认 typed 身份失败及新公开构造缺口，测试不取私有 driver/handler。
- [x] 双 provider、Host kind/DTO、跨合同、旧view/registration、替换/停用A保B与API、真实latch在途drain。
- [x] prepare失败及重复close/失败清理、loader关闭/lease/活跃对照分别留证；不以目录空代替关闭。
- [x] 修正旧外部provider fixture缓存driver/双关闭示范，保留SPI局部测试且明确其证明范围。

## 4. 同批文档、签名、审查与发行

- [x] 直接更新 `docs/api/README.md`、vNext §6.2、Client Foundation §10，链接整体设计；无矛盾旧正文。
- [x] 经既有 `ApiSignatureBaselineTest` 生成签名，只接纳本任务 Java 公共变化。
- [x] 独立代码—设计—来源双审，B/I/M清零。
- [x] 全量 `clean deploy`（含verify）、architecture、固定Node/pnpm客户端、可复现发行与仓外consumer；不弱化门禁。
- [x] 构建仓 `/private/tmp/fibra-host-api-evidence-20261015/build-m2`，只预置第三方cache，排除原com/sstlfsj。
  原生命令/源码摘要/退出统一由同目录 `run.py` 保存。独立发布目录和consumer仓只含本批Fibra字节。
- [x] 输出精确 POM/JAR/ZIP、摘要、接入文档及已证/未证边界。验收输入已归档；随后按用户要求在同一分支本地提交，main引用与Tela保持不动，不push。

定向命令（设置技能指定 JDK21/Maven 后）：

```sh
$MVN -o -Dmaven.repo.local=/private/tmp/fibra-host-api-evidence-20261015/build-m2 \
  -pl fibra-runtime-java -am test -Dtest=JavaApiContractTest,JavaHostApiTest,PluginClassLoaderTest,JavaClassIndexTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

任何重复问题连续两次失败即停止硬试并记录证据。设计变更先更新同一权威规格并重审，不在实现里暗加兜底。
