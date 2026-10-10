# Java 平台类加载修复与 Tela 复验交付

## 事实与范围

2026-10-10，在 HEAD `90fcf8985ef4553297128ae8210a89b36016f158` 的干净工作树上复现：真实
`PluginClassLoader` 加载 MyBatis 3.5.19 / MyBatis-Plus core、annotation 3.5.16 时，Configuration
构造因 `org/xml/sax/EntityResolver` 不可见而失败。原因是平台可见性依赖宿主共享前缀；不是 JDK 21
不支持框架。修复在同一内部方法中优先调用平台 loader，加载与重复类预检共同使用该规则。
权威合同见 [vNext §6.2](../specs/2026-09-07-fibra-vnext-architecture.md#62-java-runtime)。

本次证据使用 Zulu 21.0.2+13-LTS、Maven 3.9.9；Host 垂直测试的 Node 为本机 v22.22.2。
本轮制品在提交前的工作树中构建，未 install、发布或 push；未修改 Tela。第三方 JAR 仅进入探针插件
classpath，未改 Fibra POM 或生产依赖。特殊模块升级/覆盖配置尚未实测；平台 loader 的这类可见性
边界依据 [JDK 21 官方合同](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html)。

## RED → GREEN 与回归

| 验证 | 结果 | 边界 |
| --- | --- | --- |
| 新增平台解析/索引用例 RED | 12 项中 1 failure、1 error：SAX 被误判本地重复；无共享前缀时 Object CNFE | 修复前运行，其他 10 项通过 |
| 同一测试集 GREEN | 12 项全通过 | SAX/DOM/代表平台 identity、重复加载、宿主 identity、parent miss、私库隔离、依赖顺序、宿主私库不可见、LinkageError |
| `-pl fibra-runtime-java -am package` | 8 模块构建成功，420 项全通过，其中 runtime-java 29 项 | 含 prepare、失败恢复、lease 共享和关闭；不是全仓 clean verify |
| parity 定向回归 | 7 项全通过 | API 基线 1、真实 Host 垂直链路 1、Java 替换/回收 5（含 50 次替换和活动对照） |
| Tela 原失败 payload | 两框架修复前均 exit 1，修复后均 exit 0，均由 PluginClassLoader 定义 | 相同 payload；runner 设置插件 TCCL |
| 独立框架探针 | 宿主 TCCL、插件 TCCL 各初始化两框架，共 4 次成功 | 框架不在 host classpath；断言平台 identity 和框架 loader identity |

探针出现 SLF4J 无 provider 的 NOP 提示，与初始化失败无关。回归日志含测试主动注入的异常；结果以
Surefire 的 failure/error 计数为准。本次没有执行全仓发行/可复现构建门禁，也不宣称数据库操作、
完整 Tela Host 生命周期或 Tela 采用许可通过。

资源消费者审计：`LocalProcessUnit` / `SystemdScopeLauncher` 使用所属类加载资源，发行 fixture
使用 `lifecycle-variant.txt`；资源的 local → dependencies → parent 与去重枚举保持现有行为。
当前生产代码没有 `ServiceLoader.load` 或显式 TCCL 切换入口，没有证据支持新增这类 owner。
历史账本的 SPI 验证描述不作为本轮验收依据。第三方实际 SPI、SQL、驱动与完整应用入口需另验。

本地原始日志和 manifest 位于 `fibra-runtime-java/target/platform-verification/`（构建目录，不入 Git）：
`red.log`、`green.log`、`package.log`、`parity.log`、`framework-{red,green}-{false,true}.log`、
`framework-tccl.log`、`manifest.json`。

## 交付制品与指纹

| 文件/制品 | SHA256 |
| --- | --- |
| `fibra-runtime-java/target/fibra-runtime-java-0.5.0-SNAPSHOT.jar` | `87413d99f9b97b05e0ee9eb1c681e006b5bc03c37d41f48389459bbb3f6cf847` |
| 同目录 `fibra-runtime-java-0.5.0-SNAPSHOT-sources.jar` | `2bb407aa5976f78e6b276eac7e1ba0b06b956c7c7f2b2400f0d93d57320308ed` |
| Tela 原失败 `candidate-plugin.jar` | `3b53606b50e848c19b9f2035111e01a33adf65abecc8f7e8ed533ac240d08e90` |
| MyBatis 3.5.19 | `93eea616ae355751bd5fbabb57f0732713fbe79f3196f33c51a0aeeb4255862a` |
| MP core 3.5.16 | `add88c06bb615b27850fd874c20d57f6740df2c43ed9679fc7c38675ffdb4fcf` |
| MP annotation 3.5.16 | `c6ddb7df1bd75440ba31711481c3fed82974233daad0fe41558d8a7a04be3e5e` |
| SLF4J API 2.0.18（探针宿主） | `44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55` |

这些哈希只对应本轮已验证制品；重建或后续修改后需重新核对，不能拿 SNAPSHOT 坐标代替字节身份。

## 复验命令

以下从 Fibra 根目录运行，不覆盖 `.m2` 已安装的 Fibra。第三方固定 JAR 必须已在本地仓库中。

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
"$MVN" -o -pl fibra-runtime-java -am package
"$MVN" -o -pl fibra-parity-tests -am test \
  -Dtest=JavaPublishedViewRetentionTest,RuntimeHostVerticalVerificationTest,ApiSignatureBaselineTest \
  -Dsurefire.failIfNoSpecifiedTests=false

probe_runtime="$PWD/fibra-runtime-java/target/fibra-runtime-java-0.5.0-SNAPSHOT.jar"
probe_classes="$PWD/fibra-runtime-java/target/platform-probe"
probe_repo="$HOME/.m2/repository"
mkdir -p "$probe_classes"
"$JAVA_HOME/bin/javac" --release 21 -cp "$probe_runtime" -d "$probe_classes" \
  docs/superpowers/references/java-platform-probe/FrameworkInitializationProbe.java
"$JAVA_HOME/bin/java" -Xmx192m \
  -cp "$probe_classes:$probe_runtime:$probe_repo/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar" \
  com.sstlfsj.fibra.runtime.java.FrameworkInitializationProbe \
  "$probe_repo/org/mybatis/mybatis/3.5.19/mybatis-3.5.19.jar" \
  "$probe_repo/com/baomidou/mybatis-plus-core/3.5.16/mybatis-plus-core-3.5.16.jar" \
  "$probe_repo/com/baomidou/mybatis-plus-annotation/3.5.16/mybatis-plus-annotation-3.5.16.jar"
shasum -a 256 "$probe_runtime"
```

预期 exit 0、四行 `INITIALIZED`。独立探针使用真实 package-private loader 测试接缝，只证明初始化
前提，不提供新的产品入口。该源码保存在仓内，不依赖 Tela 临时 payload 的长期存活。

本轮相同 payload 对照还使用了下面命令。临时目录若被清理，使用上面的仓内探针重建初始化证据：

```sh
probe_root=/private/var/folders/81/xzdg_qfn3rd_rmb5_nh6v5680000gn/T/tela-lifecycle-qualification-23un4wq7
for mode in false true; do
  "$JAVA_HOME/bin/java" -Xmx192m \
    -cp "$probe_root/host-classes:$probe_runtime:$probe_repo/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar" \
    com.sstlfsj.fibra.runtime.java.LifecycleHost "$probe_root/candidate-plugin.jar" "$mode"
done
```

## 独立审查

只读独立 reviewer 核对了实际 diff、异常传播、JavaClassSpace 的 LOAD 包装、失败关闭链路和测试报告，
首轮未发现阻塞项。非阻塞建议为补充 `javax.*` 非平台宿主副本的专门用例；现有宿主共享命中测试已
覆盖同一解析路径，默认 `javax.*` 前缀未改。最终证据复核亦无阻塞项：reviewer 独立重算了制品、
第三方依赖及原 payload 的 SHA256，核对 sources JAR 与当前源码相同，并确认 420/7 项测试汇总及
真实框架 RED/GREEN、四次 TCCL 初始化日志与文档一致。
