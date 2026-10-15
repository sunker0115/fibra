# 延迟链接生命周期修复资格

2026-10-15，分支 `codex/lazy-linkage-lifecycle`；基线 `dde0e5de36f8bb524d2f552261a853c44f2489f1`。
本记录绑定验证时的工作树；修复源码由包含本记录的 Git 提交定位，不能把基线 SHA 当作修复提交。实现见
[设计](../../specs/2026-10-15-lazy-linkage-lifecycle-design.md)，机器回执见 verification-receipt.json。
机器回执保留验证时尚未提交的事实；后续提交只同步交付状态文字，生产和测试源码保持已验证字节。

## 结果与范围

受控生命周期回调的非 VM 致命链接失败现在交付原失败，Java Unit 沿原路径关闭 admission、清理 Scope；
清理失败保留必要 owner/lease，成功 stop 后才释放最后 lease。真实缺导入 factory/start 不再令部署等待者失去终态。
新 apply 保留 SAVED/RECONCILING；reconcile/bootstrap 为 NOT_APPLICABLE；原 prepare LOAD 拒绝仍为 NOT_SAVED。
独立 peer、重复失败/重试、重复关闭、物理 loader close 和回收均有实际断言。

不新增公开 API、依赖、持久字段或恢复状态；19份签名基线、POM、client 源码无 diff。
直接修正原路径，无新旧双实现、兼容开关、旧签名转发或产品特判。
原 ClassLoader 隔离未改；没有字节码预扫描、预执行 factory、提前引用或自动重试。
VMError/ThreadDeath 保持 fatal；第三方异步线程直接 throw、违约下游或永不返回的 Publisher 不在受控恢复保证内。

## 正式门禁

原始证据根：`/private/tmp/fibra-lazy-linkage-evidence-20261015`。
每个运行目录保存实际 argv/cwd、源码摘要、原生退出和 stdout/stderr；formal-test-reports 保留 clean 前的全部 XML。

| 运行 | 实际结果 |
|---|---|
| core-red-native / java-red-native | Core 9项预期hang；Java真实factory/start/bootstrap 3项失败；父进程有界回收自己child |
| callback-subscription-red | 22项中4失败，明确复现普通同步subscribe throw转换为onError后的上游取消遗漏 |
| affected-all-tests | 原生0，78份XML共661项，0失败/错误/跳过 |
| full-verified-publication | 原生0，完整clean deploy，177份XML共1317项，0失败/错误/跳过；包含签名、Java/Node、重启、正式插件和发行ZIP验证 |
| architecture-boundaries / client-public-packages / engine-client-protocol | 均原生0；架构、声明/归档消费者及真实Engine→Java codec→TypeScript通过 |
| public-distribution-consumers | 原生0，六模块9份XML共22项，0失败/错误/跳过 |
| public-host-contract-consumer | 原生0，仓外公开制品7项，0失败/错误/跳过，包含真实迟发链接和冷启动 |
| model-consumer | 新ZIP根lib编译；原成功矩阵与真实缺导入矩阵各独立JVM原生0、未超时；原API/provider/config输入零漂移 |
| release-reproducibility | 原生0，两次clean package及一次已有JAR重打包的POM/主JAR/sources/Javadoc、ZIP与目录树一致 |

独立设计、实现及文档复核 B0/I0/M0，见 design-review.md 与 implementation-review.md。
本机 macOS ARM/JDK21、Maven3.9.9、Node22.14.0、pnpm11.19.0、ripgrep15.0.1、Bash5.3；未执行远端CI。

## 来源和制品绑定

交付目录：`/private/tmp/fibra-lazy-linkage-evidence-20261015/artifacts`。
ZIP SHA256：`bd4bb774b4221dea927d3e430f54f8f4b4d7d13355bce7bf860573469d68b654`。
30个正式模块115份输出与本轮公开仓逐字节对应；431份主Java源码与公开sources JAR一致。
仓外consumer-m2的110份POM/JAR（含base与timestamp snapshot文件）逐一匹配本轮公开仓的同坐标字节和来源记录。
真实Model Host classpath只有指定ZIP的26个根lib、原API JAR与新consumer classes，没有provider JAR或reactor classes。
四个provider变体除META-INF/fibra/plugin.yaml之外的全部entry与原JAR相同；坏包仅去掉hostApis。

全量门后非文档源码零漂移；旧e133814和79626c0交付目录全部既有文件及摘要保持不变。
source.patch/source-snapshot.zip和最终artifact-sha256.json固定验证时的源码快照；不发布至外部仓库。

## 消费者交接

本轮没有修改Tela，也没有向其他会话发送消息。Tela若仍固定e133814，现有制品一字节不变；
升级必须明确切换本轮file仓/ZIP摘要并重新验收Host，不能混用同坐标SNAPSHOT缓存。
从e133814升级还须同步父基线已完成的CAS/普通快照、Registry构造器与handle调用迁移：
使用`PluginRegistry(engine,audit)`，从单个PublishedView.resolve取得handle，再invoke(handle,kind,input)。
直接使用当前合同，不添加旧签名兼容层。生命周期修复自身不再要求新增调用参数或持久数据迁移。

用户已明确授权提交并合入public-api-boundaries，使用快进保持单一提交线；具体提交及分支状态以Git为准。
不推送远端，不触碰Claude工作树或主工作树原有的.claude用户文件。
