package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import java.nio.file.Path;
import java.util.List;

/** 以三包批量事务完成初装、配置装载和关联升级。 */
public final class FibraExampleHost {
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    private FibraExampleHost() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 8) {
            throw new IllegalArgumentException(
                "usage: FibraExampleHost <plugins-directory> <config-file> "
                    + "<contract-v1.zip> <provider-v1.zip> <consumer-v1.zip> "
                    + "<contract-v2.zip> <provider-v2.zip> <consumer-v2.zip>");
        }

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(
                 root, Path.of(arguments[0]));
             FibraConfigLoader config = FibraConfigLoader.builder(
                 root, artifacts, Path.of(arguments[1])).build()) {
            artifacts.loadArtifacts();
            artifacts.applyArtifacts(List.of(Path.of(arguments[2]), Path.of(arguments[3]),
                Path.of(arguments[4])));
            config.load();
            root.logger().info("Fibra example provider started at version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));

            artifacts.applyArtifacts(List.of(Path.of(arguments[5]), Path.of(arguments[6]),
                Path.of(arguments[7])));
            root.logger().info("Fibra example provider updated to version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));
        }
    }
}
