package external.consumer.host;

import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.config.FibraConfigErrorStage;
import com.sstlfsj.fibra.loader.config.FibraConfigException;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.config.FibraConfigPatch;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class HostApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostApplication.class);
    private static final Set<String> EXPECTED_ARTIFACT_IDS = Set.of(
        "external-contract-plugin", "external-provider-plugin", "external-consumer-plugin");
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    private HostApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                "usage: HostApplication <plugins-directory> <config-file>"
                    + " <contract-v2.zip> <provider-v2.zip> <consumer-v2.zip>");
        }
        var pluginsDirectory = Path.of(arguments[0]);
        var configFile = Path.of(arguments[1]);
        var v2Candidates = List.of(Path.of(arguments[2]), Path.of(arguments[3]),
            Path.of(arguments[4]));

        try (var root = FibraRuntime.create();
             var artifacts = new FibraPluginLoader(root, pluginsDirectory);
             var config = FibraConfigLoader.builder(root, artifacts, configFile).build()) {
            assertIds(artifacts.loadArtifacts(), EXPECTED_ARTIFACT_IDS, "artifact load");
            var first = config.load();
            assertResult(config, "consumer-one", "consumer->provider-one");
            assertResult(config, "consumer-two", "consumer->provider-two");
            var providerOneUid = config.resolve("provider-one").orElseThrow().fibra().uid();

            config.update("provider-one",
                FibraConfigPatch.override("provider-one", "external-provider-plugin",
                    Map.of("config", "provider-one-updated")), null, 1);
            assertResult(config, "consumer-one", "consumer->provider-one-updated");
            if (!providerOneUid.equals(
                config.resolve("provider-one").orElseThrow().fibra().uid())) {
                throw new IllegalStateException("config-only update replaced provider Fibra");
            }

            var stable = config.snapshot();
            var bytes = Files.readAllBytes(configFile);
            try {
                config.update("provider-two",
                    FibraConfigPatch.override("provider-two", "external-provider-plugin",
                        Map.of("config", "fail")), null, -1);
                throw new IllegalStateException("failed config update unexpectedly succeeded");
            } catch (FibraConfigException expected) {
                if (expected.stage() != FibraConfigErrorStage.APPLY) {
                    throw new IllegalStateException("unexpected update failure stage", expected);
                }
            }
            if (config.snapshot() != stable || !Arrays.equals(bytes, Files.readAllBytes(configFile))) {
                throw new IllegalStateException("failed update changed snapshot or config file");
            }
            assertResult(config, "consumer-one", "consumer->provider-one-updated");
            assertResult(config, "consumer-two", "consumer->provider-two");
            assertIds(artifacts.artifactIds(), EXPECTED_ARTIFACT_IDS, "config transactions");

            assertIds(artifacts.applyArtifacts(v2Candidates), EXPECTED_ARTIFACT_IDS,
                "batch plugin update");
            assertResult(config, "consumer-one", "consumer->provider-one-updated");
            assertResult(config, "consumer-two", "consumer->provider-two");
            assertInstalledVersion(pluginsDirectory, "external-contract-plugin", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "external-provider-plugin", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "external-consumer-plugin", "2.0.0");
        }
        LOGGER.info("EXTERNAL_CONFIG_LOADER_CONSUMER_OK");
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

    private static void assertResult(FibraConfigLoader loader, String entryId,
                                     String expected) {
        var runtime = loader.resolve(entryId).orElseThrow(() ->
            new IllegalStateException("missing runtime entry " + entryId));
        if (runtime.fibra().state() != FibraState.ACTIVE) {
            throw new IllegalStateException(entryId + " is not ACTIVE: "
                + runtime.fibra().state());
        }
        var actual = runtime.context().get(CONSUMER_RESULT);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(entryId + " result mismatch: " + actual);
        }
    }

    private static void assertIds(Iterable<String> actualIds, Set<String> expectedIds,
                                  String phase) {
        var actual = new java.util.LinkedHashSet<String>();
        actualIds.forEach(actual::add);
        if (!actual.equals(expectedIds)) {
            throw new IllegalStateException("unexpected ids after " + phase
                + ": expected=" + expectedIds + ", actual=" + actual);
        }
    }
}
