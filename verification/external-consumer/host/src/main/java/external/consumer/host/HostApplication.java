package external.consumer.host;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class HostApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(HostApplication.class);
    private static final ServiceKey<String> MESSAGE =
        ServiceKey.of("external.consumer.plugin.message", String.class);

    private HostApplication() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("usage: HostApplication <plugins-directory>");
        }

        try (var root = FibraRuntime.create()) {
            try (var loader = new FibraPluginLoader(root, Path.of(arguments[0]))) {
                var pluginIds = loader.loadPlugins();
                if (!pluginIds.equals(java.util.List.of("external-consumer-plugin"))) {
                    throw new IllegalStateException("unexpected plugin ids: " + pluginIds);
                }

                loader.startPlugins();
                if (!"fibra-plugin-ready".equals(root.get(MESSAGE))) {
                    throw new IllegalStateException("external plugin service lookup failed");
                }
            }

            if (root.get(MESSAGE, false) != null) {
                throw new IllegalStateException("external plugin service disposal failed");
            }
            LOGGER.info("EXTERNAL_PLUGIN_CONSUMER_OK");
        }
    }
}
