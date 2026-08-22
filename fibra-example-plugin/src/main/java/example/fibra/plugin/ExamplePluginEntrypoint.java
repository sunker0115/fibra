package example.fibra.plugin;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.FibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/** 从真实插件 JAR Manifest 暴露当前制品版本。 */
@Extension
public final class ExamplePluginEntrypoint implements FibraPluginEntrypoint {
    private static final ServiceKey<String> VERSION =
        ServiceKey.of("example.plugin.version", String.class);

    @Override
    public Mono<Disposable> apply(Context context, Void config) {
        var version = getClass().getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("plugin JAR has no Implementation-Version");
        }
        context.provide(VERSION, version);
        return Mono.just(Disposables.noop());
    }
}
