package example.fibra.plugin;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ConfigurableEntrypoint implements FibraPluginEntrypoint<String> {
    private static final ServiceKey<String> VALUE =
        ServiceKey.of("fixture.value", String.class);
    private boolean used;

    @Override
    public Class<String> configType() {
        return String.class;
    }

    @Override
    public PluginDescriptor<String> descriptor(String entryId) {
        return PluginDescriptor.<String>builder(entryId).provide(VALUE).build();
    }

    @Override
    public Plugin<String> create(String entryId) {
        if (used) {
            throw new IllegalStateException("entrypoint instance was reused");
        }
        used = true;
        return (context, config) -> Mono.just(
            context.provide(VALUE, entryId + ":" + config));
    }
}
