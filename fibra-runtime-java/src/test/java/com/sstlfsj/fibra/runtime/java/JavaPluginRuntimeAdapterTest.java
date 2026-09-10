package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.ArtifactState;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.engine.RuntimeChangeRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPluginRuntimeAdapterTest {
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
        var prepared = adapter.prepare(new RuntimeChangeRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record), null)).block();

        assertEquals(record.id(), inspection.artifactId());
        var catalogEntry = prepared.catalog().find("sample").orElseThrow();
        assertNotSame(fixture.SampleEntrypoint.class.getClassLoader(),
            catalogEntry.definition().factory().create().getClass().getClassLoader());
        prepared.commit().block();
        try (var runtime = FibraRuntime.create()) {
            var instance = runtime.rootScope().context().plugins().mount("sample",
                catalogEntry.definition(), null);
            instance.settled().block();
            assertEquals(PluginInstanceState.ACTIVE, instance.state());
        }
        prepared.retire().block();
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

        var prepared = adapter.prepare(new RuntimeChangeRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record), null)).block();

        assertTrue(prepared.catalog().entries().isEmpty());
        prepared.rollback().block();
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
        var prepared = adapter.prepare(new RuntimeChangeRequest(
            JavaPluginRuntimeAdapter.RUNTIME_ID, List.of(record), null)).block();
        var classLoader = prepared.catalog().find("sample").orElseThrow()
            .definition().factory().create().getClass().getClassLoader();
        prepared.commit().block();
        prepared.retire().block();
        adapter.close();
        return new WeakReference<>(classLoader);
    }

    private static void writeFixtureJar(Path jar) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/fibra/plugin.yaml"));
            output.write(("id: sample-artifact\n"
                + "version: 1.0.0\n"
                + "entrypoint: fixture.SampleEntrypoint\n"
                + "requires: []\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("fixture/SampleEntrypoint.class"));
            try (InputStream input = fixture.SampleEntrypoint.class.getResourceAsStream(
                "/fixture/SampleEntrypoint.class")) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
        }
        assertTrue(Files.size(jar) > 0);
    }
}
