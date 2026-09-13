package com.sstlfsj.fibra.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import picocli.CommandLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在同一宿主中按行捕获命令代并连续执行命令的交互入口。 */
final class CliRepl {
    private static final List<String> GLOBAL_OPTIONS = List.of("--home", "--profile", "--config-root",
        "--plugins-root", "--data-root", "--node");

    private CliRepl() {
    }

    static int run(Dispatcher dispatcher, InputStream input, PrintWriter output, PrintWriter error,
                   boolean useSystemTerminal) {
        return run(dispatcher, input, output, error, useSystemTerminal, null);
    }

    static int run(Dispatcher dispatcher, InputStream input, PrintWriter output, PrintWriter error,
                   boolean useSystemTerminal, java.nio.file.Path historyFile) {
        return run(dispatcher, input, output, error, useSystemTerminal, historyFile, null);
    }

    static int run(Dispatcher dispatcher, InputStream input, PrintWriter output, PrintWriter error,
                   boolean useSystemTerminal, java.nio.file.Path historyFile, String sessionSummary) {
        return run(dispatcher, input, output, error, useSystemTerminal, historyFile,
            sessionSummary, "fibra> ", GLOBAL_OPTIONS);
    }

    static int run(Dispatcher dispatcher, InputStream input, PrintWriter output, PrintWriter error,
                   boolean useSystemTerminal, java.nio.file.Path historyFile, String sessionSummary,
                   String prompt, List<String> forbiddenRootOptions) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        final CliTerminalSession terminal;
        try {
            terminal = CliTerminalSession.open(input, output, error, useSystemTerminal, null);
        } catch (RuntimeException failure) {
            error.println("无法启动交互终端: " + failure.getMessage());
            return 3;
        }
        return runOwned(dispatcher, terminal, historyFile, sessionSummary, prompt,
            forbiddenRootOptions);
    }

    static int run(Dispatcher dispatcher, Terminal terminal, PrintWriter output,
                   PrintWriter error) {
        return run(dispatcher, terminal, output, error, null, null,
            "fibra> ", GLOBAL_OPTIONS);
    }

    private static int run(Dispatcher dispatcher, Terminal terminal, PrintWriter output,
                           PrintWriter error, java.nio.file.Path historyFile) {
        return run(dispatcher, terminal, output, error, historyFile, null);
    }

    static int run(Dispatcher dispatcher, Terminal terminal, PrintWriter output,
                   PrintWriter error, java.nio.file.Path historyFile, String sessionSummary) {
        return run(dispatcher, terminal, output, error, historyFile, sessionSummary,
            "fibra> ", GLOBAL_OPTIONS);
    }

    private static int run(Dispatcher dispatcher, Terminal terminal, PrintWriter output,
                           PrintWriter error, java.nio.file.Path historyFile,
                           String sessionSummary, String prompt,
                           List<String> forbiddenRootOptions) {
        var session = CliTerminalSession.open(InputStream.nullInputStream(), output, error,
            false, terminal);
        return runOwned(dispatcher, session, historyFile, sessionSummary,
            prompt, forbiddenRootOptions);
    }

    private static int runOwned(Dispatcher dispatcher, CliTerminalSession terminal,
                                java.nio.file.Path historyFile, String sessionSummary,
                                String prompt, List<String> forbiddenRootOptions) {
        int result;
        try {
            result = run(dispatcher, terminal, historyFile, sessionSummary, prompt,
                forbiddenRootOptions);
        } catch (RuntimeException | Error failure) {
            try {
                terminal.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        try {
            terminal.close();
        } catch (RuntimeException failure) {
            terminal.errorWriter().println(failure.getMessage());
            return 7;
        }
        return result;
    }

    static int run(Dispatcher dispatcher, CliTerminalSession terminal,
                   java.nio.file.Path historyFile, String sessionSummary,
                   String prompt, List<String> forbiddenRootOptions) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(terminal, "terminal");
        var inputMode = dispatcher instanceof InputDispatcher candidate && candidate.inputMode();
        CliHistory history = historyFile == null ? null : new CliHistory(historyFile);
        var completer = new CliCommandCompleter();
        var highlighter = new CliCommandHighlighter();
        var builder = LineReaderBuilder.builder().terminal(terminal.terminal());
        if (!inputMode) builder.completer(completer).highlighter(highlighter);
        if (history != null) builder.history(history)
            .variable(LineReader.HISTORY_FILE, historyFile);
        var reader = builder.build();
        if (history != null) history.attach(reader);
        if (sessionSummary != null && terminal.interactive()) terminal.printAbove(sessionSummary);
        var result = readLines(reader, dispatcher, terminal, history, completer, highlighter,
            terminal.outputWriter(), terminal.errorWriter(), prompt,
            forbiddenRootOptions);
        if (history != null) try {
            history.savePersisted();
        } catch (IOException failure) {
            terminal.errorWriter().println("保存命令历史失败: " + failure.getMessage());
            return 7;
        }
        return result;
    }

    static boolean colorEnabled(Map<String, ?> environment) {
        return !environment.containsKey("NO_COLOR");
    }

    private static int readLines(LineReader reader, Dispatcher dispatcher,
                                 CliTerminalSession terminal,
                                 CliHistory history, CliCommandCompleter completer,
                                 CliCommandHighlighter highlighter,
                                 PrintWriter output, PrintWriter error, String prompt,
                                 List<String> forbiddenRootOptions) {
        var parser = new DefaultParser();
        var inputMode = dispatcher instanceof InputDispatcher candidate && candidate.inputMode();
        while (true) {
            var generated = !inputMode && dispatcher instanceof GeneratedDispatcher candidate
                ? candidate.capture() : null;
            if (!inputMode && dispatcher instanceof GeneratedDispatcher candidate) {
                if (history != null) {
                    history.commandDescriptors(candidate.historyDescriptors(generated));
                }
                var completionLine = candidate.completionLine(generated);
                completer.commandLine(completionLine);
                completer.commandGeneration(generated);
                highlighter.commandLine(completionLine);
            } else if (history != null) {
                history.commandDescriptors(List.of());
            }
            final String line;
            try {
                line = terminal.readLine(reader, prompt);
            } catch (EndOfFileException ignored) {
                return 0;
            } catch (UserInterruptException ignored) {
                if (terminal.stopping()) return 0;
                continue;
            }
            if (inputMode && dispatcher instanceof InputDispatcher candidate) {
                try {
                    var outcome = candidate.executeInput(line, terminal.controller());
                    if (outcome.exitRequested()) return outcome.status();
                } catch (RuntimeException failure) {
                    error.println("输入执行失败: " + failure.getMessage());
                }
                if (terminal.stopping()) return 0;
                continue;
            }
            var arguments = arguments(parser, line, error);
            if (arguments == null || arguments.length == 0) continue;
            if (arguments[0].equals("exit") || arguments[0].equals("quit")) return 0;
            if (hasForbiddenOption(arguments, forbiddenRootOptions)) {
                error.println("不能在 REPL 中指定全局选项");
                continue;
            }
            if (arguments[0].equals("repl")) {
                error.println("不能在 REPL 中递归启动 repl");
                continue;
            }
            try {
                if (dispatcher instanceof GeneratedDispatcher candidate) {
                    candidate.execute(generated, arguments, terminal.controller());
                } else {
                    dispatcher.execute(arguments, terminal.controller());
                }
            } catch (RuntimeException failure) {
                error.println("命令执行失败: " + failure.getMessage());
            }
            if (terminal.stopping()) return 0;
        }
    }

    @FunctionalInterface
    interface Dispatcher {
        int execute(String[] arguments, CliTerminalController terminals);
    }

    interface GeneratedDispatcher extends Dispatcher {
        CommandGeneration capture();

        CommandLine completionLine(CommandGeneration generation);

        int execute(CommandGeneration generation, String[] arguments,
                    CliTerminalController terminals);

        default List<com.sstlfsj.fibra.cli.api.CliCommandDescriptor> historyDescriptors(
            CommandGeneration generation) {
            return generation.commands().stream().map(CommandGeneration.Command::descriptor).toList();
        }

        @Override
        default int execute(String[] arguments, CliTerminalController terminals) {
            return execute(capture(), arguments, terminals);
        }
    }

    interface InputDispatcher extends GeneratedDispatcher {
        boolean inputMode();

        InputDispatch executeInput(String text, CliTerminalController terminals);
    }

    record InputDispatch(int status, boolean exitRequested) {
    }

    private static String[] arguments(DefaultParser parser, String line, PrintWriter error) {
        try {
            return parser.parse(line, line.length()).words().stream().filter(word -> !word.isBlank())
                .toArray(String[]::new);
        } catch (RuntimeException failure) {
            error.println("命令解析失败");
            return null;
        }
    }

    private static boolean hasForbiddenOption(String[] arguments, List<String> options) {
        for (var argument : arguments) {
            for (var option : options) {
                if (argument.equals(option) || argument.startsWith(option + "=")) return true;
            }
        }
        return false;
    }

}
