package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.CancellationSource;
import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionSnapshotEntry;
import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.cli.api.CliCommandRequest;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliInvocation;
import com.sstlfsj.fibra.cli.api.CliOutput;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.MutationGateClosedException;
import com.sstlfsj.fibra.engine.PluginInstanceSnapshot;
import com.sstlfsj.fibra.engine.PublishedRevisionConflictException;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.plugins.tool.ToolContent;
import com.sstlfsj.fibra.plugins.tool.ToolContributions;
import com.sstlfsj.fibra.plugins.tool.ToolDescriptor;
import com.sstlfsj.fibra.plugins.tool.ToolOutcome;
import com.sstlfsj.fibra.plugins.tool.ToolOutcomes;
import com.sstlfsj.fibra.plugins.tool.ToolRequest;
import com.sstlfsj.fibra.registry.PluginInstallRequest;
import com.sstlfsj.fibra.registry.PluginAuditDeliveryFailure;
import com.sstlfsj.fibra.registry.RegistrySnapshot;
import com.sstlfsj.fibra.value.LiteralValue;
import picocli.CommandLine;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@CommandLine.Command(name = "fibra", mixinStandardHelpOptions = true,
    description = "管理 Fibra profile、插件和已发布工具。", showDefaultValues = true,
    versionProvider = FibraCli.VersionProvider.class,
    subcommands = {FibraCli.Plugins.class, FibraCli.Tools.class, FibraCli.Apply.class,
        FibraCli.Repl.class, CommandLine.HelpCommand.class})
public final class FibraCli implements java.util.concurrent.Callable<Integer> {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    @CommandLine.Option(names = "--home", paramLabel = "DIR", description = "Fibra 安装目录。") private Path home;
    @CommandLine.Option(names = "--profile", defaultValue = "default", paramLabel = "NAME",
        description = "选择独立的配置与持久状态命名空间。") private String profile;
    @CommandLine.Option(names = "--config-root", paramLabel = "DIR", description = "配置根目录。") private Path configRoot;
    @CommandLine.Option(names = "--plugins-root", paramLabel = "DIR", description = "候选插件包根目录。") private Path pluginsRoot;
    @CommandLine.Option(names = "--data-root", paramLabel = "DIR", description = "持久数据根目录。") private Path dataRoot;
    @CommandLine.Option(names = "--node", paramLabel = "EXECUTABLE", description = "Node.js 可执行文件。") private Path nodeExecutable;
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

    private final Function<CliPaths, CliHost> hostFactory;
    private final CliApplication application;
    private final boolean installShutdownHook;
    private final InputStream input;
    private final PrintWriter output;
    private final PrintWriter error;
    private final LinkedHashSet<CancellationSource> invocations = new LinkedHashSet<>();
    private CliTerminal terminal = NonInteractiveTerminal.INSTANCE;
    private CliHost host;
    private Thread shutdownHook;
    private boolean closing;

