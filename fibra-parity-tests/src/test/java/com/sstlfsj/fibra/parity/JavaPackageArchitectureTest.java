package com.sstlfsj.fibra.parity;

import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPackageArchitectureTest {
    private static final String ENGINE = "com.sstlfsj.fibra.engine";
    private static final String CLI = "com.sstlfsj.fibra.cli.api";
    private static final Map<String, Set<String>> ENGINE_ALLOWED = Map.of(
        ENGINE, Set.of(ENGINE + ".runtime", ENGINE + ".publication", ENGINE + ".observation",
            ENGINE + ".deployment", ENGINE + ".execution"),
        ENGINE + ".runtime", Set.of(ENGINE + ".publication", ENGINE + ".observation",
            ENGINE + ".deployment", ENGINE + ".execution"),
        ENGINE + ".publication", Set.of(ENGINE + ".observation"),
        ENGINE + ".observation", Set.of(ENGINE + ".deployment", ENGINE + ".execution"),
        ENGINE + ".deployment", Set.of(ENGINE + ".execution"),
        ENGINE + ".execution", Set.of());
    private static final Map<String, Set<String>> CLI_ALLOWED = Map.of(
        CLI, Set.of(CLI + ".command", CLI + ".input"),
        CLI + ".input", Set.of(CLI + ".command", CLI + ".invocation"),
        CLI + ".command", Set.of(CLI + ".invocation"),
        CLI + ".invocation", Set.of(CLI + ".terminal"),
        CLI + ".terminal", Set.of());

    @TempDir Path temporary;

    @Test
    void engineProductionPackagesFollowCapabilityDag() throws Exception {
        assertArchitecture(moduleLocation(FibraEngine.class), ENGINE, ENGINE_ALLOWED);
    }

    @Test
    void cliApiProductionPackagesFollowCapabilityDag() throws Exception {
        assertArchitecture(moduleLocation(CliApplication.class), CLI, CLI_ALLOWED);
    }

    @Test
    void compiledAllowedDependenciesAreAccepted() throws Exception {
        var classes = compileFixture(Map.of(
            ENGINE + ".Entry", "public " + ENGINE + ".runtime.Driver driver;",
            ENGINE + ".runtime.Driver", "public " + ENGINE + ".publication.View view;",
            ENGINE + ".publication.View", "public " + ENGINE + ".observation.State state;",
            ENGINE + ".observation.State", "public " + ENGINE + ".deployment.Target target;",
            ENGINE + ".deployment.Target", "public " + ENGINE + ".execution.Plan plan;",
            ENGINE + ".execution.Plan", ""));
        var graph = graph(classes, ENGINE);
        assertEquals(Set.of(ENGINE + ".runtime"), graph.get(ENGINE));
        assertArchitecture(classes, ENGINE, ENGINE_ALLOWED);
    }

    @Test
    void compiledReverseDependencyIsRejected() throws Exception {
        var classes = compileFixture(Map.of(
            ENGINE + ".execution.Plan", "public " + ENGINE + ".runtime.Driver driver;",
            ENGINE + ".runtime.Driver", ""));
        var graph = graph(classes, ENGINE);
        assertEquals(List.of(ENGINE + ".execution -> " + ENGINE + ".runtime"),
            violations(graph, ENGINE_ALLOWED));
        assertFalse(hasCycle(graph));
    }

    @Test
    void compiledCycleIsRejected() throws Exception {
        var classes = compileFixture(Map.of(
            CLI + ".invocation.Invocation", "public " + CLI + ".terminal.Terminal terminal;",
            CLI + ".terminal.Terminal", "public " + CLI + ".invocation.Invocation invocation;"));
        var graph = graph(classes, CLI);
        assertTrue(hasCycle(graph));
        assertEquals(List.of(CLI + ".terminal -> " + CLI + ".invocation"),
            violations(graph, CLI_ALLOWED));
    }

    @Test
    void compiledUnknownPackageWithoutInternalDependenciesIsRejected() throws Exception {
        var classes = compileFixture(Map.of(ENGINE + ".unknown.Isolated", ""));
        var graph = graph(classes, ENGINE);
        assertEquals(Set.of(ENGINE + ".unknown"), graph.keySet());
        assertEquals(List.of("unknown package: " + ENGINE + ".unknown"),
            violations(graph, ENGINE_ALLOWED));
    }

    @Test
    void engineImplementationClassesCannotBeImportedByConsumers() throws Exception {
        for (var name : List.of("EngineCommandLoop", "RuntimeProviderRegistry")) {
            var implementation = engineClass(ENGINE + "." + name);
            assertFalse(Modifier.isPublic(implementation.getModifiers()), implementation.getName());
            assertCompiles("public " + ENGINE + ".FibraEngine.Builder builder() { return "
                + ENGINE + ".FibraEngine.builder(null, null); }");
            assertAccessDenied("import " + implementation.getName() + ";\n",
                "private " + name + " implementation;");
        }
    }

    @Test
    void durableTokenIssuanceAndStoredTargetConfirmationRemainStorePrivate() throws Exception {
        var token = engineClass(ENGINE + ".deployment.DurableTargetToken");
        var target = engineClass(ENGINE + ".deployment.DeploymentTarget");
        var stored = engineClass(ENGINE + ".deployment.DeploymentTargetStore$StoredTarget");
        assertPackagePrivate(token.getDeclaredMethod("issue", long.class, String.class).getModifiers());
        assertPackagePrivate(stored.getDeclaredMethod("confirmed", target).getModifiers());

        assertCompiles("public long revision(" + token.getName() + " token) { "
            + "return token.targetRevision(); }");
        assertAccessDenied("", "public Object issue() { return " + token.getName()
            + ".issue(1L, \"0\".repeat(64)); }");

        var storedName = stored.getCanonicalName();
        assertCompiles("public " + target.getName() + " target(" + storedName
            + " stored) { return stored.target(); }");
        assertAccessDenied("", "public Object confirm(" + target.getName() + " target) { return "
            + storedName + ".confirmed(target); }");
    }

    @Test
    void engineAndCandidateKeepNonPublicConstructionAndMutationBoundaries() throws Exception {
        assertTrue(Modifier.isPrivate(FibraEngine.class
            .getDeclaredConstructor(FibraEngine.Builder.class).getModifiers()));
        assertNonPublicConstructors(FibraEngine.class);
        for (var name : List.of("EngineCommandLoop", "RuntimeProviderRegistry", "DeploymentPlanner")) {
            var type = engineClass(ENGINE + "." + name);
            assertPackagePrivate(type.getModifiers());
            assertNonPublicConstructors(type);
        }
        var candidate = engineClass(ENGINE + ".DeploymentCandidate");
        assertNonPublicConstructors(candidate);
        var target = engineClass(ENGINE + ".deployment.DeploymentTarget");
        assertPackagePrivate(candidate.getDeclaredConstructor(target).getModifiers());
        assertPackagePrivate(candidate.getDeclaredMethod("register",
            Class.forName("com.sstlfsj.fibra.artifact.RuntimeId"),
            engineClass(ENGINE + ".runtime.RuntimeCandidate")).getModifiers());
        assertPackagePrivate(candidate.getDeclaredMethod("registerSealed",
            Class.forName("com.sstlfsj.fibra.artifact.RuntimeId"),
            engineClass(ENGINE + ".runtime.PreparedRuntimeGeneration")).getModifiers());
        assertPackagePrivate(candidate.getDeclaredMethod("seal",
            engineClass(ENGINE + ".deployment.CompiledDeployment"), Map.class).getModifiers());
    }

    private static void assertPackagePrivate(int modifiers) {
        assertEquals(0, modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE),
            "member or collaborator must remain package-private");
    }

    private static void assertNonPublicConstructors(Class<?> type) {
        for (var constructor : type.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers())
                || Modifier.isProtected(constructor.getModifiers()), constructor.toString());
        }
    }

    private static Class<?> engineClass(String name) throws Exception {
        var type = Class.forName(name, false, FibraEngine.class.getClassLoader());
        assertEquals(moduleLocation(FibraEngine.class), moduleLocation(type),
            "boundary checks must resolve the real engine production class: " + name);
        return type;
    }

    private static Path moduleLocation(Class<?> anchor) throws Exception {
        return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void assertArchitecture(Path classes, String root,
                                           Map<String, Set<String>> allowed) throws Exception {
        var graph = graph(classes, root);
        assertEquals(allowed.keySet(), graph.keySet(), "production package inventory changed");
        assertEquals(List.of(), violations(graph, allowed), "forbidden package dependencies");
        assertFalse(hasCycle(graph), "production package dependencies must be acyclic");
    }

    private static Map<String, Set<String>> graph(Path classes, String root) throws Exception {
        var graph = new TreeMap<String, Set<String>>();
        // Inventory includes isolated and package-private classes, not just jdeps edges.
        var names = new ArrayList<String>();
        if (Files.isDirectory(classes)) {
            try (var paths = Files.walk(classes)) {
                paths.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> classes.relativize(path).toString())
                    .forEach(names::add);
            }
        } else {
            try (var jar = new JarFile(classes.toFile())) {
                jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class")).forEach(names::add);
            }
        }
        for (var name : names) {
            var type = name.substring(0, name.length() - 6).replace('/', '.').replace('\\', '.');
            if (type.startsWith(root + ".")) {
                graph.putIfAbsent(packageName(type), new TreeSet<>());
            }
        }
        var output = new StringWriter();
        int status = java.util.spi.ToolProvider.findFirst("jdeps").orElseThrow().run(
            new PrintWriter(output), new PrintWriter(output), "--ignore-missing-deps",
            "-verbose:class", "-filter:none", classes.toString());
        assertEquals(0, status, output.toString());
        for (var line : output.toString().split("\\R")) {
            var fields = line.trim().split("\\s+");
            if (fields.length < 3 || !fields[1].equals("->")
                || !fields[0].startsWith(root + ".") || !fields[2].startsWith(root + ".")) {
                continue;
            }
            var from = packageName(fields[0]);
            var to = packageName(fields[2]);
            if (!from.equals(to)) {
                graph.computeIfAbsent(from, ignored -> new TreeSet<>()).add(to);
            }
        }
        return graph;
    }

    private static String packageName(String className) {
        return className.substring(0, className.lastIndexOf('.'));
    }

    private static List<String> violations(Map<String, Set<String>> graph,
                                            Map<String, Set<String>> allowed) {
        var failures = new TreeSet<String>();
        graph.forEach((from, targets) -> {
            if (!allowed.containsKey(from)) failures.add("unknown package: " + from);
            targets.forEach(to -> {
                if (!allowed.getOrDefault(from, Set.of()).contains(to)) {
                    failures.add(from + " -> " + to);
                }
            });
        });
        return List.copyOf(failures);
    }

    private static boolean hasCycle(Map<String, Set<String>> graph) {
        var visited = new HashSet<String>();
        var path = new HashSet<String>();
        return graph.keySet().stream().anyMatch(node -> cycle(node, graph, visited, path));
    }

    private static boolean cycle(String node, Map<String, Set<String>> graph,
                                 Set<String> visited, Set<String> path) {
        if (path.contains(node)) return true;
        if (!visited.add(node)) return false;
        path.add(node);
        for (var next : graph.getOrDefault(node, Set.of())) {
            if (cycle(next, graph, visited, path)) return true;
        }
        path.remove(node);
        return false;
    }

    private Path compileFixture(Map<String, String> bodies) throws Exception {
        var sources = new TreeMap<String, String>();
        bodies.forEach((name, body) -> sources.put(name, "package " + packageName(name)
            + "; public class " + name.substring(name.lastIndexOf('.') + 1) + " { " + body + " }"));
        var result = compile(sources);
        assertTrue(result.success(), result.errors().toString());
        return result.classes();
    }

    private void assertCompiles(String body) throws Exception {
        var result = compile(Map.of("external.Consumer", "package external; public class Consumer { "
            + body + " }"));
        assertTrue(result.success(), "public API control must compile: " + result.errors());
    }

    private void assertAccessDenied(String imports, String body) throws Exception {
        var result = compile(Map.of("external.Consumer", "package external; " + imports
            + "public class Consumer { " + body + " }"));
        assertFalse(result.success(), "internal API became accessible");
        assertFalse(result.errors().isEmpty(), "negative compilation must report an error");
        // JDK 21 uses the same diagnostic for package-private types and methods.
        assertTrue(result.errors().stream().allMatch("compiler.err.not.def.public.cant.access"::equals),
            "expected only access errors, never a missing class or symbol: " + result.errors());
    }

    private Compilation compile(Map<String, String> sources) throws Exception {
        var workspace = Files.createTempDirectory(temporary, "compile-");
        var classes = Files.createDirectory(workspace.resolve("classes"));
        var paths = new ArrayList<Path>();
        for (var entry : sources.entrySet()) {
            var source = workspace.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            paths.add(source);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var options = List.of("-proc:none", "-d", classes.toString(), "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            boolean success = compiler.getTask(null, files, diagnostics, options, null,
                files.getJavaFileObjectsFromPaths(paths)).call();
            var errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(Diagnostic::getCode).toList();
            return new Compilation(classes, success, errors);
        }
    }

    private record Compilation(Path classes, boolean success, List<String> errors) { }
}
