package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.ServiceKey;
import reactor.core.publisher.Mono;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 测试通过临时 JAR 以 child loader 载入，不能由宿主 classpath 直接替代。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ContextJavaEntrypoint implements PluginEntrypoint<String> {
    private static final ServiceKey<BiConsumer> CONFIG = ServiceKey.of(
        "conditional-java-config", BiConsumer.class);

    @Override
    public PluginDefinition<String> definition() {
        return PluginDefinition.builder("conditional-java", String.class,
            () -> (context, config) -> {
                context.services().require(CONFIG).accept(getClass().getClassLoader(), config);
                return Mono.empty();
            }).require(CONFIG).build();
    }

    public static final class Stable implements PluginEntrypoint<Void> {
        private static final ServiceKey<Consumer> LOADER = ServiceKey.of(
            "stable-java-loader", Consumer.class);
        private static final ServiceKey<Consumer> CLEANUP = ServiceKey.of(
            "stable-java-cleanup", Consumer.class);

        @Override
        public PluginDefinition<Void> definition() {
            return PluginDefinition.builder("stable-java", Void.class,
                () -> (context, config) -> {
                    var loader = getClass().getClassLoader();
                    context.services().require(LOADER).accept(loader);
                    context.effects().add(() -> Mono.fromRunnable(() ->
                        context.services().require(CLEANUP).accept(loader)));
                    return Mono.empty();
                }).require(LOADER).require(CLEANUP).build();
        }
    }
}