    private FibraCli(CliApplication application, Function<CliPaths, CliHost> hostFactory,
                     boolean installShutdownHook,
                     InputStream input, PrintWriter output, PrintWriter error) {
        this.application = Objects.requireNonNull(application, "application");
        this.hostFactory = Objects.requireNonNull(hostFactory, "hostFactory");
        this.installShutdownHook = installShutdownHook;
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    public static void main(String[] args) {
        System.exit(execute(defaultApplication(), args, System.in, new PrintWriter(System.out, true),
            new PrintWriter(System.err, true), CliHost::open, true));
    }

    public static int run(String[] args, InputStream in, PrintWriter out, PrintWriter err) {
        return run(defaultApplication(), args, in, out, err);
    }

    public static int run(CliApplication application, String[] args, InputStream in,
                          PrintWriter out, PrintWriter err) {
        return execute(application, args, in, out, err, CliHost::open, false);
    }

    static int run(String[] args, InputStream in, PrintWriter out, PrintWriter err,
                   Function<CliPaths, CliHost> hostFactory) {
        return execute(defaultApplication(), args, in, out, err, hostFactory, false);
    }

    static int execute(String[] args, InputStream in, PrintWriter out, PrintWriter err,
                       Function<CliPaths, CliHost> hostFactory, boolean installShutdownHook) {
        return execute(defaultApplication(), args, in, out, err, hostFactory,
            installShutdownHook);
    }

    private static int execute(CliApplication application, String[] args, InputStream in,
                               PrintWriter out, PrintWriter err,
                               Function<CliPaths, CliHost> hostFactory,
                               boolean installShutdownHook) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        var command = new FibraCli(application, hostFactory, installShutdownHook, in, out, err);
        CommandGeneration generation = null;
        var line = command.commandLine(generation);
        int result;
        try {
            if (requiresPublishedGeneration(args, line)) {
                line.setStopAtUnmatched(true).setUnmatchedArgumentsAllowed(true).parseArgs(args);
                generation = CommandGeneration.capture(command.host().published());
                line = command.commandLine(generation);
            }
            result = line.execute(args);
        } catch (CommandLine.ParameterException failure) {
            result = command.parameterFailure(failure, args, generation);
        } catch (RuntimeException failure) {
            result = command.executionFailure(failure, args, generation);
        } catch (Error failure) {
            try {
                command.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        try {
            command.close();
        } catch (RuntimeException failure) {
            err.println("关闭宿主失败: " + message(failure));
            return 7;
        }
        return result;
    }

    private CommandLine commandLine(CommandGeneration generation) {
        var descriptors = commandDescriptors(generation);
        var line = new CommandLine(this).setExpandAtFiles(false).setOut(output).setErr(error)
            .setParameterExceptionHandler((failure, arguments) ->
                parameterFailure(failure, arguments, descriptors))
            .setExecutionExceptionHandler((failure, commandLine, result) ->
                executionFailure(failure, result.originalArgs(), descriptors));
        line.getCommandSpec().name(application.rootName());
        line.getCommandSpec().usageMessage().description(application.description());
        line.getCommandSpec().versionProvider(() -> new String[] {
            application.rootName() + " " + application.version()
        });
        CommandGeneration.installBootstrap(line, application.bootstrapCommands(),
            this::invokeBootstrap);
        if (generation != null) generation.install(line,
            (command, arguments, options) -> invokeDynamic(
                generation, command, arguments, options));
        return line;
    }

    private List<CliCommandDescriptor> commandDescriptors(CommandGeneration generation) {
        var descriptors = new ArrayList<CliCommandDescriptor>();
        application.bootstrapCommands().forEach(command -> descriptors.add(command.descriptor()));
        if (generation != null) {
            generation.commands().forEach(command -> descriptors.add(command.descriptor()));
        }
        return List.copyOf(descriptors);
    }

    private static boolean requiresPublishedGeneration(String[] args, CommandLine base) {
        var command = rootCommand(args);
        if (command == null) return false;
        if (!command.equals("help")) return !base.getSubcommands().containsKey(command);
        var target = commandAfter(args, "help");
        return target != null && !base.getSubcommands().containsKey(target);
    }

    private static String rootCommand(String[] args) {
        for (var index = 0; index < args.length; index++) {
            var value = args[index];
            if (value.equals("--help") || value.equals("-h") || value.equals("--version")
                || value.equals("-V")) return null;
            if (isGlobalOption(value)) {
                if (!value.contains("=") && index + 1 < args.length) index++;
                continue;
            }
            if (!value.startsWith("-")) return value;
        }
        return null;
    }

    private static String commandAfter(String[] args, String command) {
        for (var index = 0; index < args.length - 1; index++) {
            if (args[index].equals(command)) return args[index + 1];
        }
        return null;
    }

    private static boolean isGlobalOption(String value) {
        for (var option : List.of("--home", "--profile", "--config-root", "--plugins-root",
            "--data-root", "--node")) {
            if (value.equals(option) || value.startsWith(option + "=")) return true;
        }
        return false;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return 2;
    }

    private synchronized CliHost host() {
        if (host != null) return host;
        if (closing) throw new CliFailure(6, "宿主正在关闭", null);
        final CliPaths resolved;
        try {
            resolved = paths();
        } catch (IllegalArgumentException failure) {
            throw new CliFailure(2, message(failure), failure);
        }
        try {
            host = Objects.requireNonNull(hostFactory.apply(resolved), "CLI host");
            if (installShutdownHook) installShutdownHook();
            return host;
        } catch (RuntimeException failure) {
            throw new CliFailure(3, "无法启动宿主: " + message(failure), failure);
        }
    }

    private CliPaths paths() {
        var defaultHome = Path.of(System.getProperty("fibra.home", "."));
        return CliPaths.resolve(home == null ? defaultHome : home, profile, configRoot, pluginsRoot,
            dataRoot, nodeExecutable);
    }

    private synchronized void installShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(this::closeFromShutdown, "fibra-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void closeFromShutdown() {
        close();
    }

    private void close() {
        final CliHost current;
        final Thread hook;
        final java.util.List<CancellationSource> active;
        synchronized (this) {
            if (closing && host == null && shutdownHook == null) return;
            closing = true;
            current = host;
            active = java.util.List.copyOf(invocations);
            hook = shutdownHook;
            shutdownHook = null;
        }
        active.forEach(CancellationSource::cancel);
        try {
            if (current != null) current.close();
        } finally {
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // JVM 已进入关闭阶段，hook 会继续负责关闭。
                }
            }
            synchronized (this) {
                host = null;
            }
        }
    }

