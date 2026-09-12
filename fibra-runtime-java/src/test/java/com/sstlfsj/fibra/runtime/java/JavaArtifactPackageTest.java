package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactException;
import com.sstlfsj.fibra.artifact.ArtifactPackage;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.ArtifactStore;
import com.sstlfsj.fibra.artifact.RuntimeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaArtifactPackageTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void storedPackageLoadsPrivateDependenciesAfterTheOriginalSourceIsDeleted(@TempDir Path work)
        throws Exception {
        var source = packageAt(work.resolve("source"), null, null);
        var adapter = new JavaPluginRuntimeAdapter();
        var candidate = adapter.probe(ArtifactPackage.read(source)).block();
        try (var store = new ArtifactStore(work.resolve("artifacts"))) {
            ArtifactRecord saved;
            try (var transaction = store.prepareInstall(candidate.artifactId(),
                    candidate.runtimeId(), candidate.version(), candidate.source())) {
                saved = transaction.save();
            }
            try (var paths = Files.walk(source)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            assertFalse(Files.exists(source));
            var recovered = store.find(saved.id(), saved.revision()).orElseThrow();
            assertEquals(saved.id(), adapter.inspect(recovered).block().artifactId());
            var owner = adapter.create();
            try {
                var update = owner.createUpdate(List.of(recovered));
                update.prepareAsync().block();
                update.adopt();
                update.closeAsync().block();
                var loader = owner.catalog().plugins().find("sample").orElseThrow()
                    .definition().factory().getClass().getClassLoader();
                var service = loader.loadClass("fixture.PrivateGreeting");
                var provider = ServiceLoader.load((Class) service, loader).iterator().next();
                assertEquals("private greeting", service.getMethod("message").invoke(provider));
                assertSame(loader, provider.getClass().getClassLoader());
                try (var input = loader.getResourceAsStream("private.txt")) {
                    assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                var managedRoot = recovered.location().toRealPath();
                assertTrue(java.util.Arrays.stream(((PluginClassLoader) loader).getURLs())
                    .allMatch(url -> Path.of(url.getPath()).startsWith(managedRoot)));
            } finally {
                owner.closeAsync().block();
            }
        }
    }

    @Test
    void probesTheInternalManifestAndRetainsTheWholeInstallationUnit(@TempDir Path work)
        throws Exception {
        var root = packageAt(work.resolve("sample"), null, null);
        var adapter = new JavaPluginRuntimeAdapter();
        var candidate = adapter.probe(ArtifactPackage.read(root)).block();

        assertEquals(new ArtifactId("sample"), candidate.artifactId());
        assertEquals("1.0.0", candidate.version());
        assertEquals(JavaPluginRuntimeAdapter.RUNTIME_ID, candidate.runtimeId());
        assertEquals(root.toRealPath(), candidate.source());
        assertEquals(new ArtifactId("sample"), adapter.inspect(record(root)).block().artifactId());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void privateClassesResourcesAndServicesUseOneOrderedIsolatedLoader(@TempDir Path work)
        throws Exception {
        var firstRoot = packageAt(work.resolve("first"), null, null);
        var secondRoot = packageAt(work.resolve("second"), null, null);
        var first = new JavaPluginRuntimeAdapter().create();
        var second = new JavaPluginRuntimeAdapter().create();
        try {
            var loaders = new java.util.ArrayList<ClassLoader>();
            for (var entry : List.of(java.util.Map.entry(first, firstRoot),
                    java.util.Map.entry(second, secondRoot))) {
                var update = entry.getKey().createUpdate(List.of(record(entry.getValue())));
                update.prepareAsync().block();
                update.adopt();
                update.closeAsync().block();
                var loader = entry.getKey().catalog().plugins().find("sample").orElseThrow()
                    .definition().factory().getClass().getClassLoader();
                loaders.add(loader);
                var service = loader.loadClass("fixture.PrivateGreeting");
                var implementation = loader.loadClass("fixture.PrivateGreetingProvider");
                assertSame(loader, service.getClassLoader());
                assertSame(loader, implementation.getClassLoader());
                var provider = ServiceLoader.load((Class) service, loader).iterator().next();
                assertEquals("private greeting", service.getMethod("message").invoke(provider));
                try (var input = loader.getResourceAsStream("priority.txt")) {
                    assertEquals("main", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                try (var input = loader.getResourceAsStream("private.txt")) {
                    assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertEquals(List.of("main.jar", "a-private.jar", "z-private.jar"),
                    java.util.Arrays.stream(((PluginClassLoader) loader).getURLs())
                        .map(url -> Path.of(url.getPath()).getFileName().toString()).toList());
            }
            assertNotSame(loaders.get(0), loaders.get(1));
            assertNotSame(loaders.get(0).loadClass("fixture.PrivateGreeting"),
                loaders.get(1).loadClass("fixture.PrivateGreeting"));
        } finally {
            first.closeAsync().block();
            second.closeAsync().block();
        }
    }

    @Test
    void rejectsImplicitClassPathsInTheMainJarAndPrivateJarsBeforeLoading(@TempDir Path work)
        throws Exception {
        var adapter = new JavaPluginRuntimeAdapter();
        for (var root : List.of(packageAt(work.resolve("main"), "../outside.jar", null),
                packageAt(work.resolve("private"), null, "https://example.invalid/library.jar"))) {
            assertThrows(JavaRuntimeException.class,
                () -> adapter.probe(ArtifactPackage.read(root)).block());
            assertThrows(JavaRuntimeException.class, () -> adapter.inspect(record(root)).block());
            var owner = adapter.create();
            try {
                var update = owner.createUpdate(List.of(record(root)));
                assertThrows(JavaRuntimeException.class, () -> update.prepareAsync().block());
                assertTrue(update.snapshot().resources().isEmpty());
                update.closeAsync().block();
            } finally {
                owner.closeAsync().block();
            }
        }
    }

    @Test
    void validatesBothPackageRuntimeAndStoredIdentity(@TempDir Path work) throws Exception {
        var root = packageAt(work.resolve("sample"), null, null);
        var adapter = new JavaPluginRuntimeAdapter();
        var record = record(root);
        assertThrows(JavaRuntimeException.class,
            () -> adapter.inspect(record.toBuilder().id(new ArtifactId("other")).build()).block());
        assertThrows(JavaRuntimeException.class,
            () -> adapter.inspect(record.toBuilder().version("2.0.0").build()).block());
        assertThrows(IllegalArgumentException.class,
            () -> adapter.inspect(record.toBuilder().runtimeId(new RuntimeId("node")).build()).block());
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=node\npayload=lib/main.jar\n");
        assertThrows(IllegalArgumentException.class,
            () -> adapter.probe(ArtifactPackage.read(root)).block());
        assertThrows(IllegalArgumentException.class, () -> adapter.inspect(record).block());
    }

    @Test
    void rejectsBareJarsAndEscapingPayloads(@TempDir Path work) throws Exception {
        var root = packageAt(work.resolve("sample"), null, null);
        var adapter = new JavaPluginRuntimeAdapter();
        assertThrows(ArtifactException.class,
            () -> adapter.inspect(record(root.resolve("lib/main.jar"))).block());
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=../outside.jar\n");
        assertThrows(ArtifactException.class, () -> adapter.inspect(record(root)).block());
    }

    private static Path packageAt(Path root, String mainClassPath, String privateClassPath)
        throws Exception {
        var lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("plugin.properties"),
            "formatVersion=1\nruntime=java\npayload=lib/main.jar\n");
        try (var jar = jar(lib.resolve("main.jar"), mainClassPath)) {
            entry(jar, "META-INF/fibra/plugin.yaml", "id: sample\nversion: 1.0.0\n"
                + "entrypoint: fixture.SampleEntrypoint\nrequires: []\n");
            copyClass(jar, fixture.SampleEntrypoint.class);
            entry(jar, "priority.txt", "main");
        }
        try (var jar = jar(lib.resolve("z-private.jar"), privateClassPath)) {
            entry(jar, "private.txt", "z");
        }
        try (var jar = jar(lib.resolve("a-private.jar"), null)) {
            copyClass(jar, fixture.PrivateGreeting.class);
            copyClass(jar, fixture.PrivateGreetingProvider.class);
            entry(jar, "META-INF/services/fixture.PrivateGreeting", "fixture.PrivateGreetingProvider\n");
            entry(jar, "priority.txt", "private");
            entry(jar, "private.txt", "a");
        }
        return root;
    }

    private static JarOutputStream jar(Path path, String classPath) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        return new JarOutputStream(Files.newOutputStream(path), manifest);
    }

    private static void copyClass(JarOutputStream jar, Class<?> type) throws Exception {
        var name = type.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(name));
        try (var input = type.getResourceAsStream('/' + name)) {
            jar.write(input.readAllBytes());
        }
        jar.closeEntry();
    }

    private static void entry(JarOutputStream jar, String name, String value) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(value.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static ArtifactRecord record(Path root) {
        return ArtifactRecord.builder().id(new ArtifactId("sample"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum("checksum").revision("revision").location(root)
            .state(ArtifactState.INSTALLED).updatedAt(Instant.EPOCH).build();
    }
}
