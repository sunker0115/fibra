package example.fibra.springboot.provider;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.example.springboot.Greeting;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

/**
 * 以标准 PF4J 插件包提供宿主 {@link Greeting} SPI 的实现。
 *
 * <p>入口类刻意放在非 {@code com.sstlfsj.fibra.*} 包，使其由插件类加载器加载；
 * 而 {@link Greeting} 属于共享前缀，运行时被委派回宿主父加载器，两侧共用同一类型。
 */
@Extension
public final class GreetingProviderEntrypoint implements VoidFibraPluginEntrypoint {
    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            Greeting greeting = name -> "Hello, " + name + " (from " + entryId + ")";
            return Mono.just(context.provide(Greeting.KEY, greeting));
        };
    }
}
