# Fibra 仓库外多插件依赖验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把仓库外消费方从单插件验收重构为 provider/consumer 真实依赖链，同时保留独立 `core-app`，证明四个 Fibra 发布坐标支持第三方多插件编译、加载、启停与卸载。

**Architecture:** Provider JAR 独占 `Greeting` 契约，Consumer 通过 Maven `provided` 依赖完成编译，并通过 PF4J `Plugin-Dependencies` 在运行时从 provider ClassLoader 获得同一类型。Host 不依赖任何插件，只用 `fibra-loader-pf4j` 从目录加载两个瘦 JAR；旧单 `plugin` fixture 直接删除。

**Tech Stack:** Java 21、Maven 3.9.9、Fibra 0.1.1-SNAPSHOT、PF4J 3.13.0、Reactor 3.8.6、SLF4J 2.0.18、JUnit 6.1.3、Bash。

---

## 文件结构

创建或替换后的职责如下：

- `verification/external-consumer/provider-plugin/pom.xml`：provider 瘦 JAR、PF4J 注解处理器和 Manifest。
- `verification/external-consumer/provider-plugin/src/main/java/external/consumer/provider/api/Greeting.java`：provider ClassLoader 拥有的唯一跨插件契约。
- `verification/external-consumer/provider-plugin/src/main/java/external/consumer/provider/ExternalProviderEntrypoint.java`：注册 `Greeting` 与 provider 状态。
- `verification/external-consumer/consumer-plugin/pom.xml`：以 `provided` scope 编译依赖 provider，并声明 PF4J 制品依赖。
- `verification/external-consumer/consumer-plugin/src/main/java/external/consumer/plugin/ExternalConsumerEntrypoint.java`：类型化调用 provider 并注册字符串结果。
- `verification/external-consumer/host/src/main/java/external/consumer/host/HostApplication.java`：执行 load、start、stop、依赖重启和 unload 的公开 API 验收。
- `scripts/verify-external-consumer.sh`：构建两个插件，校验 ClassLoader 隔离、Manifest、JAR 内容和成功标记。
- `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`：静态锁定四模块 fixture、无 Fibra parent 和非 reactor 边界。
- `README.md`、`docs/release.md`、`verification/external-consumer/README.md`：同步最终验收语义。

删除：

- `verification/external-consumer/plugin/pom.xml`
- `verification/external-consumer/plugin/src/main/java/external/consumer/plugin/ExternalPluginEntrypoint.java`

### Task 1: 用失败测试锁定四模块外部工程结构

**Files:**
- Modify: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`
- Test: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`

- [ ] **Step 1: 把 fixture 模块期望改成四模块**

将测试中的模块断言和子模块循环统一改为：

```java
private static final List<String> EXTERNAL_CONSUMER_MODULES = List.of(
    "core-app",
    "provider-plugin",
    "consumer-plugin",
    "host"
);

assertEquals(EXTERNAL_CONSUMER_MODULES,
    childTexts(fixtureModules, "module"),
    "外部消费方必须分别验证内核、provider、consumer 和宿主装载");

for (var module : EXTERNAL_CONSUMER_MODULES) {
    var moduleProject = parseProject(fixtureDirectory.resolve(module).resolve("pom.xml"));
    var parent = directChild(moduleProject, "parent");
    assertNotNull(parent, module + " 缺少独立消费方 parent");
    assertEquals("external-consumer", directChildText(parent, "artifactId"),
        module + " 不得继承 Fibra parent");
}
```

- [ ] **Step 2: 运行定向测试并确认红灯原因准确**

Run:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn \
  --batch-mode --no-transfer-progress \
  -pl fibra-parity-tests -am \
  -Dtest=ReleaseArtifactBaselineTest#externalConsumerFixtureIsIndependentFromFibraReactor \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，实际模块仍为 `[core-app, plugin, host]`。

### Task 2: 重构 Provider 与 Consumer 插件制品

