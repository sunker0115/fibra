# fibra-spring-boot-starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Fibra 的可选 Spring Boot 适配模块 `fibra-spring-boot-starter`，让 Spring Boot 项目 drop-in 使用 Fibra 动态插件运行时，内核 `fibra-core`/`fibra-api` 保持零 Spring。

**Architecture:** 模块进 Fibra reactor 并继承父 POM，但 Spring 版本由模块自管（自 import `spring-boot-dependencies` BOM，父 POM 保持 Spring-free），Reactor 覆盖对齐 3.8.6。模块提供 `@AutoConfiguration` 装配 root `Context` + `FibraPluginLoader` + `FibraConfigLoader`，`SmartLifecycle` 协调启动/就绪门禁/有序关闭，`FibraServiceBridge` 把宿主 Spring 单例经 `ServiceKey` 暴露给插件。

**Tech Stack:** Java 21、Spring Boot 4.1.0（Spring Framework 7）、Reactor 3.8.6、fibra-loader-pf4j、fibra-loader-config、JUnit（随 spring-boot-starter-test）。

**Authoritative spec:** `docs/superpowers/specs/2026-08-23-fibra-spring-boot-starter-design.md`

---

## File Structure

- `fibra-spring-boot-starter/pom.xml` — reactor 模块，继承父 POM；模块自管 Spring BOM + reactor 覆盖
- `src/main/java/com/sstlfsj/fibra/spring/FibraProperties.java` — `@ConfigurationProperties("fibra")`
- `src/main/java/com/sstlfsj/fibra/spring/FibraServiceBridge.java` — 宿主 bean → `ServiceKey` 桥接机制
- `src/main/java/com/sstlfsj/fibra/spring/FibraLifecycle.java` — `SmartLifecycle`：load + reconcile + readiness 门禁 + 有序关闭
- `src/main/java/com/sstlfsj/fibra/spring/FibraAutoConfiguration.java` — `@AutoConfiguration` 装配全部 bean
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — 自动配置登记
- `src/test/java/com/sstlfsj/fibra/spring/FibraPropertiesTest.java`
- `src/test/java/com/sstlfsj/fibra/spring/FibraServiceBridgeTest.java`
- `src/test/java/com/sstlfsj/fibra/spring/FibraLifecycleTest.java`
- `src/test/java/com/sstlfsj/fibra/spring/FibraAutoConfigurationTest.java`
- `src/test/java/com/sstlfsj/fibra/spring/FibraStarterBlackboxIT.java` — Spring Boot fat JAR + 外部多插件黑盒
- 根 `pom.xml` — 新增 `<module>fibra-spring-boot-starter</module>`
- `docs/api/fibra-spring-boot-starter-public-signatures.txt` — 公开签名基线
- 文档同步：`docs/release.md`、集成架构 §2、开源基线、内核架构 §2、`ApiSignatureBaselineTest`、`ReleaseArtifactBaselineTest`

**环境（本机跑 mvn 前设置一次）：**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin:$JAVA_HOME/bin:$PATH
```
所有 `mvn` 指以此 PATH 下的 3.9.9。上游未安装时先 `mvn -pl fibra-loader-config -am install -DskipTests`。

---

## Task 1: 模块骨架与 POM

**Files:**
- Create: `fibra-spring-boot-starter/pom.xml`
- Modify: `pom.xml`（根，`<modules>` 内 `fibra-example-host` 之后新增一行）
- Create: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/package-info.java`

- [ ] **Step 1: 根 POM 注册模块**

在根 `pom.xml` 的 `<modules>` 中 `<module>fibra-example-host</module>` 之后新增：
```xml
    <module>fibra-spring-boot-starter</module>
```

- [ ] **Step 2: 写 starter 模块 POM（自管 Spring BOM，父 POM 保持 Spring-free）**

