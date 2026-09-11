package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.Disposables;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionBinding;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.plugins.subprocess.SubprocessServices;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ToolFsSearchEntrypoint implements PluginEntrypoint<SearchPluginConfig> {
    @Override
    public PluginDefinition<SearchPluginConfig> definition() {
        return PluginDefinition.builder("tool-fs-search", SearchPluginConfig.class,
                () -> (context, config) -> Mono.defer(() -> {
                    validatePaths(config);
                    var runner = new SearchRunner(config);
                    var instanceId = context.plugins().current().orElseThrow().id();
                    var glob = new ContributionBinding<>(ToolContributions.KIND, "glob",
                        globDescriptor(), (invocation, request) -> runner.glob(
                            invocation.withCancellation(request.cancellation()), request));
                    var grep = new ContributionBinding<>(ToolContributions.KIND, "grep",
                        grepDescriptor(), (invocation, request) -> runner.grep(
                            invocation.withCancellation(request.cancellation()), request));
                    return context.services().require(ContributionServices.REGISTRAR)
                        .registerAll(context, instanceId, List.of(glob, grep),
                            Disposables.noop())
                        .then();
                }))
            .validator(config -> {
                if (config == null) {
                    throw new IllegalArgumentException("tool-fs-search config is required");
                }
                return config;
            })
            .require(ContributionServices.REGISTRAR)
            .require(SubprocessServices.SUBPROCESS)
            .build();
    }

    private static void validatePaths(SearchPluginConfig config) {
        var executable = Path.of(config.rgExecutable());
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException("rgExecutable must be an executable file");
        }
        if (!Files.isDirectory(Path.of(config.workdir()))) {
            throw new IllegalArgumentException("workdir must be a directory");
        }
    }

    private static ToolDescriptor globDescriptor() {
        var inputSchema = schema(Map.of(
            "type", "object",
            "required", List.of("pattern"),
            "properties", Map.of(
                "pattern", Map.of("type", "string"),
                "path", Map.of("type", "string"))));
        var outputSchema = schema(Map.of(
            "type", "object",
            "properties", Map.of(
                "paths", Map.of("type", "array"),
                "seen", Map.of("type", "integer"),
                "truncated", Map.of("type", "boolean"))));
        return new ToolDescriptor("Glob", "Find files by ripgrep glob pattern.", inputSchema,
            outputSchema);
    }

    private static ToolDescriptor grepDescriptor() {
        var inputSchema = schema(Map.of(
            "type", "object",
            "required", List.of("pattern"),
            "properties", Map.of(
                "pattern", Map.of("type", "string"),
                "path", Map.of("type", "string"),
                "include", Map.of("type", "string"))));
        var outputSchema = schema(Map.of(
            "type", "object",
            "properties", Map.of(
                "matches", Map.of("type", "array"),
                "seen", Map.of("type", "integer"),
                "truncated", Map.of("type", "boolean"))));
        return new ToolDescriptor("Grep", "Search file contents with a ripgrep expression.",
            inputSchema, outputSchema);
    }

    private static LiteralValue.ObjectValue schema(Map<String, ?> value) {
        return (LiteralValue.ObjectValue) LiteralValue.of(value);
    }
}
