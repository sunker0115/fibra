# fibra-benchmarks

本模块跟踪 Fibra vNext 的稳定热路径，不是展示性质的压测项目。它参加默认 reactor，因而能及时发现公开 API 漂移；默认 Maven 生命周期只编译、打包 JMH，不执行耗时测量，也不发布该模块。

## 测量边界

JMH 只测可在单 JVM 内稳定重复的路径：

- `LifecycleDispatchBenchmark`：一次空事件调用，作为生命周期线程调度边界的基线。
- `ServiceResolutionBenchmark`：服务直接解析、绑定引用调用，以及生命周期线程内批量解析。
- `EventDispatchBenchmark`：1、8、64 个监听器下的广播和 waterfall 调用。
- `ContributionInvocationBenchmark`：已注册本地贡献从冻结路由查找、类型校验、调用到 inflight 释放的完整 `ContributionRoutes.invoke` 路径。
- `EngineTransactionBenchmark`：将 noop definition 作为 `JavaRuntimeProvider` 私有的 built-in package，从 `PluginRegistry` 发起 desired entry disable/enable，经 Engine 单写通道、完整 `DeploymentTarget` CAS、代际发布和旧 Scope 退役的完整控制面路径。该用例使用内存 target store 和丢弃型 audit，排除存储介质差异。

以下能力不放入 JMH：

- package 复制、校验与目录落盘；
- Java JAR 扫描、`ClassLoader` 创建与关闭；
- Node sidecar 进程启动、握手、JSON-RPC 和进程终止；
- Spring Boot 启动与 HTTP 请求。

这些路径受文件系统、进程调度和操作系统权限影响，微基准数字容易误导。它们由各模块集成测试、`fibra-example` 真实场景测试和分发验证负责。

## 构建与运行

```bash
mvn -pl fibra-benchmarks -am -DskipTests clean package
java -jar fibra-benchmarks/target/fibra-benchmarks.jar
```

开发时可先跑短测，确认全部基准可发现、可执行：

```bash
java -jar fibra-benchmarks/target/fibra-benchmarks.jar \
  -f 1 -wi 1 -i 1 -w 200ms -r 200ms
```

只测一组并输出 JSON：

```bash
java -jar fibra-benchmarks/target/fibra-benchmarks.jar \
  ContributionInvocation -rf json -rff result.json
```

## 结果使用规则

- 比较前后版本时固定机器、JDK、JMH 参数和电源状态，保存原始 JSON。
- 先看误差区间和多次趋势，不依据一次运行下结论。
- JMH 结果只用于识别回归和定位固定成本，不代表插件安装或端到端请求延迟。
- 仓库不保存会快速失真的单机数值；需要发布性能结论时，随版本附环境、提交号、命令和原始结果。
