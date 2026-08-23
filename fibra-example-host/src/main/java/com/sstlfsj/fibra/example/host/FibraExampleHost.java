package com.sstlfsj.fibra.example.host;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.PluginInstanceSpec;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import java.nio.file.Path;

/** 加载一个已安装插件，再用外部候选 JAR 完成一次显式更新。 */
public final class FibraExampleHost {
    private static final String PROVIDER = "fibra-example-provider";
    private static final String CONSUMER = "fibra-example-consumer";
    private static final ServiceKey<String> PROVIDER_VERSION =
        ServiceKey.of("example.provider.version", String.class);
    private static final ServiceKey<String> CONSUMER_RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    private FibraExampleHost() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "usage: FibraExampleHost <plugins-directory> <candidate-jar>");
        }

        try (Context root = FibraRuntime.create();
             FibraPluginLoader loader = new FibraPluginLoader(
                 root, Path.of(arguments[0]))) {
            loader.loadArtifacts();
            loader.mount(instance(root, PROVIDER));
            loader.mount(instance(root, CONSUMER));
            root.logger().info("Fibra example provider started at version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));

            loader.reloadArtifact(Path.of(arguments[1]));
            root.logger().info("Fibra example provider updated to version {} with result {}",
                root.get(PROVIDER_VERSION), root.get(CONSUMER_RESULT));
        }
    }

    private static PluginInstanceSpec instance(Context root, String pluginId) {
        return PluginInstanceSpec.builder(pluginId, pluginId)
            .parentContext(root)
            .build();
    }
}
