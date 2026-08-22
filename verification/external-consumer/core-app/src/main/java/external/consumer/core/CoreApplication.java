package external.consumer.core;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoreApplication.class);
    private static final ServiceKey<String> MESSAGE =
        ServiceKey.of("external.consumer.core.message", String.class);

    private CoreApplication() {
    }

    public static void main(String[] arguments) {
        try (var root = FibraRuntime.create()) {
            var registration = root.provide(MESSAGE, "fibra-core-ready");
            if (!"fibra-core-ready".equals(root.get(MESSAGE))) {
                throw new IllegalStateException("fibra-core service lookup failed");
            }

            registration.dispose().block();
            if (root.get(MESSAGE, false) != null) {
                throw new IllegalStateException("fibra-core service disposal failed");
            }

            LOGGER.info("EXTERNAL_CORE_CONSUMER_OK");
        }
    }
}
