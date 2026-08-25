package verification.distribution.consumer;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;
import verification.distribution.contract.Greeting;
import org.pf4j.Extension;
import reactor.core.publisher.Mono;

@Extension
public final class DistributionConsumerEntrypoint implements VoidFibraPluginEntrypoint {
    @Override
    public Plugin<Void> create(String entryId) {
        return (context, config) -> {
            assertProviderPrivateDependencyIsHidden();
            var greeting = context.get(Greeting.KEY);
            var result = ServiceKey.of("verification.distribution.consumer.result." + entryId,
                String.class);
            return Mono.just(context.provide(result, "consumer->" + greeting.greeting()));
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
