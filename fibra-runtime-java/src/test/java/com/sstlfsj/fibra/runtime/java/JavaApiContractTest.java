package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavaApiContractTest {
    private static final ArtifactId OWNER = new ArtifactId("consumer");
    @TempDir static Path work;
    private static URLClassLoader loader;
    private static Path classes;

    @BeforeAll
    static void compileApiFixtures() throws Exception {
        classes = Files.createDirectories(work.resolve("classes"));
        var sources = Map.ofEntries(
            Map.entry("Plain", "public interface Plain {}"),
            Map.entry("Other", "public record Other(String value) {}"),
            Map.entry("ApiFailure", "public class ApiFailure extends Exception {}"),
            Map.entry("Ref", "public interface Ref { Other call(java.util.List<? extends Other[]> values) throws ApiFailure; }"),
            Map.entry("Nested", "public interface Nested { record Request(String text) {} enum Mode { ONE } final class Builder { public Request build() { return null; } } }"),
            Map.entry("ProtectedNested", "public class ProtectedNested { protected static class Hidden {} }"),
            Map.entry("BadHierarchy", "class HiddenBase {} public class BadHierarchy extends HiddenBase {}"),
            Map.entry("BadSignature", "class HiddenValue {} public interface BadSignature { HiddenValue value(); }"),
            Map.entry("PublicSealed", "public sealed interface PublicSealed permits PublicPermit, HiddenPermit {} final class HiddenPermit implements PublicSealed {}"),
            Map.entry("PublicPermit", "public record PublicPermit(String text) implements PublicSealed {}"),
            Map.entry("Recursive", "public interface Recursive<T extends Recursive<T>> {}"),
            Map.entry("Bound", "public interface Bound<T extends Other> {}"),
            Map.entry("ArrayBound", "public interface ArrayBound<T extends Other> { T[] array(); }"),
            Map.entry("ProtectedSignature", "public class ProtectedSignature { protected Other value; protected ProtectedSignature(Other value) throws ApiFailure {} protected <T extends Other> T method(T value) throws ApiFailure { return value; } }"),
            Map.entry("Outer", "public class Outer<T> { public class Inner<U> {} }"),
            Map.entry("OwnerSignature", "public interface OwnerSignature { Outer<Other>.Inner<String> value(); }"),
            Map.entry("PrivateGraph", "public class PrivateGraph { private HiddenValue value; private HiddenValue method() { return null; } }"),
            Map.entry("Initializing", "public class Initializing { static { System.setProperty(\"fibra.inventory.initialized\", \"yes\"); } public static final String VALUE = new String(\"value\"); }"),
            Map.entry("Missing", "public interface Missing {}"),
            Map.entry("PrivateMissing", "public class PrivateMissing { private Missing hidden; public String value() { return \"ok\"; } }"),
            Map.entry("Broken", "public interface Broken { Missing value(); }")
        );
        var arguments = new ArrayList<String>(List.of("--release", "21", "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            var file = work.resolve(source.getKey() + ".java");
            Files.writeString(file, "package fixture.api; " + source.getValue());
            arguments.add(file.toString());
        }
        for (var exposed : Map.of("BaselineExposure", "JavaApiContracts",
            "NestedBaselineExposure", "JavaApiContractTest.HiddenTemplate").entrySet()) {
            var file = work.resolve(exposed.getKey() + ".java");
            Files.writeString(file, "package com.sstlfsj.fibra.runtime.java; public interface "
                + exposed.getKey() + " { " + exposed.getValue() + " hidden(); }");
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            arguments.toArray(String[]::new)));
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
            JavaApiContractTest.class.getClassLoader());
    }

    @AfterAll static void closeFixtureLoader() throws Exception { if (loader != null) loader.close(); }

    @Test void freezesTheRecordListAndRejectsInvalidValueShapes() throws Exception {
        var values = new ArrayList<Class<?>>(List.of(type("Plain")));
        var contract = new JavaApiContract("plain", "1", values);
        values.clear();
        assertEquals(List.of(type("Plain")), contract.classes());
        assertThrows(UnsupportedOperationException.class, () -> contract.classes().clear());
        for (var id : Arrays.asList(null, "", " ")) {
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContract(id, "1", contract.classes()));
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("plain", id, contract.classes()));
        }
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("plain", "1", null));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("plain", "1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("plain", "1", Arrays.asList((Class<?>) null)));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("plain", "1", List.of(type("Plain"), type("Plain"))));
    }

    @Test void rejectsIneligibleTypesAndManagedDefinitions() throws Exception {
        class Local {}
        for (var type : List.of(int.class, String[].class, Local.class, new Object() {}.getClass(),
            type("HiddenValue"), type("ProtectedNested$Hidden"))) {
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("bad", "1", List.of(type)));
        }
        try (var managed = new PluginClassLoader(List.of(classes.toUri().toURL()),
            getClass().getClassLoader(), JavaApiContracts.defaultParentPackages())) {
            var managedType = managed.loadClass("fixture.api.Plain");
            assertThrows(IllegalArgumentException.class,
                () -> new JavaApiContract("managed", "1", List.of(managedType)));
        }
    }

    @Test void rejectsHiddenClasses() throws Exception {
        // Defining in this test's package avoids crossing the fixture loader lookup boundary.
        var bytes = Files.readAllBytes(Path.of(getClass().getResource("JavaApiContractTest$HiddenTemplate.class").toURI()));
        var hidden = java.lang.invoke.MethodHandles.lookup().defineHiddenClass(bytes, false).lookupClass();
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContract("hidden", "1", List.of(hidden)));
    }
    public static class HiddenTemplate {}

    @Test void rejectsDuplicateIdsAndGlobalBinaryNameOwners() throws Exception {
        var plain = contract("plain", "Plain");
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(plain, plain)));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(plain, contract("other", "Plain"))));
        try (var alternate = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(plain,
                new JavaApiContract("other", "1", List.of(alternate.loadClass("fixture.api.Plain"))))));
        }
    }

    @Test void rejectsActualPlatformAndFixedSharedBaselineExports() {
        for (var type : List.of(String.class, java.sql.Connection.class, org.xml.sax.EntityResolver.class,
            com.sstlfsj.fibra.Context.class, reactor.core.publisher.Mono.class, org.slf4j.Logger.class)) {
            assertThrows(IllegalArgumentException.class,
                () -> new JavaApiContracts(List.of(new JavaApiContract("baseline", "1", List.of(type)))));
        }
    }

    @Test void rejectsNonpublicBaselineSignatureTypes() throws Exception {
        var exposed = loader.loadClass("com.sstlfsj.fibra.runtime.java.BaselineExposure");
        assertSame(JavaApiContracts.class, exposed.getMethod("hidden").getReturnType());
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new JavaApiContracts(List.of(new JavaApiContract("exposure", "1", List.of(exposed)))));
        assertTrue(failure.getMessage().contains(JavaApiContracts.class.getName()));
    }

    @Test void rejectsPublicBaselineNestedTypesWithNonpublicEnclosingClasses() throws Exception {
        var exposed = loader.loadClass("com.sstlfsj.fibra.runtime.java.NestedBaselineExposure");
        assertSame(HiddenTemplate.class, exposed.getMethod("hidden").getReturnType());
        var failure = assertThrows(IllegalArgumentException.class,
            () -> new JavaApiContracts(List.of(new JavaApiContract("nested-exposure", "1", List.of(exposed)))));
        assertTrue(failure.getMessage().contains(JavaApiContractTest.class.getName()));
    }

    @Test void checksCrossContractUnionAndRequiresExplicitFacetImports() throws Exception {
        var a = contract("a", "Ref");
        var b = contract("b", "Other", "ApiFailure");
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(a)));
        var contracts = new JavaApiContracts(List.of(a, b));
        var missing = assertThrows(JavaRuntimeException.class, () -> contracts.bind(OWNER, Map.of("a", "1")));
        assertEquals(JavaRuntimePhase.LOAD, missing.phase());
        assertEquals(OWNER, missing.artifactId());
        assertTrue(missing.getMessage().contains("fixture.api.Other"));
        assertTrue(missing.getMessage().contains("b"));
        var bound = contracts.bind(OWNER, Map.of("a", "1", "b", "1"));
        assertSame(type("Ref"), bound.get("fixture.api.Ref"));
        assertSame(type("Other"), bound.get("fixture.api.Other"));
        assertThrows(UnsupportedOperationException.class, bound::clear);
    }

    @Test void rejectsMissingAndNonExactVersionsWithoutChangingInventory() throws Exception {
        var contracts = new JavaApiContracts(List.of(contract("a", "Plain")));
        for (var imports : List.of(Map.of("unknown", "1"), Map.of("a", "2"), Map.of("a", " 1"), Map.of("a", "latest"))) {
            assertEquals(JavaRuntimePhase.LOAD,
                assertThrows(JavaRuntimeException.class, () -> contracts.bind(OWNER, imports)).phase());
        }
        assertTrue(contracts.bind(OWNER, Map.of()).isEmpty());
        assertSame(type("Plain"), contracts.bind(OWNER, Map.of("a", "1")).get("fixture.api.Plain"));
    }

    @Test void malformedProgrammaticImportsFailAtTheLoadBoundary() throws Exception {
        var contracts = new JavaApiContracts(List.of(contract("a", "Plain")));
        var nullId = new java.util.HashMap<String, String>();
        nullId.put(null, "1");
        var nullVersion = new java.util.HashMap<String, String>();
        nullVersion.put("a", null);
        for (var imports : Arrays.asList(null, nullId, nullVersion, Map.of("", "1"))) {
            assertEquals(JavaRuntimePhase.LOAD,
                assertThrows(JavaRuntimeException.class, () -> contracts.bind(OWNER, imports)).phase());
        }
    }

    @Test void requiresPublicNestedBuildersEnumsAndEnclosingTypes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("nested", "Nested"))));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("nested", "Nested$Request"))));
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("nested", "Nested", "Nested$Request", "Nested$Mode", "Nested$Builder"))));
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("protected", "ProtectedNested"))));
    }

    @Test void rejectsNonpublicHierarchyAndSignatureTypesButIgnoresPrivateGraphs() throws Exception {
        for (var name : List.of("BadHierarchy", "BadSignature")) {
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("bad", name))));
        }
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("private", "PrivateGraph"))));
    }

    @Test void includesPublicPermittedTypesAndDoesNotExpandHiddenImplementations() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("sealed", "PublicSealed"))));
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("sealed", "PublicSealed", "PublicPermit"))));
    }

    @Test void traversesRecursiveBoundsGenericArraysAndParameterizedOwners() throws Exception {
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("recursive", "Recursive"))));
        for (var name : List.of("Bound", "ArrayBound", "ProtectedSignature")) {
            assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("bad", name))));
            assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("good", name, "Other", "ApiFailure"))));
        }
        assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(contract("owner", "OwnerSignature", "Outer", "Outer$Inner"))));
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("owner", "OwnerSignature", "Outer", "Outer$Inner", "Other"))));
    }

    @Test void rejectsSameNamedDifferentClassInReferencedSignature() throws Exception {
        try (var alternate = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var values = List.of(type("Ref"), alternate.loadClass("fixture.api.Other"), type("ApiFailure"));
            assertThrows(IllegalArgumentException.class,
                () -> new JavaApiContracts(List.of(new JavaApiContract("mixed", "1", values))));
        }
    }

    @Test void preservesReflectionLinkageCause() throws Exception {
        try (var broken = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("fixture.api.Missing")) throw new ClassNotFoundException(name);
                return super.loadClass(name);
            }
        }) {
            var value = new JavaApiContract("broken", "1", List.of(broken.loadClass("fixture.api.Broken")));
            var failure = assertThrows(IllegalArgumentException.class, () -> new JavaApiContracts(List.of(value)));
            assertInstanceOf(LinkageError.class, failure.getCause());
        }
    }

    @Test void missingPrivateDescriptorDependenciesRejectCompositionWithTheJvmCause() throws Exception {
        try (var incomplete = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("fixture.api.Missing")) throw new ClassNotFoundException(name);
                return super.loadClass(name);
            }
        }) {
            var type = incomplete.loadClass("fixture.api.PrivateMissing");
            assertEquals("ok", type.getMethod("value").invoke(type.getConstructor().newInstance()));
            var contract = new JavaApiContract("incomplete", "1", List.of(type));
            var failure = assertThrows(IllegalArgumentException.class,
                () -> new JavaApiContracts(List.of(contract)));
            assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("fixture/api/Missing"));
        }
        assertDoesNotThrow(() -> new JavaApiContracts(List.of(contract("private", "PrivateGraph"))));
    }

    @Test void neverInitializesApiClassesOrReadsStaticValues() throws Exception {
        System.clearProperty("fibra.inventory.initialized");
        var contracts = new JavaApiContracts(List.of(contract("init", "Initializing")));
        contracts.bind(OWNER, Map.of("init", "1"));
        assertNull(System.getProperty("fibra.inventory.initialized"));
    }

    @Test void freezesConfigurationAndCanonicalizesIdentity() throws Exception {
        var a = contract("a", "Plain");
        var b = contract("b", "Other", "ApiFailure");
        var values = new ArrayList<>(List.of(a, b));
        var first = new JavaApiContracts(values);
        values.clear();
        assertEquals(first.identity(), new JavaApiContracts(List.of(
            contract("b", "ApiFailure", "Other"), a)).identity());
        assertTrue(first.identity().startsWith(JavaRuntimeProvider.CONTRACT_IDENTITY));
        assertNotEquals(first.identity(), new JavaApiContracts(List.of(a)).identity());
        assertNotEquals(first.identity(), new JavaApiContracts(List.of(a,
            new JavaApiContract("b", "2", b.classes()))).identity());
        assertEquals(3, first.names().size());
        assertThrows(UnsupportedOperationException.class, () -> first.names().clear());
        assertTrue(new JavaApiContracts(List.of()).bind(OWNER, Map.of()).isEmpty());
    }

    private static Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass("fixture.api." + name); }

    private static JavaApiContract contract(String id, String... names) throws ClassNotFoundException {
        var values = new ArrayList<Class<?>>();
        for (var name : names) values.add(type(name));
        return new JavaApiContract(id, "1", values);
    }
}
