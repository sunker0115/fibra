package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.bridge.ContributionUnavailableException;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandRequest;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliExitStatus;
import com.sstlfsj.fibra.cli.api.CliInvocation;
import com.sstlfsj.fibra.cli.api.CliInputRequest;
import com.sstlfsj.fibra.cli.api.CliOutput;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import com.sstlfsj.fibra.engine.EngineChangeException;
import com.sstlfsj.fibra.engine.MutationGateClosedException;
import com.sstlfsj.fibra.engine.PublishedRevisionConflictException;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import picocli.CommandLine;

import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** bootstrap 与动态命令共用的命令代、invocation、REPL 和失败投影实现。 */
final class CliApplicationRunner {
    private final CliApplication application;
    private final Supplier<PublishedRuntime> published;
    private final Supplier<CliProfile> profile;
    private final CliTerminalSession terminalSession;
    private final PrintWriter output;
    private final PrintWriter error;
    private final CliInvocationCoordinator invocations;
    private final BaseLineFactory baseLineFactory;
    private final Path historyFile;
    private final List<String> rootOptions;

    CliApplicationRunner(CliApplication application, Supplier<PublishedRuntime> published,
                         Supplier<CliProfile> profile, CliTerminalSession terminalSession,
                         CliInvocationCoordinator invocations,
                         BaseLineFactory baseLineFactory) {
        this(application, published, profile, terminalSession, invocations, baseLineFactory,
            null, List.of());
    }

    CliApplicationRunner(CliApplication application, Supplier<PublishedRuntime> published,
                         Supplier<CliProfile> profile, CliTerminalSession terminalSession,
                         CliInvocationCoordinator invocations, BaseLineFactory baseLineFactory,
                         Path historyFile, List<String> rootOptions) {
        this.application = Objects.requireNonNull(application, "application");
        this.published = Objects.requireNonNull(published, "published");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.terminalSession = Objects.requireNonNull(terminalSession, "terminalSession");
        this.output = terminalSession.outputWriter();
        this.error = terminalSession.errorWriter();
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.baseLineFactory = baseLineFactory == null ? this::genericLine : baseLineFactory;
        this.historyFile = historyFile;
        this.rootOptions = List.copyOf(rootOptions);
    }

    int execute(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        CommandGeneration generation = null;
        var line = commandLine(null, terminalSession.controller());
        try {
            if (requiresPublishedGeneration(arguments, line)) {
                line.setStopAtUnmatched(true).setUnmatchedArgumentsAllowed(true)
                    .parseArgs(arguments);
                generation = CommandGeneration.capture(published.get());
                line = commandLine(generation, terminalSession.controller());
            }
            return line.execute(arguments);
        } catch (CommandLine.ParameterException failure) {
            return parameterFailure(failure, arguments, generation);
        } catch (RuntimeException failure) {
            return executionFailure(failure, arguments, generation);
        }
    }

    CommandLine commandLine(CommandGeneration generation) {
        return commandLine(generation, null);
    }

