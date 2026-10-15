# 统一公共 API 本地交付证据

2026-10-15。Fibra 基线 `5b2d2176fd195525d8b5274511d187c182bdfe28`，分支 `codex/host-api-contract`，
验收工作树 `/private/tmp/fibra-host-api-20261015`；验收时源码未提交，未 push，未发布远端 release。
本记录只证明下述本地组合；整体合同以[权威设计](../../specs/2026-10-15-public-api-architecture.md)为准。
验收后按用户要求，将同字节源码带入 `/Users/sunke/dev/ai-project/fibra` 的同名分支，并随本批本地提交归档；
仅同步了工作树与提交状态文案，运行代码及测试没有变化，不自动 push。

## 结果与边界

在既有分层 API 内，由 `JavaRuntimeProvider` 冻结 Host 的 `JavaApiContract`，facet 通过唯一
`hostApis: [{id: example.document, version: '1'}]` 格式显式导入。动态代码获得 Host 的精确 Class；
对象绑定仍归 HostServiceRegistry，贡献和调用仍沿原 Bridge/PublishedRuntime，生命周期仍归原 Engine/driver。
生产实现仅修改 Java runtime，不增加模块、依赖、容器、服务定位器、版本 resolver 或产品包名特判。

两种中立合同与原有私有库隔离已验证。真实 Model API 的 26 个 public 类也通过同一公开入口消费，
没有动态 API unit、空占位插件或 kind 重建。这是消费者合同的仓外资格，不是 Tela 迁移、P3 完成或产品交付。

## 可复核输入与制品

完整原生命令、stdout/stderr 和报告位于 `/private/tmp/fibra-host-api-evidence-20261015`。
精简命令回执与原日志摘要归档于 [verification-receipts.json](verification-receipts.json)，
生产源码摘要见 [published-main-source-sha256.json](published-main-source-sha256.json)。
完整源码快照及其摘要在该证据目录的 `artifacts/source-snapshot.zip`、`source-snapshot-sha256.json`，
包含本次未跟踪文件，不能只用 `git diff` 重现本次变更。

公开制品目录为 `/private/tmp/fibra-host-api-evidence-20261015/artifacts`：

- `maven/`：30 个正式模块的本批最新 timestamped snapshot POM/JAR、sources/Javadoc 和元数据；没有混入较早构建。
- `fibra-0.5.0-SNAPSHOT-bin.zip`：SHA256 `7abac3af044e87dce072107b4795313c26c4065f266e38ce08308055e9200639`。
- 各制品逐文件摘要见 [artifact-sha256.json](artifact-sha256.json)，npm tarball 另存证据目录 `npm-artifacts/`。

构建使用 JDK21、Maven3.9.9、Node22.14.0、pnpm11.19.0、ripgrep15.0.1。
`build-m2` 与 `consumer-m2` 只预置第三方 cache，最初排除全部 `com/sstlfsj`；没有写默认 Maven 仓的 Fibra 坐标。
消费者使用独立 settings 和本批 file 仓，16 个实际解析的 Fibra POM/JAR 均已按字节及
`_remote.repositories` 比对，见 [consumer-resolution-sha256.json](consumer-resolution-sha256.json)。

## 验证结果

| 证据命令名 | 原生结果 | 证明范围 |
|---|---|---|
| inventory-imports-red / inventory-baseline-red / manifest-list-red | 各有预期断言失败 | 空值导入、非 public 基线签名及 YAML 严格声明的真实 RED；新类型缺失造成的编译 RED 单独记录 |
| engine-qualified-fixture / host-api-reclamation | 0 | 严格 LOAD 负例、中立 typed 调用、旧 fence、替换/停用、真实在途 drain、失败重复与 loader 回收 |
| runtime-engine-regression / signature-generation / architecture-boundaries | 0 | 受影响运行回归、既有签名生成门和架构边界；签名增量仅 Java runtime |
| client-public-packages | 0 | 原 frozen lock、客户端测试、边界、d.ts、打包及仓外 npm consumer |
| full-native-publication | 0 | 无跳测试参数的全量 `clean deploy`，含 verify、真实插件、archetype 和 ZIP launcher；1,254 tests，失败/错误/跳过均为 0 |
| public-maven-consumer-isolated | 0，4 tests | 仅公开 POM/JAR，HostServiceRegistry 配合两种合同、双 provider、私有类型隔离、旧引用拒绝、在途 drain 和回收 |
| public-spi-consumer-isolated | 0，4 tests | 修正后的公开 SPI 夹具；provider 仅作 factory，Engine 拥有 driver；直接 candidate 资格测试自行关闭 generation/candidate/driver |
| real-model-compile-final / real-model-public-zip-final | 0 / 0 | 仅新 ZIP 根 lib 的 26 JAR 加原 API JAR 编译运行真实 Model provider |
| release-reproducibility | 0 | 仓库原脚本两次 clean package 和一次重复 package；POM、主/source/Javadoc JAR、ZIP 与发行树字节/权限一致，最终 ZIP 与交付副本相同 |

