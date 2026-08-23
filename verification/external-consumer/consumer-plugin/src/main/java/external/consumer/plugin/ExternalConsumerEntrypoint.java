package external.consumer.plugin;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import external.consumer.contract.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class ExternalConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    private static final ServiceKey<String> RESULT =
        ServiceKey.of("external.consumer.plugin.result", String.class);

    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            assertProviderPrivateDependencyIsHidden();
            var greeting = context.get(Greeting.KEY);
            return Mono.just(context.provide(RESULT, "consumer->" + greeting.greeting()));
        };
    }

    private void assertProviderPrivateDependencyIsHidden() {
        try {
            getClass().getClassLoader().loadClass("org.apache.commons.text.StringEscapeUtils");
            throw new IllegalStateException("provider private dependency leaked to consumer");
        } catch (ClassNotFoundException expected) {
            // 每个插件只能看到自己的 lib、显式插件依赖和宿主共享运行时。
        }
    }
}
