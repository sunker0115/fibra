package external.consumer.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import external.consumer.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalConsumerEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        var greeting = context.get(Greeting.KEY);
        return Mono.just(context.provide(RESULT, "consumer->" + greeting.greeting()));
    }
}
