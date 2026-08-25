package verification.distribution.springboot;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.engine.FibraEngine;
import com.sstlfsj.fibra.spring.FibraServiceBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public final class DistributionSpringBootApplication {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(DistributionSpringBootApplication.class);

    private DistributionSpringBootApplication() {
    }

    public static void main(String[] arguments) {
        FibraEngine engine;
        try (var application = SpringApplication.run(
            DistributionSpringBootApplication.class, arguments)) {
            engine = application.getBean(FibraEngine.class);
            var root = application.getBean(Context.class);
            application.getBean(FibraServiceBridge.class);
            if (!engine.isRunning()) {
                throw new IllegalStateException("FibraEngine was not started by Spring lifecycle");
            }
            if (root != engine.root()) {
                throw new IllegalStateException("Spring did not expose the engine root Context");
            }
        }
        if (engine.isRunning()) {
            throw new IllegalStateException("FibraEngine was not closed with Spring context");
        }
        LOGGER.info("FIBRA_DISTRIBUTION_SPRING_BOOT_OK");
    }
}
