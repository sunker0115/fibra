package example.fibra.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.loader.pf4j.PluginLifecycleRecorder;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ReplacementEntrypoint implements FibraPluginEntrypoint {
    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        PluginLifecycleRecorder.EVENTS.add("replacement:start");
        context.provide(ServiceKey.of("fixture.value", String.class), "replacement");
        return Mono.just(Disposables.from(
            () -> PluginLifecycleRecorder.EVENTS.add("replacement:stop")));
    }
}
