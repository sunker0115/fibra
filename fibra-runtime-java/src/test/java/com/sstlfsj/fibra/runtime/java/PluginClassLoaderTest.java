package com.sstlfsj.fibra.runtime.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginClassLoaderTest {
    @Test
    void platformTypesHavePlatformIdentityWithoutSharedPrefixes(@TempDir Path work) throws Exception {
        // Invalid local copies must never be defined when the platform supplies the type.
        var shadow = classJar(work.resolve("platform-shadow.jar"), Map.of(
            "org.xml.sax.EntityResolver", new byte[]{0},
            "org.w3c.dom.Document", new byte[]{0}));
        var rejectingParent = new ClassLoader(null) {
            @Override public Class<?> loadClass(String name) {
                throw new AssertionError("platform type reached host parent: " + name);
            }
        };
        try (var loader = new PluginClassLoader(List.of(shadow.toUri().toURL()),
            rejectingParent, List.of())) {
            for (var type : List.of(Object.class, org.xml.sax.EntityResolver.class,
                org.w3c.dom.Document.class, javax.xml.parsers.DocumentBuilderFactory.class,
                java.sql.Connection.class, org.ietf.jgss.GSSManager.class)) {
                assertSame(type, loader.loadClass(type.getName(), true));
                assertSame(type, loader.loadClass(type.getName()));
            }
        }
    }

    @Test
    void parentMissAllowsLocalDynamicContract(@TempDir Path work) throws Exception {
        try (var local = loader(compileContract(work))) {
            assertSame(local, local.loadClass("com.sstlfsj.fibra.plugins.fixture.Contract")
                .getClassLoader());
        }
    }

    @Test
    void privateVersionsRemainLocalAndDependenciesUseDeclaredOrder(@TempDir Path work) throws Exception {
        var firstJar = privateLibrary(work.resolve("first"), "one");
        var secondJar = privateLibrary(work.resolve("second"), "two");
        try (var first = loader(firstJar); var second = loader(secondJar);
             var consumer = loader(work.resolve("empty"));
             var reverse = loader(work.resolve("empty-reverse"))) {
            first.dependencies(List.of(second));
            consumer.dependencies(List.of(first, second));
            reverse.dependencies(List.of(second, first));
            var one = first.loadClass("privatepkg.Library");
            var two = second.loadClass("privatepkg.Library");
            assertNotSame(one, two);
            assertSame(first, one.getClassLoader());
            assertSame(second, two.getClassLoader());
            assertEquals("one", one.getMethod("version").invoke(null));
            assertEquals("two", two.getMethod("version").invoke(null));
            assertSame(one, consumer.loadClass("privatepkg.Library"));
            assertSame(two, reverse.loadClass("privatepkg.Library"));
        }
    }

    @Test
    void nonSharedHostClassesRemainInvisible(@TempDir Path work) throws Exception {
        try (var local = loader(work)) {
            assertNotNull(local.getParent().loadClass("org.junit.jupiter.api.Test"));
            assertThrows(ClassNotFoundException.class,
                () -> local.loadClass("org.junit.jupiter.api.Test"));
        }
    }

    @Test
    void hostLinkageFailureDoesNotFallBackToLocalCopy(@TempDir Path work) throws Exception {
        var local = compileContract(work);
        var failure = new NoClassDefFoundError("host dependency is broken");
        var brokenParent = new ClassLoader(getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.sstlfsj.fibra.plugins.fixture.Contract")) throw failure;
                return super.loadClass(name);
            }
        };
        try (var loader = new PluginClassLoader(List.of(local.toUri().toURL()),
            brokenParent, List.of("com.sstlfsj.fibra."))) {
            assertSame(failure, assertThrows(NoClassDefFoundError.class,
                () -> loader.loadClass("com.sstlfsj.fibra.plugins.fixture.Contract")));
        }
    }

    @Test
    void localAndDependencyLinkageFailuresDoNotFallBack(@TempDir Path work) throws Exception {
        var brokenJar = classJar(work.resolve("broken.jar"),
            Map.of("privatepkg.Library", new byte[]{0}));
        try (var valid = loader(privateLibrary(work.resolve("valid"), "valid"));
             var broken = loader(brokenJar);
             var consumer = loader(work.resolve("empty"))) {
            broken.dependencies(List.of(valid));
            consumer.dependencies(List.of(broken, valid));
            assertThrows(ClassFormatError.class, () -> broken.loadClass("privatepkg.Library"));
            assertThrows(ClassFormatError.class, () -> consumer.loadClass("privatepkg.Library"));
        }
    }

    @Test
    void parentFirstPackagesPreferTheParentAndFallBackToDeclaredDependencies(
        @TempDir Path work) throws Exception {
        var contractClasses = compileContract(work.resolve("contract"));
        var consumerClasses = compileShadowContext(work.resolve("consumer"));

        try (var contract = loader(contractClasses);
             var consumer = loader(consumerClasses)) {
            consumer.dependencies(List.of(contract));

            assertSame(com.sstlfsj.fibra.Context.class,
                consumer.loadClass("com.sstlfsj.fibra.Context"));
            assertSame(contract, consumer.loadClass(
                "com.sstlfsj.fibra.plugins.fixture.Contract").getClassLoader());
        }
    }

    @Test
    void resolvesClassesAndResourcesThroughTheDeclaredDependencyGraph(
        @TempDir Path work) throws Exception {
        var contractJar = work.resolve("contract.jar");
        writeContractJar(contractJar);
        var providerJar = work.resolve("provider.jar");
        writeEmptyJar(providerJar);
        var consumerJar = work.resolve("consumer.jar");
        writeEmptyJar(consumerJar);

        try (var contract = loader(contractJar);
             var provider = loader(providerJar);
             var consumer = loader(consumerJar)) {
            assertThrows(ClassNotFoundException.class,
                () -> consumer.loadClass("fixture.SampleEntrypoint"));
            provider.dependencies(List.of(contract));
            consumer.dependencies(List.of(provider));

            assertSame(contract, consumer.loadClass("fixture.SampleEntrypoint")
                .getClassLoader());
            try (var input = consumer.getResourceAsStream("contracts/shipping-rate.txt")) {
                assertNotNull(input);
                assertEquals("shipping-rate-contract", new String(input.readAllBytes(),
                    StandardCharsets.UTF_8));
            }
            assertEquals(1, Collections.list(
                consumer.getResources("contracts/shipping-rate.txt")).size());
        }
    }

    private static PluginClassLoader loader(Path jar) throws Exception {
        return new PluginClassLoader(List.of(jar.toUri().toURL()),
            PluginClassLoaderTest.class.getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "org.reactivestreams.", "reactor."));
    }

    private static Path privateLibrary(Path work, String version) throws Exception {
        var source = work.resolve("src/privatepkg/Library.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package privatepkg;
            public class Library {
                public static String version() { return "%s"; }
            }
            """.formatted(version));
        var classes = Files.createDirectories(work.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), source.toString()));
        return classJar(work.resolve("library.jar"), Map.of("privatepkg.Library",
            Files.readAllBytes(classes.resolve("privatepkg/Library.class"))));
    }

    private static Path classJar(Path path, Map<String, byte[]> classes) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static Path compileContract(Path work) throws Exception {
        var classes = compileShadowContext(work);
        var source = work.resolve(
            "src/com/sstlfsj/fibra/plugins/fixture/Contract.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package com.sstlfsj.fibra.plugins.fixture;
            public interface Contract { }
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
            source.toString()));
        return classes;
    }

    private static Path compileShadowContext(Path work) throws Exception {
        var source = work.resolve("src/com/sstlfsj/fibra/Context.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package com.sstlfsj.fibra;
            public interface Context { }
            """);
        var classes = Files.createDirectories(work.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
            source.toString()));
        return classes;
    }

    private static void writeContractJar(Path jar) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/SampleEntrypoint.class"));
            try (InputStream input = fixture.SampleEntrypoint.class.getResourceAsStream(
                "/fixture/SampleEntrypoint.class")) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("contracts/shipping-rate.txt"));
            output.write("shipping-rate-contract".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void writeEmptyJar(Path jar) throws Exception {
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // The dependency graph, not local content, must answer the lookup.
        }
    }
}
