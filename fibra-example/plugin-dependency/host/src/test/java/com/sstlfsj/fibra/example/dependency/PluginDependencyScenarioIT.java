package com.sstlfsj.fibra.example.dependency;

import com.sstlfsj.fibra.PluginInstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginDependencyScenarioIT {
    @Test
    void rejectsProviderOnlyDisableAndRetainsTheActiveGeneration(@TempDir Path work)
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
            assertEquals(PluginInstanceState.ACTIVE,
                scenario.state(PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.ACTIVE,
                scenario.state(PluginDependencyScenario.CONSUMER_INSTANCE));
            assertQuote(scenario.projectedQuote(), 1_200, "1.0");

            var beforeRejectedDisable = scenario.generationRevision();
            assertThrows(RuntimeException.class, scenario::disableProvider);
            assertEquals(beforeRejectedDisable, scenario.generationRevision());
            assertQuote(scenario.projectedQuote(), 1_200, "1.0");

            scenario.upgradeProvider(providerV11, "1.1.0");
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

            var beforeRejectedUpgrade = scenario.generationRevision();
            assertThrows(RuntimeException.class,
                () -> scenario.upgradeContract(contractV2, "2.0.0"));
            assertEquals(beforeRejectedUpgrade, scenario.generationRevision());
            assertQuote(scenario.projectedQuote(), 0, "1.1");

            scenario.stopInDependencyOrder();
            assertEquals(PluginInstanceState.DISPOSED,
                scenario.state(PluginDependencyScenario.PROVIDER_INSTANCE));
            assertEquals(PluginInstanceState.DISPOSED,
                scenario.state(PluginDependencyScenario.CONSUMER_INSTANCE));

            scenario.startInDependencyOrder();
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
}
