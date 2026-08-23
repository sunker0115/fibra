package example.fibra.provider;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import example.fibra.provider.api.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/** 从真实插件 JAR Manifest 暴露当前 provider 制品版本。 */
@Extension
public final class ProviderEntrypoint implements VoidFibraPluginEntrypoint {
    private static final ServiceKey<String> VERSION =
        ServiceKey.of("example.provider.version", String.class);

    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            var version = getClass().getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("plugin JAR has no Implementation-Version");
            }
            context.provide(VERSION, version);
            var greeting = context.root().provide(Greeting.KEY, () -> "provider-" + version);
            return Mono.just(greeting);
        };
    }
}
