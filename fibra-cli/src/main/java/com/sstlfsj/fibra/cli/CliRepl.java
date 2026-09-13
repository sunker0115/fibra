package com.sstlfsj.fibra.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 在同一宿主中按行捕获命令代并连续执行命令的交互入口。 */
final class CliRepl {
    private static final List<String> GLOBAL_OPTIONS = List.of("--home", "--profile", "--config-root",
        "--plugins-root", "--data-root", "--node");

    private CliRepl() {
    }

    static int run(Dispatcher dispatcher, InputStream input, PrintWriter output, PrintWriter error,
                   boolean useSystemTerminal) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        final Terminal terminal;
        try {
            if (useSystemTerminal) {
                terminal = TerminalBuilder.terminal();
            } else {
                terminal = new DumbTerminal(input, new PrintWriterOutputStream(output));
            }
        } catch (IOException failure) {
            error.println("无法启动交互终端: " + failure.getMessage());
            return 3;
        }
        return run(dispatcher, terminal, output, error);
    }

    static int run(Dispatcher dispatcher, Terminal terminal, PrintWriter output,
                   PrintWriter error) {
        final int result;
        try {
            var reader = LineReaderBuilder.builder().terminal(terminal).build();
            try (var terminalController = new CliTerminalController(
                terminal.input(), terminal.writer(), true)) {
                result = readLines(reader, dispatcher, terminalController, output, error);
            }
        } catch (RuntimeException | Error failure) {
            try {
                terminal.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        try {
            terminal.close();
        } catch (IOException failure) {
            error.println("关闭交互终端失败: " + failure.getMessage());
            return 7;
        }
        return result;
    }

    private static int readLines(LineReader reader, Dispatcher dispatcher,
                                 CliTerminalController terminalController,
                                 PrintWriter output, PrintWriter error) {
        var parser = new DefaultParser();
        while (true) {
            final String line;
            try {
                line = reader.readLine("fibra> ");
            } catch (EndOfFileException ignored) {
                return 0;
            } catch (UserInterruptException ignored) {
                return 130;
            }
            var arguments = arguments(parser, line, error);
            if (arguments == null || arguments.length == 0) continue;
            if (arguments[0].equals("exit") || arguments[0].equals("quit")) return 0;
            if (hasGlobalOption(arguments)) {
                error.println("不能在 REPL 中指定全局选项");
                continue;
            }
            if (arguments[0].equals("repl")) {
                error.println("不能在 REPL 中递归启动 repl");
                continue;
            }
            try (var invocationTerminal = terminalController.openInvocation()) {
                dispatcher.execute(arguments, invocationTerminal);
            } catch (RuntimeException failure) {
                error.println("命令执行失败: " + failure.getMessage());
            }
        }
    }

    @FunctionalInterface
    interface Dispatcher {
        int execute(String[] arguments, com.sstlfsj.fibra.cli.api.CliTerminal terminal);
    }

    private static String[] arguments(DefaultParser parser, String line, PrintWriter error) {
        try {
            return parser.parse(line, line.length()).words().stream().filter(word -> !word.isBlank())
                .toArray(String[]::new);
        } catch (RuntimeException failure) {
            error.println("命令解析失败: " + failure.getMessage());
            return null;
        }
    }

    private static boolean hasGlobalOption(String[] arguments) {
        for (var argument : arguments) {
            for (var option : GLOBAL_OPTIONS) {
                if (argument.equals(option) || argument.startsWith(option + "=")) return true;
            }
        }
        return false;
    }

    private static final class PrintWriterOutputStream extends OutputStream {
        private final PrintWriter writer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private PrintWriterOutputStream(PrintWriter writer) {
            this.writer = writer;
        }

        @Override public void write(int value) {
            buffer.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            buffer.write(bytes, offset, length);
        }

        @Override public void flush() {
            if (buffer.size() != 0) {
                writer.print(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            }
            writer.flush();
        }

        @Override public void close() {
            flush();
        }
    }
}
