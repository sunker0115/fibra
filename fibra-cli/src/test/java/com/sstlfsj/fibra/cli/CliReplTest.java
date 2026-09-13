package com.sstlfsj.fibra.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.impl.DumbTerminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.terminal.Terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliReplTest {
    @Test
    void noColorEnvironmentDisablesOnlyAnsiColor() {
        assertTrue(CliRepl.colorEnabled(java.util.Map.of()));
        assertFalse(CliRepl.colorEnabled(java.util.Map.of("NO_COLOR", "")));
        assertFalse(CliRepl.colorEnabled(java.util.Map.of("NO_COLOR", "1")));
    }

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
        var rendered = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, occurrences(rendered, "\"artifacts\":[]"));
        assertFalse(rendered.contains("\u001B"), rendered);
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
    void ctrlCWhileEditingClearsTheLineAndKeepsTheSessionOpen() throws Exception {
        var dispatched = new AtomicInteger();
        var input = new PipedInputStream();
        try (var feeder = new PipedOutputStream(input);
             var executor = Executors.newSingleThreadExecutor()) {
            var terminal = new InterruptibleTrackingTerminal(input);
            var running = executor.submit(() -> CliRepl.run((arguments, invocationTerminal) -> {
                assertEquals("next", arguments[0]);
                dispatched.incrementAndGet();
                return 0;
            }, terminal, writer(new ByteArrayOutputStream()), writer(new ByteArrayOutputStream())));

            assertTrue(terminal.awaitLineRead());
            terminal.raise(Terminal.Signal.INT);
            feeder.write("next\nexit\n".getBytes(StandardCharsets.UTF_8));
            feeder.flush();

            assertEquals(0, running.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, dispatched.get());
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
    void closesTheTerminalWhenPersistingHistoryFails(@TempDir Path work) throws Exception {
        var historyParent = work.resolve("history-parent");
        var historyFile = historyParent.resolve("repl.history");
        var terminal = new TrackingTerminal(false, "break\n");
        var error = new ByteArrayOutputStream();

        var exitCode = CliRepl.run((arguments, invocationTerminal) -> {
            try {
                Files.createDirectories(historyParent);
                Files.deleteIfExists(historyFile);
                Files.delete(historyParent);
                Files.writeString(historyParent, "not-a-directory");
                return 0;
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }, terminal, writer(new ByteArrayOutputStream()), writer(error), historyFile, null);

        assertEquals(7, exitCode);
        assertTrue(terminal.closed);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("保存命令历史失败:"));
    }

    @Test
    void invocationScopeRestoresALeakedTerminalLeaseBeforeTheNextLine() throws Exception {
        var acquired = new AtomicInteger();
        var terminal = new InteractiveTrackingTerminal("first\nsecond\nexit\n");
        var error = new ByteArrayOutputStream();
        var invocations = new CliInvocationCoordinator();

        var exitCode = CliRepl.run((arguments, terminals) -> {
            try (var invocation = invocations.begin();
                 var invocationTerminal = terminals.openInvocation(invocation)) {
                invocationTerminal.acquire();
                if (acquired.incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated command failure");
                }
                return 0;
            }
        }, terminal, writer(new ByteArrayOutputStream()), writer(error), null, null);

        assertEquals(0, exitCode);
        assertEquals(2, acquired.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("simulated command failure"));
    }

    @Test
    void persistsOnlyReplaySafeHistoryAndNeverLeaksToolInput(@TempDir Path home) throws Exception {
        initialize(home);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var secret = "f2-history-secret";

        assertEquals(0, execute(home, "plugins list\n"
            + "tools invoke missing run --input {\"token\":\"" + secret + "\"}\nexit\n",
            output, error, CliHost::open));

        var history = home.resolve("data/profiles/default/repl.history");
        assertTrue(Files.isRegularFile(history));
        var persisted = Files.readString(history);
        assertTrue(persisted.contains("plugins list"), persisted);
        assertTrue(persisted.contains("[敏感命令已省略]"), persisted);
        assertFalse(persisted.contains(secret), persisted);
        assertFalse(persisted.contains("tools invoke missing run --input"), persisted);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(secret));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(secret));
    }

    @Test
    void reloadsPersistedHistoryWhenTheReplRestarts(@TempDir Path home) throws Exception {
        initialize(home);

        assertEquals(0, execute(home, "plugins list\nexit\n", new ByteArrayOutputStream(),
            new ByteArrayOutputStream(), CliHost::open));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        assertEquals(0, execute(home, "exit\n", output, error, CliHost::open),
            error.toString(StandardCharsets.UTF_8));
        var persisted = Files.readString(home.resolve("data/profiles/default/repl.history"));
        assertTrue(persisted.contains("plugins list"), persisted);
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("\u001B"));
    }

    @Test
    void writesHumanSessionSummaryOnlyToStderrForANonDumbTerminal() throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var terminal = new InteractiveTrackingTerminal("exit\n");

        assertEquals(0, CliRepl.run((arguments, invocationTerminal) -> 0, terminal,
            writer(output), writer(error), null,
            "profile=default workspace=/work tools=0 revision=view-1"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertEquals("profile=default workspace=/work tools=0 revision=view-1\n",
            error.toString(StandardCharsets.UTF_8));
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

    private static class TrackingTerminal extends DumbTerminal {
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

    private static final class InteractiveTrackingTerminal extends TrackingTerminal {
        private InteractiveTrackingTerminal(String input) throws IOException {
            super(false, input);
        }

        @Override public String getType() {
            return "xterm-256color";
        }
    }

    private static final class InterruptibleTrackingTerminal extends DumbTerminal {
        private final CountDownLatch lineRead = new CountDownLatch(1);

        private InterruptibleTrackingTerminal(PipedInputStream input) throws IOException {
            super(input, new ByteArrayOutputStream());
        }

        @Override public SignalHandler handle(Signal signal, SignalHandler handler) {
            var previous = super.handle(signal, handler);
            if (signal == Signal.INT && handler != SignalHandler.SIG_DFL) lineRead.countDown();
            return previous;
        }

        private boolean awaitLineRead() throws InterruptedException {
            return lineRead.await(5, TimeUnit.SECONDS);
        }
    }
}
