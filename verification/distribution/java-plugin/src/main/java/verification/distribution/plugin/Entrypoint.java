package verification.distribution.plugin;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.StorageServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public final class Entrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("external", Void.class,
            () -> (context, config) -> context.services()
                .require(ContributionServices.REGISTRAR)
                .register(context, ToolContributions.KIND,
                    context.plugins().current().orElseThrow().id(), "config",
                    descriptor(), Entrypoint::invoke)
                .then())
            .require(ContributionServices.REGISTRAR)
            .require(StorageServices.CONFIG_STORE)
            .build();
    }

    private static Mono<ToolResult> invoke(InvocationContext invocation, ToolRequest request) {
        return Mono.defer(() -> {
            var operation = text(request.arguments().values().get("operation"), "operation");
            var store = invocation.withCancellation(request.cancellation())
                .service(StorageServices.CONFIG_STORE);
            Mono<ConfigDocument> result = switch (operation) {
                case "load" -> Mono.fromSupplier(() -> store.invoke(
                    (caller, service) -> service.load(caller)));
                case "put" -> store.invoke((caller, service) -> service.put(caller,
                    text(request.arguments().values().get("key"), "key"),
                    required(request.arguments().values().get("value"), "value")));
                default -> Mono.error(new ToolException(ToolFailureCode.INVALID_ARGUMENT,
                    "unsupported operation " + operation));
            };
            return result.map(document -> ToolResult.structured(LiteralValue.of(Map.of(
                "revision", document.revision(), "values", document.values()))));
        });
    }

    private static LiteralValue required(LiteralValue value, String name) {
        if (value == null) {
            throw new ToolException(ToolFailureCode.INVALID_ARGUMENT, name + " is required");
        }
        return value;
    }

    private static String text(LiteralValue value, String name) {
        if (value instanceof LiteralValue.StringValue text && !text.value().isBlank()) {
            return text.value();
        }
        throw new ToolException(ToolFailureCode.INVALID_ARGUMENT,
            name + " must be a non-blank string");
    }

    private static ToolDescriptor descriptor() {
        return new ToolDescriptor("Configuration", "Read and write the shared configuration store",
            object(Map.of("type", "object", "required", List.of("operation"))),
            object(Map.of("type", "object")));
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }
}
