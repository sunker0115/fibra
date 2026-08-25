package verification.distribution.provider;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import verification.distribution.contract.Greeting;
import org.apache.commons.text.StringEscapeUtils;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class DistributionProviderEntrypoint implements FibraPluginEntrypoint<String> {
    @Override
    public Class<String> configType() {
        return String.class;
    }

    @Override
    public PluginDescriptor<String> descriptor(String entryId) {
        return PluginDescriptor.<String>builder(entryId).provide(Greeting.KEY).build();
    }

    @Override
    public Plugin<String> create(String entryId) {
        return (context, config) -> {
            if ("fail".equals(config)) {
                return Mono.error(new IllegalStateException("configured provider failure"));
            }
            return Mono.just(context.provide(Greeting.KEY,
                () -> StringEscapeUtils.escapeJava(config)));
        };
    }
}