**Files:**
- Modify: `verification/external-consumer/pom.xml`
- Delete: `verification/external-consumer/plugin/pom.xml`
- Delete: `verification/external-consumer/plugin/src/main/java/external/consumer/plugin/ExternalPluginEntrypoint.java`
- Create: `verification/external-consumer/provider-plugin/pom.xml`
- Create: `verification/external-consumer/provider-plugin/src/main/java/external/consumer/provider/api/Greeting.java`
- Create: `verification/external-consumer/provider-plugin/src/main/java/external/consumer/provider/ExternalProviderEntrypoint.java`
- Create: `verification/external-consumer/consumer-plugin/pom.xml`
- Create: `verification/external-consumer/consumer-plugin/src/main/java/external/consumer/plugin/ExternalConsumerEntrypoint.java`
- Test: `fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java`

- [ ] **Step 1: 替换根 fixture 模块和内部依赖管理**

根 fixture POM 的模块必须精确为：

```xml
<modules>
  <module>core-app</module>
  <module>provider-plugin</module>
  <module>consumer-plugin</module>
  <module>host</module>
</modules>
```

在现有 `dependencyManagement` 中增加外部工程自己的 provider 坐标，consumer 声明依赖时不重复版本：

```xml
<dependency>
  <groupId>com.sstlfsj.verification</groupId>
  <artifactId>external-provider-plugin</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: 删除旧单插件 fixture，不保留旧 artifact 或入口名**

删除旧 `verification/external-consumer/plugin` 下两个文件。不得创建转发类、兼容目录或 `external-plugin` artifact。

- [ ] **Step 3: 创建 provider 契约**

`Greeting.java` 内容：

```java
package external.consumer.provider.api;

import com.sstlfsj.fibra.ServiceKey;

public interface Greeting {
    ServiceKey<Greeting> KEY =
        ServiceKey.of("external.consumer.provider.greeting", Greeting.class);

    String greeting();
}
```

- [ ] **Step 4: 创建 provider 入口**

`ExternalProviderEntrypoint.java` 内容：

```java
package external.consumer.provider;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import external.consumer.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalProviderEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> STATUS =
        ServiceKey.of("external.consumer.provider.status", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        context.provide(STATUS, "provider-ready");
        return Mono.just(context.root().provide(Greeting.KEY, () -> "provider-ready"));
    }
}
```

- [ ] **Step 5: 创建 provider POM**

从旧 plugin POM 的注解处理器和瘦 JAR依赖模式重建，坐标和 Manifest 必须为：

```xml
<artifactId>external-provider-plugin</artifactId>
<finalName>external-provider-plugin</finalName>
<Plugin-Id>external-provider-plugin</Plugin-Id>
<Plugin-Version>1.0.0</Plugin-Version>
<Implementation-Version>1.0.0</Implementation-Version>
```

依赖只有 `fibra-pf4j-api` 和 `org.pf4j:pf4j`，两者 scope 均为 `provided`；`maven-compiler-plugin` 的 `annotationProcessorPaths` 继续使用 `${pf4j.version}`。

- [ ] **Step 6: 创建 consumer 入口**

`ExternalConsumerEntrypoint.java` 内容：

```java
package external.consumer.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import external.consumer.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalConsumerEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        var greeting = context.get(Greeting.KEY);
        return Mono.just(context.provide(RESULT, "consumer->" + greeting.greeting()));
    }
}
```

- [ ] **Step 7: 创建 consumer POM**

Consumer 依赖必须精确包含以下三项，全部为 `provided` 且不在模块内重复版本：

```xml
<dependency>
  <groupId>com.sstlfsj.verification</groupId>
  <artifactId>external-provider-plugin</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>com.sstlfsj</groupId>
  <artifactId>fibra-pf4j-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>org.pf4j</groupId>
  <artifactId>pf4j</artifactId>
  <scope>provided</scope>
