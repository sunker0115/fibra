# Fibra 示例与分发验收实施计划

**Goal:** 在不减少任何现有示例与黑盒断言的前提下，分离 archetype、仓内示例和仓库外分发验收，并补齐 Spring Boot starter 与已发布 archetype 的仓外消费门禁。

**Architecture:** `fibra-plugin-archetype` 保持唯一用户模板；`fibra-example` 按 Engine 和 Spring Boot 两个 application 场景分组；`verification/distribution` 作为不进 reactor 的独立 Maven 工程，从临时远端仓库消费十个 `artifact`。所有旧 `host` 模块名改为 `application`，不提供兼容路径。

**Tech Stack:** Java 21、Maven reactor、Maven Archetype Plugin 3.4.1、Spring Boot 4.1.0、JUnit 5、Failsafe、Bash、Fibra Engine。

---

### Task 1: 冻结结构与失败基线

**Files:**
- Modify: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`
- Test: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`

- [x] 把 example 非发布模块基线改为七个新 artifactId 和新目录，并把 distribution 基线改为六模块、新 parent artifactId、无 reactor module。
- [x] 增加断言：根目录不存在旧 `verification/external-consumer`，根脚本不存在旧 `verify-external-consumer.sh`，新 `verification/distribution` 与 `verify-distribution.sh` 存在。
- [x] 运行 `mvn -pl fibra-parity-tests -am -DskipITs test`，确认测试因新目录尚未建立而按预期失败。

### Task 2: 重构 Engine example

**Files:**
- Move: `fibra-example/fibra-example-contract-plugin` → `fibra-example/engine/contract-plugin`
- Move: `fibra-example/fibra-example-provider-plugin` → `fibra-example/engine/provider-plugin`
- Move: `fibra-example/fibra-example-consumer-plugin` → `fibra-example/engine/consumer-plugin`
- Move: `fibra-example/fibra-example-host` → `fibra-example/engine/application`
- Modify: `fibra-example/pom.xml`
- Modify: `pom.xml`
- Modify: all moved POMs, plugin descriptors, assemblies, Java packages, application class and IT
- Create: `fibra-example/README.md`

- [x] 移动四个目录；只提交受 Git 控制的源码，`.flattened-pom.xml`、`.iml` 和 `target` 等构建或 IDE 残留继续保持忽略。
- [x] 将 artifactId、plugin id、版本 property、复制路径、系统属性和类名统一为 `fibra-example-engine-*` 与 `application`。
- [x] 保持 contract/provider/consumer v1/v2、broken v3、独立进程、升级/降级/回滚及 classpath 隔离断言逐项不变。
- [x] 运行 `mvn -pl fibra-example/engine/application -am verify`，确认全部 Engine example 集成测试通过。

### Task 3: 重构 Spring Boot example

**Files:**
- Move: `fibra-example/fibra-example-spring-host-api` → `fibra-example/spring-boot/application-api`
- Move: `fibra-example/fibra-example-spring-host-plugin` → `fibra-example/spring-boot/provider-plugin`
- Move: `fibra-example/fibra-example-spring-host` → `fibra-example/spring-boot/application`
- Modify: all moved POMs, plugin descriptor, Java packages, application class, controllers, configuration, README and IT

- [x] 移动三个目录；只提交受 Git 控制的源码，`.flattened-pom.xml`、`.iml` 和 `target` 等构建或 IDE 残留继续保持忽略。
- [x] 将 artifactId、plugin id、Java package/class、系统属性和 deployment id 统一为 `fibra-example-spring-boot-*` 与 `application`。
- [x] 保持 starter-only Fibra 依赖、上传暂存、apply、服务调用、状态查询和真实插件 ZIP 断言逐项不变。
- [x] 运行 `mvn -pl fibra-example/spring-boot/application -am verify`，确认 Spring Boot example 集成测试通过。

### Task 4: 重命名 distribution fixture 并保持原验收

