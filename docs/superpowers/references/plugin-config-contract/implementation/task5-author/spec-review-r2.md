# Task 5 合同独立复审 R2

结论：**B0 / I0 / M0；Ready: Yes（Task 5 合同范围）**。R1 唯一 I1 已闭合，未发现窄修新增越界；不代表 Task 6 或总目标完成。

## 输入绑定

- 首次及结束前独立复算修复后 54 个冻结源码，54/54 SHA256 一致；收据 `spec-review-r2-input-sha256.json`。
- 与 R1 输入比较：旧 helper 删除并移动到现有仓外 engine-application 专用测试源；四个测试消费者 POM 同源编译；新增仓外作者测试与 README；EngineConsumerTest 仅将既有正式包列表和组包方法改为包内可复用。没有新增生产类、生产依赖、公共 API、Maven 模块或生成工程仓库路径依赖。
- 将 helper 新 Javadoc 还原为旧文字后，内容 SHA256 与 R1 helper 完全一致，证明本轮没有暗改其资源策略。类注释已明确保留整个 Engine scope 是既定保守政策。
- 使用既有 requesting-code-review 流程；定向 Nowledge thread_search 返回空集，不以历史信息充当证据。

## I1 闭合依据

`AuthorConfigurationConsumerTest` 在仓外真实执行三项测试：

1. 用本次正式发布的 12 个最终 JAR 经既有组包函数创建完整包，安装并 disabled selection；断言无 desired instance/运行实例；使用实际 target revision，调用唯一 helper 的整包 `describeDefinitions`、verified record 固定资源 read/实际 close、parse、完整 facet/definition keySet 比较及 `ConfigSchema.checkSources`。四 API 包的空 main 映射同门通过。
2. 真实安装和停用 bad schema、缺 definition、orphan definition 三种根合同；先证明包存在、selection disabled、没有运行实例且实际目录名称存在，再要求同一 helper 拒绝。该反例不以模拟 catalog 或安装失败代替发布资格失败。
3. 代表性关闭失败沿 verified resource 实际 read，测试宿主保留同一个 QA owner 并记录终止通知；随后 QA.close 不重试资源关闭、不关闭 Engine/store，真实 find 和目录仍可观察。原仓内同 helper 的 read/close/fatal/通知异常矩阵保留并重新运行，未另建第二套 owner。

唯一 helper 位于 `fibra-distribution/src/test/consumers/engine-application/src/author-qa/java`，由外部 POM 相对本项目编译；三个内部 POM 引用同一源且保留各自 src/test/java。仓外测试的 Fibra classpath 不含工作树 target/classes 或工作树 JAR。原 11 项仓外 Java/Node 生命周期及其他消费者测试保留，新测试没有替换原门。

## 独立证据复核

- `external-input-sha256.json` 的 24 个实际仓外输入全部与当前仓库对应文件及记录 SHA256 相同。
- 从本轮真实 XML 读取 classpath，所有外部测试合计 15 个 Fibra JAR；加 12 个正式 staged plugin JAR，共 27 条实际字节与原锁定 `author/distribution/run/remote` 相同。作者资格测试自身使用其中 11 个公开 Fibra JAR，无仓库路径借用。
- 仓外原生 `external.exit=0`；8 份实际 TEST XML 合计 **14 tests，0 failure/error/skipped**，包括新增作者资格 3 项。日志记录同样的执行类与成功结果。
- 本轮内部原生 `internal.exit=0`；当前实际执行的 4 份 XML 合计 **11 tests，0 failure/error/skipped**：archetype 1、formal artifacts 7、QA owner 2、Node author 1。未宣称本轮再次执行完整 1059 项。
- 独立比较当前 archetype flattened POM、当前主 JAR 与原发布仓精确字节，分别匹配 `d74b168dd5b57646d0ffa68c1ded91a8bf2cc29540b8d3888d67bff412ecfd8b` 和 `a0e2fe696b519f87fc27a20de715a1df38bbe95aefa65720f408b469569a75ac`。正式 12 包与构建配置未变，本轮无需重发正式制品；R1 的最终字节链、84 发布 JAR 排除 helper 和原发行证据继续成立。
- 本轮发现 `internal-reports.json` 曾误索引历史 RED XML，使报告错误记成 12 项。实现者已只改临时证据索引/REPORT，明确隔离历史 RED，原 XML 与错误收据均保留；我复核修正后 4 份索引、实际 XML、日志一致。这不是本轮测试失败，也不隐藏旧失败。
- 按主线程要求，额外把初轮 `full-author-final.log` 的 134 条逐 class 执行摘要与归档 134 份 XML 一一对齐：确为 **1059 tests，0 failure/error/skipped**，无只在 XML 出现但本轮未执行的 suite，也无数量差异。回执 `spec-review-r2-initial-run-log-audit.json`。

汇总复算回执：`spec-review-r2-evidence.json`。修正后 REPORT SHA256=`e743191f57cebc77eda6f39c4ee092d736294e433860a2e3d0dfad57202af376`；当前内部报告索引 SHA256=`590bd1aa49d1f9080435628543e2cf69eaeb8b7356f6056a1779e7d948025d5c`。

## 测试决策与释放

本审查没有运行 Maven；现有运行已绑定冻结输入、完整执行日志、native exit、实际 XML、正式依赖字节，额外机械重跑不能增加必要证据。只写临时审查报告/收据，未改源码、未 commit/push/Tela。结束前获准只读定向 ps 未发现相关 Maven、Surefire 或 fibra-config-contract 测试进程。**Maven 唯一写入权已释放给主线程。**

Task 6 的 CAS/validator 次数/安全实际出口/Node 数值/client 产品/Tela 仍按原计划继续，不重开或改变 Engine 保守关闭政策。
