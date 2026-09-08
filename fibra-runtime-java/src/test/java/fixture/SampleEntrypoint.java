package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import reactor.core.publisher.Mono;

public final class SampleEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("sample", Void.class,
            () -> (context, config) -> Mono.empty()).build();
    }
}
