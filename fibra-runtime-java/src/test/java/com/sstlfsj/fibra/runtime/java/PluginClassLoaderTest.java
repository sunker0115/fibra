package com.sstlfsj.fibra.runtime.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginClassLoaderTest {
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
        return new PluginClassLoader(jar.toUri().toURL(),
            PluginClassLoaderTest.class.getClassLoader(),
            List.of("java.", "com.sstlfsj.fibra.", "org.reactivestreams.", "reactor."));
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
