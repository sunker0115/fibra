package verification.host.fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.verification.host.HostVerificationContract;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 真正写入动态 JAR 的 Java 插件入口。 */
public final class DynamicJavaEntrypoint implements PluginEntrypoint<Map> {
    @Override
    public PluginDefinition<Map> definition() {
        return PluginDefinition.builder("java", Map.class, () ->
            (context, config) -> {
                var target = Path.of(required(config, "targetPath"));
                if (!Files.isRegularFile(target)) {
                    return Mono.error(new IllegalStateException(
                        "Java unit started before durable target was saved"));
                }
                try {
                    Files.writeString(Path.of(required(config, "startMarker")),
                        ProcessHandle.current().pid() + ":"
                            + getClass().getClassLoader());
                } catch (IOException failure) {
                    return Mono.error(failure);
                }
                HostVerificationContract.javaStarted();
                var prefix = required(config, "prefix");
                var registrar = context.services().require(
                    ContributionServices.REGISTRAR);
                return registrar.register(context,
                    HostVerificationContract.ECHO_KIND, "java-echo",
                    "java-descriptor",
                    (invocation, input) ->
                        HostVerificationContract.javaReply(prefix, input)).then();
            }).build();
    }

    private static String required(Map<?, ?> values, String name) {
        var value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }
}
