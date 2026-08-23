package external.consumer.plugin;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import external.consumer.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            var greeting = context.get(Greeting.KEY);
            return Mono.just(context.provide(RESULT, "consumer->" + greeting.greeting()));
        };
    }
}
