package external.consumer.host;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.engine.FibraEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class HostApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostApplication.class);
    private HostApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                "usage: HostApplication <plugins-directory> <config-file> <deployment-v2.zip>");
        }
        var pluginsDirectory = Path.of(arguments[0]);
        var configFile = Path.of(arguments[1]);
        var deployment = Path.of(arguments[2]);

        try (var engine = FibraEngine.builder(pluginsDirectory, configFile)
            .requiredEntries(java.util.List.of("consumer-one", "consumer-two",
                "provider-one", "provider-two"))
            .build()) {
            engine.start();
            assertResult(engine, "consumer-one", "consumer->provider-one");
            assertResult(engine, "consumer-two", "consumer->provider-two");

            var result = engine.applyDeployment(deployment);
            if (!result.changedArtifactIds().equals(java.util.List.of(
                "external-consumer-plugin", "external-contract-plugin",
                "external-provider-plugin"))) {
                throw new IllegalStateException("unexpected deployment result: " + result);
            }
            assertResult(engine, "consumer-one", "consumer->provider-one");
            assertResult(engine, "consumer-two", "consumer->provider-two");
            assertInstalledVersion(pluginsDirectory, "external-contract-plugin", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "external-provider-plugin", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "external-consumer-plugin", "2.0.0");
        }
        LOGGER.info("EXTERNAL_ENGINE_CONSUMER_OK");
    }

    private static void assertInstalledVersion(Path pluginsDirectory, String pluginId,
                                               String expected) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(
            pluginsDirectory.resolve(pluginId).resolve("plugin.properties"))) {
            properties.load(input);
        }
        var actual = properties.getProperty("plugin.version");
        if (!expected.equals(actual)) {
            throw new IllegalStateException(pluginId + " version mismatch: " + actual);
        }
    }

    private static void assertResult(FibraEngine engine, String entryId, String expected) {
        var key = ServiceKey.of("external.consumer.plugin.result." + entryId, String.class);
        var actual = engine.root().get(key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("consumer result mismatch for " + entryId
                + ": " + actual);
        }
    }
}
