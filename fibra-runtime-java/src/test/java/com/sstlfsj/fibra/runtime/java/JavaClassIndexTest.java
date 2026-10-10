package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClassIndexTest {
    private static final ArtifactId OWNER = new ArtifactId("fixture.index");
    private static final List<String> SHARED = List.of("java.", "com.sstlfsj.fibra.");

    @Test
    void actualPlatformAndHostHitsAreExcludedFromLocalDuplicates(@TempDir Path work) throws Exception {
        var jars = duplicates(work, "org.xml.sax.EntityResolver", "org.w3c.dom.Document",
            "java.sql.Connection", "com.sstlfsj.fibra.Context");
        var parent = getClass().getClassLoader();
        assertDoesNotThrow(() -> JavaClassIndex.validate(OWNER, jars, parent, SHARED));
        try (var loader = new PluginClassLoader(
            List.of(jars.get(0).toUri().toURL(), jars.get(1).toUri().toURL()), parent, SHARED)) {
            for (var type : List.of(org.xml.sax.EntityResolver.class, org.w3c.dom.Document.class,
                java.sql.Connection.class, com.sstlfsj.fibra.Context.class)) {
                assertSame(type, loader.loadClass(type.getName()));
            }
        }
    }

    @Test
    void sharedPrefixMissStillRejectsLocalDuplicates(@TempDir Path work) throws Exception {
        var jars = duplicates(work, "com.sstlfsj.fibra.plugins.fixture.DynamicContract");
        var error = assertThrows(JavaRuntimeException.class,
            () -> JavaClassIndex.validate(OWNER, jars, getClass().getClassLoader(), SHARED));
        assertTrue(error.getMessage().contains("DynamicContract"));
        assertTrue(error.getMessage().contains(jars.get(0).toString()));
        assertTrue(error.getMessage().contains(jars.get(1).toString()));
    }

    @Test
    void nonSharedHostHitStillRejectsLocalDuplicates(@TempDir Path work) throws Exception {
        var jars = duplicates(work, "org.junit.jupiter.api.Test");
        assertThrows(JavaRuntimeException.class,
            () -> JavaClassIndex.validate(OWNER, jars, getClass().getClassLoader(), SHARED));
    }

    @Test
    void hostLinkageFailureIsReportedInsteadOfTreatedAsLocal(@TempDir Path work) throws Exception {
        var jars = duplicates(work, "com.sstlfsj.fibra.plugins.fixture.Broken");
        var failure = new NoClassDefFoundError("missing host dependency");
        var parent = new ClassLoader(null) {
            @Override public Class<?> loadClass(String name) { throw failure; }
        };
        var error = assertThrows(JavaRuntimeException.class,
            () -> JavaClassIndex.validate(OWNER, jars, parent, SHARED));
        assertSame(failure, error.getCause());
    }

    private static List<Path> duplicates(Path work, String... names) throws Exception {
        var paths = List.of(work.resolve("first.jar"), work.resolve("second.jar"));
        for (var path : paths) {
            try (var output = new JarOutputStream(Files.newOutputStream(path))) {
                for (var name : names) {
                    output.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                    // Index uses effective binary names; invalid copies also detect accidental local loading.
                    output.write(new byte[]{0});
                    output.closeEntry();
                }
            }
        }
        return paths;
    }
}
