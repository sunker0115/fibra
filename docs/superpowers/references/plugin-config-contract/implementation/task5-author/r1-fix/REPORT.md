# Task 5 合同独审 I1 窄修复

状态：DONE；源码冻结，Maven 写入权已释放，待同一合同审查者复审。无 Engine 政策改动、无生产类/依赖/公共 API、无 commit/push。

## 缺口与改动

R1 指出正式 JAR 仓外消费者未调用全包作者资格。唯一共享 helper 已移动到 `fibra-distribution/src/test/consumers/engine-application/src/author-qa/java`，原包名与逻辑不变（仅修正类注释，明确失败保留属于保守隔离政策）。仓外 POM 从本项目相对目录编译它；原三个内部测试 POM 指向同一源，各自保留默认测试源。无需新模块、发布 JAR 或测试依赖。

新增 `AuthorConfigurationConsumerTest` 三项测试：

1. 从本次正式仓解析的 12 个最终 JAR 组完整包，全部 disabled selection，实际提交后 target revision，整包 catalog 与 verified resource 实际读取/关闭后执行同一 helper；四 API 空映射同门通过。
2. 真实安装并停用的 schema 非法、缺 definition、额外 orphan 三种包均失败；先证实安装成功、选择停用、无执行实例及真实目录存在，再调用资格。
3. 同一 helper 的代表性 close 失败注入委托 verified resource 实际 read，宿主保留同一个 owner 并通知终止，后续 close 不重试资源或关闭 Engine；store/find 与名称查询仍可观察。全面读/关/fatal/通知矩阵继续由原内部同 helper 测试承担，没有再造矩阵。

复用原 EngineConsumerTest 的正式包列表和 JAR 组包方法。消费者 README 提供实际运行入口 `bash scripts/verify-distribution.sh`。

## 验证

- `internal.exit=0`，`internal-reports.json`：受影响内部 QA 4 个测试类共 11 tests，0 failure/error/skipped。运行 archetype、acceptance-host、spring-host 的原 Maven verify 生命周期，筛选本次受影响 QA，不机械重跑 1059 项。
- `external.exit=0`，`external-reports.json`：全仓外 consumer reactor 原 11 项 + 新 3 项 = 14 tests / 8 XML，0 failure/error/skipped。原 Java/Node lifecycle、CLI、Spring 等保留并通过。
- 仓外源码位于 `r1-fix/consumer`；`external-input-sha256.json` 锁定 24 个输入，运行前后与仓库对应源码字节一致，无仓库绝对路径。
- `resolved-fibra-jar-sha256.json`：实际测试 XML classpath 中 15 个 Fibra JAR + 12 个 staged formal JAR，27 条逐字节匹配本次正式发布仓。classpath 没有 Fibra 工作树目录；Fibra 依赖来自独立 consumer-m2 的正式 JAR，不借 reactor target/classes。
- 继续使用已锁定发布仓 `author/distribution/run/remote`，独立 consumer cache 为 `author/distribution/run/consumer-m2`；更新 file 仓 URL 指向保留后的真实路径。
- `publication-reuse.json`：受此测试 POM 迁移影响的已发布 archetype flattened POM 与主 JAR 都与原正式发布字节相同，因此未重发正式制品；12 个 formal 主 JAR 仍与原锁定仓匹配。原 ZIP/发行/84 JAR 排除 helper 的证据继续有效；本次未声称再次跑完整发行。
- `source-sha256.json`：修复后 54 个 Task5 源文件冻结回执；旧 51 文件回执保留在上级目录，用于追溯 R1 输入。
- `git diff --check` 通过。

## 精确命令

内部：

```sh
/private/tmp/fibra-config-contract-evidence/author/mvn.sh -o \
  -pl fibra-plugin-archetype,fibra-plugins/fibra-plugins-acceptance/fibra-plugins-acceptance-host,fibra-example/content-sanitizer/spring-host -am verify \
  -Dtest=AuthorConfigurationQualificationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=FormalPluginArtifactsIT,FibraPluginArchetypeIT,SanitizerAuthorConfigurationIT \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dmaven.javadoc.skip=true
```

仓外：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/bin:/private/tmp/fibra-config-contract-node22.14/node-v22.14.0-darwin-arm64/bin:/Users/sunke/.nvm/versions/node/v22.22.2/bin:$PATH \
/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn \
  --settings /private/tmp/fibra-config-contract-evidence/author/r1-fix/consumer/settings.xml \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local=/private/tmp/fibra-config-contract-evidence/author/distribution/run/consumer-m2 \
  -Dfibra.repository.url=file:///private/tmp/fibra-config-contract-evidence/author/distribution/run/remote \
  -Dfibra.version=0.5.0-SNAPSHOT -Djunit.version=6.1.3 -Dspring-boot.version=4.1.0 \
  -f /private/tmp/fibra-config-contract-evidence/author/r1-fix/consumer/pom.xml clean verify
```

仓外原 Node/进程测试仍在获准的进程探针环境运行，未修改或跳过。保持 JDK21/Node22.14.0；未新增跨平台证明或 Task6 产品消费者承诺。Nowledge 定向搜索仅返回不相关旧线程，未用作实现证据。

## R2 收据修正

原收集器按类名递归收集 XML，误把 16:52:02 的 Surefire `FormalPluginArtifactsIT` 红灯历史混入本次索引，造成此前“12 项全绿”的错误报告。本次 17:23 执行中，该类只由 Failsafe 运行 7 项；四类实际合计 1 + 7 + 2 + 1 = 11 项，全部通过。修正依据为 `internal.log` 第 687、1257、1280、1375 行、各 XML 的 plugin 路径/时间/用例数/耗时及 SHA，而非删除失败结果。

`internal-reports.json` 现仅索引本次四份 XML，`internal-summary.json` 给出本次汇总。历史红灯 XML 原路径与字节未改，单独列于 `historical-red-reports.json`，关联原 `red-jar.log` 与 exit1；错误版本的索引/报告保留于 `receipt-before-correction/`。本次仅修正证据收据与报告，未修改源码、未运行 Maven。
