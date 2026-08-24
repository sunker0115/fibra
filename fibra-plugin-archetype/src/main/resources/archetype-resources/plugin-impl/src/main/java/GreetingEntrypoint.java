package ${package}.plugin;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import ${package}.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class GreetingEntrypoint implements FibraPluginEntrypoint<GreetingConfig> {
    @Override
    public Class<GreetingConfig> configType() {
        return GreetingConfig.class;
    }

    @Override
    public PluginDescriptor<GreetingConfig> descriptor(String entryId) {
        return PluginDescriptor.<GreetingConfig>builder(entryId)
            .provide(Greeting.KEY)
            .build();
    }

    @Override
    public Plugin<GreetingConfig> create(String entryId) {
        return (context, config) -> Mono.just(context.provide(Greeting.KEY,
            name -> config.prefix() + ", " + name));
    }
}
