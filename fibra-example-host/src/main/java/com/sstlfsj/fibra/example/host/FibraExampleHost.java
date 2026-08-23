package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import java.nio.file.Path;

/** 从配置装载插件树，再用外部候选 JAR 完成一次显式更新。 */
public final class FibraExampleHost {
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    private FibraExampleHost() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                "usage: FibraExampleHost <plugins-directory> <config-file> <candidate-jar>");
        }

        try (Context root = FibraRuntime.create();
             FibraPluginLoader artifacts = new FibraPluginLoader(
                 root, Path.of(arguments[0]));
             FibraConfigLoader config = FibraConfigLoader.builder(
                 root, artifacts, Path.of(arguments[1])).build()) {
            artifacts.loadArtifacts();
            config.load();
            root.logger().info("Fibra example provider started at version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));

            artifacts.reloadArtifact(Path.of(arguments[2]));
            root.logger().info("Fibra example provider updated to version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));
        }
    }
}
