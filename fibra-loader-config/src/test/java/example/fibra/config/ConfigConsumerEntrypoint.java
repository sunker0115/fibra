package example.fibra.config;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ConfigConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    private static final ServiceKey<String> SOURCE =
        ServiceKey.of("source.value", String.class);
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("consumer.result", String.class);

    @Override
    public PluginDescriptor<Void> descriptor(String entryId) {
        return PluginDescriptor.<Void>builder(entryId).provide(RESULT).build();
    }

    @Override
    public Plugin<Void> create(String entryId) {
        return (context, ignored) -> Mono.just(
            context.provide(RESULT, context.get(SOURCE) + ':' + entryId));
    }
}
