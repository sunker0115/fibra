package example.fibra.config;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class TypedConfigEntrypoint implements FibraPluginEntrypoint<ConfigValue> {
    private static final ServiceKey<String> VALUE =
        ServiceKey.of("fixture.value", String.class);

    @Override
    public Class<ConfigValue> configType() {
        return ConfigValue.class;
    }

    @Override
    public PluginDescriptor<ConfigValue> descriptor(String entryId) {
        return PluginDescriptor.<ConfigValue>builder(entryId).provide(VALUE).build();
    }

    @Override
    public Plugin<ConfigValue> create(String entryId) {
        return (context, config) -> Mono.just(
            context.provide(VALUE, entryId + ':' + config.value()));
    }
}
