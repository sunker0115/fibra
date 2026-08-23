package com.sstlfsj.fibra.parity;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseArtifactBaselineTest {
    private static final int JAVA_21_CLASS_MAJOR_VERSION = 65;
    // 5 个中立内核/loader 制品：只依赖 Reactor + SLF4J，父 POM 保持 Spring-free。
    private static final List<String> NEUTRAL_KERNEL_MODULES = List.of(
        "fibra-api",
        "fibra-core",
        "fibra-pf4j-api",
        "fibra-loader-pf4j",
        "fibra-loader-config"
    );
    // 第 6 个可发布制品：可选 Spring 适配制品，自管 Spring BOM，与中立制品分类区分。
    private static final List<String> SPRING_ADAPTER_MODULES = List.of(
        "fibra-spring-boot-starter"
    );
    // 全部可发布制品享有一致发布待遇：主 JAR + sources + Javadoc + flatten POM + deploy。
    private static final List<String> RELEASABLE_MODULES =
        Stream.concat(NEUTRAL_KERNEL_MODULES.stream(), SPRING_ADAPTER_MODULES.stream()).toList();
    private static final List<String> VERIFICATION_MODULES = List.of(
        "fibra-example-contract-plugin",
        "fibra-example-provider-plugin",
        "fibra-example-consumer-plugin",
        "fibra-example-host",
        "fibra-parity-tests"
    );
    private static final List<String> EXTERNAL_CONSUMER_MODULES = List.of(
        "core-app",
        "contract-plugin",
        "provider-plugin",
        "consumer-plugin",
        "host"
    );

    @Test
    void releasableModulesProduceCompleteSelfContainedArtifacts() throws Exception {
        for (var module : RELEASABLE_MODULES) {
            var moduleDirectory = repositoryRoot().resolve(module);
            var target = moduleDirectory.resolve("target");
            var version = assertSelfContainedPom(
                moduleDirectory.resolve(".flattened-pom.xml"), module);

            assertTrue(Files.isRegularFile(target.resolve(module + "-" + version + ".jar")),
                module + " 缺少主 JAR");
            assertTrue(Files.isRegularFile(target.resolve(module + "-" + version + "-sources.jar")),
                module + " 缺少 sources JAR");
            assertTrue(Files.isRegularFile(target.resolve(module + "-" + version + "-javadoc.jar")),
                module + " 缺少 Javadoc JAR");

            assertJava21MainJar(target.resolve(module + "-" + version + ".jar"));
        }
    }

    @Test
    void onlyReleasableModulesEnableRemoteDeployment() throws Exception {
        var root = repositoryRoot();
        assertEquals("true", property(parseProject(root.resolve("pom.xml")),
            "maven.deploy.skip"));

        for (var module : RELEASABLE_MODULES) {
            assertEquals("false", property(parseProject(root.resolve(module).resolve("pom.xml")),
                "maven.deploy.skip"), module + " 必须显式开启远程发布");
        }
        for (var module : VERIFICATION_MODULES) {
            assertFalse("false".equals(property(
                    parseProject(root.resolve(module).resolve("pom.xml")), "maven.deploy.skip")),
                module + " 不得开启远程发布");
        }
    }

    @Test
    void externalConsumerFixtureIsIndependentFromFibraReactor() throws Exception {
        var root = repositoryRoot();
        var rootProject = parseProject(root.resolve("pom.xml"));
        var rootModules = directChild(rootProject, "modules");
        assertNotNull(rootModules, "Fibra 根 POM 缺少 modules");
        assertFalse(childTexts(rootModules, "module").contains("verification/external-consumer"),
            "外部消费验收不得加入 Fibra reactor");

        var fixtureDirectory = root.resolve("verification/external-consumer");
        var fixturePom = fixtureDirectory.resolve("pom.xml");
        assertTrue(Files.isRegularFile(fixturePom), "缺少独立外部消费方 POM");

        var fixtureProject = parseProject(fixturePom);
        assertNull(directChild(fixtureProject, "parent"),
            "外部消费方根 POM 不得继承 Fibra parent");
        var fixtureModules = directChild(fixtureProject, "modules");
        assertNotNull(fixtureModules, "外部消费方根 POM 缺少 modules");
        assertEquals(EXTERNAL_CONSUMER_MODULES,
            childTexts(fixtureModules, "module"),
            "外部消费方必须分别验证内核、contract、provider、consumer 和宿主装载");

        var fixtureContent = Files.readString(fixturePom);
        assertFalse(fixtureContent.contains("${revision}"),
            "外部消费方不得读取 Fibra 的 revision 属性");
        assertFalse(fixtureContent.contains("target/classes"),
            "外部消费方不得引用 Fibra 编译输出");
        assertFalse(fixtureContent.contains("systemPath"),
            "外部消费方不得通过 systemPath 引用本地文件");

        for (var module : EXTERNAL_CONSUMER_MODULES) {
            var moduleProject = parseProject(fixtureDirectory.resolve(module).resolve("pom.xml"));
            var parent = directChild(moduleProject, "parent");
            assertNotNull(parent, module + " 缺少独立消费方 parent");
            assertEquals("external-consumer", directChildText(parent, "artifactId"),
                module + " 不得继承 Fibra parent");
        }

        assertEquals(List.of("fibra-api:provided"),
            dependencies(fixtureDirectory.resolve("contract-plugin").resolve("pom.xml")),
            "contract 只能通过 provided scope 使用 Fibra API");
        assertEquals(List.of(
                "external-contract-plugin:provided",
                "fibra-pf4j-api:provided",
                "pf4j:provided",
                "commons-text:compile"
            ),
            dependencies(fixtureDirectory.resolve("provider-plugin").resolve("pom.xml")),
            "provider 必须依赖 contract，并只把 Commons Text 作为私有运行时依赖");
        assertEquals(List.of(
                "external-contract-plugin:provided",
                "fibra-pf4j-api:provided",
                "pf4j:provided"
            ),
            dependencies(fixtureDirectory.resolve("consumer-plugin").resolve("pom.xml")),
            "consumer 必须以 provided scope 使用 contract、Fibra PF4J API 和 PF4J");
        assertEquals(List.of("fibra-loader-config:compile", "slf4j-simple:runtime"),
            dependencies(fixtureDirectory.resolve("host").resolve("pom.xml")),
            "Host 不得声明 provider 或 consumer Maven 依赖");
    }

    private static void assertJava21MainJar(Path jarPath) throws Exception {
        try (var jar = new JarFile(jarPath.toFile())) {
            var classes = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().endsWith(".class"))
                .toList();

            assertFalse(classes.isEmpty(), jarPath + " 不包含生产 class");
            assertTrue(classes.stream().noneMatch(entry -> entry.getName().endsWith("Test.class")),
                jarPath + " 混入测试 class");
            for (var entry : classes) {
                try (var input = new DataInputStream(jar.getInputStream(entry))) {
                    assertEquals(0xCAFEBABE, input.readInt(), entry.getName() + " 不是 class 文件");
                    input.readUnsignedShort();
                    assertEquals(JAVA_21_CLASS_MAJOR_VERSION, input.readUnsignedShort(),
                        entry.getName() + " 不是 Java 21 字节码");
                }
            }
        }
    }

    private static String assertSelfContainedPom(Path pomPath, String artifactId) throws Exception {
        assertTrue(Files.isRegularFile(pomPath), artifactId + " 缺少扁平发布 POM");
        var content = Files.readString(pomPath);
        assertFalse(content.contains("${revision}"), artifactId + " 发布 POM 残留 revision");
        assertFalse(content.contains("${project.version}"), artifactId + " 发布 POM 残留 project.version");

        var project = parseProject(pomPath);

        assertNull(directChild(project, "parent"), artifactId + " 发布 POM 仍依赖根 parent");
        assertEquals("com.sstlfsj", directChildText(project, "groupId"));
        assertEquals(artifactId, directChildText(project, "artifactId"));
        var version = directChildText(project, "version");
        assertNotNull(version, artifactId + " 发布 POM 缺少 version");
        assertNotNull(directChild(project, "name"), artifactId + " 发布 POM 缺少 name");
        assertNotNull(directChild(project, "description"), artifactId + " 发布 POM 缺少 description");

        var dependencies = project.getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = (Element) dependencies.item(index);
            assertNotNull(directChild(dependency, "version"),
                artifactId + " 发布依赖未展开版本：" + directChildText(dependency, "artifactId"));
            assertFalse("test".equals(directChildText(dependency, "scope")),
                artifactId + " 发布 POM 混入测试依赖：" + directChildText(dependency, "artifactId"));
        }
        return version;
    }

    private static Element parseProject(Path pomPath) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(pomPath.toFile()).getDocumentElement();
    }

    private static String property(Element project, String propertyName) {
        var properties = directChild(project, "properties");
        return properties == null ? null : directChildText(properties, propertyName);
    }

    private static Element directChild(Element parent, String localName) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String localName) {
        var child = directChild(parent, localName);
        return child == null ? null : child.getTextContent().strip();
    }

    private static List<String> childTexts(Element parent, String localName) {
        var result = new ArrayList<String>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element.getTextContent().strip());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> dependencies(Path pomPath) throws Exception {
        var project = parseProject(pomPath);
        var dependencies = directChild(project, "dependencies");
        assertNotNull(dependencies, pomPath + " 缺少 dependencies");
        var result = new ArrayList<String>();
        for (var child = dependencies.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            if (child instanceof Element dependency
                && "dependency".equals(dependency.getLocalName())) {
                var scope = directChildText(dependency, "scope");
                result.add(directChildText(dependency, "artifactId") + ":"
                    + (scope == null ? "compile" : scope));
            }
        }
        return List.copyOf(result);
    }

    private static Path repositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return current.getFileName().toString().equals("fibra-parity-tests")
            ? current.getParent()
            : current;
    }
}
