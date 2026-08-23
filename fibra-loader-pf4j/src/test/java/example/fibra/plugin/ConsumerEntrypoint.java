package example.fibra.plugin;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.loader.pf4j.PluginLifecycleRecorder;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            PluginLifecycleRecorder.EVENTS.add("consumer:start");
            return Mono.just(Disposables.from(
                () -> PluginLifecycleRecorder.EVENTS.add("consumer:stop")));
        };
    }
}
