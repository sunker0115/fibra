package com.sstlfsj.fibra.plugins.fs.tool;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.fs.FileSystemServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes the formal file tool contributions through the runtime registrar only. */
public final class FileToolEntrypoint implements PluginEntrypoint<FileToolConfig> {
    @Override
    public PluginDefinition<FileToolConfig> definition() {
        return PluginDefinition.builder("tool-fs", FileToolConfig.class,
                () -> (context, config) -> {
                    var registrar = context.services().require(ContributionServices.REGISTRAR);
                    var provider = context.plugins().current().orElseThrow(
                        () -> new IllegalStateException("tool contribution requires plugin ownership")).id();
                    var descriptors = descriptors(config);
                    return registrar.registerAll(context, provider, List.of(
                        new ContributionBinding<>(ToolContributions.KIND, "read", descriptors.get("read"),
                            (invocation, request) -> invocation.withCancellation(request.cancellation())
                                .service(FileSystemServices.FILE_SYSTEM).invoke((fileContext, fileSystem) ->
                                    FileToolHandlers.read(fileSystem, fileContext, config, request))),
                        new ContributionBinding<>(ToolContributions.KIND, "write", descriptors.get("write"),
                            (invocation, request) -> invocation.withCancellation(request.cancellation())
                                .service(FileSystemServices.FILE_SYSTEM).invoke((fileContext, fileSystem) ->
                                    FileToolHandlers.write(fileSystem, fileContext, request))),
                        new ContributionBinding<>(ToolContributions.KIND, "edit", descriptors.get("edit"),
                            (invocation, request) -> invocation.withCancellation(request.cancellation())
                                .service(FileSystemServices.FILE_SYSTEM).invoke((fileContext, fileSystem) ->
                                    FileToolHandlers.edit(fileSystem, fileContext, request)))
                    ), Disposables.noop()).then();
                })
            .validator(config -> config == null ? new FileToolConfig() : config)
            .require(FileSystemServices.FILE_SYSTEM)
            .require(ContributionServices.REGISTRAR)
            .build();
    }

    static Map<String, ToolDescriptor> descriptors(FileToolConfig config) {
        var string = Map.of("type", "string");
        var bool = Map.of("type", "boolean");
        var version = Map.of("type", "string");
        var readInput = schema(List.of("path"), Map.of(
            "path", string,
            "cwd", string,
            "offset", Map.of("type", "integer", "minimum", 1),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", config.readLimit())
        ));
        var line = schema(List.of("number", "text"), Map.of(
            "number", Map.of("type", "integer", "minimum", 1),
            "text", string
        ));
        var readOutput = schema(List.of("path", "version", "offset", "lines", "totalLines"), Map.of(
            "path", string,
            "version", version,
            "offset", Map.of("type", "integer", "minimum", 1),
            "lines", Map.of("type", "array", "items", line),
            "totalLines", Map.of("type", "integer", "minimum", 0)
        ));
        var writeInput = schema(List.of("path", "content"), Map.of(
            "path", string,
            "cwd", string,
            "content", string,
            "createIfAbsent", bool,
            "version", version
        ));
        var mutationOutput = schema(List.of("path", "operation", "version", "before", "after"), Map.of(
            "path", string,
            "operation", Map.of("type", "string", "enum", List.of("create", "update", "edit")),
            "version", version,
            "before", Map.of("type", List.of("string", "null")),
            "after", string
        ));
        var editInput = schema(List.of("path", "oldText", "newText"), Map.of(
            "path", string,
            "cwd", string,
            "oldText", string,
            "newText", string,
            "replaceAll", bool,
            "version", version
        ));
        return Map.of(
            "read", new ToolDescriptor("Read file", "Read line-numbered UTF-8 text.", readInput, readOutput),
            "write", new ToolDescriptor("Write file", "Atomically write UTF-8 text.", writeInput,
                mutationOutput),
            "edit", new ToolDescriptor("Edit file", "Replace literal UTF-8 text.", editInput,
                mutationOutput));
    }

    private static LiteralValue.ObjectValue schema(List<String> required,
                                                    Map<String, ?> properties) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        return (LiteralValue.ObjectValue) LiteralValue.of(schema);
    }
}
