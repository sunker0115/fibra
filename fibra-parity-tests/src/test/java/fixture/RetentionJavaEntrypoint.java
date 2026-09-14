package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import reactor.core.publisher.Mono;

/** descriptor 与入口一起从临时 JAR 的真实插件 loader 定义。 */
public final class RetentionJavaEntrypoint implements PluginEntrypoint<Void> {
    public record Descriptor(String label) { }

    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("retention", Void.class, () -> (context, config) -> {
            var kind = ContributionKind.local("retention", Descriptor.class, String.class, String.class);
            return context.services().require(ContributionServices.REGISTRAR)
                .register(context, kind, "retention", "value", new Descriptor("retention"),
                    (invocation, input) -> Mono.just(input)).then();
        }).require(ContributionServices.REGISTRAR).build();
    }
}
