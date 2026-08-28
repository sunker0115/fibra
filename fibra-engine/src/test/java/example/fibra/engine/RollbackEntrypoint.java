package example.fibra.engine;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDescriptor;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

@Extension
public final class RollbackEntrypoint implements FibraPluginEntrypoint<String> {
    @Override
    public Class<String> configType() {
        return String.class;
    }

    @Override
    public PluginDescriptor<String> descriptor(String entryId) {
        return PluginDescriptor.<String>builder(entryId).build();
    }

    @Override
    public Plugin<String> create(String entryId) {
        var attempts = new AtomicInteger();
        return (context, config) -> {
            var attempt = attempts.incrementAndGet();
            if ("fail".equals(config)
                || "rollback-fail".equals(config) && attempt > 1) {
                return Mono.error(new IllegalStateException("configured failure"));
            }
            return Mono.empty();
        };
    }
}
