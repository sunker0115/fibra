package verification.distribution.engine;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.engine.FibraDeploymentException;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.engine.FibraEngineState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class EngineApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineApplication.class);
    private static final long WAIT_TIMEOUT_MILLIS = 10_000;

    private EngineApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                "usage: EngineApplication <plugins-directory> <config-file> "
                    + "<incomplete-v2.zip> <deployment-v2.zip>");
        }
        var pluginsDirectory = Path.of(arguments[0]);
        var configFile = Path.of(arguments[1]);
        var incompleteDeployment = Path.of(arguments[2]);
        var deployment = Path.of(arguments[3]);

        try (var engine = FibraEngine.builder(pluginsDirectory, configFile)
            .requiredEntries(java.util.List.of("consumer-one", "consumer-two",
                "provider-one", "provider-two"))
            .build()) {
            engine.start();
            assertResult(engine, "consumer-one", "consumer->provider-one");
            assertResult(engine, "consumer-two", "consumer->provider-two");

            Files.writeString(configFile, config("provider-one-updated", "provider-two"));
            engine.requestReconcile();
            await(() -> "consumer->provider-one-updated"
                .equals(result(engine, "consumer-one")), "config-only update did not converge");
            assertResult(engine, "consumer-two", "consumer->provider-two");

            Files.writeString(configFile, config("fail", "provider-two"));
            engine.requestReconcile();
            await(() -> engine.status().state() == FibraEngineState.DEGRADED,
                "failed config did not degrade the engine");
            assertResult(engine, "consumer-one", "consumer->provider-one-updated");
            assertResult(engine, "consumer-two", "consumer->provider-two");

            Files.writeString(configFile, config("provider-one-updated", "provider-two"));
            engine.requestReconcile();
            await(() -> engine.status().state() == FibraEngineState.RUNNING
                    && engine.status().failures().isEmpty(),
                "valid config did not recover the engine");

            try {
                engine.applyDeployment(incompleteDeployment);
                throw new IllegalStateException("incomplete associated upgrade was accepted");
            } catch (FibraDeploymentException expected) {
                assertInstalledVersion(pluginsDirectory, "fibra-distribution-contract", "1.0.0");
                assertInstalledVersion(pluginsDirectory, "fibra-distribution-provider", "1.0.0");
                assertInstalledVersion(pluginsDirectory, "fibra-distribution-consumer", "1.0.0");
                assertResult(engine, "consumer-one", "consumer->provider-one-updated");
                assertResult(engine, "consumer-two", "consumer->provider-two");
            }

            var result = engine.applyDeployment(deployment);
            if (!result.changedArtifactIds().equals(java.util.List.of(
                "fibra-distribution-consumer", "fibra-distribution-contract",
                "fibra-distribution-provider"))) {
                throw new IllegalStateException("unexpected deployment result: " + result);
            }
            assertResult(engine, "consumer-one", "consumer->provider-one");
            assertResult(engine, "consumer-two", "consumer->provider-two");
            assertInstalledVersion(pluginsDirectory, "fibra-distribution-contract", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "fibra-distribution-provider", "2.0.0");
            assertInstalledVersion(pluginsDirectory, "fibra-distribution-consumer", "2.0.0");
        }
        LOGGER.info("FIBRA_DISTRIBUTION_ENGINE_OK");
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
        var actual = result(engine, entryId);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("consumer result mismatch for " + entryId
                + ": " + actual);
        }
    }

    private static String result(FibraEngine engine, String entryId) {
        var key = ServiceKey.of("verification.distribution.consumer.result." + entryId, String.class);
        return engine.root().get(key);
    }

    private static String config(String providerOne, String providerTwo) {
        return """
            - id: consumer-one
              name: fibra-distribution-consumer
              inject: [verification.distribution.provider.greeting]
              isolate:
                verification.distribution.provider.greeting: one
            - id: provider-one
              name: fibra-distribution-provider
              isolate:
                verification.distribution.provider.greeting: one
              config: %s
            - id: consumer-two
              name: fibra-distribution-consumer
              inject: [verification.distribution.provider.greeting]
              isolate:
                verification.distribution.provider.greeting: two
            - id: provider-two
              name: fibra-distribution-provider
              isolate:
                verification.distribution.provider.greeting: two
              config: %s
            """.formatted(providerOne, providerTwo);
    }

    private static void await(Check check, String message) throws Exception {
        var deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (check.test()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check {
        boolean test() throws Exception;
    }
}
