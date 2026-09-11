package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import reactor.core.publisher.Mono;

import java.util.function.BiConsumer;

/** 临时 JAR 的真实入口；宿主提供同一个 contribution kind 及生命周期观察服务。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class DisableJavaEntrypoint implements PluginEntrypoint<Void> {
    private static final ServiceKey<ContributionKind> CONTROL =
        ServiceKey.of("disable-control-kind", ContributionKind.class);
    private static final ServiceKey<BiConsumer> START =
        ServiceKey.of("disable-java-start", BiConsumer.class);
    private static final ServiceKey<BiConsumer> CLEANUP =
        ServiceKey.of("disable-java-cleanup", BiConsumer.class);

    @Override
    public PluginDefinition<Void> definition() {
        return definition("self-java", getClass().getClassLoader(), true);
    }

    private static PluginDefinition<Void> definition(String name, ClassLoader loader, boolean self) {
        return PluginDefinition.builder(name, Void.class, () -> (context, config) -> {
            context.services().require(START).accept(name, loader);
            context.effects().add(() -> Mono.fromRunnable(() ->
                context.services().require(CLEANUP).accept(name, loader)));
            if (!self) return Mono.empty();
            ContributionKind<String, String, String> kind = context.services().require(CONTROL);
            return context.services().require(ContributionServices.REGISTRAR)
                .register(context, kind, name, "control", "Control", (invocation, input) -> {
                    context.plugins().requestDisable();
                    return Mono.just("requested");
                }).then();
        }).require(START).require(CLEANUP).require(CONTROL)
            .require(ContributionServices.REGISTRAR).build();
    }

    public static final class Stable implements PluginEntrypoint<Void> {
        @Override
        public PluginDefinition<Void> definition() {
            return DisableJavaEntrypoint.definition("stable-java", getClass().getClassLoader(), false);
        }
    }
}
