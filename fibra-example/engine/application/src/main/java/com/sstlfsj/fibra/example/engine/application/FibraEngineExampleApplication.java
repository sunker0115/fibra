package com.sstlfsj.fibra.example.engine.application;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.engine.FibraEngine;

import java.nio.file.Path;

/** 通过 FibraEngine 的部署事务完成多插件与配置的关联升级。 */
public final class FibraEngineExampleApplication {
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    private FibraEngineExampleApplication() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                "usage: FibraEngineExampleApplication <plugins-directory> <config-file> "
                    + "<deployment-v1.zip> <deployment-v2.zip>");
        }

        try (var engine = FibraEngine.builder(Path.of(arguments[0]),
            Path.of(arguments[1])).build()) {
            engine.start();
            engine.applyDeployment(Path.of(arguments[2]));
            var root = engine.root();
            root.logger().info("Fibra example provider started at version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));

            engine.applyDeployment(Path.of(arguments[3]));
            root.logger().info("Fibra example provider updated to version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));
        }
    }
}