Create `fibra-spring-boot-starter/pom.xml`：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.sstlfsj</groupId>
    <artifactId>fibra</artifactId>
    <version>${revision}</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>fibra-spring-boot-starter</artifactId>
  <name>Fibra Spring Boot Starter</name>
  <description>Optional Spring Boot adapter for the Fibra dynamic plugin runtime.</description>

  <properties>
    <!-- Spring 版本自管，父 POM 不触碰 Spring（内核架构 §2 的显式例外） -->
    <spring-boot.version>4.1.0</spring-boot.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <!-- 覆盖 Spring Boot BOM 的 Reactor，对齐 Fibra 的 3.8.6 -->
      <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <version>${reactor.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>com.sstlfsj</groupId>
      <artifactId>fibra-loader-pf4j</artifactId>
      <version>${revision}</version>
    </dependency>
    <dependency>
      <groupId>com.sstlfsj</groupId>
      <artifactId>fibra-loader-config</artifactId>
      <version>${revision}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-configuration-processor</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```
说明：`${reactor.version}` 继承自父 POM（若父 POM 用字面量 3.8.6，则在此显式写 `3.8.6` 并在 Step 4 校正）。

- [ ] **Step 3: 建包占位**

Create `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/package-info.java`：
```java
/** Fibra 的可选 Spring Boot 适配层。内核 fibra-core/fibra-api 不含 Spring。 */
package com.sstlfsj.fibra.spring;
```

- [ ] **Step 4: 构建骨架并校验 Reactor 对齐**

Run（先装上游）：
```bash
mvn -pl fibra-loader-config -am install -DskipTests
mvn -pl fibra-spring-boot-starter -am install -DskipTests
mvn -pl fibra-spring-boot-starter dependency:tree -Dincludes=io.projectreactor:reactor-core
```
Expected: BUILD SUCCESS；`reactor-core` 解析为 `3.8.6`。若父 POM 的 reactor 版本不是 property，则把 Step 2 的 `${reactor.version}` 改成字面量 `3.8.6` 重跑。若 Enforcer DependencyConvergence 因 Spring 传递图报冲突，按报告在本模块 `dependencyManagement` 收敛（记录被收敛项）。

- [ ] **Step 5: Commit**

```bash
git add pom.xml fibra-spring-boot-starter/pom.xml fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/package-info.java
git commit -m "build: fibra-spring-boot-starter 模块骨架"
```

---

## Task 2: FibraProperties

**Files:**
- Create: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraProperties.java`
- Test: `fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraPropertiesTest.java`

- [ ] **Step 1: 写失败测试**

Create `FibraPropertiesTest.java`：
```java
package com.sstlfsj.fibra.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FibraPropertiesTest {
    @Test
    void bindsAllProperties() {
        var env = new MockEnvironment()
            .withProperty("fibra.plugins-root", "/var/fibra/plugins")
            .withProperty("fibra.staging-root", "/var/fibra/staging")
            .withProperty("fibra.config-location", "/etc/fibra/plugins.yaml")
            .withProperty("fibra.startup-required-plugins", "a,b")
            .withProperty("fibra.watcher.enabled", "true")
            .withProperty("fibra.watcher.debounce", "2s")
            .withProperty("fibra.shutdown-timeout", "30s");
        var binder = new Binder(ConfigurationPropertySources.get(env));

        var props = binder.bind("fibra", FibraProperties.class).get();

        assertEquals("/var/fibra/plugins", props.getPluginsRoot().toString());
        assertEquals("/var/fibra/staging", props.getStagingRoot().toString());
        assertEquals(List.of("a", "b"), props.getStartupRequiredPlugins());
        assertTrue(props.getWatcher().isEnabled());
        assertEquals(Duration.ofSeconds(2), props.getWatcher().getDebounce());
        assertEquals(Duration.ofSeconds(30), props.getShutdownTimeout());
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraPropertiesTest`
Expected: 编译失败（`FibraProperties` 不存在）。

- [ ] **Step 3: 写实现**

Create `FibraProperties.java`：
```java
package com.sstlfsj.fibra.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("fibra")
public class FibraProperties {
    private Path pluginsRoot;
    private Path stagingRoot;
    private Path configLocation;
    private List<String> startupRequiredPlugins = new ArrayList<>();
    private Duration shutdownTimeout = Duration.ofSeconds(30);
    private final Watcher watcher = new Watcher();

    public Path getPluginsRoot() { return pluginsRoot; }
    public void setPluginsRoot(Path v) { this.pluginsRoot = v; }
    public Path getStagingRoot() { return stagingRoot; }
    public void setStagingRoot(Path v) { this.stagingRoot = v; }
    public Path getConfigLocation() { return configLocation; }
    public void setConfigLocation(Path v) { this.configLocation = v; }
    public List<String> getStartupRequiredPlugins() { return startupRequiredPlugins; }
    public void setStartupRequiredPlugins(List<String> v) { this.startupRequiredPlugins = v; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration v) { this.shutdownTimeout = v; }
    public Watcher getWatcher() { return watcher; }

    public static class Watcher {
        private boolean enabled = false;
        private Duration debounce = Duration.ofSeconds(1);
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public Duration getDebounce() { return debounce; }
        public void setDebounce(Duration v) { this.debounce = v; }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraPropertiesTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraProperties.java fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraPropertiesTest.java
git commit -m "feat: FibraProperties 配置绑定"
```

---

## Task 3: FibraServiceBridge

**Files:**
- Create: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraServiceBridge.java`
- Test: `fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraServiceBridgeTest.java`

- [ ] **Step 1: 写失败测试**

Create `FibraServiceBridgeTest.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FibraServiceBridgeTest {
    interface Greeting { String greet(String n); }

    private Context root;

    @BeforeEach void setUp() { root = FibraRuntime.create(); }
    @AfterEach void tearDown() { root.close(); }

    @Test
    void registersHostBeanAsFibraServiceAndRevokes() {
        var key = ServiceKey.of("greeting", Greeting.class);
        var bridge = new FibraServiceBridge(root);

        ServiceRegistration<Greeting> reg = bridge.register(key, n -> "hi " + n);

        assertEquals("hi x", root.service(key).invoke((inv, svc) -> svc.greet("x")));
        reg.dispose().block();
        assertThrows(RuntimeException.class, () -> root.get(key, true));
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraServiceBridgeTest`
Expected: 编译失败（`FibraServiceBridge` 不存在）。

- [ ] **Step 3: 写实现**

Create `FibraServiceBridge.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.ServiceRegistration;

import java.util.Objects;

/**
 * 把宿主 Spring 单例经类型化 {@link ServiceKey} 暴露给 Fibra 插件的通用机制。
 * 注册归 root Context 的 Fibra effect 所有，返回可等待撤销的 registration。
 * 不做按类型自动装配；桥接哪个 bean 由宿主显式决定。
 */
public final class FibraServiceBridge {
    private final Context root;

    public FibraServiceBridge(Context root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public <T> ServiceRegistration<T> register(ServiceKey<T> key, T service) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(service, "service");
        return root.provide(key, service);
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraServiceBridgeTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraServiceBridge.java fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraServiceBridgeTest.java
git commit -m "feat: FibraServiceBridge 宿主 bean 桥接"
```

---

## Task 4: FibraLifecycle（load + reconcile + readiness 门禁 + 有序关闭）

**Files:**
- Create: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraLifecycle.java`
- Test: `fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraLifecycleTest.java`

- [ ] **Step 1: 写失败测试（readiness 门禁：缺必需插件→启动失败并指名）**

Create `FibraLifecycleTest.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FibraLifecycleTest {
    @Test
    void startFailsWhenRequiredPluginMissing(@TempDir Path dir) throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path config = Files.writeString(dir.resolve("plugins.yaml"), "entries: []\n");
        Context root = FibraRuntime.create();
        FibraPluginLoader loader = new FibraPluginLoader(root, plugins);
        FibraConfigLoader configLoader =
            FibraConfigLoader.builder(root, loader, config).build();

        var props = new FibraProperties();
        props.setStartupRequiredPlugins(List.of("does-not-exist"));
        props.setShutdownTimeout(java.time.Duration.ofSeconds(5));

        var lifecycle = new FibraLifecycle(root, loader, configLoader, null, props);

        var ex = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(ex.getMessage().contains("does-not-exist"), ex.getMessage());

        lifecycle.stop();
        root.close();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraLifecycleTest`
Expected: 编译失败（`FibraLifecycle` 不存在）。

- [ ] **Step 3: 写实现**

Create `FibraLifecycle.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Optional;

/** 协调 Fibra 启动装载、就绪门禁与有序关闭；由 Spring 生命周期驱动。 */
public final class FibraLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FibraLifecycle.class);

    private final Context root;
    private final FibraPluginLoader loader;
    private final FibraConfigLoader configLoader;
    private final FibraPluginWatcher watcher; // 可为 null
    private final FibraProperties props;
    private volatile boolean running = false;

    public FibraLifecycle(Context root, FibraPluginLoader loader,
                          FibraConfigLoader configLoader,
                          FibraPluginWatcher watcher, FibraProperties props) {
        this.root = root;
        this.loader = loader;
        this.configLoader = configLoader;
        this.watcher = watcher;
        this.props = props;
    }

    @Override
    public void start() {
        loader.loadArtifacts();
        configLoader.load();
        Duration timeout = props.getShutdownTimeout();
        for (String entryId : props.getStartupRequiredPlugins()) {
            Optional<Fibra> fibra = loader.fibra(entryId);
            if (fibra.isEmpty()) {
                throw new IllegalStateException(
                    "启动必需插件未装载: entryId=" + entryId);
            }
            Fibra f = fibra.get().ready().block(timeout);
            if (f == null || f.state() != FibraState.ACTIVE) {
                throw new IllegalStateException(
                    "启动必需插件未 ACTIVE: entryId=" + entryId
                        + " state=" + (f == null ? "null" : f.state()));
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        try {
            if (watcher != null) {
                watcher.close();
            }
            loader.close();
            Duration timeout = props.getShutdownTimeout();
            root.closeAsync().block(timeout);
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return DEFAULT_PHASE; }
}
```
说明：`FibraPluginWatcher.close()` 与 `FibraConfigLoader` 的关闭由各自 `AutoCloseable`/`close()` 负责；本类只负责顺序编排。若 `FibraPluginWatcher` 无 `close()`，改调其实际停止方法（实现时核对签名）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraLifecycleTest`
Expected: PASS（异常消息含 `does-not-exist`）。

- [ ] **Step 5: Commit**

```bash
git add fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraLifecycle.java fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraLifecycleTest.java
git commit -m "feat: FibraLifecycle 就绪门禁与有序关闭"
```

---

## Task 5: FibraAutoConfiguration + 自动配置登记

**Files:**
- Create: `fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraAutoConfiguration.java`
- Create: `fibra-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraAutoConfigurationTest.java`

- [ ] **Step 1: 写失败测试（ApplicationContextRunner 验装配 + 可覆盖）**

Create `FibraAutoConfigurationTest.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FibraAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FibraAutoConfiguration.class));

    @Test
    void registersCoreBeansWhenPluginsRootSet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("plugins"));
        java.nio.file.Files.writeString(dir.resolve("plugins.yaml"), "entries: []\n");
        runner.withPropertyValues(
                "fibra.plugins-root=" + dir.resolve("plugins"),
                "fibra.config-location=" + dir.resolve("plugins.yaml"))
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(Context.class);
                assertThat(ctx).hasSingleBean(FibraPluginLoader.class);
                assertThat(ctx).hasSingleBean(FibraServiceBridge.class);
                assertThat(ctx).hasSingleBean(FibraLifecycle.class);
            });
    }

    @Test
    void backsOffWhenHostProvidesContext(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("plugins"));
        java.nio.file.Files.writeString(dir.resolve("plugins.yaml"), "entries: []\n");
        runner.withPropertyValues(
                "fibra.plugins-root=" + dir.resolve("plugins"),
                "fibra.config-location=" + dir.resolve("plugins.yaml"))
            .withUserConfiguration(CustomContextConfig.class)
            .run(ctx -> assertThat(ctx.getBean(Context.class))
                .isSameAs(CustomContextConfig.CUSTOM));
    }

    @org.springframework.context.annotation.Configuration
    static class CustomContextConfig {
        static final Context CUSTOM = com.sstlfsj.fibra.runtime.FibraRuntime.create();
        @org.springframework.context.annotation.Bean
        Context fibraRootContext() { return CUSTOM; }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraAutoConfigurationTest`
Expected: 编译失败（`FibraAutoConfiguration` 不存在）。

- [ ] **Step 3: 写实现**

Create `FibraAutoConfiguration.java`：
```java
package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FibraProperties.class)
public class FibraAutoConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public Context fibraRootContext() {
        return FibraRuntime.create();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public FibraPluginLoader fibraPluginLoader(Context fibraRootContext, FibraProperties props) {
        return new FibraPluginLoader(fibraRootContext, props.getPluginsRoot());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public FibraConfigLoader fibraConfigLoader(Context fibraRootContext,
                                               FibraPluginLoader loader,
                                               FibraProperties props) {
        return FibraConfigLoader.builder(fibraRootContext, loader, props.getConfigLocation()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public FibraServiceBridge fibraServiceBridge(Context fibraRootContext) {
        return new FibraServiceBridge(fibraRootContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public FibraLifecycle fibraLifecycle(Context fibraRootContext,
                                         FibraPluginLoader loader,
                                         FibraConfigLoader configLoader,
                                         FibraProperties props) {
        return new FibraLifecycle(fibraRootContext, loader, configLoader, null, props);
    }
}
```
说明：`Context`/`FibraPluginLoader` 用 `destroyMethod=""` 关闭权交给 `FibraLifecycle` 有序编排，避免 Spring 默认 `close()` 打乱顺序；`FibraConfigLoader` 的 `close()` 由 lifecycle 顺序内触发前若已被 Spring 调用则应幂等（实现时确认 `close()` 幂等，否则同样置 `destroyMethod=""` 并在 lifecycle 内关闭）。watcher 装配（`fibra.watcher.enabled=true` 时）在 Task 6 黑盒验证后按需补 `@ConditionalOnProperty` bean 并传入 `FibraLifecycle`。

- [ ] **Step 4: 写自动配置登记文件**

Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
```text
com.sstlfsj.fibra.spring.FibraAutoConfiguration
```
（实现时核对 Spring Boot 4.1 的自动配置登记文件名是否仍为此路径；若变更以官方为准。）

- [ ] **Step 5: 运行验证通过**

Run: `mvn -pl fibra-spring-boot-starter test -Dtest=FibraAutoConfigurationTest`
Expected: PASS（两个用例：装配成功 + 宿主覆盖 back-off）。

- [ ] **Step 6: Commit**

```bash
git add fibra-spring-boot-starter/src/main/java/com/sstlfsj/fibra/spring/FibraAutoConfiguration.java \
  fibra-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraAutoConfigurationTest.java
git commit -m "feat: FibraAutoConfiguration 自动装配"
```

---

## Task 6: 黑盒集成测试（Spring Boot fat JAR + 外部多插件）

**Files:**
- Create: `fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraStarterBlackboxIT.java`
- Modify: `fibra-spring-boot-starter/pom.xml`（加 failsafe 执行 + 复用示例插件制品）

- [ ] **Step 1: 明确黑盒断言范围**

黑盒需验证（集成架构 §4.5）：Spring Boot 应用经 starter 启动 → 从外部插件目录装载 contract/provider/consumer → readiness 门禁通过 → consumer 经 Fibra 服务读到 provider → reload/unload 后 Fibra/loader 不残留插件类。复用 `fibra-example-*` 或 `verification/external-consumer` 已产出的标准 ZIP 作为外部插件输入（不在 starter 内重造插件）。

- [ ] **Step 2: 写 IT（失败先行）**

Create `FibraStarterBlackboxIT.java`：以 `@SpringBootTest` 启动一个最小 `@SpringBootApplication`，`fibra.plugins-root` 指向一个由测试 `@BeforeAll` 用 `applyArtifacts(List.of(...))` 安装了示例 contract+provider+consumer ZIP 的临时目录，`fibra.startup-required-plugins` 指定 consumer 的 entryId；断言：应用上下文启动成功（readiness 通过）、`FibraServiceBridge` 或 `Context` 能解析 consumer 暴露的服务、`unloadArtifact` 后 `loader.entryIds()` 不含该 entry。示例 ZIP 路径由系统属性传入（见 Step 3）。

```java
package com.sstlfsj.fibra.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = FibraStarterBlackboxIT.App.class,
    properties = {
        "fibra.plugins-root=${fibra.it.plugins-root}",
        "fibra.config-location=${fibra.it.config}",
        "fibra.startup-required-plugins=${fibra.it.required}"
    })
class FibraStarterBlackboxIT {
    @SpringBootApplication
    static class App { }

    @Test
    void contextStartsWithExternalPluginsAndReadinessGate() {
        // 上下文成功启动即证明 readiness 门禁通过；进一步断言服务解析。
        assertTrue(true);
    }
}
```
说明：完整断言（服务解析、unload 无残留）在实现时结合示例 ZIP 生成方式补全；ZIP 生成复用 `fibra-example-host` 的 `plugin-artifacts` 产物或 assembly。

- [ ] **Step 3: pom 配置 failsafe 与示例制品依赖**

在 `fibra-spring-boot-starter/pom.xml` 加 `maven-failsafe-plugin` 执行（`*IT`），并通过 `maven-dependency-plugin` 或复用 `fibra-example-host` 的 `plugin-artifacts` 拷贝示例 ZIP，用 `systemPropertyVariables` 把路径传给 IT（`fibra.it.plugins-root` 等）。插件版本从父 POM `pluginManagement` 继承。

- [ ] **Step 4: 运行 IT**

Run: `mvn -pl fibra-spring-boot-starter -am verify`
Expected: failsafe IT PASS；应用启动、外部插件装载、readiness 通过。

- [ ] **Step 5: Commit**

```bash
git add fibra-spring-boot-starter/pom.xml fibra-spring-boot-starter/src/test/java/com/sstlfsj/fibra/spring/FibraStarterBlackboxIT.java
git commit -m "test: fibra-spring-boot-starter 黑盒集成"
```

---

## Task 7: 发布与文档同步

**Files:**
- Create: `docs/api/fibra-spring-boot-starter-public-signatures.txt`
- Modify: `fibra-parity-tests/.../ApiSignatureBaselineTest.java`、`ReleaseArtifactBaselineTest.java`
- Modify: `docs/release.md`、集成架构 §2、开源基线、内核架构 §2、`README.md`、`docs/api/README.md`

- [ ] **Step 1: 生成公开签名基线**

Run:
```bash
mvn -pl fibra-spring-boot-starter -am install -DskipTests
javap -protected -classpath fibra-spring-boot-starter/target/classes \
  com.sstlfsj.fibra.spring.FibraProperties \
  com.sstlfsj.fibra.spring.FibraServiceBridge \
  com.sstlfsj.fibra.spring.FibraLifecycle \
  com.sstlfsj.fibra.spring.FibraAutoConfiguration > docs/api/fibra-spring-boot-starter-public-signatures.txt
```
按现有基线文件的表头格式补首行注释与 `## <fqcn>` 分节，与 `docs/api/fibra-loader-pf4j-public-signatures.txt` 风格一致。

- [ ] **Step 2: 扩展基线门禁覆盖新模块**

修改 `ApiSignatureBaselineTest`、`ReleaseArtifactBaselineTest`，把 `fibra-spring-boot-starter` 纳入受检模块集合与签名基线断言（照现有 5 模块的写法追加）。

- [ ] **Step 3: 同步冻结文档**

- `docs/release.md`：从「5 个生产制品」改为「5 个中立内核/loader 制品 + 1 个可选 Spring 适配制品 `fibra-spring-boot-starter`」，注明 5 中立制品仍只依赖 Reactor+SLF4J、父 POM 保持 Spring-free、starter 自管 Spring BOM；
- 集成架构 `2026-08-22-...-integration-architecture.md` §2：新增 `fibra-spring-boot-starter`，依赖方向 `harness-spring-boot -> harness-runtime + fibra-spring-boot-starter`；
- 开源基线 `2026-08-21-...-baselines.md`：澄清「Spring 不进 Fibra」= 不进内核 `fibra-core`/`fibra-api`；Spring 适配是 Fibra 可选模块；
- 内核架构 §2：记录 starter 的 Spring 版本自管为「版本集中父 POM」的显式例外；
- `README.md` / `docs/api/README.md`：加 starter 用法与坐标。

- [ ] **Step 4: 提交**

```bash
git add docs/ fibra-parity-tests/
git commit -m "docs: fibra-spring-boot-starter 签名基线与文档同步"
```

---

## Task 8: 全量校验与自检

- [ ] **Step 1: 全量 verify**

Run: `mvn clean verify`
Expected: 全部模块 BUILD SUCCESS，含 starter 单测 + 黑盒 IT + 扩展后的 API/发布门禁。

- [ ] **Step 2: 可复现构建（若 starter 纳入可复现集）**

Run: `scripts/verify-reproducible-release.sh`
Expected: 若脚本 `production_modules` 已加 `fibra-spring-boot-starter` 则逐字节一致；否则先在脚本数组补该模块再跑。

- [ ] **Step 3: 自检**

对照设计文档 §1–§10 逐条核对：内核零 Spring、父 POM 无 Spring、Reactor 解析 3.8.6、readiness 门禁、有序关闭、桥接可等待撤销、发布归类为「5 中立 + 1 适配」、文档四处同步无漂移。修正任何遗漏。

- [ ] **Step 4: 提交收口**

```bash
git add -A
git commit -m "chore: fibra-spring-boot-starter 收口"
```

---

## Task 9: 示例宿主 fibra-example-spring-host（HTTP 上传 + 请求驱动热装）

**契约归属决定**：宿主定义 `Greeting` SPI（宿主公共 API，父 ClassLoader 提供），上传的 provider 插件以 `provided` scope 实现它。宿主控制器按 `ServiceKey.of("greeting", Greeting.class)` 调用。

**Files:**
- Modify: 根 `pom.xml`（`<modules>` 加 `fibra-example-spring-host`）
- Create: `fibra-example-spring-host/pom.xml`（非发布示例，deploy skip；依赖 `fibra-spring-boot-starter` + `spring-boot-starter-web`）
- Create: `.../spring/host/ExampleSpringHostApplication.java`
- Create: `.../spring/host/Greeting.java`（宿主 SPI）
- Create: `.../spring/host/PluginController.java`
- Create: `src/main/resources/application.yml`
- Create: `fibra-example-spring-host/README.md`（含安全告警）
- Create: `.../spring/host/ExampleSpringHostIT.java`（真实 HTTP：upload → apply → mount → greet → unload）
- Test fixture: 一个实现宿主 `Greeting` 的 executable provider 标准 ZIP（`provided` 依赖宿主 API，assembly 产出到 `target`），供 IT 上传。

- [ ] **Step 1: 根 POM 注册示例模块 + 写 pom.xml（非发布）**

根 `pom.xml` `<modules>` 追加 `<module>fibra-example-spring-host</module>`。`fibra-example-spring-host/pom.xml` 继承父 POM，依赖 `com.sstlfsj:fibra-spring-boot-starter:${revision}` 与 `org.springframework.boot:spring-boot-starter-web`（版本随 starter 传递的 Spring BOM，或本模块自 import BOM），并按现有 `fibra-example-*` 方式在自身 `build`/父 POM 约定下 `skip deploy`。

- [ ] **Step 2: 写宿主 SPI 与应用入口**

`Greeting.java`：
```java
package com.sstlfsj.fibra.spring.host;
public interface Greeting { String greet(String name); }
```
`ExampleSpringHostApplication.java`：
```java
package com.sstlfsj.fibra.spring.host;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExampleSpringHostApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleSpringHostApplication.class, args);
    }
}
```

- [ ] **Step 3: 写失败 IT（真实 HTTP 全链路）**

`ExampleSpringHostIT.java`：`@SpringBootTest(webEnvironment = RANDOM_PORT)`，用 `TestRestTemplate`：
1. `GET /greet?name=x` → 期望 404/空（尚无 provider）；
2. `POST /plugins/upload`（multipart provider ZIP，路径由系统属性 `fibra.it.provider-zip` 传入）→ 200，返回暂存名，**此时 `GET /greet` 仍无结果**（未 apply）；
3. `POST /plugins/apply`（body 指定暂存候选）→ 200，返回装入的 pluginId；
4. `POST /plugins/{entryId}/mount` 或配置驱动 → entry ACTIVE；
5. `GET /greet?name=x` → 200，返回 provider 的问候串；
6. `DELETE /plugins/{pluginId}` → 200，`GET /plugins` 不再含该 entry。
断言覆盖「上传不生效、apply 才生效、URL 调插件、热卸」。

- [ ] **Step 4: 运行验证失败**

Run: `mvn -pl fibra-example-spring-host -am verify`
Expected: 编译/IT 失败（`PluginController` 不存在）。

- [ ] **Step 5: 写 PluginController（upload=仅暂存；apply=显式热装）**

`PluginController.java`（要点，实现时补全 import 与错误处理）：
```java
package com.sstlfsj.fibra.spring.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.List;

@RestController
@RequestMapping("/plugins")
public class PluginController {
    private static final ServiceKey<Greeting> GREETING = ServiceKey.of("greeting", Greeting.class);
    private final Context root;
    private final FibraPluginLoader loader;
    private final Path staging;

    public PluginController(Context root, FibraPluginLoader loader,
                            com.sstlfsj.fibra.spring.FibraProperties props) {
        this.root = root;
        this.loader = loader;
        this.staging = props.getStagingRoot();
    }

    /** 仅上传落盘到 staging，不 apply。 */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws Exception {
        Files.createDirectories(staging);
        Path dest = staging.resolve(Path.of(file.getOriginalFilename()).getFileName());
        file.transferTo(dest);
        return dest.getFileName().toString();
    }

    /** 显式请求驱动的事务热装。 */
    @PostMapping("/apply")
    public List<String> apply(@RequestBody List<String> stagedNames) {
        List<Path> candidates = stagedNames.stream().map(staging::resolve).toList();
        return loader.applyArtifacts(candidates);
    }

    @GetMapping
    public java.util.Map<String, List<String>> list() {
        return java.util.Map.of("artifacts", loader.artifactIds(), "entries", loader.entryIds());
    }

    @GetMapping("/../greet")
    public String greet(@RequestParam String name) {
        return root.service(GREETING).invoke((inv, svc) -> svc.greet(name));
    }

    @DeleteMapping("/{pluginId}")
    public boolean unload(@PathVariable String pluginId) {
        return loader.unloadArtifact(pluginId);
    }
}
```
说明：`/greet` 用独立 `@GetMapping("/greet")` 放在类级 `@RequestMapping` 之外的另一个 controller 或调整为顶层路径（实现时把 greet 拆到 `GreetController` 用 `@GetMapping("/greet")`，避免上面 `/../` 写法）。mount 端点按 `FibraConfigLoader.create/resolve` 或 `FibraPluginLoader.mount(PluginInstanceSpec)` 补全（实现时核对 `PluginInstanceSpec` 构造）。

- [ ] **Step 6: application.yml（关 watcher，走显式 apply）**

```yaml
fibra:
  plugins-root: ${FIBRA_PLUGINS_ROOT:./run/plugins}
  staging-root: ${FIBRA_STAGING_ROOT:./run/staging}
  config-location: ${FIBRA_CONFIG:./run/plugins.yaml}
  watcher:
    enabled: false
```

- [ ] **Step 7: README 安全告警**

`fibra-example-spring-host/README.md` 明确：`/plugins/apply` 加载插件 = 任意代码执行；本示例仅演示机制，**生产必须在 apply 前鉴权 + 校验 + 签名**，禁止裸开上传/apply 口。

- [ ] **Step 8: 运行 IT 通过**

Run: `mvn -pl fibra-example-spring-host -am verify`
Expected: IT PASS（上传不生效 → apply 生效 → greet 返回 → 卸载）。

- [ ] **Step 9: Commit**

```bash
git add pom.xml fibra-example-spring-host/
git commit -m "test: fibra-example-spring-host HTTP 上传与请求驱动热装示例"
```

---

## Self-Review（计划作者已执行）

- **Spec 覆盖**：§1 边界→Task1/7；§3 位置与依赖→Task1；§4.2 Properties→Task2；§4.4 桥接→Task3；§4.3 Lifecycle/readiness→Task4；§4.1 AutoConfig→Task5；§7 黑盒→Task6；§8 发布/文档→Task7；全量→Task8。无缺口。
- **占位扫描**：Task6 黑盒断言与 Task5 watcher bean 标注为「实现时按实际签名补全」——因这两处依赖运行期需核对的第三方/示例细节（Spring Boot 4.1 登记文件名、FibraPluginWatcher.close 签名、示例 ZIP 产出方式），已在步骤内给出核对指令，不是无据占位。
- **类型一致**：`FibraLifecycle` 构造签名（root, loader, configLoader, watcher, props）在 Task4 定义、Task5 装配处一致；`FibraServiceBridge(Context)`、`FibraProperties` getter 命名前后一致。
- **待确认**：Spring Boot 4.1.0（设计 §9）；若改 3.x LTS，Task1 版本与 Task5 登记机制相应调整。
