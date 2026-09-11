package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.RuntimeGenerationRequest;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPluginRuntimeAdapterTest {
    @Test
    void closeBeforePreparationPreventsAllLaterAllocation(@TempDir Path work) throws Exception {
        var loaders = new java.util.ArrayList<ClassLoader>();
        fixture.PreparationObserver.callback = loaders::add;
        try {
            var artifact = preparationArtifact(work, "closed");
            var generation = preparationAdapter().create(
                new RuntimeGenerationRequest(JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(artifact)));
            assertTrue(loaders.isEmpty());
            assertThrows(IllegalStateException.class, generation::catalog);
            assertThrows(IllegalStateException.class, generation::snapshot);
            generation.closeAsync().block();
            assertThrows(IllegalStateException.class, () -> generation.prepareAsync().block());
            generation.closeAsync().block();
            assertTrue(loaders.isEmpty());
        } finally {
            fixture.PreparationObserver.callback = null;
        }
    }

    @Test
    void definitionFailureClosesTheUntransferredClassLoader(@TempDir Path work) throws Exception {
        var loaders = new java.util.ArrayList<ClassLoader>();
        var failure = new IllegalStateException("definition rejected");
        fixture.PreparationObserver.callback = loader -> {
            loaders.add(loader);
            throw failure;
        };
        try {
            var artifact = preparationArtifact(work, "first");
            var generation = preparationAdapter().create(
                new RuntimeGenerationRequest(JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(artifact)));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> generation.prepareAsync().block()));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> generation.prepareAsync().block()));
            generation.closeAsync().block();
            assertEquals(1, loaders.size());
            assertNull(loaders.getFirst().getResource("generation-marker.txt"), "failed preparation must close its loader");
        } finally {
            fixture.PreparationObserver.callback = null;
            for (var loader : loaders) ((java.net.URLClassLoader) loader).close();
        }
    }

    @Test
    void duplicateDefinitionsCloseEveryUntransferredClassLoader(@TempDir Path work) throws Exception {
        var loaders = new java.util.ArrayList<ClassLoader>();
        fixture.PreparationObserver.callback = loaders::add;
        try {
            var artifacts = List.of(preparationArtifact(work, "first"), preparationArtifact(work, "second"));
            var generation = preparationAdapter().create(
                new RuntimeGenerationRequest(JavaPluginRuntimeAdapter.RUNTIME_ID, artifacts));
            var failure = assertThrows(IllegalArgumentException.class, () -> generation.prepareAsync().block());
            generation.closeAsync().block();
            assertTrue(failure.getMessage().contains("duplicate plugin definition"));
            assertEquals(2, loaders.size());
            for (var loader : loaders) {
                assertNull(loader.getResource("generation-marker.txt"), "every failed candidate loader must be closed");
            }
        } finally {
            fixture.PreparationObserver.callback = null;
            for (var loader : loaders) ((java.net.URLClassLoader) loader).close();
        }
    }

    private static JavaPluginRuntimeAdapter preparationAdapter() {
        return new JavaPluginRuntimeAdapter(JavaPluginRuntimeAdapterTest.class.getClassLoader(),
            List.of("java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.", "reactor.",
                "org.reactivestreams.", "org.slf4j.", "fixture.PreparationObserver"));
    }

    private static ArtifactRecord preparationArtifact(Path work, String id) throws Exception {
        var jar = work.resolve(id + ".jar");
        writeFixtureJar(jar, fixture.PreparationEntrypoint.class, id);
        return ArtifactRecord.builder().id(new ArtifactId(id))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum(id).revision(id).location(jar).state(ArtifactState.STAGED)
            .updatedAt(Instant.EPOCH).build();
    }

    @Test
    void desiredOnlyEngineChangeUsesANewRealJarClassSpace(@TempDir Path work) throws Exception {
        var jar = work.resolve("observed.jar");
        writeFixtureJar(jar, fixture.GenerationEntrypoint.class);
        var store = new com.sstlfsj.fibra.artifact.ArtifactStore(work.resolve("store"));
        store.prepareInstall(new ArtifactId("sample-artifact"), JavaPluginRuntimeAdapter.RUNTIME_ID,
            "1.0.0", jar).commit();
        var loaders = new java.util.concurrent.CopyOnWriteArrayList<ClassLoader>();
        var services = new com.sstlfsj.fibra.engine.HostServiceRegistry();
        services.register(com.sstlfsj.fibra.ServiceKey.of("loader-observer", java.util.function.Consumer.class),
            (java.util.function.Consumer<ClassLoader>) loaders::add);
        var graph = new com.sstlfsj.fibra.config.DesiredInputGraph(List.of(
            com.sstlfsj.fibra.config.DesiredInputEntry.builder("p", "sample").build()));
        try (var engine = com.sstlfsj.fibra.engine.FibraEngine.builder(
            new com.sstlfsj.fibra.config.InMemoryDesiredStateRepository(graph))
            .artifactStore(store).hostServices(services).runtimeAdapter(new JavaPluginRuntimeAdapter()).build()) {
            var first = engine.start().block();
            engine.submit(new com.sstlfsj.fibra.engine.ReplaceDesiredGraph(first.viewRevision(),
                first.engine().desiredSource().revision(), graph)).block();

            assertEquals(2, loaders.size());
            assertNotSame(loaders.getFirst(), loaders.getLast());
            assertNotSame(fixture.GenerationEntrypoint.class.getClassLoader(), loaders.getLast());
        }
    }

    @Test
    void closingOneHandleDoesNotCloseAnotherHandleFromTheSameAdapter(@TempDir Path work)
        throws Exception {
        var jar = work.resolve("parallel.jar");
        writeFixtureJar(jar);
        var record = ArtifactRecord.builder().id(new ArtifactId("sample-artifact"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID).version("1.0.0")
            .checksum("checksum").revision("revision").location(jar)
            .state(ArtifactState.INSTALLED).updatedAt(Instant.EPOCH).build();
        var adapter = new JavaPluginRuntimeAdapter();
        var request = new RuntimeGenerationRequest(JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record));
        var first = adapter.create(request);
        first.prepareAsync().block();
        var firstCatalog = first.catalog();
        first.prepareAsync().block();
        assertSame(firstCatalog, first.catalog());
        var second = adapter.create(request);
        second.prepareAsync().block();
        var firstLoader = first.catalog().find("sample").orElseThrow().definition().factory()
            .create().getClass().getClassLoader();
        var secondLoader = second.catalog().find("sample").orElseThrow().definition().factory()
            .create().getClass().getClassLoader();
        try {
            assertNotSame(firstLoader, secondLoader);
            first.closeAsync().block();
            first.closeAsync().block();
            assertNull(firstLoader.getResource("generation-marker.txt"));
            try (var marker = secondLoader.getResourceAsStream("generation-marker.txt")) {
                assertEquals("owned", new String(marker.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            first.closeAsync().block();
            second.closeAsync().block();
        }
    }

    @Test
    void loadsTheSingleExplicitEntrypointFromARealIsolatedJar(@TempDir Path work)
        throws Exception {
        var jar = work.resolve("sample.jar");
        writeFixtureJar(jar);
        var record = ArtifactRecord.builder()
            .id(new ArtifactId("sample-artifact"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0")
            .checksum("checksum")
            .revision("revision")
            .location(jar)
            .state(ArtifactState.STAGED)
            .updatedAt(Instant.now())
            .build();
        var adapter = new JavaPluginRuntimeAdapter();

        var inspection = adapter.inspect(record).block();
        var prepared = adapter.create(new RuntimeGenerationRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record)));
        prepared.prepareAsync().block();

        assertEquals(record.id(), inspection.artifactId());
        var catalogEntry = prepared.catalog().find("sample").orElseThrow();
        assertNotSame(fixture.SampleEntrypoint.class.getClassLoader(),
            catalogEntry.definition().factory().create().getClass().getClassLoader());
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins().mount("sample",
                catalogEntry.definition().prepare(null));
            instance.settled().block();
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
        }
        prepared.closeAsync().block();
    }

    @Test
    void loadsAContractOnlyArtifactWithoutInventingAnEntrypoint(@TempDir Path work)
        throws Exception {
        var jar = work.resolve("contract.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("id: contract-artifact\n"
                + "version: 1.0.0\n"
                + "requires: []\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var record = ArtifactRecord.builder()
            .id(new ArtifactId("contract-artifact"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0").checksum("checksum").revision("revision")
            .location(jar).state(ArtifactState.STAGED).updatedAt(Instant.now()).build();
        var adapter = new JavaPluginRuntimeAdapter();

        var prepared = adapter.create(new RuntimeGenerationRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record)));
        prepared.prepareAsync().block();

        assertTrue(prepared.catalog().entries().isEmpty());
        prepared.closeAsync().block();
    }

    @Test
    void retiredClassSpaceReleasesItsClassLoader(@TempDir Path work) throws Exception {
        var loader = loadAndRetire(work);

        for (int attempt = 0; attempt < 80 && loader.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(loader.get(), "retired plugin ClassLoader remained strongly reachable");
    }

    private static WeakReference<ClassLoader> loadAndRetire(Path work) throws Exception {
        var jar = work.resolve("collectable.jar");
        writeFixtureJar(jar);
        var record = ArtifactRecord.builder()
            .id(new ArtifactId("sample-artifact"))
            .runtimeId(JavaPluginRuntimeAdapter.RUNTIME_ID)
            .version("1.0.0").checksum("checksum").revision("revision")
            .location(jar).state(ArtifactState.STAGED).updatedAt(Instant.now()).build();
        var adapter = new JavaPluginRuntimeAdapter();
        var prepared = adapter.create(new RuntimeGenerationRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record)));
        prepared.prepareAsync().block();
        var classLoader = prepared.catalog().find("sample").orElseThrow()
            .definition().factory().create().getClass().getClassLoader();
        prepared.closeAsync().block();
        return new WeakReference<>(classLoader);
    }

    private static void writeFixtureJar(Path jar) throws Exception {
        writeFixtureJar(jar, fixture.SampleEntrypoint.class);
    }

    private static void writeFixtureJar(Path jar, Class<?> entrypoint) throws Exception {
        writeFixtureJar(jar, entrypoint, "sample-artifact");
    }

    private static void writeFixtureJar(Path jar, Class<?> entrypoint, String artifactId) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("id: " + artifactId + "\n"
                + "version: 1.0.0\n"
                + "entrypoint: " + entrypoint.getName() + "\n"
                + "requires: []\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            var classFile = entrypoint.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(classFile));
            try (InputStream input = entrypoint.getResourceAsStream("/" + classFile)) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("generation-marker.txt"));
            output.write("owned".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertTrue(Files.size(jar) > 0);
    }
}
