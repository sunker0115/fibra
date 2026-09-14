package com.sstlfsj.fibra.example.dependency;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.TargetSaveState;
import com.sstlfsj.fibra.runtime.java.JavaPluginRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDependencyScenarioIT {
    @Test
    void savesProviderDisableUntilTheRequiredConsumerCanRecover(@TempDir Path work)
        throws Exception {
        var contractV1 = artifact("fibra.example.contract.v1");
        var providerV1 = artifact("fibra.example.provider.v1");
        var providerV11 = artifact("fibra.example.provider.v11");
        var consumer = artifact("fibra.example.consumer");

        try (var jar = new JarFile(contractV1.toFile())) {
            assertNotNull(jar.getEntry("META-INF/fibra/plugin.yaml"));
            assertNotNull(jar.getEntry(
                "example/fibra/shipping/ShippingRateService.class"));
        }
        try (var jar = new JarFile(providerV1.toFile())) {
            assertNull(jar.getEntry(
                "example/fibra/shipping/ShippingRateService.class"));
        }
        try (var jar = new JarFile(consumer.toFile())) {
            assertNotNull(jar.getEntry("META-INF/fibra/plugin.yaml"));
            assertNull(jar.getEntry(
                "example/fibra/shipping/ShippingRateService.class"));
        }

        try (var scenario = PluginDependencyScenario.open(
            contractV1, providerV1, consumer, work)) {
            var initial = scenario.view();
            assertEquals(PluginInstanceState.ACTIVE,
                state(initial, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.ACTIVE,
                state(initial, PluginDependencyScenario.CONSUMER_INSTANCE));
            assertQuote(scenario.projectedQuote(), 1_200, "1.0");

            scenario.upgradeProvider(providerV11, "1.1.0");
            assertQuote(scenario.projectedQuote(), 0, "1.1");

            var failure = assertThrows(EngineChangeException.class,
                scenario::disableProvider);
            var pending = failure.view();
            assertEquals(TargetSaveState.SAVED, failure.targetSaveState());
            assertFalse(pending.engineDiagnostics().targetSatisfied());
            assertFalse(pending.engine().desiredGraph().plugins()
                .get(PluginDependencyScenario.PROVIDER_INSTANCE).enabled());
            assertEquals(PluginInstanceState.DISPOSED,
                state(pending, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.PENDING,
                state(pending, PluginDependencyScenario.CONSUMER_INSTANCE));
            assertEquals(PluginInstanceState.PENDING,
                state(pending, PluginDependencyScenario.PROJECTION_INSTANCE));
            assertFalse(pending.engine().instances()
                .get(PluginDependencyScenario.CONSUMER_INSTANCE).requirementSatisfied());
            assertThrows(IllegalStateException.class, scenario::projectedQuote);

            scenario.enableProvider();
            var recovered = scenario.view();
            assertTrue(recovered.engineDiagnostics().targetSatisfied());
            assertEquals(PluginInstanceState.ACTIVE,
                state(recovered, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.ACTIVE,
                state(recovered, PluginDependencyScenario.CONSUMER_INSTANCE));
            assertEquals(PluginInstanceState.ACTIVE,
                state(recovered, PluginDependencyScenario.PROJECTION_INSTANCE));
            assertQuote(scenario.projectedQuote(), 0, "1.1");
        }
    }

    @Test
    void preservesCompatibleProvidersAndRejectsAnIncompatibleContract(
        @TempDir Path work) {
        var contractV1 = artifact("fibra.example.contract.v1");
        var contractV2 = artifact("fibra.example.contract.v2");
        var providerV1 = artifact("fibra.example.provider.v1");
        var providerV11 = artifact("fibra.example.provider.v11");
        var consumer = artifact("fibra.example.consumer");

        try (var scenario = PluginDependencyScenario.open(
            contractV1, providerV1, consumer, work)) {
            scenario.upgradeProvider(providerV11, "1.1.0");
            assertQuote(scenario.projectedQuote(), 0, "1.1");

            var beforeRejectedUpgrade = scenario.view();
            var failure = assertThrows(EngineChangeException.class,
                () -> scenario.upgradeContract(contractV2, "2.0.0"));
            var rejected = failure.view();
            assertEquals(TargetSaveState.NOT_SAVED, failure.targetSaveState());
            assertEquals(selectedArtifactRevisions(beforeRejectedUpgrade),
                selectedArtifactRevisions(rejected));
            assertEquals(instanceIdentity(beforeRejectedUpgrade,
                    PluginDependencyScenario.PROVIDER_INSTANCE),
                instanceIdentity(rejected, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(instanceIdentity(beforeRejectedUpgrade,
                    PluginDependencyScenario.CONSUMER_INSTANCE),
                instanceIdentity(rejected, PluginDependencyScenario.CONSUMER_INSTANCE));
            assertEquals(loaderResourceIdentity(beforeRejectedUpgrade,
                    PluginDependencyScenario.CONTRACT_ARTIFACT),
                loaderResourceIdentity(rejected, PluginDependencyScenario.CONTRACT_ARTIFACT));
            assertEquals(loaderResourceIdentity(beforeRejectedUpgrade,
                    PluginDependencyScenario.PROVIDER_ARTIFACT),
                loaderResourceIdentity(rejected, PluginDependencyScenario.PROVIDER_ARTIFACT));
            assertEquals(loaderResourceIdentity(beforeRejectedUpgrade,
                    PluginDependencyScenario.CONSUMER_ARTIFACT),
                loaderResourceIdentity(rejected, PluginDependencyScenario.CONSUMER_ARTIFACT));
            assertQuote(scenario.projectedQuote(), 0, "1.1");

            scenario.stopInDependencyOrder();
            var stopped = scenario.view();
            assertEquals(PluginInstanceState.DISPOSED,
                state(stopped, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.DISPOSED,
                state(stopped, PluginDependencyScenario.CONSUMER_INSTANCE));

            scenario.startInDependencyOrder();
            var restarted = scenario.view();
            assertEquals(PluginInstanceState.ACTIVE,
                state(restarted, PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.ACTIVE,
                state(restarted, PluginDependencyScenario.CONSUMER_INSTANCE));
            assertQuote(scenario.projectedQuote(), 0, "1.1");
        }
    }

    private static Path artifact(String property) {
        var value = System.getProperty(property);
        assertNotNull(value, property + " must be configured");
        return Path.of(value);
    }

    private static void assertQuote(CheckoutQuote quote, int shippingCents,
                                    String policyVersion) {
        assertEquals(6_000, quote.subtotalCents());
        assertEquals(shippingCents, quote.shippingCents());
        assertEquals(6_000 + shippingCents, quote.totalCents());
        assertEquals(policyVersion, quote.policyVersion());
    }

    private static PluginInstanceState state(PublishedView view, String instanceId) {
        var instance = view.engine().instances().get(instanceId);
        return instance == null ? PluginInstanceState.DISPOSED : instance.state();
    }

    private static Map<ArtifactId, String> selectedArtifactRevisions(PublishedView view) {
        return view.engine().artifacts().entrySet().stream().collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey, entry -> entry.getValue().revision()));
    }

    private static long instanceIdentity(PublishedView view, String instanceId) {
        return view.engine().instances().get(instanceId).identity();
    }

    private static String loaderResourceIdentity(PublishedView view, ArtifactId artifactId) {
        return view.engine().runtimes().get(JavaPluginRuntimeAdapter.RUNTIME_ID).resources().stream()
            .filter(resource -> resource.artifact().id().equals(artifactId))
            .findFirst().orElseThrow().identity();
    }
}