    private synchronized CancellationSource beginInvocation() {
        if (closing) throw new CliFailure(6, "宿主正在关闭", null);
        var cancellation = new CancellationSource();
        invocations.add(cancellation);
        return cancellation;
    }

    private synchronized void endInvocation(CancellationSource cancellation) {
        invocations.remove(cancellation);
    }

    private int repl() {
        var paths = paths();
        host();
        return CliRepl.run(new CliRepl.GeneratedDispatcher() {
            @Override public CommandGeneration capture() {
                return CommandGeneration.capture(host().published());
            }

            @Override public CommandLine completionLine(CommandGeneration generation) {
                return commandLine(generation);
            }

            @Override public List<CliCommandDescriptor> historyDescriptors(
                CommandGeneration generation) {
                return commandDescriptors(generation);
            }

            @Override public int execute(CommandGeneration generation, String[] arguments,
                                         CliTerminal invocationTerminal) {
                return executeReplLine(generation, arguments, invocationTerminal);
            }
        },
            input, output, error,
            installShutdownHook && System.console() != null, paths.replHistoryFile(), replSessionSummary(paths));
    }

    private String replSessionSummary(CliPaths paths) {
        var view = host().published().current();
        var tools = view.contributions().entries().stream()
            .filter(entry -> ToolContributions.KIND.name().equals(entry.kind())).count();
        return "profile=" + paths.profile() + " workspace=" + paths.workspaceRoot()
            + " tools=" + tools + " revision=" + view.viewRevision();
    }

    private int executeReplLine(CommandGeneration generation, String[] arguments,
                                CliTerminal invocationTerminal) {
        terminal = Objects.requireNonNull(invocationTerminal, "invocationTerminal");
        try {
            return commandLine(generation).execute(arguments);
        } finally {
            terminal = NonInteractiveTerminal.INSTANCE;
        }
    }

    private int invokeDynamic(CommandGeneration generation, CommandGeneration.Command command,
                              List<String> arguments,
                              Map<String, String> options) {
        var cancellation = beginInvocation();
        try {
            var request = new CliCommandRequest(arguments, options,
                new CliInvocation(cancellation.token(), output(), terminal, profile()));
            var result = generation.invoke(command, request).block();
            if (result == null) throw new CliFailure(4,
                "命令调用没有返回结果", null);
            return result.status().code();
        } finally {
            endInvocation(cancellation);
        }
    }

    private int invokeBootstrap(CliBootstrapCommand command, List<String> arguments,
                                Map<String, String> options) throws Exception {
        var cancellation = beginInvocation();
        try {
            var request = new CliCommandRequest(arguments, options,
                new CliInvocation(cancellation.token(), output(), terminal, profile()));
            var result = command.handler().invoke(request);
            if (result == null) throw new CliFailure(4,
                "命令调用没有返回结果", null);
            return result.status().code();
        } finally {
            endInvocation(cancellation);
        }
    }

    private CliOutput output() {
        return new CliOutput() {
            @Override public void stdout(String value) { output.println(value); }
            @Override public void stderr(String value) { error.println(value); }
        };
    }

    private CliProfile profile() {
        var paths = paths();
        return new CliProfile(paths.profile(), paths.home(), paths.configRoot(),
            paths.pluginsRoot(), paths.dataRoot());
    }

    private void print(Object value) {
        spec.commandLine().getOut().println(LiteralValue.of(value).canonicalJson());
    }

