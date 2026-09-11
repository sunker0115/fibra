package com.sstlfsj.fibra.parity;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureBaselineTest {
    private static final List<String> MODULES = List.of(
        "fibra-api", "fibra-core", "fibra-config", "fibra-artifact",
        "fibra-engine", "fibra-bridge", "fibra-runtime-java",
        "fibra-runtime-node", "fibra-registry", "fibra-spring",
        "fibra-spring-boot-starter", "fibra-plugin-archetype",
        "fibra-example", "fibra-parity-tests", "fibra-benchmarks");

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
        for (var type : List.of("PreparedRuntimeGeneration", "RuntimeChangeRequest",
            "RuntimeGeneration", "RuntimeGenerationRequest", "RuntimeGenerationSnapshot",
            "TransactionJournal", "FileTransactionJournal",
            "ChangeParticipant", "PreparedChange", "ChangeSet", "ChangeSetResult",
            "ChangeSetException")) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.sstlfsj.fibra.engine." + type));
        }
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
                "spring-context"));

        for (var entry : forbiddenByModule.entrySet()) {
            var pom = Files.readString(root.resolve(entry.getKey()).resolve("pom.xml"));
            for (var forbidden : entry.getValue()) {
                assertFalse(pom.contains("<artifactId>" + forbidden + "</artifactId>"),
                    () -> entry.getKey() + " must not depend on " + forbidden);
            }
        }
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
