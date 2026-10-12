# Task 1 config 纯边界回执

2026-10-12；工作树 codex/plugin-config-contract；未 commit/push。

## 范围

新增 config 公共类型：ConfigSchema、ConfigSchemaSource、ConfigSchemaException、ConfigViolation、ConfigValidationInput、ConfigBatchFailure、DefinitionConfigDocument。
内部协作者：SchemaBudget、SchemaDocumentReader、SchemaProfile、SchemaLibrary、SchemaDiagnostics。
测试：ConfigSchemaBootstrapTest、ConfigSchemaTest、ConfigSchemaBudgetTest、ConfigSchemaBatchOrderTest、ConfigSchemaBatchAllocationTest、DefinitionConfigDocumentTest。
POM：根依赖管理增加 networknt 3.0.8 并排除其 YAML；fibra-config 引用该依赖，原 Jackson/YAML/SLF4J 版本不变。
没有修改 artifact、Engine、公共签名基线、Tela 或原配置求值代码。

## 命令与实际结果

运行环境：JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home，Maven=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn。
统一参数：-o -Dmaven.repo.local=/private/tmp/fibra-config-contract-m2 -pl fibra-config -am。

- test -Dtest=ConfigSchemaBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false：nativeExit=1，1 test / 1 failure / 0 errors；反射 assertDoesNotThrow 确证 ConfigSchemaSource 缺失；bootstrap-red.log。
- test -Dtest='ConfigSchema*Test,DefinitionConfigDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false：在最小 API 骨架上 16 test / 11 failures / 4 errors；4 个错误为测试取空诊断产生的 NoSuchElementException，行为仍未实现；all-behavior-red.log。
- 相同定向命令实现后 nativeExit=0，16 tests / 0 failures / 0 errors；implementation-2.log。
- test：质量R1修复后最终 nativeExit=0，fibra-api 2 tests，fibra-config 94 tests，全部 0 failures / 0 errors / 0 skipped；config-allocation-fix-final.log。config 内31项新测试，63项原回归。此前84项GREEN保留在config-final.log，合同R1顺序修复89项GREEN保留在config-review-fix-final.log。
- dependency:tree 指定 networknt/Jackson/SLF4J/itu/Joni/Graal 依赖：nativeExit=0；dependency-tree.log：networknt 3.0.8、itu 1.14.0、Jackson 3.1.5、SLF4J 2.0.18；无 Joni/Graal/provider，YAML 仍为 config 直接既有依赖。
- git diff --check：nativeExit=0。

## 验证边界

已执行真实库数字/类型/nullable/标量相等、object/array/string约束、allOf/anyOf/oneOf、typed map、local/remote、false-or-object、number-or-disabled、rootref、外部ref/循环/菱形、预算与batch、strict JSON/UTF-8/envelope、dormant延期、source不可变、secret路径、hostile TCCL测试。

预算是接纳成本，不是 CPU/内存/毫秒 SLA。尚未验证最终发行 JAR/ZIP、作者仓外消费、Engine 预检、builtin 迁移或真实 Node 表示；这些属于后续 Task。未使用端到端日志 provider 捕获；当前纯边界不记录原异常，测试验证返回诊断/异常 cause/suppressed 不带 canary。合同独审R1发现的顺序I-1和质量独审R1发现的整批分配I-1均已按下述回归修复，等待最新质量复审。

## 独审 R1 / I-1 修复

仅修改 ConfigSchema.batch 的错误选择：预扫描仅提前返回预算失败；非预算的 INVALID_SCHEMA/UNSUPPORTED_PROFILE 缓存在原 source 映射，预算通过后与编译/实例校验共同按 index 顺序返回。没有修改公共签名、预算公式、SchemaProfile/SchemaLibrary或规格。

新增 ConfigSchemaBatchOrderTest 共5项：前值错后schema错、前值错后profile错、前正确后schema错、后项schema/实例预算优先、库调用隔离哨兵。哨兵通过独立 URLClassLoader拦截真实SchemaRegistry加载，无生产hook；unique129、100次大array实例超总U、后项单实例预算拒绝均为0次，正常compile控制组为1次。

- 最初测试文件包名笔误造成javac失败，nativeExit=1，保留batch-order-red.log；不算行为RED。
- 修正测试包名后、修改生产前：`test -Dtest=ConfigSchemaBatchOrderTest -Dsurefire.failIfNoSpecifiedTests=false`，nativeExit=1，5 tests / 3 assertion failures / 0 errors / 0 skipped；batch-order-red-2.log。
- 最小生产修复后同命令：nativeExit=0，5 tests全部通过；batch-order-green.log。
- 原config reactor全回归：上述最终89+2 tests全部通过；config-review-fix-final.log；git diff --check通过。

修复后停止编辑，等待主线程独立复审。未commit/push。

## 质量独审 R1 / I-1 修复

仅修改 ConfigSchema 内部batch接纳，以及新增 ConfigSchemaBatchAllocationTest。移除List.copyOf/等长source投影/按失败次数累积的集合。每次调用私有Batch仅保存最多128个unique source及其首index、受总U限制且位于首个语义失败之前的immutable input前缀、一个安全的首语义失败。遇确定预算拒绝立即短路；语义失败后仍做必要预算扫描，但不保存后项effective引用。全部可接纳后仅执行捕获记录，不再次读caller List。没有新增size/profile限额，没有改U公式、库执行、公开API、POM或其它模块，没有吞JVM Error。

- RED：`test -Dtest=ConfigSchemaBatchAllocationTest -Dsurefire.failIfNoSpecifiedTests=false`，nativeExit=1，5 tests / 5 assertion failures / 0 errors / 0 skipped；batch-allocation-red.log，失败由整批toArray哨兵证实。
- GREEN：`test -Dtest='ConfigSchemaBatchAllocationTest,ConfigSchemaBatchOrderTest' -Dsurefire.failIfNoSpecifiedTests=false`，nativeExit=0，10 tests全部通过；batch-allocation-green.log。保留前值错/后schema错顺序以及库调用哨兵。
- 自定义惰性List证明：千万项首值/首schema预算拒绝只读1项；首语义错后第2项预算拒绝只读3项；正常接纳不调用toArray、不二次读取；null集合/已遍历null元素仍同步抛NPE。
- 原独立BatchCopyProbe重新运行：`java -Xmx32m -cp <本次Maven target/classes优先，原probe及release依赖随后> BatchCopyProbe 1/10000000`，两者nativeExit=0，均返回index0/BUDGET_EXCEEDED，无stderr；完整argv/stdout/stderr在batch-allocation-small-heap-green.json。优先当前target/classes避免probe目录内旧生产class遮蔽修复。
- 完整config reactor：nativeExit=0，config94 + api2 tests全部通过；config-allocation-fix-final.log。git diff --check通过。

32MiB仅为原OOM反例复现实验，不是新宿主最低内存或SLA承诺。修复后停止编辑，等待复审；未commit/push。
