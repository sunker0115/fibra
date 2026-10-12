# Task2 固定配置资源独立合同审查 R1

结论：PASS；未发现需提交的 B / I / M 级问题。本结论只关闭本次 Task2 合同审查，不代替后续质量审查、Task4 Engine 资源登记/兄弟清理测试或 Task7 签名基线核验。

审查日期：2026-10-12。分支 codex/plugin-config-contract；输入 baseline 0a674176b8b762b4e77044bcba7f051bf06c41d9。只读核对仓库源代码；未修改生产/测试/设计文件，未 commit/push、未编辑 Tela。仅 Maven 生成 target 与 /private/tmp 下审查证据。

## 输入绑定

实现者 source-sha256.json 自身 SHA-256 已实算确认：2c10b97defb8942e24068a71fafaf87e01f7a07ac80641eeb7dd54da6c119c79，其 10 个文件与本次真实源码完全匹配。

本次额外绑定完整 fibra-artifact/src Java 文件、root/artifact/api POM、权威规格、resource-protocol-review、implementation-plan，共 31 文件，见 spec-review-r1-input-sha256.json；清单 SHA-256：38755ac568942fe5e8e7b3a1783c7cf4819ead507f1573b490fdc0d37efebf34。测试及探针后再次逐文件核对，无漂移。当前生产 class 摘要另见 spec-review-r1-class-sha256.json。

## 合同核对

1. PluginPackage.digest(root,true) 在原根摘要的同一 updateFile 流与 chunk 上派生 raw SHA-256/length；固定名目录记录 NONREGULAR、未出现记录 ABSENT。原前缀、条目排序、类型/frame/长度及字节输入保持不变；facet 使用 capture=false。Snapshot.sameContent 显式纳入派生证据。未增加资源文件打开次数或持久字段。
2. Store.record 为私有集中注入位置；package-private verified 接收派生事实。readStoredRecord 仍重新读完整包并核 pluginId/version/revision 后构造；save 的 state/time 变化沿 toBuilder 保留事实。手造 public builder 无注入入口。VerifiedConfiguration.matches 在最终 build 后比较规范化 root、pluginId/version/revision 和完整 ManagedPluginPackage.equals；后者包含全部 ManagedFacet 与 PluginFacet 值。metadata equals/hashCode 不纳入事实。
3. configurationResource 只分配句柄，既不查文件也不补核验；未绑定 record 立即 IllegalStateException。ABSENT/NONREGULAR 在实际 read 不调用 ResourceIo；原 REGULAR 丢失或变更沿固定安全 ArtifactException。NOFOLLOW_LINKS 检查 exact root 与文件，并用于真正 open。真实流最多读取 1 MiB + 1 个超限探测字节，成功只返回至多 1 MiB，返回前核 raw hash/length，防御复制。
4. read/close 使用同一 synchronized 边界；input = io.open(path) 在所有后续分配/摘要/读取前执行，read 不主动关闭。readAttempted/readFailure/bytes 与 closeAttempted/closeFailed 各记录真实终态。close 失败不清 input、不重试物理关闭；成功清 input，已完成的安全读终态继续回放，close-before-read 拒绝后读。没有新 Reactor/config 依赖。
5. 原异常只在 catch 栈内存在；代码不访问 foreign message/cause/toString，不缓存原异常。错误分类每次生成新安全异常，null path/cause，无 suppressed 继承。非致命 Error 转安全失败；VirtualMachineError/ThreadDeath 先登记对应安全终态再原样重抛。现有测试使用 InternalError/ThreadDeath，未制造真实 OOM。
6. 新公开 API 与规格 §6 一致：PluginPackageRecord.configurationResource，以及 PluginConfigurationResource.read/close；其他新增类型/注入入口均非 public。未发现额外公开状态、路径参数、错误分类或 builder setter。

未把全目录原子快照、恶意 ABA、Engine 兄弟清理纳入已证明范围；这与权威规格明确边界一致。

## 独立验证及防假绿证据

使用 mvn-env 技能核对 root POM release=21，JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home。实际命令及起止时间/native exit 见 spec-review-r1-command.json：

```text
/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn -o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-artifact -am test -Dtest=PluginPackageTest,PluginPackageStoreTest,PluginConfigurationResourceTest,FixedPluginConfigurationResourceTest,PluginConfigurationResourceBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false
```

native exit=0，88 tests，0 failure/error/skip。日志 spec-review-r1-maven.log 第44–52行明确重新编译 20 个生产文件和 5 个测试文件，覆盖实现者最后的注解/Javadoc 改动；不是沿用先前测试结果。

spec-review-r1-classpath-evidence.json 从本次五份 surefire XML 提取 classpath 与测试结果：生产使用当前 fibra-artifact/target/classes，测试使用当前 target/test-classes；classpath 不含 com/sstlfsj 缓存 JAR，因此无旧 Fibra SNAPSHOT 替代当前实现。探针额外打印生产 CodeSource，确认同一当前 classes 目录。

在 /private/tmp 独立编写 SpecReviewProbe.java（不改项目测试），SHA-256 cd1ee836c5b2cbdb99f5b8995eb33aedcd6275a92c44f91030bee402d003870d；javac/java 实际完整命令、各 exit=0 和输出见 spec-review-r1-probe-results.json。四组反例尝试全部通过：

- evidence 原长度3但实际注入持续增长流：只读取1,048,577字节即安全失败，read 未提前关闭，调用 close 才关闭一次。
- IOException 的 getMessage/getCause/toString 均在调用时抛 AssertionError：读取仍安全失败；对首次公开异常添加 secret suppressed 后，重复 read / close 后 read 不携带污染。
- 成功 read 后 stream.close 抛出同一恶意 IOException：关闭失败仅物理关闭一次，重复 read 仍返回原防御性成功字节。
- exact root 本身替换为 symlink，即使目标配置字节正确仍安全失败。

现有测试的1MiB+1 fixture验证的是已知超限事实提前拒绝，不单独证明真实读取流上限；因此上述增长流探针补上本次审查证据，而未凭测试名称推断其覆盖。

git diff --check native exit=0，见 spec-review-r1-diff-check.json。

## 检索与执行交接

已按隔离子 agent 路由执行一次 Nowledge thread_search(query="fibra plugin config contract artifact resource", limit=3)。返回旧 Fibra 泛匹配，没有当前合同的直接决策证据；未据此推断任何合同，也未展开历史线程。审查依据为当前冻结规格与真实源码。

本审查发起的 Maven、javac、java 进程均已退出；Maven exec session 37861 已返回最终 exit=0，没有待轮询或后台验证进程。本审查停止全部 Maven/验证执行，下一质量审查可以接管 Maven 写权限。尝试以 ps 全局交叉核验被当前 sandbox 以 operation not permitted 拒绝，因此不声称看见并确认其他会话的全局进程状态；自身进程终态有上述实际退出证据。
