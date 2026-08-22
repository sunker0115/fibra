package external.consumer.provider;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import external.consumer.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalProviderEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> STATUS =
        ServiceKey.of("external.consumer.provider.status", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        context.provide(STATUS, "provider-ready");
        return Mono.just(context.root().provide(Greeting.KEY, () -> "provider-ready"));
    }
}
