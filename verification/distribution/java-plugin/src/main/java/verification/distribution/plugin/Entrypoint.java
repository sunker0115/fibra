package verification.distribution.plugin;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import reactor.core.publisher.Mono;

public final class Entrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("external", Void.class,
            () -> (context, config) -> Mono.empty()).build();
    }
}
