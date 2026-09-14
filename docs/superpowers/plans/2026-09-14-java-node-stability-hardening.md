# Java/Node 长稳与性能打磨计划

> 执行约束：只使用 superpowers 工作流；保持 `0.5.0-SNAPSHOT`、公开 API、POM、依赖和冻结语义不变；没有可重复红灯或增长曲线，不修改生产实现。

**目标：** 完成权威架构第 11.9 节第 4 项的最小证据闭环：用真实制品 revision 验证 Java 同内容重装、同版本换内容和依赖闭包多轮更新；用真实 Node sidecar 验证多轮启停、调用、PID/线程/会话目录回收；补齐分片帧和半帧 EOF 协议边界，并记录资源与延迟基线。

**边界：** 不建设监控框架，不把一次 GC、绝对堆大小、FD 或单机耗时写成硬阈值；不把制品历史的正常增长算作运行时泄漏；不扩展到容器、远端执行或通用秘密模型。

**技术栈：** Java 21、JUnit 5、Reactor、Maven Surefire、Node.js、现有 JMH。

## 已有证据与最小缺口

| 范围 | 已有证据 | 本次缺口 |
|---|---|---|
| Java 依赖闭包 | 单次升级会重建变更制品及依赖者，保留无关 loader；失败清理会保留真实先决资源 | 缺真实 `ArtifactStore` revision 驱动的同版本不同内容与多轮 loader 收口 |
| Java 重复安装 | 50 次同 records 更新与并发 snapshot 已证明 no-op 路径无锁反转 | records 使用伪 revision；不能证明同版本同内容保持 identity、同版本换内容触发闭包更新 |
| Node 异常与静默 | 异常退出、心跳失联、顽固子进程、清理失败和调用取消均有真实进程测试 | 缺真实 sidecar 多轮启动、调用、停止后的 PID/线程/会话目录曲线 |
| Node 帧边界 | 超大帧、格式错误、重复字段和错误对象已有直接测试 | 缺跨多次写入的合法分片帧与无换行半帧 EOF |
| 性能 | JMH 已覆盖调用热路径和 Engine 事务，且明确排除文件系统/进程路径 | 缺当前 HEAD 的短基线；无依据设置回归阈值 |

## 任务 1：Java 多轮 loader 收口与更新基线

**文件：**

- 修改：`fibra-runtime-java/src/test/java/com/sstlfsj/fibra/runtime/java/JavaPluginRuntimeAdapterTest.java`

1. 用真实 `ArtifactStore` 保存同版本同内容、同版本不同内容的制品，避免测试伪造 revision。
2. 保持一个无关插件，循环切换依赖制品内容；每轮断言变更制品及依赖者重建、无关 definition/loader identity 保持不变。
3. 通过现有 `LoaderCloser` seam 记录已创建和已关闭 loader；每轮活动 loader 数必须保持闭包大小，owner 最终关闭后创建数与关闭数相等。
4. 同内容重复安装必须是 no-op：revision、definition 和 loader identity 不变，不能多关闭 loader。
5. 记录线程、FD、堆、更新耗时样本，只把确定的 loader 所有权和最终关闭作为硬断言。

## 任务 2：Node 多轮真实 sidecar 收口

**文件：**

- 修改：`fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodePluginRuntimeAdapterTest.java`

1. 用真实 `ArtifactStore` 保存两个同版本、不同内容的 Node 制品；同内容重装必须保持 definition identity，换内容必须更新 definition。
2. 保持同一个 runtime 和 owner，在有截止的循环中 mount、通过 contribution 调用取得 sidecar PID、dispose。
3. 每轮断言调用结果属于当前制品 revision；dispose 后 PID 已退出、session 目录为空、`fibra-node-*` 线程数回到循环前基线。
4. 记录线程、FD、堆、更新、启动、调用和停止耗时；FD、堆和延迟只形成原始基线，不设置未经证明的硬阈值。

## 任务 3：Node 分片帧与半帧 EOF

**文件：**

- 修改：`fibra-runtime-node/src/test/java/com/sstlfsj/fibra/runtime/node/NodeSidecarTest.java`

1. 让合法 JSON-RPC 响应拆成两次 stdout 写入，断言 host 在完整换行帧到达后正常完成。
2. 让 sidecar 写出无换行半帧后主动结束 stdout、暂时保持进程存活，断言请求在截止内以 `PROTOCOL` 失败。
3. 半帧失败后确认 sidecar 受管进程已退出且 session 目录清空，避免只断言 Java 包装状态。

## 任务 4：验证、基线与审查

1. 先运行新增定向测试；若出现产品红灯，保留最小复现并只修根因；若全部通过，不改生产代码。
2. 运行 Java/Node runtime 模块及上游测试，再运行一次 50 模块 `clean verify`；大日志只写临时文件。
3. 对现有 `ContributionInvocationBenchmark`、`EngineTransactionBenchmark` 各跑一次短 JMH JSON 基线；记录环境、HEAD 和样本，不以单次结果设阈值。
4. `git diff --check`；确认 POM、依赖、版本和公开签名未变化。
5. 安排规格与质量独立复审；Critical/Important 返回对应任务修正。
6. 更新本计划完成记录；等待用户推送后的同一 HEAD Linux CI。
