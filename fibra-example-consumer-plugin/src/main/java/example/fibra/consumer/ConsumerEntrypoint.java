package example.fibra.consumer;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import example.fibra.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/** 通过 PF4J 依赖 ClassLoader 消费 provider 私有服务契约。 */
@Extension
public final class ConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            var greeting = context.get(Greeting.KEY);
            context.provide(RESULT, "consumer->" + greeting.greeting());
            return Mono.just(Disposables.noop());
        };
    }
}
