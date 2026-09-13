package verification.distribution.plugin;

import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.cli.api.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandOption;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
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
            () -> (context, config) -> {
                var registrar = context.services().require(ContributionServices.REGISTRAR);
                var instanceId = context.plugins().current().orElseThrow().id();
                return registrar.register(context, ToolContributions.KIND, instanceId, "config",
                    descriptor(), Entrypoint::invoke).then(registrar.register(context,
                    CliCommandContributions.KIND, instanceId, "echo", commandDescriptor(),
                    (invocation, request) -> {
                        var prefix = request.options().getOrDefault("--prefix", "");
                        request.invocation().output().stdout(prefix
                            + String.join(" ", request.arguments()));
                        return Mono.just(CliCommandResult.success());
                    })).then();
            })
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

    private static CliCommandDescriptor commandDescriptor() {
        return new CliCommandDescriptor(List.of("external-cli", "echo"),
            "输出仓外 Java 动态命令。", List.of(new CliCommandOption(List.of("--prefix"),
            "输出前缀。", false, false, List.of("from-"))), "TEXT", List.of());
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }
}
