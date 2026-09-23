# Fibra 最终发行 ZIP 正式发布实施计划

> **执行约束：** 按测试先行逐项实施；本计划不使用 OpenSpec。

**目标：** 将现有经过仓外与可复现验证的 `fibra-<version>-bin.zip` 作为 `com.sstlfsj:fibra-distribution:<version>:bin@zip` 正式发布，使 Tela 只能从最终发行制品装配标准 fs/shell package。

**架构：** 不新增第二种 package 或 catalog。`fibra-distribution` 继续生成相同 ZIP，只把它作为 Maven `bin` 分类附件随 POM 一起安装、部署、签名和发布；BOM 管理该精确 `type + classifier` 坐标。现有 ZIP 内容、运行时 API、插件 manifest 和生命周期不变。

**技术栈：** Java 21、Maven 3.9.9、Maven Assembly、Central Portal、JUnit 6、Bash。

---

### 任务 1：冻结公开发行坐标

**文件：**
- 修改：`fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ArchitectureBaselineTest.java`
- 修改：`fibra-distribution/pom.xml`
- 修改：`fibra-bom/pom.xml`

- [x] 先修改 `bomManagesExactlyThePublishedFibraArtifacts`，要求所有 `maven.deploy.skip=false` 模块都由 BOM 管理；`fibra-distribution` 的唯一合法形状是 `type=zip`、`classifier=bin`，其它制品不声明 type/classifier。
- [x] 运行 `ArchitectureBaselineTest`，确认因 distribution 尚未发布且 BOM 尚未管理而失败。
- [x] 在 `fibra-distribution/pom.xml` 显式设置 `maven.deploy.skip=false`，让 assembly 以 `bin` classifier 附着同一个最终 ZIP；不改变归档内容与文件名。
- [x] 在 `fibra-bom/pom.xml` 增加 `fibra-distribution` 的 `zip/bin` dependencyManagement 条目。
- [x] 重跑 `ArchitectureBaselineTest`，确认通过。

### 任务 2：让发布门禁识别并核对 ZIP

**文件：**
- 修改：`scripts/release-maven-modules.sh`
- 修改：`scripts/verify-reproducible-release.sh`
- 修改：`scripts/verify-distribution.sh`

- [x] 将正式 Maven 发布模块数量从 29 调整为 30；`fibra-distribution` 只增加 POM 与 `bin.zip`，不要求主 JAR、sources 或 Javadoc。
- [x] 消除 `verify-reproducible-release.sh` 中显式追加 distribution 造成的重复 reactor module，并继续逐字节比较最终 ZIP 与完整目录 manifest。
- [x] 在 `verify-distribution.sh` 的隔离临时仓检查中要求且只允许一个 `fibra-distribution-*-bin.zip`，兼容 SNAPSHOT 的远端时间戳文件名，并与本轮重新构建的最终 ZIP 做字节比较。
- [x] 从隔离消费者仓库按 `com.sstlfsj:fibra-distribution:<version>:zip:bin` 解析制品，证明消费者无需 Fibra reactor 或源码目录。

### 任务 3：同步权威发行文档

**文件：**
- 修改：`docs/release.md`
- 修改：`docs/superpowers/specs/2026-09-07-fibra-vnext-architecture.md`

- [x] 把正式发布集合更新为 30 个 Maven 模块，明确 28 个库 JAR、BOM POM 和 distribution POM + `bin.zip` 的不同附件形状。
- [x] 记录 ZIP 是标准 package、目标平台运行件和 CLI 的唯一最终发行形态；上层产品必须消费该制品，不得从插件 JAR 重建 manifest。
- [x] 保留现有“package 是安装原子单位”和“仓外验证”语义，不改写历史账本。

### 任务 4：最终验证与提交

**文件：**
- 验证：上述全部文件

- [x] 运行定向 `ArchitectureBaselineTest`。
- [x] 运行 `mvn -pl fibra-distribution -am clean verify`，确认最终 ZIP 内容与现有仓外归档测试不退化。
- [x] 运行 `scripts/verify-reproducible-release.sh`，确认三轮 ZIP 与目录树字节一致。
- [x] 运行 `scripts/verify-distribution.sh`，确认隔离临时仓可以解析分类 ZIP并完成既有仓外调用。
- [x] 运行 `git diff --check`、检查实际 diff，并独立审查发行边界、脚本计数和文档一致性。
- 提交本阶段但不推送；Tela 后续只以该提交生成的最终 ZIP 坐标作为上游输入。
