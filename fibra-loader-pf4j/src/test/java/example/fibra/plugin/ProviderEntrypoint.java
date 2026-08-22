package example.fibra.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.loader.pf4j.PluginLifecycleRecorder;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ProviderEntrypoint implements FibraPluginEntrypoint {
    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        PluginLifecycleRecorder.EVENTS.add("provider:start");
        return Mono.just(Disposables.from(
            () -> PluginLifecycleRecorder.EVENTS.add("provider:stop")));
    }
}
