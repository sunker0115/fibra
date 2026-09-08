package ${package};

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;

public final class FibraPluginEntrypoint implements PluginEntrypoint<PluginConfig> {
    @Override
    public PluginDefinition<PluginConfig> definition() {
        return PluginDefinition.builder("${pluginId}", PluginConfig.class,
                () -> (context, config) -> {
                    context.logger().info(config.message());
                    return reactor.core.publisher.Mono.empty();
                })
            .validator(config -> config == null
                ? new PluginConfig("${pluginId} started") : config)
            .build();
    }
}
