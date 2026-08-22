package external.consumer.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalPluginEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> MESSAGE =
        ServiceKey.of("external.consumer.plugin.message", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        return Mono.just(context.root().provide(MESSAGE, "fibra-plugin-ready"));
    }
}
