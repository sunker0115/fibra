package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactErrorStage;
import com.sstlfsj.fibra.loader.pf4j.FibraArtifactException;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraExampleHostIT {
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);
    private static final String CONTRACT_TYPE = "example.fibra.contract.Greeting";
    private static final String PROVIDER_ENTRYPOINT =
        "example.fibra.provider.ProviderEntrypoint";
    private static final String CONSUMER_ENTRYPOINT =
        "example.fibra.consumer.ConsumerEntrypoint";

    @Test
    void runsFiniteHostAgainstThreeRealPluginPackages(@TempDir Path work) throws Exception {
        var artifacts = artifacts();
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = work.resolve("fibra.yaml");
        copyConfig(config);

        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        var host = Path.of(System.getProperty("fibra.example.host.jar"));
        var process = new ProcessBuilder(javaExecutable.toString(), "-jar", host.toString(),
            plugins.toString(), config.toString(),
            artifacts.resolve("fibra-example-contract-1.0.0.zip").toString(),
            artifacts.resolve("fibra-example-provider-1.0.0.zip").toString(),
            artifacts.resolve("fibra-example-consumer-1.0.0.zip").toString(),
            artifacts.resolve("fibra-example-contract-2.0.0.zip").toString(),
            artifacts.resolve("fibra-example-provider-2.0.0.zip").toString(),
            artifacts.resolve("fibra-example-consumer-2.0.0.zip").toString())
            .redirectErrorStream(true)
            .start();
        var finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "executable example host did not finish");
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("consumer->provider-1.0.0"), output);
        assertTrue(output.contains("consumer->provider-2.0.0"), output);
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-contract")));
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-provider")));
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-consumer")));
    }

    @Test
    void verifiesContractIsolationBatchUpgradeAndFailedApplyRollback(@TempDir Path work)
        throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(CONTRACT_TYPE));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PROVIDER_ENTRYPOINT));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(CONSUMER_ENTRYPOINT));

        var artifacts = artifacts();
        var contractV1 = artifacts.resolve("fibra-example-contract-1.0.0.zip");
        var providerV1 = artifacts.resolve("fibra-example-provider-1.0.0.zip");
        var consumerV1 = artifacts.resolve("fibra-example-consumer-1.0.0.zip");
        assertTrue(mainJarContains(contractV1,
            "example/fibra/contract/Greeting.class"));
        assertFalse(mainJarContains(providerV1,
            "example/fibra/contract/Greeting.class"));
        assertFalse(mainJarContains(consumerV1,
            "example/fibra/contract/Greeting.class"));
        assertEquals("fibra-example-contract@>=1.0.0 & <2.0.0",
            descriptor(consumerV1).getProperty("plugin.dependencies"));

        var plugins = Files.createDirectory(work.resolve("plugins"));
        var configPath = work.resolve("fibra.yaml");
        copyConfig(configPath);
        try (Context root = FibraRuntime.create();
             var loader = new FibraPluginLoader(root, plugins);
             var config = FibraConfigLoader.builder(root, loader, configPath).build()) {
            loader.loadArtifacts();
            loader.applyArtifacts(List.of(contractV1, providerV1, consumerV1));
            config.load();
            assertEquals("1.0.0", root.get(PROVIDER_VERSION));
            assertEquals("consumer->provider-1.0.0", root.get(CONSUMER_RESULT));

            loader.applyArtifacts(List.of(
                artifacts.resolve("fibra-example-contract-2.0.0.zip"),
                artifacts.resolve("fibra-example-provider-2.0.0.zip"),
                artifacts.resolve("fibra-example-consumer-2.0.0.zip")));
            assertEquals("2.0.0", root.get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", root.get(CONSUMER_RESULT));

            var failure = assertThrows(FibraArtifactException.class,
                () -> loader.applyArtifacts(List.of(
                    artifacts.resolve("fibra-example-provider-3.0.0.zip"))));
            assertEquals(FibraArtifactErrorStage.APPLY, failure.stage());
            assertEquals("2.0.0", root.get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", root.get(CONSUMER_RESULT));
            assertEquals(List.of("fibra-example-consumer", "fibra-example-provider"),
                loader.entryIds());
            assertEquals(FibraState.ACTIVE,
                config.resolve("fibra-example-provider").orElseThrow().fibra().state());
            assertEquals(FibraState.ACTIVE,
                config.resolve("fibra-example-consumer").orElseThrow().fibra().state());
        }
    }

    private static Path artifacts() {
        return Path.of(System.getProperty("fibra.example.artifacts"));
    }

    private static void copyConfig(Path target) throws Exception {
        Files.copy(Path.of(System.getProperty("fibra.example.config")), target);
    }

    private static String installedVersion(Path packageRoot) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(packageRoot.resolve("plugin.properties"))) {
            properties.load(input);
        }
        return properties.getProperty("plugin.version");
    }

    private static Properties descriptor(Path pluginZip) throws Exception {
        try (var zip = new ZipFile(pluginZip.toFile())) {
            var id = pluginZip.getFileName().toString().replaceFirst("-[0-9].*", "");
            var properties = new Properties();
            try (var input = zip.getInputStream(zip.getEntry(id + "/plugin.properties"))) {
                properties.load(input);
            }
            return properties;
        }
    }

    private static boolean mainJarContains(Path pluginZip, String classEntry) throws Exception {
        try (var zip = new ZipFile(pluginZip.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().contains("/lib/") || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                var bytes = zip.getInputStream(entry).readAllBytes();
                try (var jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
                    java.util.jar.JarEntry jarEntry;
                    while ((jarEntry = jar.getNextJarEntry()) != null) {
                        if (jarEntry.getName().equals(classEntry)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
