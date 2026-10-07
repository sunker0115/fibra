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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

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
        "fibra-bom", "fibra-api", "fibra-core", "fibra-config", "fibra-artifact",
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
    void bomManagesExactlyThePublishedFibraArtifacts() throws Exception {
        var root = reactorRoot();
        var bomPom = root.resolve("fibra-bom/pom.xml");
        var expected = new LinkedHashSet<String>();
        for (var pom : reactorPomFiles(root.resolve("pom.xml"))) {
            if (!pom.equals(bomPom)
                && Files.readString(pom).contains("<maven.deploy.skip>false</maven.deploy.skip>")) {
                var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(pom.toFile());
                var artifactId = directChildText(document.getDocumentElement(), "artifactId");
                expected.add(publishedCoordinate(artifactId,
                    artifactId.equals("fibra-distribution") ? "zip" : null,
                    artifactId.equals("fibra-distribution") ? "bin" : null));
            }
        }

        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(bomPom.toFile());
        var project = document.getDocumentElement();
        assertEquals("pom", directChildText(project, "packaging"));
        assertTrue(directChildElements(project, "dependencies").isEmpty(),
            "BOM must manage versions without adding runtime dependencies");
        assertEquals("false", directChildText(
            directChildElements(project, "properties").getFirst(), "maven.deploy.skip"));
        assertEquals("bom", document.getElementsByTagName("flattenMode")
            .item(0).getTextContent().trim());

        var dependencyManagement = directChildElements(project, "dependencyManagement");
        assertEquals(1, dependencyManagement.size());
        var managedDependencyContainers = directChildElements(
            dependencyManagement.getFirst(), "dependencies");
        assertEquals(1, managedDependencyContainers.size());
        var dependencies = directChildElements(managedDependencyContainers.getFirst(), "dependency");

        var actual = new LinkedHashSet<String>();
        for (var dependency : dependencies) {
            assertEquals("com.sstlfsj", directChildText(dependency, "groupId"));
            assertEquals("${project.version}", directChildText(dependency, "version"));
            var artifactId = directChildText(dependency, "artifactId");
            var type = optionalDirectChildText(dependency, "type");
            var classifier = optionalDirectChildText(dependency, "classifier");
            assertTrue(directChildElements(dependency, "scope").isEmpty());
            if (artifactId.equals("fibra-distribution")) {
                assertEquals("zip", type);
                assertEquals("bin", classifier);
            } else {
                assertEquals(null, type);
                assertEquals(null, classifier);
            }
            actual.add(publishedCoordinate(artifactId, type, classifier));
        }

        assertEquals(dependencies.size(), actual.size(),
            "BOM must not contain duplicate managed artifacts");
        assertEquals(expected, actual);
    }

    @Test
    void distributionPublishesTheVerifiedArchiveAsTheBinClassifier() throws Exception {
        var root = reactorRoot();
        var pom = Files.readString(root.resolve("fibra-distribution/pom.xml"));

        assertTrue(pom.contains("<maven.deploy.skip>false</maven.deploy.skip>"));
        assertTrue(pom.contains("<finalName>fibra-${project.version}</finalName>"));
        assertTrue(pom.contains("<appendAssemblyId>true</appendAssemblyId>"));
        assertTrue(Files.readString(root.resolve("fibra-distribution/src/assembly/bin.xml"))
            .contains("<id>bin</id>"));
    }

    @Test
    void centralPublishUsesTheVerifiedDistributionAndPinnedRuntimes() throws Exception {
        var workflow = Files.readString(reactorRoot().resolve(".github/workflows/release.yml"));
        var verify = workflowJob(workflow, "verify-release");
        var publish = workflowJob(workflow, "publish-central");

        var archiveUpload = verify.indexOf("name: fibra-distribution-${{ steps.release-ref.outputs.commit }}");
        assertTrue(archiveUpload > verify.indexOf("scripts/verify-distribution.sh"),
            "the distribution artifact must be saved after its independent verification");
        assertTrue(verify.contains("path: fibra-distribution/target/fibra-*-bin.zip"));
        assertTrue(publish.contains("name: fibra-distribution-${{ needs.verify-release.outputs.commit }}"));
        assertTrue(publish.contains("path: ${{ runner.temp }}/fibra-verified-distribution"));
        assertTrue(publish.contains("node-version-file: client/.node-version"));
        assertTrue(publish.contains("<ripgrep.runtime.version>"));
        assertTrue(publish.contains("<ripgrep.runtime.sha256>"));
        assertTrue(publish.contains("sha256sum --check -"));
        assertTrue(publish.contains("-Dfibra.distribution.verifiedArchive=\"$RUNNER_TEMP/fibra-verified-distribution/fibra-$revision-bin.zip\""));
    }

    @Test
    void centralPublishingUsesTheSameModulePublicationBoundary() throws Exception {
        var profile = profile(reactorRoot().resolve("pom.xml"), "central-release");
        var plugin = plugin(profile, "central-publishing-maven-plugin");
        var configuration = directChildElements(plugin, "configuration").getFirst();

        assertEquals("${maven.deploy.skip}", optionalDirectChildText(configuration, "skipPublishing"));
        assertEquals("false", directChildText(configuration, "autoPublish"));
    }

    @Test
    void centralDistributionComparisonRejectsMissingDifferentOrSameFiles(@TempDir Path work)
        throws Exception {
        var profile = profile(reactorRoot().resolve("fibra-distribution/pom.xml"), "central-release");
        var plugin = plugin(profile, "exec-maven-plugin");
        var executions = directChildElements(directChildElements(plugin, "executions").getFirst(),
            "execution");
        var comparison = executions.stream().filter(execution ->
            "verify-central-distribution".equals(directChildText(execution, "id")))
            .findFirst().orElseThrow();
        assertEquals("verify", directChildText(comparison, "phase"));
        assertEquals("exec", directChildText(directChildElements(comparison, "goals").getFirst(), "goal"));
        var configuration = directChildElements(comparison, "configuration").getFirst();
        assertEquals("bash", directChildText(configuration, "executable"));
        assertTrue(directChildElements(configuration, "skip").isEmpty(),
            "the Central archive comparison must not be optional");
        var arguments = directChildElements(directChildElements(configuration, "arguments").getFirst(),
            "argument").stream().map(element -> element.getTextContent().trim()).toList();
        assertEquals(5, arguments.size());
        assertEquals("-euc", arguments.get(0));
        assertEquals("${fibra.distribution.verifiedArchive}", arguments.get(3));
        assertEquals("${project.build.directory}/fibra-${project.version}-bin.zip", arguments.get(4));

        var verified = work.resolve("verified.zip");
        var rebuilt = work.resolve("rebuilt.zip");
        Files.writeString(verified, "verified archive");
        Files.writeString(rebuilt, "verified archive");
        var script = arguments.get(1);
        assertEquals(0, compareArchives(script, verified.toString(), rebuilt.toString()));
        Files.writeString(rebuilt, "changed archive");
        assertTrue(compareArchives(script, verified.toString(), rebuilt.toString()) != 0);
        assertTrue(compareArchives(script, work.resolve("missing.zip").toString(), rebuilt.toString()) != 0);
        assertTrue(compareArchives(script, "", rebuilt.toString()) != 0);
        assertTrue(compareArchives(script, "${fibra.distribution.verifiedArchive}", rebuilt.toString()) != 0);
        assertTrue(compareArchives(script, verified.toString(), verified.toString()) != 0,
            "comparing a rebuilt archive with itself is not verification");
        var alias = Files.createSymbolicLink(work.resolve("verified-alias.zip"), verified);
        assertTrue(compareArchives(script, alias.toString(), verified.toString()) != 0,
            "a symlink must not turn the rebuilt archive into its own verification input");
    }

    private static int compareArchives(String script, String verified, String rebuilt) throws Exception {
        var process = new ProcessBuilder("bash", "-euc", script, "fibra-central-distribution", verified, rebuilt)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "archive comparison timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String workflowJob(String workflow, String id) {
        var matcher = Pattern.compile("(?ms)^  " + Pattern.quote(id)
            + ":\\R(.*?)(?=^  [a-z][a-z-]*:\\R|\\z)").matcher(workflow);
        assertTrue(matcher.find(), () -> "missing workflow job " + id);
        return matcher.group(1);
    }

    private static Element profile(Path pom, String id) throws Exception {
        var project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
            .getDocumentElement();
        var containers = directChildElements(project, "profiles");
        assertEquals(1, containers.size(), () -> pom + " must declare the release profile");
        var profile = directChildElements(containers.getFirst(), "profile").stream()
            .filter(candidate -> id.equals(directChildText(candidate, "id"))).findFirst();
        assertTrue(profile.isPresent(), () -> pom + " must declare profile " + id);
        return profile.orElseThrow();
    }

    private static Element plugin(Element profile, String artifactId) {
        var build = directChildElements(profile, "build").getFirst();
        return directChildElements(directChildElements(build, "plugins").getFirst(), "plugin").stream()
            .filter(plugin -> artifactId.equals(directChildText(plugin, "artifactId")))
            .findFirst().orElseThrow();
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

    private static String directChildText(Element parent, String name) {
        var children = directChildElements(parent, name);
        if (children.size() != 1) {
            throw new IllegalStateException("expected one direct " + name + " child");
        }
        return children.getFirst().getTextContent().trim();
    }

    private static String optionalDirectChildText(Element parent, String name) {
        var children = directChildElements(parent, name);
        if (children.size() > 1) {
            throw new IllegalStateException("expected at most one direct " + name + " child");
        }
        return children.isEmpty() ? null : children.getFirst().getTextContent().trim();
    }

    private static String publishedCoordinate(String artifactId, String type,
                                              String classifier) {
        if (type == null && classifier == null) {
            return artifactId;
        }
        return artifactId + ':' + type + ':' + classifier;
    }

    private static List<Element> directChildElements(Element parent, String name) {
        var matches = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                matches.add(element);
            }
        }
        return List.copyOf(matches);
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
