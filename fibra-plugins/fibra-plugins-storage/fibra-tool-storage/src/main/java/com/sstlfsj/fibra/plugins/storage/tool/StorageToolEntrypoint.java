package com.sstlfsj.fibra.plugins.storage.tool;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.InvocationContext;
import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.storage.ConfigChange;
import com.sstlfsj.fibra.plugins.storage.ConfigDocument;
import com.sstlfsj.fibra.plugins.storage.StorageErrorCode;
import com.sstlfsj.fibra.plugins.storage.StorageException;
import com.sstlfsj.fibra.plugins.storage.StorageServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.plugins.tool.ToolResult;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Publishes formal ConfigStore tools through the runtime contribution registrar. */
public final class StorageToolEntrypoint implements PluginEntrypoint<Void> {
    private static final Set<String> NO_ARGUMENTS = Set.of();
    private static final Set<String> PUT_ARGUMENTS = Set.of("key", "value");
    private static final Set<String> REMOVE_ARGUMENTS = Set.of("key");

    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("tool-storage", Void.class, StorageToolPlugin::new)
            .require(StorageServices.CONFIG_STORE)
            .require(ContributionServices.REGISTRAR)
            .build();
    }

    static Map<String, ToolDescriptor> descriptors() {
        var document = schema(List.of("revision", "values"), Map.of(
            "revision", Map.of("type", "integer", "minimum", 0),
            "values", Map.of("type", "object", "additionalProperties", true)));
        var key = Map.of("type", "string", "minLength", 1);
        var change = schema(List.of("revision", "key", "operation", "value"), Map.of(
            "revision", Map.of("type", "integer", "minimum", 1),
            "key", key,
            "operation", Map.of("type", "string", "enum", List.of("PUT", "REMOVED")),
            "value", Map.of()));
        var result = new LinkedHashMap<String, ToolDescriptor>();
        result.put("load", new ToolDescriptor("Load configuration", "Load the current configuration document.",
            schema(List.of(), Map.of()), document));
        result.put("put", new ToolDescriptor("Store configuration value", "Durably store one configuration value.",
            schema(List.of("key", "value"), Map.of("key", key, "value", Map.of())), document));
        result.put("remove", new ToolDescriptor("Remove configuration value", "Durably remove one configuration value.",
            schema(List.of("key"), Map.of("key", key)), document));
        result.put("changes", new ToolDescriptor("Configuration changes", "Read this plugin instance's recent configuration changes.",
            schema(List.of(), Map.of()), schema(List.of("changes", "dropped"), Map.of(
                "changes", Map.of("type", "array", "items", change),
                "dropped", Map.of("type", "boolean")))));
        return Collections.unmodifiableMap(result);
    }

    private static LiteralValue.ObjectValue schema(List<String> required, Map<String, ?> properties) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        return (LiteralValue.ObjectValue) LiteralValue.of(schema);
    }

    private static final class StorageToolPlugin implements Plugin<Void> {
        private final ChangeSnapshot changes = new ChangeSnapshot();

        @Override
        public Mono<Void> start(Context context, Void ignored) {
            var provider = context.plugins().current().orElseThrow(
                () -> new IllegalStateException("storage tools require plugin ownership")).id();
            context.services().reference(StorageServices.CONFIG_STORE).invoke(
                (invocation, store) -> store.subscribe(invocation, changes::add));
            var descriptors = descriptors();
            return context.services().require(ContributionServices.REGISTRAR).registerAll(context, provider, List.of(
                new ContributionBinding<>(ToolContributions.KIND, "load", descriptors.get("load"), this::load),
                new ContributionBinding<>(ToolContributions.KIND, "put", descriptors.get("put"), this::put),
                new ContributionBinding<>(ToolContributions.KIND, "remove", descriptors.get("remove"), this::remove),
                new ContributionBinding<>(ToolContributions.KIND, "changes", descriptors.get("changes"), this::changes)
            ), Disposables.noop()).then();
        }

        private Mono<ToolResult> load(InvocationContext invocation, ToolRequest request) {
            return execute(invocation, request, NO_ARGUMENTS, context -> Mono.fromSupplier(() ->
                context.service(StorageServices.CONFIG_STORE).invoke((serviceContext, store) ->
                    store.load(serviceContext))).map(StorageToolEntrypoint::document));
        }

        private Mono<ToolResult> put(InvocationContext invocation, ToolRequest request) {
            return execute(invocation, request, PUT_ARGUMENTS, context -> {
                var values = request.arguments().values();
                var key = key(values);
                var value = values.get("value");
                if (value == null) throw invalid("value is required");
                return context.service(StorageServices.CONFIG_STORE).invoke((serviceContext, store) ->
                    store.put(serviceContext, key, value)).map(StorageToolEntrypoint::document);
            });
        }

        private Mono<ToolResult> remove(InvocationContext invocation, ToolRequest request) {
            return execute(invocation, request, REMOVE_ARGUMENTS, context -> {
                var key = key(request.arguments().values());
                return context.service(StorageServices.CONFIG_STORE).invoke((serviceContext, store) ->
                    store.remove(serviceContext, key)).map(StorageToolEntrypoint::document);
            });
        }

        private Mono<ToolResult> changes(InvocationContext invocation, ToolRequest request) {
            return execute(invocation, request, NO_ARGUMENTS, context -> Mono.fromSupplier(() ->
                ToolResult.structured(LiteralValue.of(changes.snapshot()))));
        }

        private static Mono<ToolResult> execute(InvocationContext invocation, ToolRequest request,
                                                Set<String> allowed,
                                                java.util.function.Function<InvocationContext,
                                                    Mono<ToolResult>> operation) {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(request, "request");
            return Mono.defer(() -> {
                requireActive(request);
                rejectUnknown(request.arguments().values(), allowed);
                return operation.apply(invocation.withCancellation(request.cancellation()))
                    .map(result -> {
                        requireActive(request);
                        return result;
                    });
            }).onErrorMap(failure -> failure instanceof ToolException ? failure : toolFailure(failure));
        }
    }

    private static ToolResult document(ConfigDocument document) {
        var data = new LinkedHashMap<String, Object>();
        data.put("revision", document.revision());
        data.put("values", document.values());
        return ToolResult.structured(LiteralValue.of(data));
    }

    private static void rejectUnknown(Map<String, LiteralValue> values, Set<String> allowed) {
        var unknown = values.keySet().stream().filter(name -> !allowed.contains(name)).findFirst();
        if (unknown.isPresent()) throw invalid("unknown argument: " + unknown.get());
    }

    private static String key(Map<String, LiteralValue> values) {
        var value = values.get("key");
        if (value instanceof LiteralValue.StringValue text && !text.value().isBlank()) return text.value();
        throw invalid("key must be a non-blank string");
    }

    private static void requireActive(ToolRequest request) {
        if (request.cancellation().isCancelled()) {
            throw new ToolException(ToolFailureCode.ABORTED, "storage operation was cancelled");
        }
    }

    private static ToolException toolFailure(Throwable failure) {
        if (failure instanceof StorageException exception) {
            return new ToolException(switch (exception.code()) {
                case MALFORMED_DOCUMENT, VERSION_MISMATCH, CLOSED, PERSISTENCE_FAILED ->
                    ToolFailureCode.IO_ERROR;
            }, exception.getMessage(), exception);
        }
        if (failure instanceof IllegalArgumentException || failure instanceof ArithmeticException) {
            return invalid(failure.getMessage() == null ? "invalid argument" : failure.getMessage());
        }
        return new ToolException(ToolFailureCode.IO_ERROR, "storage tool failed", failure);
    }

    private static ToolException invalid(String message) {
        return new ToolException(ToolFailureCode.INVALID_ARGUMENT, message);
    }

    private static final class ChangeSnapshot {
        private static final int CAPACITY = 64;
        private final ArrayDeque<ConfigChange> values = new ArrayDeque<>(CAPACITY);
        private boolean dropped;

        synchronized void add(ConfigChange change) {
            if (values.size() == CAPACITY) {
                values.removeFirst();
                dropped = true;
            }
            values.addLast(change);
        }

        synchronized Map<String, Object> snapshot() {
            var changes = new ArrayList<Map<String, Object>>(values.size());
            for (var change : values) {
                var value = new LinkedHashMap<String, Object>();
                value.put("revision", change.revision());
                value.put("key", change.key());
                value.put("operation", change.operation().name());
                value.put("value", change.value());
                changes.add(value);
            }
            return Map.of("changes", changes, "dropped", dropped);
        }
    }
}
