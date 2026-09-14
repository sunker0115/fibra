package verification.distribution.plugin;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LifecycleEntrypoint implements PluginEntrypoint<Map> {
    private static final ContributionKind<LifecycleDescriptor, Void, Void> LIFECYCLE =
        ContributionKind.local("fixture.lifecycle", LifecycleDescriptor.class, Void.class, Void.class);

    @Override
    public PluginDefinition<Map> definition() {
        return PluginDefinition.builder("lifecycle", Map.class, () -> (context, config) -> {
            var facts = Facts.of(config, packagedVariant());
            var registrar = context.services().require(ContributionServices.REGISTRAR);
            var instance = context.plugins().current().orElseThrow().id();
            return registrar.register(context, ToolContributions.KIND, instance, "lifecycle",
                descriptor(), (invocation, request) -> invoke(invocation, request, facts))
                .then(registrar.register(context, LIFECYCLE, instance, "descriptor",
                    new LifecycleDescriptor(facts.variant()), (invocation, ignored) -> Mono.empty()))
                .then();
        }).require(ContributionServices.REGISTRAR).build();
    }

    private static Mono<ToolResult> invoke(InvocationContext invocation, ToolRequest request,
                                           Facts facts) {
        var operation = ((LiteralValue.StringValue) request.arguments().values().get("operation")).value();
        if ("facts".equals(operation)) return Mono.just(facts.result());
        if (!"hold".equals(operation)) return Mono.error(new IllegalArgumentException("unsupported operation"));
        return Mono.fromCallable(() -> {
            Files.writeString(Path.of(facts.text("holdEntered")), "entered");
            while (Files.notExists(Path.of(facts.text("release")))) Thread.sleep(10);
            return facts.result();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static ToolDescriptor descriptor() {
        return new ToolDescriptor("Lifecycle", "External lifecycle fixture",
            object(Map.of("type", "object")), object(Map.of("type", "object")));
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }

    private static String packagedVariant() {
        try (var input = LifecycleEntrypoint.class.getResourceAsStream("/lifecycle-variant.txt")) {
            if (input == null) throw new IllegalStateException("missing lifecycle variant");
            return new String(input.readAllBytes()).trim();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read lifecycle variant", failure);
        }
    }

    private record LifecycleDescriptor(String variant) {
    }

    private record Facts(String variant, Map<String, Object> config) {
        private static Facts of(Map value, String variant) {
            var raw = (Map<?, ?>) value;
            var config = new LinkedHashMap<String, Object>();
            raw.forEach((key, current) -> config.put((String) key, current));
            return new Facts(variant, Map.copyOf(config));
        }

        private String text(String key) {
            var value = config.get(key);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(key + " must be a non-blank string");
            }
            return text;
        }

        private ToolResult result() {
            return ToolResult.structured(LiteralValue.of(Map.of("variant", variant, "config", config)));
        }
    }
}
