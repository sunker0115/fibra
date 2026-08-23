package example.fibra.plugin;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class FixtureEntrypoint implements VoidFibraPluginEntrypoint {
    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            context.provide(ServiceKey.of("fixture.value", String.class), "fixture");
            return Mono.just(Disposables.noop());
        };
    }
}