    private void printSnapshot(RegistrySnapshot snapshot) {
        var artifacts = snapshot.artifacts().values().stream()
            .sorted(Comparator.comparing(record -> record.id().value()))
            .map(record -> map("id", record.id().value(), "runtime", record.runtimeId().value(),
                "version", record.version(), "revision", record.revision())).toList();
        var instanceIds = new java.util.TreeSet<String>();
        instanceIds.addAll(snapshot.desiredGraph().plugins().keySet());
        instanceIds.addAll(snapshot.observed().keySet());
        var instances = instanceIds.stream().map(id -> instance(id,
            snapshot.desiredGraph().plugins().get(id), snapshot.observed().get(id))).toList();
        var auditFailures = snapshot.auditFailures().stream().map(FibraCli::auditFailure).toList();
        print(map("viewRevision", snapshot.viewRevision(), "artifacts", artifacts,
            "instances", instances, "auditFailures", auditFailures));
    }

    static Map<String, Object> instance(String id, DesiredInputEntry desired,
                                        PluginInstanceSnapshot observed) {
        var publicationRequirement = desired != null ? desired.publicationRequirement()
            : observed.publicationRequirement();
        var requirementSatisfied = desired != null && (desired.enabled()
            ? observed != null && observed.requirementSatisfied() : observed == null);
        return map("id", id, "definition", desired != null ? desired.definitionName()
                : observed.definitionName(),
            "desired", desired != null, "enabled", desired == null ? null : desired.enabled(),
            "observed", observed != null, "identity", observed == null ? null : observed.identity(),
            "state", observed == null ? null : observed.state().name(),
            "publicationRequirement", publicationRequirement.name(),
            "requirementSatisfied", requirementSatisfied,
            "failure", observed == null ? null : observed.failure());
    }

    static Map<String, Object> auditFailure(PluginAuditDeliveryFailure failure) {
        return map("timestamp", failure.timestamp().toString(), "operation", failure.operation(),
            "target", failure.target(), "succeeded", failure.succeeded(),
            "targetSaveState", failure.targetSaveState().name(),
            "viewRevision", failure.viewRevision(), "detail", failure.detail());
    }

    private void listTools() {
        var view = host().published().current();
        var tools = view.contributions().entries().stream()
            .filter(entry -> ToolContributions.KIND.name().equals(entry.kind()))
            .map(FibraCli::tool)
            .sorted(Comparator.comparing((Map<String, Object> tool) -> (String) tool.get("provider"))
                .thenComparing(tool -> (String) tool.get("name"))).toList();
        print(map("viewRevision", view.viewRevision(), "tools", tools));
    }

    private static Map<String, Object> tool(ContributionSnapshotEntry entry) {
        if (!(entry.descriptor() instanceof ToolDescriptor descriptor)) {
            throw new CliFailure(4, "工具描述无效: " + entry.id().providerInstanceId() + "/" + entry.id().localName(), null);
        }
        return map("provider", entry.id().providerInstanceId(), "name", entry.id().localName(),
            "displayName", descriptor.displayName(), "description", descriptor.description(),
            "inputSchema", descriptor.inputSchema().toJava(), "outputSchema", descriptor.outputSchema().toJava());
    }

    private void invoke(String provider, String name, String input) {
        var arguments = parseObject(input);
        var view = host().published().current();
        var id = new ContributionId(provider, name);
        var registrationIdentity = registrationIdentity(view, ToolContributions.KIND, id);
        var cancellation = beginInvocation();
        final ToolOutcome outcome;
        try {
            outcome = ToolOutcomes.normalize(() -> host().published().invoke(view.viewRevision(),
                registrationIdentity, ToolContributions.KIND, id,
                new ToolRequest(arguments, cancellation.token()))).block();
        } finally {
            endInvocation(cancellation);
        }
        switch (outcome) {
            case ToolOutcome.Failure failure -> throw new CliFailure(4, canonical(map(
                "isError", true, "viewRevision", view.viewRevision(),
                "content", content(failure.content()), "error", map(
                    "code", failure.error().code().name(), "message", failure.error().message()))), null);
            case ToolOutcome.Success success -> {
                var result = success.result();
                var output = map("isError", false, "viewRevision", view.viewRevision(),
                    "content", content(result.content()));
                result.structuredContent().ifPresent(value -> output.put("structuredContent", value.toJava()));
                print(output);
            }
        }
    }