完整统计见 [full-test-summary.json](full-test-summary.json) 和 [consumer-test-summary.json](consumer-test-summary.json)。
全量正式测试后仅收紧两处测试清理/探针断言，生产源码未变；最新夹具分别由后续两组仓外 4 tests 实际覆盖。
可复现脚本原有 `-DskipTests` 仅用于三次包装字节比较，不抵扣此前无跳测试的全量正式验证。

关闭证据按范围解释：公共 Engine 测试确认已关闭 loader 不再提供自身资源、未停 peer 保持可用，
并在独立工作线程结束后以 WeakReference 观察退休与两次失败 loader 均回收，活跃 peer 作为对照。
原 JavaRuntimeDriverTest 同批覆盖 diamond lease、最后 lease 释放、部分取得失败、清理失败保留 owner、
失败依赖重试与重复关闭。本轮没有通过 OS 文件描述符枚举证明每个句柄，也不把目录为空等同全部释放。

## 真实 Model API 仓外证明

原 API JAR 和 provider class 输入及精确摘要见 [model-artifact-inputs.json](model-artifact-inputs.json)。
API JAR SHA256 为 `f37b73abeab51effa23a222cf1d87860f598b3d576b962ea0917ec3a0975a30a`；
原 provider JAR 为 `7f5bb25db7b0f3454d0419fb7e56769019645462f34067d323ce2cffe27511d9`。
仓外包仅修改身份/依赖元数据与 Host API 导入声明；三个 provider 包的每个 class 字节与原 JAR 相同，API JAR 原样保留。
Host classpath 仅使用 ZIP 根 `lib/`，不包含发行插件的 `main.jar`、reactor target/classes 或 Tela 实现源码。
根 lib 摘要见 [model-distribution-lib-sha256.json](model-distribution-lib-sha256.json)，
实际消费者源码见 [ModelApiConsumer.java](ModelApiConsumer.java)。

真实 provider 首轮产生 RequiresAction/ToolCall；Host 构造工具结果并以原 continuation 再次调用，断言
Completed/AssistantText 的精确内容。这验证 DTO/continuation/builder/enum 和 kind 身份，不运行真实文件工具。
输出为 `HOST_TYPED_BOTH=true`、`API_INVENTORY_CLASSES=26`、`NO_API_UNIT=true`、
`AFTER_REPLACE_TYPED_BOTH=true`、`AFTER_REMOVE_TYPED_PEER=true`、`AFTER_CLOSE_ENTRIES=0`。
旧 view 和旧 registration 仍分别被既有异常拒绝。重复 Engine close 后 Host API 仍能实例化。

## 失败记录与未验证项

- 首轮受限沙箱全量在 NodeProcessUnitTest 出现 16 个 identity 夹具文件缺失，因 payload 的 `/bin/ps` 被沙箱拒绝。
  单项原生环境通过后，同源全量原生 `clean deploy` 通过；没有更改 Node 实现或断言。
- 初次 Maven 仓外运行受用户默认 settings 影响，无法解析第三方传递依赖；独立 settings 经官方 Central 补齐后通过。
  公开 POM 自身的传递版本可能与 ZIP 固定依赖不同，本次没有更改其版本或强制消费者降级。
- Model 首轮夹具错误地假定 Initial 直接返回 Completed；按真实 provider 的两轮合同修正后通过。
  ZIP 提取随后收紧为仅根 lib，最终两次原生命令重新验证；不使用此前包含插件 JAR 的 classpath 充当最终证明。
- 本次为 macOS/JDK21 本地资格；没有新远端 CI、Windows 原生运行、恶意插件安全隔离、性能 benchmark 或完整 Tela 产品回归证据。
  空仓在线分发门按 CONTRIBUTING 只在 push/release 执行，本次未运行，不能将预置第三方 cache 的独立消费称为完全空仓。
- API 定义 loader 由 Host 持有至所有使用者停止；driver 不关闭它。API 内容变更须新版本、重建消费者并受控重启。
  JDK 反射对缺失 permits 项的窗口及 private metadata 链接限制仍按权威设计保留，不声称静态 inventory 等于完整发行验证。

验收时 main 工作树保持干净；后续按用户要求将该目录切到 `codex/host-api-contract`，main 引用未变。
已读取的 10 个 Tela API 源码摘要未变。没有修改 Tela、用户库/数据或执行迁移。
Tela 的 core 所有权选择、回退门、判定时点与 overview 冻结项同步应在 Tela 自身设计中决定，本上游不替其作决定。
