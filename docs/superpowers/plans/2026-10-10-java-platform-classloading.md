# Java 平台类加载修复计划

**目标：** JDK 21 下真实 PluginClassLoader 可解析 SAX/DOM 等平台类型，同时维持宿主 API identity、插件私有隔离及声明依赖顺序。

**架构：** Java runtime 独占解析规则；已加载 → platform 实际命中 → 共享前缀 host parent 实际命中 → local → requires。加载和重复类预检复用一个内部解析方法；不增加依赖、公共 SPI、缓存或生命周期 owner。

**技术栈：** Java 21、Maven 3.9.9、JUnit 5、真实编译/JAR fixture。

**授权与边界：** 已授权在 Fibra 修复并独立审查，随后授权创建 `codex/java-platform-classloading` 分支并本地提交；未授权 push、安装或发布。不改 Tela。MyBatis/MP 仅作仓外验证 payload，不引入生产依赖。完整 Host/数据库采用验收不由初始化探针替代。

## 基线与有界模式分析

- HEAD `90fcf8985ef4553297128ae8210a89b36016f158`，初始工作树干净，无仓内 AGENTS.md。
- `PluginClassLoader.parentClass` 同时被 loadClass 与 isParentDefined 调用；JavaClassIndex 使用后者排除宿主类型。故障是平台可见性错误依赖包名前缀。
- 按既有 synchronized class lock、CNFE 回退、JavaRuntimeException(LOAD) 包装、try-with-resources 和动态 javac fixture 风格实现。LinkageError 不代表缺类，必须传播。
- 直接采用 JDK 平台 loader 能力；最小改造现有内部方法。拒绝扩展 org.* 白名单、任意 application 兜底、自建平台包索引及第三方加载框架：这些会扩大可见性或增加第二事实源。只有现有 owner 无法表达新合同才重开选型。
- 官方依据：[JDK 21 ClassLoader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)，平台 loader 在模块升级/覆盖时可能委派其他具名模块，不保证任意启动配置下只看到 JDK 类型。
- 权威合同在 `docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md` §6.2；现有最终架构关闭计划的提交/推送授权属于历史任务，不沿用。
- Nowledge 定向搜索本次连接被拒绝，不作为事实依据。

## 执行与验收

- [x] 核实源码、工作树、权威合同、JDK 参照与测试惯例。
- [x] 在 PluginClassLoaderTest 添加平台类型 identity/重复查找、parent miss、本地隔离/依赖顺序、宿主私库不可见和 LinkageError 用例；新增 JavaClassIndexTest 验证平台/宿主实际命中与重复类判定一致。
- [x] 运行 RED：`mvn -o -pl fibra-runtime-java -am test -Dtest=PluginClassLoaderTest,JavaClassIndexTest -Dsurefire.failIfNoSpecifiedTests=false`，确认平台解析和索引预检失败原因。
- [x] 最小修复共享内部解析方法，同步 JavaClassIndex 命名和诊断；相同命令取得 GREEN。
- [x] 运行 runtime-java 及依赖完整回归，保留加载/替换/关闭证据；审计资源/SPI/TCCL 消费者。
- [x] 构建 runtime-java JAR，在仓外或 target 内真实 loader 执行 MyBatis/MP 初始化，记录 payload/修复制品 SHA256 与复验命令。
- [x] 同步 §6.2 与本计划；独立审查实际 diff，关闭有效问题。

**停止条件：** 同一问题两次失败先停止试错并说明证据；公共合同扩张、生命周期 owner 变更、依赖新增或外部安装/发布需另行明确授权。

## 验证记录

- 定向 RED：12 项中 1 failure、1 error（SAX 重复类、Object CNFE）；原真实 payload 的两个框架均复现 EntityResolver CNFE。修复后同组 12 项全通过。
- `mvn -o -pl fibra-runtime-java -am package`：8 模块成功，420 项全通过，其中 Java runtime 29 项。
- `mvn -o -pl fibra-parity-tests -am test -Dtest=JavaPublishedViewRetentionTest,RuntimeHostVerticalVerificationTest,ApiSignatureBaselineTest -Dsurefire.failIfNoSpecifiedTests=false`：7 项全通过，含 50 次替换、回收对照和真实 Host。
- 修复 binary 跑原 payload 的 MyBatis/MP 均 exit 0；仓内独立探针在原宿主与插件 TCCL 条件各初始化两框架，4 次成功。无生产依赖新增。
- 资源/SPI/TCCL 只读审计未发现需要本次修复扩大范围的证据；资源规则保持。独立源码与最终交付证据审查均无阻塞项；reviewer 独立重算制品/依赖/payload 哈希，核对 sources 内容与回归日志，均一致。
- 原始日志在 `fibra-runtime-java/target/platform-verification/`；制品哈希、完整复验命令及范围见 [交付证据](../references/2026-10-10-java-platform-classloading-verification.md)。
- 未运行全仓 clean verify/发行门禁；未验证 SQL/驱动、完整 Tela Host 或任意模块升级启动配置。用户已授权在修复分支本地提交，未安装、发布或推送。
