package example.fibra.config;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Extension
public final class ConfigLoaderEntrypoint implements FibraPluginEntrypoint<String> {
    private static final ServiceKey<String> VALUE =
        ServiceKey.of("fixture.value", String.class);
    private static final ConcurrentHashMap<String, AtomicInteger> LIFETIME_STARTS =
        new ConcurrentHashMap<>();

    @Override
    public Class<String> configType() {
        return String.class;
    }

    @Override
    public PluginDescriptor<String> descriptor(String entryId) {
        return PluginDescriptor.<String>builder(entryId).provide(VALUE).build();
    }

    @Override
    public Plugin<String> create(String entryId) {
        var starts = new AtomicInteger();
        return (context, config) -> {
            var attempt = starts.incrementAndGet();
            var lifetimeAttempt = LIFETIME_STARTS.computeIfAbsent(entryId,
                ignored -> new AtomicInteger()).incrementAndGet();
            if ("fail".equals(config)
                || "rollback-fail".equals(config) && attempt > 1
                || "restore-fail".equals(config) && lifetimeAttempt > 1) {
                return Mono.error(new IllegalStateException("configured failure"));
            }
            return Mono.just(context.provide(VALUE, entryId + ':' + config));
        };
    }
}
