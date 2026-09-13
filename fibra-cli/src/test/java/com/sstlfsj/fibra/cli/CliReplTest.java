package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.impl.DumbTerminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliReplTest {
    @Test
    void reusesOneHostForMultipleCommandsAndQuotedInput(@TempDir Path home) throws Exception {
        initialize(home);
        var opened = new AtomicInteger();
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = execute(home, "  \n\"plugins\"   \"list\"\nplugins list\nexit\n", output, error,
            paths -> {
                opened.incrementAndGet();
                return CliHost.open(paths);
            });

        assertEquals(0, exitCode);
        assertEquals(1, opened.get());
        assertEquals(2, occurrences(output.toString(StandardCharsets.UTF_8), "\"artifacts\":[]"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsGlobalOptionsWithoutReplacingTheActiveHost(@TempDir Path home) throws Exception {
        initialize(home);
        var opened = new AtomicInteger();
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = execute(home, "--profile another\nplugins list\nquit\n", output, error,
            paths -> {
                opened.incrementAndGet();
                return CliHost.open(paths);
            });

        assertEquals(0, exitCode);
        assertEquals(1, opened.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("不能在 REPL 中指定全局选项"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"artifacts\":[]"));
    }

    @Test
    void commandTreeResetsArgumentsBetweenReplExecutions(@TempDir Path home) throws Exception {
        initialize(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = execute(home,
            "tools invoke missing run --input {}\ntools invoke missing run\nhelp tools\nrepl\nexit\n",
            output, error, CliHost::open);

        assertEquals(0, exitCode);
        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("Missing required option: '--input=JSON'"), diagnostics);
        assertTrue(diagnostics.contains("不能在 REPL 中递归启动 repl"), diagnostics);
        var rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Usage: fibra tools"), rendered);
    }

    @Test
    void doesNotExpandArgumentFilesInsideTheSharedCommandTree(@TempDir Path home) throws Exception {
        initialize(home);
        var arguments = home.resolve("repl.args");
        Files.writeString(arguments, "plugins list\n");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = execute(home, "@" + arguments + "\nexit\n", output, error, CliHost::open);

        assertEquals(0, exitCode);
        assertTrue(!output.toString(StandardCharsets.UTF_8).contains("\"artifacts\":"));
        var diagnostics = error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("Unmatched argument"), diagnostics);
        assertTrue(diagnostics.contains("@" + arguments), diagnostics);
    }

    @Test
    void eofEndsTheSessionSuccessfully(@TempDir Path home) throws Exception {
        initialize(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exitCode = execute(home, "", output, error, CliHost::open);

        assertEquals(0, exitCode);
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void closesEveryOwnedTerminalAndReportsCloseFailure() throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var terminal = new TrackingTerminal(false);

        CliRepl.Dispatcher dispatcher = (arguments, invocationTerminal) -> 0;
        var exitCode = CliRepl.run(dispatcher, terminal,
            writer(output), writer(error));

        assertEquals(0, exitCode);
        assertTrue(terminal.closed);
        var failing = new TrackingTerminal(true);
        assertEquals(7, CliRepl.run(dispatcher, failing,
            writer(new ByteArrayOutputStream()), writer(error)));
        assertTrue(failing.closed);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("关闭交互终端失败: simulated"));
    }

    @Test
    void invocationScopeRestoresALeakedTerminalLeaseBeforeTheNextLine() throws Exception {
        var acquired = new AtomicInteger();
        var terminal = new TrackingTerminal(false, "first\nsecond\nexit\n");
        var error = new ByteArrayOutputStream();

        var exitCode = CliRepl.run((arguments, invocationTerminal) -> {
            invocationTerminal.acquire();
            if (acquired.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated command failure");
            }
            return 0;
        }, terminal, writer(new ByteArrayOutputStream()), writer(error));

        assertEquals(0, exitCode);
        assertEquals(2, acquired.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("simulated command failure"));
    }

    private static int execute(Path home, String input, ByteArrayOutputStream output,
                               ByteArrayOutputStream error,
                               java.util.function.Function<CliPaths, CliHost> factory) {
        return FibraCli.execute(new String[] {"--home", home.toString(), "repl"},
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), writer(output), writer(error),
            factory, false);
    }

    private static void initialize(Path home) throws Exception {
        var profiles = Files.createDirectories(home.resolve("config/profiles"));
        Files.writeString(profiles.resolve("default.yaml"), "[]\n");
        Files.writeString(profiles.resolve("default.artifacts.yaml"), "[]\n");
    }

    private static PrintWriter writer(ByteArrayOutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String expected) {
        return text.split(java.util.regex.Pattern.quote(expected), -1).length - 1;
    }

    private static final class TrackingTerminal extends DumbTerminal {
        private final boolean failClose;
        private boolean closed;

        private TrackingTerminal(boolean failClose) throws IOException {
            this(failClose, "exit\n");
        }

        private TrackingTerminal(boolean failClose, String input) throws IOException {
            super(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
            this.failClose = failClose;
        }

        @Override protected void doClose() throws IOException {
            closed = true;
            if (failClose) throw new IOException("simulated");
            super.doClose();
        }
    }
}