    private static long registrationIdentity(PublishedView view,
                                             ContributionKind<?, ?, ?> kind,
                                             ContributionId id) {
        return view.contributions().entries().stream()
            .filter(entry -> entry.kind().equals(kind.name()) && entry.id().equals(id))
            .findFirst().map(ContributionSnapshotEntry::registrationIdentity)
            .orElse(Long.MIN_VALUE);
    }

    private static List<Map<String, Object>> content(List<ToolContent> content) {
        return content.stream().map(block -> switch (block) {
            case ToolContent.Text text -> map("type", "text", "text", text.text());
        }).toList();
    }

    private LiteralValue.ObjectValue parseObject(String input) {
        try {
            var value = LiteralValue.of(JSON.readValue(input, Object.class));
            if (value instanceof LiteralValue.ObjectValue object) return object;
        } catch (JacksonException failure) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--input 必须是 JSON object", failure);
        }
        throw new CommandLine.ParameterException(spec.commandLine(), "--input 必须是 JSON object");
    }

    private PluginInstallRequest probe(Path value) {
        try {
            var artifact = host().probe().probe(source(value)).block();
            if (artifact == null) throw new IllegalStateException("插件探测没有返回结果");
            return PluginInstallRequest.builder().artifactId(artifact.artifactId()).runtimeId(artifact.runtimeId())
                .version(artifact.version()).source(artifact.source()).build();
        } catch (CliFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CliFailure(4, "插件探测失败: " + message(failure), failure);
        }
    }

    private Path source(Path value) {
        return value.toAbsolutePath().normalize();
    }

    private int parameterFailure(CommandLine.ParameterException failure, String[] arguments,
                                 CommandGeneration generation) {
        return parameterFailure(failure, arguments, commandDescriptors(generation));
    }

    private int parameterFailure(CommandLine.ParameterException failure, String[] arguments,
                                 List<CliCommandDescriptor> descriptors) {
        error.println(safeDiagnostic(message(failure), descriptors, List.of(arguments)));
        failure.getCommandLine().usage(error);
        return 2;
    }

    private int executionFailure(RuntimeException failure, String[] arguments,
                                 CommandGeneration generation) {
        return executionFailure(failure, List.of(arguments), commandDescriptors(generation));
    }

    private int executionFailure(Exception failure, List<String> arguments,
                                 List<CliCommandDescriptor> descriptors) {
        error.println(safeDiagnostic(message(failure), descriptors, arguments));
        if (failure instanceof CliFailure cli) return cli.code;
        if (failure instanceof PublishedRevisionConflictException
            || failure instanceof ContributionUnavailableException) return 5;
        if (failure instanceof MutationGateClosedException || failure instanceof EngineChangeException) return 6;
        return 4;
    }

    private static String safeDiagnostic(String diagnostic, List<CliCommandDescriptor> descriptors,
                                         List<String> arguments) {
        return CliSensitiveInput.redactDiagnostic(diagnostic, descriptors, arguments);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static Map<String, Object> map(Object... values) {
        var map = new LinkedHashMap<String, Object>();
        for (var index = 0; index < values.length; index += 2) map.put((String) values[index], values[index + 1]);
        return map;
    }

    private static String canonical(Object value) {
        return LiteralValue.of(value).canonicalJson();
    }

    @CommandLine.Command(name = "plugins", description = "查询和变更当前 profile 的插件目标。",
        subcommands = {Plugins.ListCommand.class, Plugins.Install.class,
        Plugins.Upgrade.class, Plugins.Uninstall.class, Plugins.Enable.class, Plugins.Disable.class})
    static final class Plugins implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
        @Override public Integer call() { spec.commandLine().usage(spec.commandLine().getErr()); return 2; }

        abstract static class Command implements java.util.concurrent.Callable<Integer> {
            @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
            final FibraCli root() { return (FibraCli) spec.root().userObject(); }
        }

        @CommandLine.Command(name = "list", description = "输出制品和实例的当前一致快照。") static final class ListCommand extends Command {
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().snapshot()); return 0; }
        }
        abstract static class SourceCommand extends Command {
            @CommandLine.Parameters(index = "0", paramLabel = "PACKAGE") private Path source;
            final PluginInstallRequest request() { return root().probe(source); }
        }
        @CommandLine.Command(name = "install", description = "从本地标准插件包安装制品。") static final class Install extends SourceCommand {
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().install(request()).block()); return 0; }
        }
        @CommandLine.Command(name = "upgrade", description = "从本地标准插件包升级制品。") static final class Upgrade extends SourceCommand {
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().upgrade(request()).block()); return 0; }
        }
        @CommandLine.Command(name = "uninstall", description = "卸载指定制品。") static final class Uninstall extends Command {
            @CommandLine.Parameters(index = "0", paramLabel = "ARTIFACT_ID") private String artifact;
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().uninstall(new ArtifactId(artifact)).block()); return 0; }
        }
        @CommandLine.Command(name = "enable", description = "启用指定配置实例。") static final class Enable extends Command {
            @CommandLine.Parameters(index = "0", paramLabel = "INSTANCE_ID") private String instance;
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().enable(instance).block()); return 0; }
        }
        @CommandLine.Command(name = "disable", description = "停用指定配置实例。") static final class Disable extends Command {
            @CommandLine.Parameters(index = "0", paramLabel = "INSTANCE_ID") private String instance;
            @Override public Integer call() { var root = root(); root.printSnapshot(root.host().registry().disable(instance).block()); return 0; }
        }
    }

    @CommandLine.Command(name = "tools", description = "查询和调用当前 PublishedView 中的工具。",
        subcommands = {Tools.ListCommand.class, Tools.Invoke.class})
    static final class Tools implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
        @Override public Integer call() { spec.commandLine().usage(spec.commandLine().getErr()); return 2; }
        abstract static class Command implements java.util.concurrent.Callable<Integer> {
            @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
            final FibraCli root() { return (FibraCli) spec.root().userObject(); }
        }
        @CommandLine.Command(name = "list", description = "输出当前已发布工具。") static final class ListCommand extends Command {
            @Override public Integer call() { root().listTools(); return 0; }
        }
        @CommandLine.Command(name = "invoke", description = "按 provider 和本地名称调用工具。") static final class Invoke extends Command {
            @CommandLine.Parameters(index = "0", paramLabel = "PROVIDER_INSTANCE_ID") private String provider;
            @CommandLine.Parameters(index = "1", paramLabel = "LOCAL_NAME") private String name;
            @CommandLine.Option(names = "--input", required = true, paramLabel = "JSON") private String input;
            @Override public Integer call() { root().invoke(provider, name, input); return 0; }
        }
    }

    @CommandLine.Command(name = "apply", description = "一次性应用 profile 配置与完整制品清单。")
    static final class Apply implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
        @Override public Integer call() {
            var root = (FibraCli) spec.root().userObject();
            try {
                root.printSnapshot(root.host().apply());
                return 0;
            } catch (CliFailure | EngineChangeException | MutationGateClosedException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new CliFailure(4, "应用配置失败: " + message(failure), failure);
            }
        }
    }

    @CommandLine.Command(name = "repl", description = "在一个长期宿主中交互执行命令。")
    static final class Repl implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        @Override public Integer call() {
            return ((FibraCli) spec.root().userObject()).repl();
        }
    }

    private static final class CliFailure extends RuntimeException {
        private final int code;
        private CliFailure(int code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }

    private enum NonInteractiveTerminal implements CliTerminal {
        INSTANCE;

        @Override public boolean interactive() { return false; }
        @Override public CliTerminalLease acquire() {
            throw new CliTerminalUnavailableException(CliTerminalUnavailableReason.UNSUPPORTED);
        }
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override public String[] getVersion() { return new String[] {"fibra " + version()}; }
    }

    private static String version() {
        var implementationVersion = FibraCli.class.getPackage().getImplementationVersion();
        return implementationVersion == null ? developmentVersion() : implementationVersion;
    }

    private static String developmentVersion() {
        try (var input = FibraCli.class.getResourceAsStream("/META-INF/fibra-cli-version")) {
            if (input != null) return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read CLI version", exception);
        }
        return "unknown";
    }

    private static CliApplication defaultApplication() {
        return CliApplication.builder("fibra")
            .description("管理 Fibra profile、插件和已发布工具。")
            .version(version())
            .build();
    }
}
