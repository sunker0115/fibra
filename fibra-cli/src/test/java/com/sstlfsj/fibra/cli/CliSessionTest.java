package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionSnapshot;
import com.sstlfsj.fibra.bridge.ContributionSnapshotEntry;
import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliBootstrapCommand;
import com.sstlfsj.fibra.cli.api.CliCommandContributions;
import com.sstlfsj.fibra.cli.api.CliCommandDescriptor;
import com.sstlfsj.fibra.cli.api.CliCommandResult;
import com.sstlfsj.fibra.cli.api.CliInputResult;
import com.sstlfsj.fibra.cli.api.CliTerminalFrame;
import com.sstlfsj.fibra.cli.api.CliTerminalRenderer;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.engine.DurableTargetState;
import com.sstlfsj.fibra.engine.EngineDiagnostics;
import com.sstlfsj.fibra.engine.EngineSnapshot;
import com.sstlfsj.fibra.engine.EngineState;
import com.sstlfsj.fibra.engine.TargetConvergence;
import com.sstlfsj.fibra.engine.PublishedRuntime;
import com.sstlfsj.fibra.engine.PublishedView;
import com.sstlfsj.fibra.engine.RuntimeDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.reader.LineReader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSessionTest {
    @Test
    void customApplicationContainsOnlyGenericAndExplicitCommands(@TempDir Path home) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var runtime = new StubPublishedRuntime(emptyView());
        var application = CliApplication.builder("agent")
            .description("组合型 Agent 命令入口。")
            .version("1.2.3")
            .addBootstrapCommand(new CliBootstrapCommand(command("status"),
                request -> CliCommandResult.success()))
            .build();

        try (var session = CliSession.builder(application, runtime, profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(output), writer(error))
            .build()) {
            assertEquals(0, session.execute("--help"));
            var help = output.toString(StandardCharsets.UTF_8);
            assertTrue(help.contains("Usage: agent"), help);
            assertTrue(help.contains("status"), help);
            assertTrue(help.contains("repl"), help);
            assertFalse(help.contains("plugins"), help);
            assertFalse(help.contains("tools"), help);
            assertFalse(help.contains("apply"), help);
            assertFalse(help.contains("--home"), help);
            assertFalse(help.contains("--node"), help);
            assertEquals(0, runtime.currentCalls);
        }
    }

    @Test
    void sessionUsesAndNeverClosesTheBorrowedPublishedRuntime(@TempDir Path home) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var descriptor = command("echo");
        var runtime = new StubPublishedRuntime(view(descriptor));
        var application = CliApplication.builder("agent").version("1").build();
        var session = CliSession.builder(application, runtime, profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(output), writer(error))
            .build();

        assertEquals(0, session.execute("echo"));
        assertTrue(runtime.invoked);
        session.close();

        assertEquals("view-1", runtime.current().viewRevision());
        assertThrows(IllegalStateException.class, () -> session.execute("echo"));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void closeCancelsAndWaitsForTheSessionsActiveInvocation(@TempDir Path home) throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var application = CliApplication.builder("agent").version("1")
            .addBootstrapCommand(new CliBootstrapCommand(command("wait"), request -> {
                entered.countDown();
                while (!request.invocation().cancellation().isCancelled()) {
                    Thread.onSpinWait();
                }
                cancelled.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return CliCommandResult.success();
            })).build();
        var session = CliSession.builder(application, new StubPublishedRuntime(emptyView()),
                profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(new ByteArrayOutputStream()))
            .build();
        var workers = Executors.newFixedThreadPool(3);
        try {
            var execution = workers.submit(() -> session.execute("wait"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> session.execute("wait"));

            var closing = workers.submit(() -> {
                session.close();
                return null;
            });
            var repeatedClose = workers.submit(() -> {
                session.close();
                return null;
            });
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertFalse(closing.isDone(), "close 必须等待 handler 和调用清理完成");
            assertFalse(repeatedClose.isDone(), "重复 close 必须等待同一个完成屏障");
            release.countDown();

            assertEquals(130, execution.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
            repeatedClose.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            session.close();
        }
    }

    @Test
    void dumbReplSuppressesInteractivePromptAndDoesNotCloseBorrowedStreams(@TempDir Path home) {
        var input = new TrackingInputStream("exit\n".getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var session = CliSession.builder(CliApplication.builder("agent").version("1").build(),
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(input, writer(output), writer(error)).build();

        assertEquals(0, session.execute("repl"));
        session.close();

        assertFalse(output.toString(StandardCharsets.UTF_8).contains("agent> "));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertFalse(input.closed);
    }

    @Test
    void inputHandlerReceivesEachRawLineAsItsOwnFiniteInvocation(@TempDir Path home)
        throws Exception {
        var input = new TrackingInputStream(("  分析 \"这个项目  \nexit\n")
            .getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var texts = new ArrayList<String>();
        var cancellations = new ArrayList<com.sstlfsj.fibra.CancellationToken>();
        var invocationOutput = new AtomicReference<com.sstlfsj.fibra.cli.api.CliOutput>();
        var application = CliApplication.builder("agent").version("1")
            .inputHandler(request -> {
                texts.add(request.text());
                cancellations.add(request.invocation().cancellation());
                invocationOutput.set(request.invocation().output());
                request.invocation().output().stdout("handled:" + request.text());
                return CliInputResult.continueWith(CliCommandResult.success());
            }).build();
        var runtime = new StubPublishedRuntime(emptyView());
        var history = home.resolve("history/repl.history");

        try (var session = CliSession.builder(application, runtime, profile(home))
            .streams(input, writer(output), writer(error)).historyFile(history).build()) {
            assertEquals(0, session.execute("repl"));
        }

        assertEquals(List.of("  分析 \"这个项目  ", "exit"), texts);
        assertEquals(2, cancellations.size());
        assertFalse(cancellations.get(0) == cancellations.get(1));
        assertThrows(IllegalStateException.class,
            () -> invocationOutput.get().stdout("late"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(
            "handled:  分析 \"这个项目  "));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("handled:exit"));
        assertEquals(0, runtime.currentCalls,
            "原样输入不得为了补全或解析借用命令代");
        assertFalse(Files.exists(history),
            "自然语言输入由产品 Session 持久化，不得写入通用命令历史");
        assertFalse(input.closed);
    }

    @Test
    void inputHandlerCanRequestReplExitWithoutAReservedFrameworkCommand(@TempDir Path home) {
        var input = new TrackingInputStream("first\n/exit\nignored\n"
            .getBytes(StandardCharsets.UTF_8));
        var texts = new ArrayList<String>();
        var application = CliApplication.builder("agent").version("1")
            .inputHandler(request -> {
                texts.add(request.text());
                return request.text().equals("/exit")
                    ? CliInputResult.exitWith(CliCommandResult.success())
                    : CliInputResult.continueWith(CliCommandResult.success());
            }).build();

        try (var session = CliSession.builder(application,
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(input, writer(new ByteArrayOutputStream()),
                writer(new ByteArrayOutputStream()))
            .build()) {
            assertEquals(0, session.execute("repl"));
        }

        assertEquals(List.of("first", "/exit"), texts);
        assertFalse(input.closed);
    }

    @Test
    void systemTerminalIsAvailableToAOneShotCommandAndRestoredBeforeReturn(
        @TempDir Path home) throws Exception {
        var terminalOutput = new ByteArrayOutputStream();
        var terminal = new SessionTerminal(new ByteArrayInputStream(new byte[0]), terminalOutput);
        var application = CliApplication.builder("agent").version("1")
            .addBootstrapCommand(new CliBootstrapCommand(command("screen"), request -> {
                assertTrue(request.invocation().terminal().interactive());
                try (var lease = request.invocation().terminal().acquire()) {
                    lease.run(new CliTerminalRenderer() {
                        @Override public void start(
                            com.sstlfsj.fibra.cli.api.CliTerminalControl control) {
                            control.finish();
                        }

                        @Override public CliTerminalFrame render(
                            com.sstlfsj.fibra.cli.api.CliTerminalSize size) {
                            return new CliTerminalFrame(List.of(), java.util.Optional.empty());
                        }
                    });
                }
                return CliCommandResult.success();
            })).build();
        try (var session = CliSession.builder(application,
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(new ByteArrayOutputStream()))
            .terminal(terminal)
            .build()) {
            assertEquals(0, session.execute("screen"));
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            assertFalse(terminal.closed);
        }
        assertTrue(terminal.closed);
    }

    @Test
    void distinctSessionsOwnIndependentTerminalLanesWhileSharingOneRuntime(
        @TempDir Path home) throws Exception {
        var renderersEntered = new CountDownLatch(2);
        var releaseRenderers = new CountDownLatch(1);
        var descriptor = command("screen");
        var published = view(descriptor);
        var runtimeInvocations = new AtomicInteger();
        var runtime = new PublishedRuntime() {
            @Override public PublishedView current() {
                return published;
            }

            @Override public Flux<PublishedView> views() {
                return Flux.just(published);
            }

            @Override @SuppressWarnings("unchecked")
            public <D, I, O> Mono<O> invoke(String expectedViewRevision,
                                            long expectedRegistrationIdentity,
                                            ContributionKind<D, I, O> kind,
                                            ContributionId id, I input) {
                var request = (com.sstlfsj.fibra.cli.api.CliCommandRequest) input;
                return (Mono<O>) Mono.fromCallable(() -> {
                    runtimeInvocations.incrementAndGet();
                    try (var lease = request.invocation().terminal().acquire()) {
                        lease.run(new CliTerminalRenderer() {
                            @Override public void start(
                                com.sstlfsj.fibra.cli.api.CliTerminalControl control)
                                throws Exception {
                                renderersEntered.countDown();
                                assertTrue(releaseRenderers.await(5, TimeUnit.SECONDS));
                                control.finish();
                            }

                            @Override public CliTerminalFrame render(
                                com.sstlfsj.fibra.cli.api.CliTerminalSize size) {
                                return new CliTerminalFrame(List.of(),
                                    java.util.Optional.empty());
                            }
                        });
                    }
                    return CliCommandResult.success();
                });
            }
        };
        var application = CliApplication.builder("agent").version("1").build();
        var terminalA = new SessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var terminalB = new SessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var errorA = new ByteArrayOutputStream();
        var errorB = new ByteArrayOutputStream();
        var sessionA = CliSession.builder(application, runtime, profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(errorA)).terminal(terminalA).build();
        var sessionB = CliSession.builder(application, runtime, profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(errorB)).terminal(terminalB).build();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var executionA = workers.submit(() -> sessionA.execute("screen"));
            var executionB = workers.submit(() -> sessionB.execute("screen"));

            assertTrue(renderersEntered.await(5, TimeUnit.SECONDS),
                "不同物理终端不得被进程级全局 lane 串行化");
            releaseRenderers.countDown();
            assertEquals(0, executionA.get(5, TimeUnit.SECONDS));
            assertEquals(0, executionB.get(5, TimeUnit.SECONDS));
            assertEquals(2, runtimeInvocations.get());

            sessionA.close();
            assertTrue(terminalA.closed);
            assertFalse(terminalB.closed);
            assertTrue(sessionB.printAbove("terminal-b-still-open"));
            assertFalse(errorA.toString(StandardCharsets.UTF_8)
                .contains("terminal-b-still-open"));
            assertTrue(errorB.toString(StandardCharsets.UTF_8)
                .contains("terminal-b-still-open"));
        } finally {
            releaseRenderers.countDown();
            sessionA.close();
            sessionB.close();
            workers.shutdownNow();
        }
    }

    @Test
    void asynchronousHumanMessageUsesPrintAboveAndPreservesTheEditedLine(
        @TempDir Path home) throws Exception {
        var input = new java.io.PipedInputStream();
        var feeder = new java.io.PipedOutputStream(input);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var terminal = new SessionTerminal(input, stderr);
        var session = CliSession.builder(CliApplication.builder("agent").version("1").build(),
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(input, writer(stdout), writer(stderr)).terminal(terminal).build();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var running = worker.submit(() -> session.execute("repl"));
            assertTrue(terminal.awaitLineRead());
            feeder.write("he".getBytes(StandardCharsets.UTF_8));
            feeder.flush();

            assertTrue(session.printAbove("background-ready"));
            feeder.write("lp\nexit\n".getBytes(StandardCharsets.UTF_8));
            feeder.flush();

            assertEquals(0, running.get(5, TimeUnit.SECONDS));
            assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: agent"));
            assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("agent> "));
            assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("background-ready"));
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("background-ready"));
        } finally {
            feeder.close();
            session.close();
            worker.shutdownNow();
        }
    }

    @Test
    void lineEditorHandoffWaitsForAnAdmittedPrintAbove() throws Exception {
        var readEntered = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var printEntered = new CountDownLatch(1);
        var releasePrint = new CountDownLatch(1);
        var reader = (LineReader) java.lang.reflect.Proxy.newProxyInstance(
            LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "readLine" -> {
                    readEntered.countDown();
                    assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
                    yield "screen";
                }
                case "printAbove" -> {
                    printEntered.countDown();
                    assertTrue(releasePrint.await(5, TimeUnit.SECONDS));
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var terminal = new SessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var session = CliTerminalSession.open(new ByteArrayInputStream(new byte[0]),
            writer(new ByteArrayOutputStream()), writer(new ByteArrayOutputStream()), false,
            terminal);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var reading = workers.submit(() -> session.readLine(reader, "agent> "));
            assertTrue(readEntered.await(5, TimeUnit.SECONDS));
            var message = workers.submit(() -> session.printAbove("ready"));
            assertTrue(printEntered.await(5, TimeUnit.SECONDS));

            releaseRead.countDown();
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> reading.get(100, TimeUnit.MILLISECONDS),
                "编辑器已准入的消息完成前不得把物理终端交给 renderer");
            releasePrint.countDown();

            assertTrue(message.get(5, TimeUnit.SECONDS));
            assertEquals("screen", reading.get(5, TimeUnit.SECONDS));
        } finally {
            releaseRead.countDown();
            releasePrint.countDown();
            workers.shutdownNow();
            session.close();
        }
    }

    @Test
    void stopWakesTheLineReaderBeforeJLineInstallsItsOwnSignalHandler() throws Exception {
        var readEntered = new CountDownLatch(1);
        var reader = (LineReader) java.lang.reflect.Proxy.newProxyInstance(
            LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("readLine")) {
                    readEntered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        throw new org.jline.reader.EndOfFileException();
                    }
                    throw new AssertionError("unreachable");
                }
                throw new UnsupportedOperationException(method.getName());
            });
        var terminal = new SessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var session = CliTerminalSession.open(new ByteArrayInputStream(new byte[0]),
            writer(new ByteArrayOutputStream()), writer(new ByteArrayOutputStream()), false,
            terminal);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var reading = worker.submit(() -> session.readLine(reader, "agent> "));
            assertTrue(readEntered.await(5, TimeUnit.SECONDS));

            session.requestStop();

            var failure = assertThrows(ExecutionException.class,
                () -> reading.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof org.jline.reader.EndOfFileException);
        } finally {
            worker.shutdownNow();
            session.close();
        }
    }

    @Test
    void lineEditingWaitsForAnAdmittedNonEditorMessage() throws Exception {
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var error = new PrintWriter(new Writer() {
            @Override public void write(char[] buffer, int offset, int length) {
                writeEntered.countDown();
                try {
                    releaseWrite.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", failure);
                }
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        }, true);
        var readEntered = new CountDownLatch(1);
        var reader = (LineReader) java.lang.reflect.Proxy.newProxyInstance(
            LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "readLine" -> {
                    readEntered.countDown();
                    yield "screen";
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var terminal = new SessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var session = CliTerminalSession.open(new ByteArrayInputStream(new byte[0]),
            writer(new ByteArrayOutputStream()), error, false, terminal);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var message = workers.submit(() -> session.printAbove("ready"));
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS));
            var reading = workers.submit(() -> session.readLine(reader, "agent> "));

            assertFalse(readEntered.await(100, TimeUnit.MILLISECONDS),
                "已准入消息完成前不得进入下一轮行编辑");
            releaseWrite.countDown();

            assertTrue(message.get(5, TimeUnit.SECONDS));
            assertEquals("screen", reading.get(5, TimeUnit.SECONDS));
        } finally {
            releaseWrite.countDown();
            workers.shutdownNow();
            session.close();
        }
    }

    @Test
    void repeatedSessionCloseWrapsTheSharedFailureForEachObserver(@TempDir Path home)
        throws Exception {
        var terminal = new FailingCloseSessionTerminal(new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
        var session = CliSession.builder(CliApplication.builder("agent").version("1").build(),
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(new ByteArrayOutputStream()))
            .terminal(terminal).build();

        var failure = assertThrows(IllegalStateException.class, () -> {
            try (session) {
                session.close();
            }
        });

        assertEquals("CLI session close failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertNotSame(failure, failure.getSuppressed()[0]);
        assertEquals("CLI session close failed", failure.getSuppressed()[0].getMessage());
        assertNotSame(failure, assertThrows(IllegalStateException.class, session::close));
    }

    @Test
    void closeWakesAnIdleReplAndWaitsForTerminalRestorationWithoutClosingBorrowedInput(
        @TempDir Path home) throws Exception {
        var input = new WaitingInputStream();
        var terminal = new SessionTerminal(input, new ByteArrayOutputStream());
        var session = CliSession.builder(CliApplication.builder("agent").version("1").build(),
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(input, writer(new ByteArrayOutputStream()), writer(new ByteArrayOutputStream()))
            .terminal(terminal).build();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var running = workers.submit(() -> session.execute("repl"));
            assertTrue(terminal.awaitLineRead());
            var closing = workers.submit(() -> {
                session.close();
                return null;
            });

            closing.get(5, TimeUnit.SECONDS);
            assertEquals(0, running.get(5, TimeUnit.SECONDS));
            assertTrue(terminal.closed);
            assertFalse(input.closed);
            assertFalse(session.printAbove("too-late"));
        } finally {
            input.release();
            workers.shutdownNow();
            session.close();
        }
    }

    @Test
    void invocationOutputRejectsWritesAfterTheInvocationEnds(@TempDir Path home) {
        var captured = new AtomicReference<com.sstlfsj.fibra.cli.api.CliOutput>();
        var application = CliApplication.builder("agent").version("1")
            .addBootstrapCommand(new CliBootstrapCommand(command("capture"), request -> {
                captured.set(request.invocation().output());
                return CliCommandResult.success();
            })).build();
        try (var session = CliSession.builder(application,
                new StubPublishedRuntime(emptyView()), profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(new ByteArrayOutputStream()),
                writer(new ByteArrayOutputStream()))
            .build()) {
            assertEquals(0, session.execute("capture"));
            assertThrows(IllegalStateException.class, () -> captured.get().stdout("late"));
            assertThrows(IllegalStateException.class, () -> captured.get().stderr("late"));
        }
    }

    @Test
    void rendererAndBackgroundProducerCanUseTheSameInvocationOutputWithoutDeadlock(
        @TempDir Path home) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var terminal = new SessionTerminal(new ByteArrayInputStream(new byte[0]), stderr);
        var producerStarted = new CountDownLatch(1);
        var producerDone = new CountDownLatch(1);
        var producer = new AtomicReference<Thread>();
        var application = CliApplication.builder("agent").version("1")
            .addBootstrapCommand(new CliBootstrapCommand(command("screen"), request -> {
                var invocationOutput = request.invocation().output();
                try (var lease = request.invocation().terminal().acquire()) {
                    lease.run(new CliTerminalRenderer() {
                        @Override public void start(
                            com.sstlfsj.fibra.cli.api.CliTerminalControl control)
                            throws Exception {
                            producer.set(Thread.ofVirtual().start(() -> {
                                producerStarted.countDown();
                                invocationOutput.stdout("background");
                                producerDone.countDown();
                            }));
                            assertTrue(producerStarted.await(5, TimeUnit.SECONDS));
                            assertTrue(awaitState(producer.get(), Thread.State.WAITING),
                                "后台输出必须先等待 terminal lane");
                            invocationOutput.stderr("renderer");
                            control.finish();
                        }

                        @Override public CliTerminalFrame render(
                            com.sstlfsj.fibra.cli.api.CliTerminalSize size) {
                            return new CliTerminalFrame(List.of("frame"),
                                java.util.Optional.empty());
                        }
                    });
                }
                assertTrue(producerDone.await(5, TimeUnit.SECONDS));
                return CliCommandResult.success();
            })).build();
        var session = CliSession.builder(application, new StubPublishedRuntime(emptyView()),
                profile(home))
            .streams(new ByteArrayInputStream(new byte[0]), writer(stdout), writer(stderr))
            .terminal(terminal).build();
        var completed = new CompletableFuture<Integer>();
        Thread.ofVirtual().start(() -> {
            try {
                completed.complete(session.execute("screen"));
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });

        assertEquals(0, completed.get(2, TimeUnit.SECONDS));
        assertTrue(producerDone.await(2, TimeUnit.SECONDS));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("background"));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("renderer"));
        session.close();
    }

    private static boolean awaitState(Thread thread, Thread.State expected)
        throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) return true;
            Thread.sleep(1);
        }
        return false;
    }

    private static CliCommandDescriptor command(String name) {
        return new CliCommandDescriptor(List.of(name), "测试命令。", List.of(), null, List.of());
    }

    private static CliProfile profile(Path home) {
        return new CliProfile("test", home, home.resolve("config"), home.resolve("plugins"),
            home.resolve("data"));
    }

    private static PrintWriter writer(ByteArrayOutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    private static PublishedView view(CliCommandDescriptor descriptor) {
        return view(List.of(new ContributionSnapshotEntry(new ContributionId("provider", "echo"),
            41L, CliCommandContributions.KIND.name(), descriptor)));
    }

    private static PublishedView emptyView() {
        return view(List.of());
    }

    private static PublishedView view(List<ContributionSnapshotEntry> contributions) {
        return new PublishedView("view-1", new EngineSnapshot(EngineState.RUNNING,
            "host", DurableTargetState.ABSENT, TargetConvergence.ABSENT,
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty()),
            new ContributionSnapshot(1L, contributions),
            RuntimeDiagnostics.builder().domainName("test").plugins(List.of()).services(List.of())
                .events(List.of()).build(),
            new EngineDiagnostics(java.util.Optional.empty(), true, true, List.of(),
                java.util.Optional.empty(), java.util.Optional.empty()));
    }

    private static final class StubPublishedRuntime implements PublishedRuntime {
        private final PublishedView view;
        private int currentCalls;
        private boolean invoked;

        private StubPublishedRuntime(PublishedView view) {
            this.view = view;
        }

        @Override public PublishedView current() {
            currentCalls++;
            return view;
        }

        @Override public Flux<PublishedView> views() {
            return Flux.just(view);
        }

        @Override @SuppressWarnings("unchecked")
        public <D, I, O> Mono<O> invoke(String expectedViewRevision,
                                        long expectedRegistrationIdentity,
                                        ContributionKind<D, I, O> kind,
                                        ContributionId id, I input) {
            assertEquals("view-1", expectedViewRevision);
            assertEquals(41L, expectedRegistrationIdentity);
            assertEquals(CliCommandContributions.KIND, kind);
            assertEquals(new ContributionId("provider", "echo"), id);
            invoked = true;
            return Mono.just((O) CliCommandResult.success());
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] contents) {
            delegate = new ByteArrayInputStream(contents);
        }

        @Override public int read() {
            return delegate.read();
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    private static final class WaitingInputStream extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean closed;

        @Override public int read() throws IOException {
            entered.countDown();
            try {
                release.await();
                return -1;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("test interrupted");
            }
        }

        @Override public void close() {
            closed = true;
            release.countDown();
        }

        private void release() {
            release.countDown();
        }
    }

    private static class SessionTerminal extends DumbTerminal {
        private final CountDownLatch lineRead = new CountDownLatch(1);
        private boolean closed;

        private SessionTerminal(InputStream input, ByteArrayOutputStream output) throws IOException {
            super("test", "xterm-256color", input, output, StandardCharsets.UTF_8);
            setSize(Size.of(80, 24));
            var attributes = getAttributes();
            attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
            attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            setAttributes(attributes);
        }

        @Override public SignalHandler handle(Signal signal, SignalHandler handler) {
            var previous = super.handle(signal, handler);
            if (signal == Signal.INT && handler != SignalHandler.SIG_DFL) lineRead.countDown();
            return previous;
        }

        @Override public Integer getNumericCapability(InfoCmp.Capability capability) {
            if (capability == InfoCmp.Capability.max_colors) return 256;
            return super.getNumericCapability(capability);
        }

        @Override protected void doClose() throws IOException {
            closed = true;
        }

        private boolean awaitLineRead() throws InterruptedException {
            return lineRead.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class FailingCloseSessionTerminal extends SessionTerminal {
        private FailingCloseSessionTerminal(InputStream input, ByteArrayOutputStream output)
            throws IOException {
            super(input, output);
        }

        @Override protected void doClose() throws IOException {
            super.doClose();
            throw new IOException("terminal close failed");
        }
    }
}
