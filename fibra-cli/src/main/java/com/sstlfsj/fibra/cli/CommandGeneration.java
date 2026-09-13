package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.cli.api.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandRequest;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import picocli.CommandLine;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次解析操作捕获的 Fibra 命令事实；不持有或复用 Picocli 解析对象。 */
final class CommandGeneration {
    private final PublishedRuntime published;
    private final String viewRevision;
    private final List<Command> commands;
    private final List<Tool> tools;

    private CommandGeneration(PublishedRuntime published, String viewRevision,
                              List<Command> commands, List<Tool> tools) {
        this.published = published;
        this.viewRevision = viewRevision;
        this.commands = List.copyOf(commands);
        this.tools = List.copyOf(tools);
    }

    static CommandGeneration capture(PublishedRuntime published) {
        Objects.requireNonNull(published, "published");
        var view = published.current();
        var commands = view.contributions().entries().stream()
            .filter(entry -> CliCommandContributions.KIND.name().equals(entry.kind()))
            .map(entry -> {
                if (!(entry.descriptor() instanceof CliCommandDescriptor descriptor)) {
                    throw new IllegalStateException("CLI command descriptor has the wrong type");
                }
                return new Command(entry.id(), entry.registrationIdentity(), descriptor);
            }).toList();
        var tools = view.contributions().entries().stream()
            .filter(entry -> ToolContributions.KIND.name().equals(entry.kind()))
            .map(entry -> new Tool(entry.id().providerInstanceId(), entry.id().localName()))
            .toList();
        return new CommandGeneration(published, view.viewRevision(), commands, tools);
    }

    String viewRevision() {
        return viewRevision;
    }

    List<Command> commands() {
        return commands;
    }

    List<Tool> tools() {
        return tools;
    }

    Mono<CliCommandResult> invoke(Command command, CliCommandRequest request) {
        Objects.requireNonNull(command, "command");
        return published.invoke(viewRevision, command.registrationIdentity(),
            CliCommandContributions.KIND, command.id(), request);
    }

    void install(CommandLine root, Executor executor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(executor, "executor");
        for (var command : commands) install(root, command.descriptor(),
            new Binding((arguments, options) -> executor.execute(command, arguments, options)));
    }

    static void installBootstrap(CommandLine root, List<CliBootstrapCommand> commands,
                                 BootstrapExecutor executor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(executor, "executor");
        for (var command : commands) {
            Objects.requireNonNull(command, "command");
            install(root, command.descriptor(), new Binding((arguments, options) ->
                executor.execute(command, arguments, options)));
        }
    }

    private static void install(CommandLine root, CliCommandDescriptor descriptor,
                                Binding binding) {
        var parent = root;
        var path = descriptor.path();
        for (var index = 0; index < path.size() - 1; index++) {
            var part = path.get(index);
            var existing = parent.getSubcommands().get(part);
            if (existing == null) {
                existing = branch(part);
                parent.addSubcommand(part, existing);
            }
            parent = existing;
        }
        var name = path.getLast();
        if (parent.getSubcommands().containsKey(name)) {
            throw new IllegalStateException("duplicate CLI command path: "
                + String.join(" ", path));
        }
        var spec = CommandLine.Model.CommandSpec.wrapWithoutInspection(binding).name(name)
            .mixinStandardHelpOptions(true);
        spec.usageMessage().description(descriptor.description());
        for (var option : descriptor.options()) {
            var model = CommandLine.Model.OptionSpec.builder(
                    option.names().toArray(String[]::new))
                .type(String.class).required(option.required())
                .description(option.description())
                .completionCandidates(option.completions()).build();
            spec.addOption(model);
            binding.options.put(option.names().getFirst(), model);
        }
        if (descriptor.argumentsLabel() != null) {
            var positional = CommandLine.Model.PositionalParamSpec.builder()
                .type(String[].class).arity("0..*")
                .paramLabel(descriptor.argumentsLabel())
                .completionCandidates(descriptor.argumentCompletions()).build();
            spec.addPositional(positional);
            binding.arguments = positional;
        }
        parent.addSubcommand(name, new CommandLine(spec));
    }

    private static CommandLine branch(String name) {
        var usage = new Usage();
        var spec = CommandLine.Model.CommandSpec.wrapWithoutInspection(usage).name(name)
            .mixinStandardHelpOptions(true);
        usage.spec = spec;
        return new CommandLine(spec);
    }

    record Command(ContributionId id, long registrationIdentity,
                   CliCommandDescriptor descriptor) {
        Command {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    record Tool(String provider, String name) {
        Tool {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(name, "name");
        }
    }

    @FunctionalInterface
    interface Executor {
        int execute(Command command, List<String> arguments, Map<String, String> options)
            throws Exception;
    }

    @FunctionalInterface
    interface BootstrapExecutor {
        int execute(CliBootstrapCommand command, List<String> arguments,
                    Map<String, String> options) throws Exception;
    }

    @FunctionalInterface
    private interface ParsedExecutor {
        int execute(List<String> arguments, Map<String, String> options) throws Exception;
    }

    private static final class Binding implements java.util.concurrent.Callable<Integer> {
        private final ParsedExecutor executor;
        private final Map<String, CommandLine.Model.OptionSpec> options = new LinkedHashMap<>();
        private CommandLine.Model.PositionalParamSpec arguments;

        private Binding(ParsedExecutor executor) {
            this.executor = executor;
        }

        @Override
        public Integer call() throws Exception {
            var values = new LinkedHashMap<String, String>();
            options.forEach((name, option) -> {
                var value = option.<String>getValue();
                if (value != null) values.put(name, value);
            });
            var positional = new ArrayList<String>();
            if (arguments != null) {
                var valuesArray = arguments.<String[]>getValue();
                if (valuesArray != null) positional.addAll(List.of(valuesArray));
            }
            return executor.execute(List.copyOf(positional), Map.copyOf(values));
        }
    }

    private static final class Usage implements java.util.concurrent.Callable<Integer> {
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return 2;
        }
    }
}
