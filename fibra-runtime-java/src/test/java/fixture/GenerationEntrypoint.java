package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.ServiceKey;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

public final class GenerationEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        var observer = ServiceKey.of("loader-observer", Consumer.class);
        return PluginDefinition.builder("sample", Void.class, () -> (context, config) -> {
            context.services().require(observer).accept(getClass().getClassLoader());
            return Mono.empty();
        }).require(observer).build();
    }
}
