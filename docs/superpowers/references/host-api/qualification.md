# 公共 API 整体设计的只读资格输入

日期：2026-10-15；Fibra 基线 `5b2d2176fd195525d8b5274511d187c182bdfe28`。
本文是来源记录，不定义另一套 API，不证明新实现通过。

## 现有体系核验

- `docs/api/README.md` 与 Client Foundation §3/§9/§10 已规定同一 Engine、Bridge、runtime SPI 和 HostServiceRegistry。
- `JavaRuntimeProvider.java` 仅提供 built-ins；`JavaBuiltInPackage` 只有定义/metadata，不是宿主 Class export。
- `PluginClassLoader.java` 原依赖 fallback 使用 dependency.loadClass；因此新的 Host import 必须先阻断未导入保留名。
- `JavaClassIndex.java` 原来跳过实际父共享类；新增影子 API 检查必须在跳过之前执行。
- `JavaRuntimeDriver.acquire/createWiring/release` 与原 AcquisitionOwner/StaticLease 是既有 owner；不再造生命周期树。
- `RuntimeProviderConsumerTest` 旧夹具直接持 provider.driver 操作 candidate，且 provider 缓存 driver；只证明 SPI 编译/局部操作。
  正式新资格必须沿 Engine start/apply/published.invoke，并纠正该示范的 factory/关闭 owner，不能拿旧夹具抵扣。

## 真实消费者模式（仅作为输入）

只读目录：`/Users/sunke/dev/ai-project/tela/tela-model-api/src/main/java/com/sstlfsj/tela/model/api`。
当前源码摘要见 [source-inputs.json](source-inputs.json)，与旧发行物明确区分。

- `ModelContributions.KIND` 是静态 kind 持有者；共享 descriptor/request/response 三个顶层 Class 仍不足。
- `ModelRequest` 含 Input/Initial/ToolResults；`ModelResponse` 含 OutputItem/Completion 及多个 public record；
  enum 包括 `ModelFailure.Category`、`ToolExecutionResult.Status`。
  `ModelFailure`、`ModelResponse`、`ToolDefinition`、`ToolExecutionResult` 的实际编译 JAR 还包含 Lombok public Builder。
- 同包 `TelaModelApiEntrypoint` 和私有 ApiPlugin 不是共享合同。精确 package+anchor 会使同包新增实现也可见，
  因此没有把它选作默认导出粒度。来源不授权 Fibra 引入这些产品类型或针对其包名判断。

## 已有仓外失败对照

目录：`/private/tmp/tela-p3-core-abi-qualification-20261015/REPORT.md` 及 AbiProbe/inputs/compile/run/stdout/stderr。
固定 Fibra 源 `0d3f770f77abfdc3e944f5f01bfe0f4137c57cc0`，公开 ZIP SHA256
`d1ddccfa380f38d70712228bcb454ee3fe3323fc41dff18fab1c219f4375c30b`，提取 26 JAR。
API main.jar SHA256 `f37b73abeab51effa23a222cf1d87860f598b3d576b962ea0917ec3a0975a30a`；
deterministic main.jar SHA256 `7f5bb25db7b0f3454d0419fb7e56769019645462f34067d323ce2cffe27511d9`。

真实 Engine 部署成功，但 descriptor instanceof Host 类型为 false，Host 同字节 KIND 被拒绝。
javac/java 原生 0 是预期失败反例成立，不能计新 API 成功。停用/关闭后目录归零，不证明并发排空或 loader/lease 逐项释放。
当前 Fibra 基线相对该输入的后续变化须以 Git/正式测试复核，不能从此旧制品直接推出新代码结果。

## 外部原则与限制

[JDK21 ClassLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html) 描述定义 loader、
平台 loader 与类型解析；[PF4J](https://pf4j.org/doc/class-loading.html) 为插件 loader/委派原则，
[OSGi Core 8](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html) 为显式导入/导出原则。
[JDK21 Class.getPermittedSubclasses](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Class.html#getPermittedSubclasses())
会忽略无法取得 Class 的 permits 项；反射闭包不能代替 Host 完整发行验证。
未引入这些框架、依赖或字节码解析库，也没有恶意插件安全沙箱保证。

## 声明格式的参照边界

用户追问后于2026-10-15核对源码；列表是Fibra的格式取舍，不称为参考项目的通用标准，也不将
“Jackson object key 是字符串”误说成所有 YAML parser 都无法校验原键。

| 参照 | 实际声明/解析 | Fibra取舍 |
|---|---|---|
| [PF4J](https://pf4j.org/doc/plugins.html#notes-about-plugin-dependencies) | MANIFEST/properties中的Plugin-Dependencies文本条目，公开descriptor返回List<PluginDependency>；名称与版本由条目解析 | 采用显式依赖记录原则；不引入它的文本DSL或可变descriptor |
| [OSGi Core §3.6.4](https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.importpackage) | Import-Package为逗号分隔clause，包名加版本等属性；重复导入同包报错 | 采用显式名称/版本要求与重复拒绝；不照搬package resolver或版本范围 |
| [Pi固定manifest](https://github.com/earendil-works/pi/blob/f1b2e77f5b13b2a199b1052cb79c235451afe7d7/packages/coding-agent/src/core/pi-manifest.ts#L17-L30) | JSON.parse(package.json)，扩展路径为string[]，npm依赖为对象；没有YAML键类型问题 | 不是Java Class inventory/版本导入的实现来源 |
| [DSH固定主loader](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/include/src/index.ts#L240-L264) | js-yaml.load后要求顶层entry对象列表；[inject](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/vendor/cordis/src/registry.ts#L12-L19)接受数组/对象，不保留AST核原键类型 | 可参照条目规范化，不能当同JVM typed ABI证明 |

DSH另有[凭据迁移](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/credentials/credentials-local/src/index.ts#L243-L253)
保留AST并检查scalar key的字符串类型；这是另一个能力路径，不是主插件loader的合同。
本轮未运行Pi/DSH测试或数字键实验；本地HEAD与上述固定commit一致，主线程及独立agent均已读所列核心路径。

Fibra map写法对文本ID也合理。选择唯一id/version对象列表的主要理由是既有dependency条目一致、两个值的
类型合同明确、重复ID显式拒绝；它同时避免当前Jackson丢失key scalar类型，无须第二parser或新DSL。