</dependency>
```

Manifest 必须为：

```xml
<Plugin-Id>external-consumer-plugin</Plugin-Id>
<Plugin-Version>1.0.0</Plugin-Version>
<Implementation-Version>1.0.0</Implementation-Version>
<Plugin-Dependencies>external-provider-plugin</Plugin-Dependencies>
```

- [ ] **Step 8: 运行结构门禁并确认转绿**

Run: Task 1 Step 2 的同一命令。

Expected: PASS，四个外部模块全部继承 `external-consumer`，根 fixture 仍无 parent 且不在 Fibra reactor。

- [ ] **Step 9: 提交结构与两个插件**

```bash
git add fibra-parity-tests/src/test/java/com/sstlfsj/fibra/parity/ReleaseArtifactBaselineTest.java \
  verification/external-consumer/pom.xml \
  verification/external-consumer/provider-plugin \
  verification/external-consumer/consumer-plugin \
  verification/external-consumer/plugin
git commit -m "test: 增加仓库外插件依赖制品"
```

### Task 3: 用 Host 验证公开生命周期组合

**Files:**
- Modify: `verification/external-consumer/host/src/main/java/external/consumer/host/HostApplication.java`

- [ ] **Step 1: 用两个服务键和两个插件 ID替换单插件断言**

Host 定义：

```java
private static final String PROVIDER_PLUGIN = "external-provider-plugin";
private static final String CONSUMER_PLUGIN = "external-consumer-plugin";
private static final ServiceKey<String> PROVIDER_STATUS =
    ServiceKey.of("external.consumer.provider.status", String.class);
private static final ServiceKey<String> CONSUMER_RESULT =
    ServiceKey.of("external.consumer.plugin.result", String.class);
```

- [ ] **Step 2: 实现 load/start/stop/restart/unload 完整断言**

`main` 中 loader 生命周期核心代码必须为：

```java
var expectedPlugins = java.util.Set.of(PROVIDER_PLUGIN, CONSUMER_PLUGIN);
if (!java.util.Set.copyOf(loader.loadPlugins()).equals(expectedPlugins)) {
    throw new IllegalStateException("unexpected plugin ids: " + loader.pluginIds());
}

loader.startPlugins();
assertActive(loader, PROVIDER_PLUGIN);
assertActive(loader, CONSUMER_PLUGIN);
assertServices(root);

loader.stopPlugin(PROVIDER_PLUGIN);
assertStopped(loader, root, expectedPlugins);

loader.startPlugin(CONSUMER_PLUGIN);
assertActive(loader, PROVIDER_PLUGIN);
assertActive(loader, CONSUMER_PLUGIN);
assertServices(root);

