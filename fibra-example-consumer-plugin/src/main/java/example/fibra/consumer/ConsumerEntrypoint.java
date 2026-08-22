package example.fibra.consumer;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import example.fibra.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/** 通过 PF4J 依赖 ClassLoader 消费 provider 私有服务契约。 */
@Extension
public final class ConsumerEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("example.consumer.result", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        var greeting = context.get(Greeting.KEY);
        context.provide(RESULT, "consumer->" + greeting.greeting());
        return Mono.just(Disposables.noop());
    }
}