**Files:**
- Move: `verification/external-consumer` → `verification/distribution`
- Move: `core-app` → `core-application`
- Move: `host` → `engine-application`
- Move: `scripts/verify-external-consumer.sh` → `scripts/verify-distribution.sh`
- Modify: distribution POMs, Java packages/classes, README, script and success标记

- [x] 先修改 parity 基线并运行失败测试，证明旧 fixture 不能满足新契约。
- [x] 完成目录与命名迁移，保持临时远端、空本地仓库、字节比对、包结构、依赖范围、隔离、版本冲突、配置恢复和联合升级断言不变。
- [x] 运行 `scripts/verify-distribution.sh`，确认原有 core 与 Engine 黑盒路径通过后再增加新能力。

### Task 5: 增加 Spring Boot 仓外消费

**Files:**
- Create: `verification/distribution/spring-boot-application/pom.xml`
- Create: `verification/distribution/spring-boot-application/src/main/java/verification/distribution/springboot/DistributionSpringBootApplication.java`
- Create: `verification/distribution/spring-boot-application/src/main/resources/application.yml`
- Modify: `verification/distribution/pom.xml`
- Modify: `scripts/verify-distribution.sh`
- Modify: parity release baseline test

- [x] 在基线测试中先要求新模块只直接依赖 starter 和 Spring Boot 启动依赖，运行并确认因模块缺失失败。
- [x] 创建非 Web Spring Boot application，从容器取得 `FibraEngine`、root `Context` 和 `FibraServiceBridge`，检查运行状态并输出 `FIBRA_DISTRIBUTION_SPRING_BOOT_OK`。
- [x] 脚本从隔离本地仓库构建并启动该 application，注入临时 installed root/config location，禁止两个 watcher。
- [x] 检查 starter、autoconfigure 和 spring 三个 `artifact` 的 Maven 来源记录及远端字节一致性。

### Task 6: 增加已发布 archetype 仓外消费

**Files:**
- Modify: `scripts/verify-distribution.sh`
- Modify: `verification/distribution/README.md`

- [x] 从临时远端仓库调用 `com.sstlfsj:fibra-plugin-archetype:${revision}`，生成到临时目录。
- [x] 使用隔离本地仓库和临时远端地址执行生成项目 `mvn verify`，不得读取 Fibra reactor。
- [x] 检查生成 POM 无 parent、`${revision}`、`target/classes`、`systemPath` 和仓库绝对路径，并检查 contract、plugin、deployment 三份 ZIP。
- [x] 输出 `FIBRA_DISTRIBUTION_ARCHETYPE_OK`，不复制 archetype 内部 Engine 断言。

### Task 7: 统一文档与发布基线

**Files:**
- Modify: `README.md`
- Modify: `docs/release.md`
- Modify: `docs/api/README.md`
- Modify: `docs/superpowers/specs/2026-08-24-fibra-engine-architecture.md`
- Modify: `docs/superpowers/specs/2026-08-22-fibra-external-multi-plugin-verification-design.md`
- Modify: `docs/superpowers/specs/2026-08-23-fibra-plugin-package-transaction-design.md`
- Modify: plans/specs containing current old paths

- [x] 删除“external-consumer 是用户模板”和固定旧五模块等过时内容。
- [x] 当前架构文档统一指向专题设计，历史实施计划中的命令更新到新脚本名，避免可执行死引用。
- [x] 全仓 `rg` 确认不存在旧目录、脚本、模块、Java package/class 和系统属性引用；历史提交哈希及明确标注的历史文本不做伪造修改。

### Task 8: 全量验证与提交

**Files:**
- Verify only

- [x] 运行 `mvn clean verify`，确认完整 reactor 与全部 example/parity 测试通过。
- [x] 运行 `scripts/verify-reproducible-release.sh`，确认十个发布 `artifact` 逐字节可复现。
- [x] 运行 `scripts/verify-distribution.sh`，确认 core、plugin graph、Engine、Spring Boot 和 archetype 五类仓外门禁通过。
- [x] 检查 `git diff --check`、`git status` 和变更清单，确认没有构建残留、IDE 文件或无关修改。
- [x] 提交最终实现，提交信息明确描述 example/application 命名和 distribution verification 增强。
