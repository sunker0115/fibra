package com.sstlfsj.fibra.example.engine.application;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.engine.FibraDeploymentException;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibraEngineExampleApplicationIT {
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
    void runsFiniteApplicationAgainstTwoRealDeployments(@TempDir Path work) throws Exception {
        var artifacts = artifacts();
        var deploymentV1 = deployment(work.resolve("deployment-v1.zip"), "1.0.0",
            List.of(
                artifacts.resolve("fibra-example-engine-consumer-1.0.0.zip"),
                artifacts.resolve("fibra-example-engine-contract-1.0.0.zip"),
                artifacts.resolve("fibra-example-engine-provider-1.0.0.zip")));
        var deploymentV2 = deployment(work.resolve("deployment-v2.zip"), "2.0.0",
            List.of(
                artifacts.resolve("fibra-example-engine-consumer-2.0.0.zip"),
                artifacts.resolve("fibra-example-engine-contract-2.0.0.zip"),
                artifacts.resolve("fibra-example-engine-provider-2.0.0.zip")));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");

        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        var application = Path.of(System.getProperty("fibra.example.engine.application.jar"));
        var process = new ProcessBuilder(javaExecutable.toString(), "-jar", application.toString(),
            plugins.toString(), config.toString(), deploymentV1.toString(),
            deploymentV2.toString()).redirectErrorStream(true).start();
        var finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "executable example application did not finish");
        var output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("consumer->provider-1.0.0"), output);
        assertTrue(output.contains("consumer->provider-2.0.0"), output);
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-engine-contract")));
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-engine-provider")));
        assertEquals("2.0.0", installedVersion(plugins.resolve("fibra-example-engine-consumer")));
    }

    @Test
    void verifiesIsolationJointUpgradeAndFailedDeploymentRollback(@TempDir Path work)
        throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(CONTRACT_TYPE));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PROVIDER_ENTRYPOINT));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(CONSUMER_ENTRYPOINT));

        var artifacts = artifacts();
        var contractV1 = artifacts.resolve("fibra-example-engine-contract-1.0.0.zip");
        var providerV1 = artifacts.resolve("fibra-example-engine-provider-1.0.0.zip");
        var consumerV1 = artifacts.resolve("fibra-example-engine-consumer-1.0.0.zip");
        assertTrue(mainJarContains(contractV1, "example/fibra/contract/Greeting.class"));
        assertFalse(mainJarContains(providerV1, "example/fibra/contract/Greeting.class"));
        assertFalse(mainJarContains(consumerV1, "example/fibra/contract/Greeting.class"));
        assertEquals("fibra-example-engine-contract@>=1.0.0 & <2.0.0",
            descriptor(consumerV1).getProperty("plugin.dependencies"));

        var deploymentV1 = deployment(work.resolve("deployment-v1.zip"), "1.0.0",
            List.of(consumerV1, contractV1, providerV1));
        var deploymentV2 = deployment(work.resolve("deployment-v2.zip"), "2.0.0",
            List.of(
                artifacts.resolve("fibra-example-engine-consumer-2.0.0.zip"),
                artifacts.resolve("fibra-example-engine-contract-2.0.0.zip"),
                artifacts.resolve("fibra-example-engine-provider-2.0.0.zip")));
        var broken = deployment(work.resolve("deployment-broken.zip"), "3.0.0",
            List.of(artifacts.resolve("fibra-example-engine-provider-3.0.0.zip")));
        var plugins = Files.createDirectory(work.resolve("plugins"));
        var config = Files.writeString(work.resolve("fibra.yaml"), "[]\n");

        try (var engine = FibraEngine.builder(plugins, config).build()) {
            engine.start();
            engine.applyDeployment(deploymentV1);
            assertEquals("1.0.0", engine.root().get(PROVIDER_VERSION));
            assertEquals("consumer->provider-1.0.0", engine.root().get(CONSUMER_RESULT));

            engine.applyDeployment(deploymentV2);
            assertEquals("2.0.0", engine.root().get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", engine.root().get(CONSUMER_RESULT));

            engine.applyDeployment(deploymentV1);
            assertEquals("1.0.0", engine.root().get(PROVIDER_VERSION));
            assertEquals("consumer->provider-1.0.0", engine.root().get(CONSUMER_RESULT));

            engine.applyDeployment(deploymentV2);
            assertEquals("2.0.0", engine.root().get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", engine.root().get(CONSUMER_RESULT));

            assertThrows(FibraDeploymentException.class,
                () -> engine.applyDeployment(broken));
            assertEquals("2.0.0", engine.root().get(PROVIDER_VERSION));
            assertEquals("consumer->provider-2.0.0", engine.root().get(CONSUMER_RESULT));
            assertEquals("2.0.0",
                installedVersion(plugins.resolve("fibra-example-engine-provider")));
        }
    }

    private static Path deployment(Path target, String version, List<Path> plugins)
        throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        var descriptor = new StringBuilder("deployment.id=fibra-example\n")
            .append("deployment.version=").append(version).append('\n')
            .append("config.path=config/fibra.yaml\n");
        var sorted = plugins.stream().sorted().toList();
        for (int index = 0; index < sorted.size(); index++) {
            var name = "plugins/" + sorted.get(index).getFileName();
            descriptor.append("plugin.").append(index).append('=').append(name).append('\n');
            entries.put(name, Files.readAllBytes(sorted.get(index)));
        }
        entries.put("config/fibra.yaml",
            Files.readAllBytes(Path.of(System.getProperty("fibra.example.config"))));
        entries.put("deployment.properties", descriptor.toString()
            .getBytes(StandardCharsets.ISO_8859_1));
        var checksums = new StringBuilder();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            checksums.append(sha(entry.getValue())).append("  ")
                .append(entry.getKey()).append('\n'));
        entries.put("checksums.sha256", checksums.toString()
            .getBytes(StandardCharsets.UTF_8));
        try (var output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return target;
    }

    private static Path artifacts() {
        return Path.of(System.getProperty("fibra.example.artifacts"));
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

    private static boolean mainJarContains(Path pluginZip, String classEntry)
        throws Exception {
        try (var zip = new ZipFile(pluginZip.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().contains("/lib/")
                    || !entry.getName().endsWith(".jar")) {
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

    private static String sha(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
