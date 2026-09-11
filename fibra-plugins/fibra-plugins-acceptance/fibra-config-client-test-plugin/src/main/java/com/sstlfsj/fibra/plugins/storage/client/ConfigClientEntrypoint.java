package com.sstlfsj.fibra.plugins.storage.client;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigClientEntrypoint implements PluginEntrypoint<ConfigClientConfig> {
    public static final String LOCAL_NAME = "config";

    @Override
    public PluginDefinition<ConfigClientConfig> definition() {
        return PluginDefinition.builder("config-client-test", ConfigClientConfig.class,
                ConfigClientPlugin::new)
            .require(StorageServices.CONFIG_STORE)
            .require(ContributionServices.REGISTRAR)
            .build();
    }

    private static final class ConfigClientPlugin implements Plugin<ConfigClientConfig> {
        private final List<ConfigChange> changes = new ArrayList<>();

        @Override
        public Mono<Void> start(Context context, ConfigClientConfig config) {
            var instanceId = context.plugins().current().orElseThrow().id();
            context.services().reference(StorageServices.CONFIG_STORE)
                .invoke((invocation, store) -> store.subscribe(invocation, change -> {
                    synchronized (changes) {
                        changes.add(change);
                    }
                    appendEvent(config, change);
                }));
            return context.services().require(ContributionServices.REGISTRAR)
                .register(context, ToolContributions.KIND, instanceId, LOCAL_NAME,
                    descriptor(), this::handle)
                .then();
        }

        private static void appendEvent(ConfigClientConfig config, ConfigChange change) {
            if (config.eventLog() == null || config.eventLog().isBlank()) return;
            try {
                var path = Path.of(config.eventLog());
                Files.createDirectories(path.getParent());
                Files.writeString(path, change.revision() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot record acceptance event", failure);
            }
        }

        private Mono<ToolResult> handle(InvocationContext call, ToolRequest request) {
            return Mono.defer(() -> {
                requireActive(request);
                var operation = string(request.arguments(), "operation");
                var store = call.withCancellation(request.cancellation())
                    .service(StorageServices.CONFIG_STORE);
                Mono<ConfigDocument> action = switch (operation) {
                    case "load" -> Mono.fromSupplier(() -> store.invoke(
                        (invocation, service) -> service.load(invocation)));
                    case "put" -> store.invoke((invocation, service) -> service.put(invocation,
                        string(request.arguments(), "key"), value(request.arguments(), "value")));
                    case "remove" -> store.invoke((invocation, service) -> service.remove(invocation,
                        string(request.arguments(), "key")));
                    default -> Mono.error(invalid("unsupported operation " + operation));
                };
                return action.map(document -> {
                    requireActive(request);
                    return result(document);
                });
            });
        }

        private ToolResult result(ConfigDocument document) {
            var documentValue = new LinkedHashMap<String, Object>();
            documentValue.put("revision", document.revision());
            documentValue.put("values", document.values());
            var eventValues = new ArrayList<Map<String, Object>>();
            synchronized (changes) {
                changes.forEach(change -> eventValues.add(change(change)));
            }
            return ToolResult.structured(LiteralValue.of(Map.of(
                "document", documentValue,
                "events", eventValues)));
        }

        private static Map<String, Object> change(ConfigChange change) {
            var value = new LinkedHashMap<String, Object>();
            value.put("revision", change.revision());
            value.put("key", change.key());
            value.put("operation", change.operation().name());
            value.put("value", change.value());
            return value;
        }
    }

    private static ToolDescriptor descriptor() {
        var input = object(Map.of(
            "type", "object",
            "required", List.of("operation"),
            "properties", Map.of(
                "operation", Map.of("type", "string"),
                "key", Map.of("type", "string"),
                "value", Map.of())));
        var output = object(Map.of("type", "object"));
        return new ToolDescriptor("Config storage acceptance client",
            "Reads, writes and reports observed ConfigStore changes", input, output);
    }

    private static LiteralValue.ObjectValue object(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }

    private static String string(LiteralValue.ObjectValue arguments, String name) {
        var value = arguments.values().get(name);
        if (value instanceof LiteralValue.StringValue text && !text.value().isBlank()) {
            return text.value();
        }
        throw invalid(name + " must be a non-blank string");
    }

    private static LiteralValue value(LiteralValue.ObjectValue arguments, String name) {
        var value = arguments.values().get(name);
        if (value == null) throw invalid(name + " is required");
        return value;
    }

    private static ToolException invalid(String message) {
        return new ToolException(ToolFailureCode.INVALID_ARGUMENT, message);
    }

    private static void requireActive(ToolRequest request) {
        if (request.cancellation().isCancelled()) {
            throw new ToolException(ToolFailureCode.ABORTED, "config operation was cancelled");
        }
    }
}
