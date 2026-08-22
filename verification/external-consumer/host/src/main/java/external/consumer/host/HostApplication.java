package external.consumer.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;

public final class HostApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostApplication.class);
    private static final String PROVIDER_PLUGIN_ID = "external-provider-plugin";
    private static final String CONSUMER_PLUGIN_ID = "external-consumer-plugin";
    private static final Set<String> EXPECTED_PLUGIN_IDS =
        Set.of(PROVIDER_PLUGIN_ID, CONSUMER_PLUGIN_ID);
    private static final ServiceKey<String> PROVIDER_STATUS =
        ServiceKey.of("external.consumer.provider.status", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    private HostApplication() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("usage: HostApplication <plugins-directory>");
        }

        try (var root = FibraRuntime.create()) {
            try (var loader = new FibraPluginLoader(root, Path.of(arguments[0]))) {
                assertPluginIds(loader.loadPlugins(), EXPECTED_PLUGIN_IDS, "load");

                loader.startPlugins();
                assertActive(loader, PROVIDER_PLUGIN_ID);
                assertActive(loader, CONSUMER_PLUGIN_ID);
                assertServices(root);

                loader.stopPlugin(PROVIDER_PLUGIN_ID);
                assertStopped(loader, PROVIDER_PLUGIN_ID);
                assertStopped(loader, CONSUMER_PLUGIN_ID);
                assertUnavailable(root);
                assertPluginIds(loader.pluginIds(), EXPECTED_PLUGIN_IDS, "stop provider");

                loader.startPlugin(CONSUMER_PLUGIN_ID);
                assertActive(loader, PROVIDER_PLUGIN_ID);
                assertActive(loader, CONSUMER_PLUGIN_ID);
                assertServices(root);

                if (!loader.unloadPlugin(PROVIDER_PLUGIN_ID)) {
                    throw new IllegalStateException("provider plugin unload returned false");
                }
                assertPluginIds(loader.pluginIds(), Set.of(), "unload provider");
                assertStopped(loader, PROVIDER_PLUGIN_ID);
                assertStopped(loader, CONSUMER_PLUGIN_ID);
                assertUnavailable(root);
            }
            LOGGER.info("EXTERNAL_MULTI_PLUGIN_CONSUMER_OK");
        }
    }

    private static void assertPluginIds(Iterable<String> actualIds, Set<String> expectedIds,
                                        String phase) {
        var actual = new java.util.LinkedHashSet<String>();
        actualIds.forEach(actual::add);
        if (!actual.equals(expectedIds)) {
            throw new IllegalStateException("unexpected plugin ids after " + phase
                + ": expected=" + expectedIds + ", actual=" + actual);
        }
    }

    private static void assertActive(FibraPluginLoader loader, String pluginId) {
        var fibra = loader.fibra(pluginId)
            .orElseThrow(() -> new IllegalStateException("missing Fibra for " + pluginId));
        if (fibra.state() != FibraState.ACTIVE) {
            throw new IllegalStateException("Fibra is not active for " + pluginId
                + ": " + fibra.state());
        }
    }

    private static void assertStopped(FibraPluginLoader loader, String pluginId) {
        if (loader.fibra(pluginId).isPresent()) {
            throw new IllegalStateException("Fibra still exists for " + pluginId);
        }
    }

    private static void assertServices(Context root) {
        if (!"provider-ready".equals(root.get(PROVIDER_STATUS))) {
            throw new IllegalStateException("provider status lookup failed");
        }
        if (!"consumer->provider-ready".equals(root.get(CONSUMER_RESULT))) {
            throw new IllegalStateException("consumer result lookup failed");
        }
    }

    private static void assertUnavailable(Context root) {
        if (root.get(PROVIDER_STATUS, false) != null) {
            throw new IllegalStateException("provider status was not disposed");
        }
        if (root.get(CONSUMER_RESULT, false) != null) {
            throw new IllegalStateException("consumer result was not disposed");
        }
    }
}
