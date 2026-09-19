package com.sstlfsj.fibra.parity;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBaselineTest {
    private static final List<String> JAVASCRIPT_ARTIFACT_IDS = List.of(
        "pnpm", "npm", "npx", "yarn", "bun", "react", "react-dom", "electron",
        "frontend-maven-plugin");
    private static final List<String> NODE_TOOL_TOKENS = List.of(
        "node", "pnpm", "npm", "npx", "yarn", "bun");
    private static final List<String> EXECUTION_CONFIGURATION_ELEMENTS = List.of(
        "executable", "command", "argument", "arguments", "commandlineArgs");
    private static final List<String> MODULES = List.of(
        "fibra-api", "fibra-core", "fibra-config", "fibra-artifact",
        "fibra-engine", "fibra-bridge", "fibra-runtime-java",
        "fibra-runtime-node", "fibra-registry", "fibra-cli-api", "fibra-cli", "fibra-spring",
        "fibra-spring-boot-starter", "fibra-plugin-archetype",
        "fibra-plugins", "fibra-distribution", "fibra-example", "fibra-parity-tests",
        "fibra-benchmarks", "fibra-client-protocol");

    @Test
    void rootDeclaresOnlyTheVNextArchitecture() throws Exception {
        var root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("fibra-api"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("cannot locate Fibra reactor root");
        }
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(root.resolve("pom.xml").toFile());
        var nodes = document.getElementsByTagName("module");
        var actual = new java.util.ArrayList<String>();
        for (int index = 0; index < nodes.getLength(); index++) {
            actual.add(nodes.item(index).getTextContent().trim());
        }

        assertEquals(MODULES, actual);
        var pom = Files.readString(root.resolve("pom.xml"));
        assertFalse(pom.contains("pf4j"));
        assertFalse(pom.contains("fibra-loader-"));
        assertFalse(pom.contains("fibra-spring-boot-autoconfigure"));
    }

    @Test
    void removedCompatibilityTypesAreNotLoadable() {
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("com.sstlfsj.fibra.Fibra"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader"));
        for (var type : List.of("DesiredEntry", "DesiredGraph", "PluginContract",
            "PluginDefinitionResolver")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.sstlfsj.fibra.config." + type));
        }
        for (var type : List.of("RuntimeChangeRequest",
            "RuntimeGeneration", "RuntimeGenerationRequest", "RuntimeGenerationSnapshot",
            "TransactionJournal", "FileTransactionJournal",
            "ChangeParticipant", "PreparedChange", "ChangeSet", "ChangeSetResult",
            "ChangeSetException")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.sstlfsj.fibra.engine." + type));
        }
        for (var type : List.of("ArtifactInstallTransaction", "ArtifactPackage",
            "ArtifactRecord", "ArtifactStore")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.sstlfsj.fibra.artifact." + type));
        }
        for (var type : List.of("PluginRuntimeAdapter", "ArtifactRuntime",
            "RuntimeCatalog", "RuntimeResourceOwner")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.sstlfsj.fibra.engine." + type));
        }
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("com.sstlfsj.fibra.runtime.client.ClientArtifactRuntime"));
    }

    @Test
    void moduleDependenciesFollowTheVNextDirection() throws Exception {
        var root = reactorRoot();
        var forbiddenByModule = Map.of(
            "fibra-api", List.of("fibra-core", "fibra-engine", "spring-context"),
            "fibra-core", List.of("fibra-config", "fibra-artifact", "fibra-engine",
                "spring-context"),
            "fibra-config", List.of("fibra-core", "fibra-artifact", "fibra-engine",
                "spring-context"),
            "fibra-artifact", List.of("fibra-api", "fibra-core", "fibra-config",
                "fibra-engine", "spring-context"),
            "fibra-engine", List.of("fibra-runtime-java", "fibra-runtime-node",
                "fibra-registry", "spring-context"),
            "fibra-bridge", List.of("fibra-engine", "fibra-registry", "spring-context"),
            "fibra-runtime-java", List.of("fibra-runtime-node", "fibra-registry",
                "spring-context"),
            "fibra-runtime-node", List.of("fibra-runtime-java", "fibra-registry",
                "spring-context"),
            "fibra-registry", List.of("fibra-runtime-java", "fibra-runtime-node",
                "spring-context"),
            "fibra-cli-api", List.of("fibra-engine", "fibra-cli", "picocli", "jline",
                "spring-context"));

        for (var entry : forbiddenByModule.entrySet()) {
            var pom = Files.readString(root.resolve(entry.getKey()).resolve("pom.xml"));
            for (var forbidden : entry.getValue()) {
                assertFalse(pom.contains("<artifactId>" + forbidden + "</artifactId>"),
                    () -> entry.getKey() + " must not depend on " + forbidden);
            }
        }
    }

    @Test
    void clientFoundationUsesDedicatedJavaModulesWithoutJavaScriptTooling() throws Exception {
        var root = reactorRoot();
        for (var module : List.of("fibra-client-protocol")) {
            assertTrue(Files.isRegularFile(root.resolve(module).resolve("pom.xml")),
                () -> module + " must be a dedicated Maven module");
        }
        for (var pom : reactorPomFiles(root.resolve("pom.xml"))) {
            assertNoJavaScriptTooling(pom.toString(), Files.readString(pom));
        }
    }

    @Test
    void javaToolingBoundaryRejectsJavaScriptBuildConfigurationWithoutRejectingReactor() {
        assertThrows(AssertionError.class, () -> assertNoJavaScriptTooling("pnpm fixture", """
            <project><build><plugins><plugin><configuration><executable>pnpm</executable>
            </configuration></plugin></plugins></build></project>
            """));
        assertThrows(AssertionError.class, () -> assertNoJavaScriptTooling("node fixture", """
            <project><build><plugins><plugin><configuration><executable>node</executable>
            </configuration></plugin></plugins></build></project>
            """));
        assertThrows(AssertionError.class, () -> assertNoJavaScriptTooling("webjar fixture", """
            <project><dependencies><dependency><groupId>org.webjars.npm</groupId>
            <artifactId>react-dom</artifactId></dependency></dependencies></project>
            """));
        assertThrows(AssertionError.class, () -> assertNoJavaScriptTooling("frontend fixture", """
            <project><build><plugins><plugin><groupId>org.codehaus.mojo</groupId>
            <artifactId>frontend-maven-plugin</artifactId><executions><execution>
            <goals><goal>install-node-and-pnpm</goal><goal>pnpm</goal></goals>
            <configuration><arguments>install</arguments></configuration>
            </execution></executions></plugin></plugins></build></project>
            """));
        assertDoesNotThrow(() -> assertNoJavaScriptTooling("reactor fixture", """
            <project><dependencies><dependency><groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId></dependency></dependencies></project>
            """));
    }

    @Test
    void reactorPomFilesRecursivelyDiscoversNestedModules(@TempDir Path temporaryDirectory)
        throws Exception {
        var rootPom = temporaryDirectory.resolve("pom.xml");
        var nestedPom = temporaryDirectory.resolve("nested").resolve("pom.xml");
        var leafPom = temporaryDirectory.resolve("nested").resolve("leaf").resolve("pom.xml");
        Files.createDirectories(leafPom.getParent());
        Files.writeString(rootPom, "<project><modules><module>nested</module></modules></project>");
        Files.writeString(nestedPom, "<project><modules><module>leaf</module></modules></project>");
        Files.writeString(leafPom, "<project/>");

        assertEquals(List.of(rootPom, nestedPom, leafPom), reactorPomFiles(rootPom));
    }

    private static List<Path> reactorPomFiles(Path rootPom) throws Exception {
        var pomFiles = new ArrayList<Path>();
        collectReactorPomFiles(rootPom.toAbsolutePath().normalize(), new LinkedHashSet<>(), pomFiles);
        return List.copyOf(pomFiles);
    }

    private static void collectReactorPomFiles(Path pom, Set<Path> visited, List<Path> pomFiles)
        throws Exception {
        if (!visited.add(pom)) {
            return;
        }
        pomFiles.add(pom);
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var modules = document.getElementsByTagName("module");
        for (int index = 0; index < modules.getLength(); index++) {
            var modulePom = pom.getParent().resolve(modules.item(index).getTextContent().trim())
                .resolve("pom.xml").normalize();
            if (!Files.isRegularFile(modulePom)) {
                throw new IllegalStateException("cannot locate reactor module POM: " + modulePom);
            }
            collectReactorPomFiles(modulePom, visited, pomFiles);
        }
    }

    private static void assertNoJavaScriptTooling(String pomName, String pom) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new InputSource(new StringReader(pom)));
        for (var artifactId : JAVASCRIPT_ARTIFACT_IDS) {
            assertFalse(hasExactElementValue(document, "artifactId", artifactId),
                () -> pomName + " must not declare " + artifactId);
        }
        for (var element : EXECUTION_CONFIGURATION_ELEMENTS) {
            var nodes = document.getElementsByTagName(element);
            for (int index = 0; index < nodes.getLength(); index++) {
                var value = nodes.item(index).getTextContent().trim();
                for (var tool : NODE_TOOL_TOKENS) {
                    assertFalse(containsExactToken(value, tool),
                        () -> pomName + " must not execute " + tool);
                }
            }
        }
    }

    private static boolean hasExactElementValue(org.w3c.dom.Document document, String element,
                                                 String expectedValue) {
        var nodes = document.getElementsByTagName(element);
        for (int index = 0; index < nodes.getLength(); index++) {
            if (expectedValue.equals(nodes.item(index).getTextContent().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExactToken(String value, String token) {
        return Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(token)
            + "(?![A-Za-z0-9_.-])").matcher(value).find();
    }

    private static Path reactorRoot() {
        var root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("fibra-api"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("cannot locate Fibra reactor root");
        }
        return root;
    }
}
