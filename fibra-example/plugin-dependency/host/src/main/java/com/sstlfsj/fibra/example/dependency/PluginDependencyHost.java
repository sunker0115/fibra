package com.sstlfsj.fibra.example.dependency;

import com.sstlfsj.fibra.engine.EngineChangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginDependencyHost {
    private static final Logger log = LoggerFactory.getLogger(PluginDependencyHost.class);

    private PluginDependencyHost() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: <shipping-contract-v1.jar> <shipping-rate-v1.jar> "
                    + "<shipping-rate-v11.jar> "
                    + "<checkout-quote-consumer.jar>");
        }
        var storage = Files.createTempDirectory("fibra-plugin-dependency-example-");
        try (var scenario = PluginDependencyScenario.open(
            Path.of(args[0]), Path.of(args[1]), Path.of(args[3]), storage)) {
            log.info("Initial quote: {}", scenario.projectedQuote());
            try {
                scenario.disableProvider();
            } catch (EngineChangeException expected) {
                var view = expected.view();
                log.info("Provider target save state: {}; consumer state: {}; required target satisfied: {}",
                    expected.targetSaveState(),
                    view.engine().instances().get(PluginDependencyScenario.CONSUMER_INSTANCE).state(),
                    view.engineDiagnostics().targetSatisfied());
            }
            scenario.enableProvider();
            log.info("Quote after provider recovery: {}", scenario.projectedQuote());
            scenario.upgradeProvider(Path.of(args[2]), "1.1.0");
            log.info("Quote after compatible upgrade: {}", scenario.projectedQuote());
            scenario.stopInDependencyOrder();
            scenario.startInDependencyOrder();
            log.info("Quote after ordered restart: {}", scenario.projectedQuote());
        }
    }
}