if (!loader.unloadPlugin(PROVIDER_PLUGIN)) {
    throw new IllegalStateException("provider unload returned false");
}
if (!loader.pluginIds().isEmpty()) {
    throw new IllegalStateException("plugins remain loaded: " + loader.pluginIds());
}
assertAbsent(loader, root);
LOGGER.info("EXTERNAL_MULTI_PLUGIN_CONSUMER_OK");
```

辅助方法必须使用 `FibraState.ACTIVE` 检查两个 Fibra；服务值分别精确为 `provider-ready` 和 `consumer->provider-ready`；停止和卸载后使用 `root.get(key, false) == null` 检查撤销。

- [ ] **Step 3: 暂不单独运行 Host**

Host 需要脚本生成隔离临时仓库和两个真实插件 JAR。此时只执行：

```bash
git diff --check
```

Expected: 无输出。完整运行留到 Task 4。

### Task 4: 扩展黑盒脚本和文档

**Files:**
- Modify: `scripts/verify-external-consumer.sh`
- Modify: `README.md`
- Modify: `docs/release.md`
- Modify: `verification/external-consumer/README.md`

- [ ] **Step 1: 把单插件路径替换为 provider/consumer 两个 JAR**

脚本制品变量必须为：

```bash
readonly provider_jar="$consumer_worktree/provider-plugin/target/external-provider-plugin.jar"
readonly consumer_jar="$consumer_worktree/consumer-plugin/target/external-consumer-plugin.jar"
readonly host_jar="$consumer_worktree/host/target/external-host-all.jar"
```

必需制品循环同时检查 `core_jar`、`provider_jar`、`consumer_jar` 和 `host_jar`。

- [ ] **Step 2: 分别生成完整 JAR 清单并检查隔离**

脚本先把三个 JAR 清单完整写入临时文件，禁止恢复 `jar tf | grep -q`：

```bash
"$jar_executable" tf "$provider_jar" > "$provider_listing"
"$jar_executable" tf "$consumer_jar" > "$consumer_listing"
"$jar_executable" tf "$host_jar" > "$host_listing"
```

断言：

- provider 包含 `ExternalProviderEntrypoint.class`、`Greeting.class` 和 `META-INF/extensions.idx`；
- consumer 包含 `ExternalConsumerEntrypoint.class` 和 `META-INF/extensions.idx`，但不包含 `Greeting.class`；
- Host 不包含两个入口或 `Greeting.class`；
- provider、consumer 清单都不匹配 `com/sstlfsj/fibra/`、`org/pf4j/`、`org/reactivestreams/`、`reactor/`、`org/slf4j/`。

- [ ] **Step 3: 分别检查两个 Manifest**

把两个 Manifest 解压到不同目录。Provider 必须包含 `Plugin-Id: external-provider-plugin` 和 `Plugin-Version: 1.0.0`；Consumer 必须包含 `Plugin-Id: external-consumer-plugin`、`Plugin-Version: 1.0.0` 和 `Plugin-Dependencies: external-provider-plugin`。

- [ ] **Step 4: 复制两个插件并检查新成功标记**

```bash
cp "$provider_jar" "$plugins_directory/external-provider-plugin.jar"
cp "$consumer_jar" "$plugins_directory/external-consumer-plugin.jar"
```

Host 运行方式不变，但成功标记改为：

```bash
grep -q 'EXTERNAL_MULTI_PLUGIN_CONSUMER_OK' "$temporary_root/host.log"
```

- [ ] **Step 5: 同步三份用户文档**

文档必须明确：

- `core-app` 是独立纯内核链；
- provider 拥有契约；consumer 同时具有 Maven `provided` 与 PF4J Manifest 依赖；
- Host POM 不依赖插件；
- 仓库外脚本验证固定版本依赖链，不重复热更新和回滚；
- 成功结论仍只针对临时发布坐标，不表示已发布到 Maven Central。

- [ ] **Step 6: 运行仓库外冷缓存黑盒验收**

Run:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
scripts/verify-external-consumer.sh
```

Expected: 输出 `EXTERNAL_CORE_CONSUMER_OK`、`EXTERNAL_MULTI_PLUGIN_CONSUMER_OK` 和 `仓库外消费验收通过：0.1.1-SNAPSHOT`。

- [ ] **Step 7: 提交 Host、脚本和文档**

```bash
git add verification/external-consumer/host \
  scripts/verify-external-consumer.sh \
  README.md docs/release.md verification/external-consumer/README.md
git commit -m "test: 验证仓库外多插件依赖链"
```

### Task 5: 全量回归和独立审查

**Files:**
- Verify only; no planned production changes.

- [ ] **Step 1: 执行全量 Maven 验收**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn \
  --batch-mode --no-transfer-progress clean verify
```

Expected: 所有模块 `BUILD SUCCESS`，Cordis 71 个逐项用例、API 基线、loader 单测、现有真实插件 IT 和发布结构门禁全部通过。

- [ ] **Step 2: 验证发布制品可复现**

```bash
export MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
scripts/verify-reproducible-release.sh
```

Expected: 退出码 0，四个正式模块的 POM、主 JAR、sources JAR 和 Javadoc JAR 逐字节一致。

- [ ] **Step 3: 再次执行冷缓存仓库外验收**

Run: Task 4 Step 6 的同一命令。

Expected: 三个成功标记全部出现，两个空本地仓库没有使用用户 Fibra 制品。

- [ ] **Step 4: 执行静态检查**

```bash
bash -n scripts/verify-external-consumer.sh
git diff --check
git status --short
```

Expected: shell 语法和 diff 检查无错误；状态只包含本计划预期文件。

- [ ] **Step 5: 请求独立代码审查**

审查重点：Host classpath 泄漏、Consumer 复制契约、Manifest 依赖错误、成功标记过早、Snapshot 来源假通过和文档夸大。Critical 与 Important 必须全部关闭后才完成。
