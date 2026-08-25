package example.fibra.provider;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import example.fibra.contract.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/** 从标准插件包主 JAR 暴露当前 provider 制品版本。 */
@Extension
public final class ProviderEntrypoint implements FibraPluginEntrypoint<ProviderConfig> {
    private static final ServiceKey<String> VERSION =
        ServiceKey.of("example.provider.version", String.class);

    @Override
    public Class<ProviderConfig> configType() {
        return ProviderConfig.class;
    }

    @Override
    public PluginDescriptor<ProviderConfig> descriptor(String entryId) {
        return PluginDescriptor.<ProviderConfig>builder(entryId)
            .provide(VERSION)
            .provide(Greeting.KEY)
            .build();
    }

    @Override
    public Plugin<ProviderConfig> create(String entryId) {
        return (context, config) -> {
            var version = getClass().getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("plugin main JAR has no Implementation-Version");
            }
            context.provide(VERSION, version);
            var greeting = context.provide(Greeting.KEY,
                () -> config.prefix() + "-" + version);
            return Mono.just(greeting);
        };
    }
}