    private CommandLine commandLine(CommandGeneration generation,
                                    CliTerminalController terminals) {
        var descriptors = commandDescriptors(generation);
        var line = baseLineFactory.create().setExpandAtFiles(false).setOut(output).setErr(error)
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
            (command, arguments, options) ->
                invokeBootstrap(command, arguments, options, terminals));
        if (generation != null) generation.install(line,
            (command, arguments, options) -> invokeDynamic(
                generation, command, arguments, options, terminals));
        return line;
    }

    List<CliCommandDescriptor> commandDescriptors(CommandGeneration generation) {
        var descriptors = new ArrayList<CliCommandDescriptor>();
        application.bootstrapCommands().forEach(command -> descriptors.add(command.descriptor()));
        if (generation != null) {
            generation.commands().forEach(command -> descriptors.add(command.descriptor()));
        }
        return List.copyOf(descriptors);
    }

    int repl(Path historyFile, String sessionSummary, List<String> forbiddenRootOptions) {
        var inputMode = application.inputHandler().isPresent();
        return CliRepl.run(new CliRepl.InputDispatcher() {
            @Override public boolean inputMode() {
                return inputMode;
            }

            @Override public CliRepl.InputDispatch executeInput(
                String text, CliTerminalController terminals) {
                return invokeInput(text, terminals);
            }

            @Override public CommandGeneration capture() {
                return CommandGeneration.capture(published.get());
            }

            @Override public CommandLine completionLine(CommandGeneration generation) {
                return commandLine(generation, null);
            }

            @Override public List<CliCommandDescriptor> historyDescriptors(
                CommandGeneration generation) {
                return commandDescriptors(generation);
            }

            @Override public int execute(CommandGeneration generation, String[] arguments,
                                         CliTerminalController terminals) {
                return commandLine(generation, terminals).execute(arguments);
            }
        }, terminalSession, inputMode ? null : historyFile, sessionSummary,
            application.rootName() + "> ", forbiddenRootOptions);
    }

    private CliRepl.InputDispatch invokeInput(String text, CliTerminalController terminals) {
        var cancellation = beginInvocation();
        try (cancellation;
             var terminal = invocationTerminal(terminals, cancellation);
             var invocationOutput = output()) {
            var request = new CliInputRequest(text, new CliInvocation(cancellation.token(),
                invocationOutput, terminal, profile.get()));
            var result = application.inputHandler().orElseThrow().invoke(request);
            if (result == null) throw new CliCommandFailure(4,
                "应用输入调用没有返回结果", null);
            var status = projectExitStatus(result.commandResult(),
                cancellation.token().isCancelled());
            return new CliRepl.InputDispatch(status, result.exitRequested());
        } catch (Exception failure) {
            if (cancellation.token().isCancelled() && cancellationFailure(failure)) {
                return new CliRepl.InputDispatch(CliExitStatus.CANCELLED.code(), false);
            }
            return new CliRepl.InputDispatch(
                executionFailure(failure, List.of(), List.of()), false);
        }
    }

    private CommandLine genericLine() {
        var root = new Usage();
        var spec = CommandLine.Model.CommandSpec.wrapWithoutInspection(root)
            .mixinStandardHelpOptions(true);
        root.spec = spec;
        var line = new CommandLine(spec);
        var repl = CommandLine.Model.CommandSpec.wrapWithoutInspection(new Repl(this))
            .name("repl").mixinStandardHelpOptions(true);
        repl.usageMessage().description("在同一 CLI 会话中交互执行命令。");
        line.addSubcommand("repl", new CommandLine(repl));
        line.addSubcommand("help", new CommandLine.HelpCommand());
        return line;
    }

    private int invokeDynamic(CommandGeneration generation, CommandGeneration.Command command,
                              List<String> arguments, java.util.Map<String, String> options,
                              CliTerminalController terminals) {
        var cancellation = beginInvocation();
        try (cancellation;
             var terminal = invocationTerminal(terminals, cancellation);
             var invocationOutput = output()) {
            var request = new CliCommandRequest(arguments, options,
                new CliInvocation(cancellation.token(), invocationOutput, terminal, profile.get()));
            var result = generation.invoke(command, request).block();
            if (result == null) throw new CliCommandFailure(4,
                "命令调用没有返回结果", null);
            return projectExitStatus(result, cancellation.token().isCancelled());
        } catch (RuntimeException failure) {
            if (cancellation.token().isCancelled() && cancellationFailure(failure)) {
                return CliExitStatus.CANCELLED.code();
            }
            throw failure;
        }
    }

    private int invokeBootstrap(CliBootstrapCommand command, List<String> arguments,
                                java.util.Map<String, String> options,
                                CliTerminalController terminals) throws Exception {
        var cancellation = beginInvocation();
        try (cancellation;
             var terminal = invocationTerminal(terminals, cancellation);
             var invocationOutput = output()) {
            var request = new CliCommandRequest(arguments, options,
                new CliInvocation(cancellation.token(), invocationOutput, terminal, profile.get()));
            var result = command.handler().invoke(request);
            if (result == null) throw new CliCommandFailure(4,
                "命令调用没有返回结果", null);
            return projectExitStatus(result, cancellation.token().isCancelled());
        } catch (Exception failure) {
            if (cancellation.token().isCancelled() && cancellationFailure(failure)) {
                return CliExitStatus.CANCELLED.code();
            }
            throw failure;
        }
    }

    private CliInvocationCoordinator.Invocation beginInvocation() {
        try {
            return invocations.begin();
        } catch (IllegalStateException failure) {
            throw new CliCommandFailure(CliExitStatus.CLOSING.code(), "CLI 会话正在关闭", failure);
        }
    }

    private static InvocationTerminal invocationTerminal(CliTerminalController terminals,
                                                         CliInvocationCoordinator.Invocation invocation) {
        return terminals == null ? NonInteractiveTerminal.INSTANCE
            : terminals.openInvocation(invocation);
    }

    private InvocationOutput output() {
        return new InvocationOutput(terminalSession);
    }

    private boolean requiresPublishedGeneration(String[] arguments, CommandLine base) {
        var command = rootCommand(arguments);
        if (command == null) return false;
        if (!command.equals("help")) return !base.getSubcommands().containsKey(command);
        var target = commandAfter(arguments, "help");
        return target != null && !base.getSubcommands().containsKey(target);
    }

    private String rootCommand(String[] arguments) {
        for (var index = 0; index < arguments.length; index++) {
            var value = arguments[index];
            if (value.equals("--help") || value.equals("-h") || value.equals("--version")
                || value.equals("-V")) return null;
            if (isRootOption(value)) {
                if (!value.contains("=") && index + 1 < arguments.length) index++;
                continue;
            }
            if (!value.startsWith("-")) return value;
        }
        return null;
    }

    private boolean isRootOption(String value) {
        for (var option : rootOptions) {
            if (value.equals(option) || value.startsWith(option + "=")) return true;
        }
        return false;
    }

    private static String commandAfter(String[] arguments, String command) {
        for (var index = 0; index < arguments.length - 1; index++) {
            if (arguments[index].equals(command)) return arguments[index + 1];
        }
        return null;
    }

    private int parameterFailure(CommandLine.ParameterException failure, String[] arguments,
                                 CommandGeneration generation) {
        return parameterFailure(failure, arguments, commandDescriptors(generation));
    }

    private int parameterFailure(CommandLine.ParameterException failure, String[] arguments,
                                 List<CliCommandDescriptor> descriptors) {
        error.println(safeDiagnostic(message(failure), descriptors, List.of(arguments)));
        failure.getCommandLine().usage(error);
        return CliExitStatus.USAGE_ERROR.code();
    }

    private int executionFailure(RuntimeException failure, String[] arguments,
                                 CommandGeneration generation) {
        return executionFailure(failure, List.of(arguments), commandDescriptors(generation));
    }

    private int executionFailure(Exception failure, List<String> arguments,
                                 List<CliCommandDescriptor> descriptors) {
        error.println(safeDiagnostic(message(failure), descriptors, arguments));
        if (failure instanceof CliCommandFailure cli) return cli.code();
        if (failure instanceof PublishedRevisionConflictException
            || failure instanceof ContributionUnavailableException) {
            return CliExitStatus.STALE_OR_REVOKED.code();
        }
        if (failure instanceof MutationGateClosedException || failure instanceof EngineChangeException) {
            return CliExitStatus.CLOSING.code();
        }
        return CliExitStatus.INVOCATION_ERROR.code();
    }

    static int projectExitStatus(CliCommandResult result, boolean cancelled) {
        Objects.requireNonNull(result, "result");
        return cancelled && result.status() == CliExitStatus.SUCCESS
            ? CliExitStatus.CANCELLED.code() : result.status().code();
    }

    private static boolean cancellationFailure(Throwable failure) {
        var cancelled = false;
        for (var current = failure; current != null; current = current.getCause()) {
            for (var suppressed : current.getSuppressed()) {
                if (!reactorBlockMarker(suppressed)) return false;
            }
            if (current instanceof InterruptedIOException
                || current instanceof CancellationException) cancelled = true;
            if (current.getCause() == current) return false;
        }
        return cancelled;
    }

    private static boolean reactorBlockMarker(Throwable failure) {
        return failure.getClass() == Exception.class
            && "#block terminated with an error".equals(failure.getMessage())
            && failure.getCause() == null && failure.getSuppressed().length == 0;
    }

    private static String safeDiagnostic(String diagnostic, List<CliCommandDescriptor> descriptors,
                                         List<String> arguments) {
        return CliSensitiveInput.redactDiagnostic(diagnostic, descriptors, arguments);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @FunctionalInterface
    interface BaseLineFactory {
        CommandLine create();
    }

    private static final class Usage implements java.util.concurrent.Callable<Integer> {
        private CommandLine.Model.CommandSpec spec;

        @Override public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return CliExitStatus.USAGE_ERROR.code();
        }
    }

    private static final class Repl implements java.util.concurrent.Callable<Integer> {
        private final CliApplicationRunner runner;

        private Repl(CliApplicationRunner runner) {
            this.runner = runner;
        }

        @Override public Integer call() {
            return runner.repl(runner.historyFile, null, List.of());
        }
    }

    private static final class InvocationOutput implements CliOutput, AutoCloseable {
        private final CliTerminalSession terminal;
        private boolean open = true;
        private int inFlight;

        private InvocationOutput(CliTerminalSession terminal) {
            this.terminal = terminal;
        }

        @Override public void stdout(String value) {
            write(value, false);
        }

        @Override public void stderr(String value) {
            write(value, true);
        }

        @Override public void close() {
            var interrupted = false;
            synchronized (this) {
                open = false;
                while (inFlight != 0) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void write(String value, boolean standardError) {
            beginWrite();
            try {
                if (standardError) terminal.stderr(value);
                else terminal.stdout(value);
            } finally {
                endWrite();
            }
        }

        private synchronized void beginWrite() {
            if (!open) throw new IllegalStateException("CLI invocation output is closed");
            inFlight++;
        }

        private synchronized void endWrite() {
            inFlight--;
            if (inFlight == 0) notifyAll();
        }
    }

    interface InvocationTerminal extends CliTerminal, AutoCloseable {
        @Override void close();
    }

    private enum NonInteractiveTerminal implements InvocationTerminal {
        INSTANCE;

        @Override public boolean interactive() { return false; }

        @Override public CliTerminalLease acquire() {
            throw new CliTerminalUnavailableException(CliTerminalUnavailableReason.UNSUPPORTED);
        }

        @Override public void close() {
        }
    }
}
