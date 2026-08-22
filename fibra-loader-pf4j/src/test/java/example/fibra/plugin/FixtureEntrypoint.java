package example.fibra.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class FixtureEntrypoint implements FibraPluginEntrypoint {
    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        context.provide(ServiceKey.of("fixture.value", String.class), "fixture");
        return Mono.just(Disposables.noop());
    }
}
