package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminalControl;
import com.sstlfsj.fibra.cli.api.CliTerminalFrame;
import com.sstlfsj.fibra.cli.api.CliTerminalInput;
import com.sstlfsj.fibra.cli.api.CliTerminalKey;
import com.sstlfsj.fibra.cli.api.CliTerminalModifier;
import com.sstlfsj.fibra.cli.api.CliTerminalRenderer;
import com.sstlfsj.fibra.cli.api.CliTerminalSize;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTerminalControllerTest {
    @Test
    void oneInvocationOwnsTheManagedLoopAndReleaseAllowsTheNextInvocation() throws Exception {
        var output = new ByteArrayOutputStream();
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[] {'a', 'b'}, output);
             var controller = new CliTerminalController(terminal, true);
             var firstInvocation = coordinator.begin();
             var firstTerminal = controller.openInvocation(firstInvocation);
             var firstLease = firstTerminal.acquire()) {
            var busy = assertThrows(CliTerminalUnavailableException.class,
                () -> controller.openInvocation(firstInvocation).acquire());
            assertEquals(CliTerminalUnavailableReason.BUSY, busy.reason());

            var first = new CollectingRenderer(1);
            firstLease.run(first);
            assertEquals(List.of(CliTerminalInput.key(CliTerminalKey.CHARACTER, "a", Set.of())),
                first.inputs);
            assertFalse(first.control.requestRender());

             firstInvocation.close();
             try (var secondInvocation = coordinator.begin();
                 var secondTerminal = controller.openInvocation(secondInvocation);
                 var secondLease = secondTerminal.acquire()) {
                var second = new CollectingRenderer(1);
                secondLease.run(second);
                assertEquals(List.of(CliTerminalInput.key(CliTerminalKey.CHARACTER, "b", Set.of())),
                    second.inputs);
            }
        }
    }

    @Test
    void dumbAndClosedTerminalsReturnStableReasons() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]),
                 new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin()) {
            assertFalse(controller.openInvocation(invocation).interactive());
            assertEquals(CliTerminalUnavailableReason.UNSUPPORTED,
                assertThrows(CliTerminalUnavailableException.class,
                    () -> controller.openInvocation(invocation).acquire()).reason());
            controller.close();
            assertEquals(CliTerminalUnavailableReason.CLOSED,
                assertThrows(CliTerminalUnavailableException.class,
                    () -> controller.openInvocation(invocation)).reason());
        }
    }

    @Test
    void closedControllerRejectsLatePhysicalOutput() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = terminal(new byte[0], output);
             var controller = new CliTerminalController(terminal, true)) {
            controller.close();

            assertFalse(controller.write(new PrintWriter(output, true), "too-late"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("too-late"));
        }
    }

    @Test
    void rawCtrlCCancelsTheExactLeaseOwnerAndIsNeverDelivered() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[] {0x03}, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var owner = coordinator.begin();
             var invocationTerminal = controller.openInvocation(owner);
             var lease = invocationTerminal.acquire();
             var newer = coordinator.begin()) {
            var renderer = new CollectingRenderer(Integer.MAX_VALUE);

            assertThrows(InterruptedIOException.class, () -> lease.run(renderer));

            assertTrue(owner.token().isCancelled());
            assertFalse(newer.token().isCancelled());
            assertEquals(List.of(), renderer.inputs);
        }
    }

    @Test
    void rawModeStartsAndRestoresInsideRunExactlyOnce() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[] {0x03}, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            var writesBeforeRun = terminal.attributeWrites;

            assertThrows(InterruptedIOException.class,
                () -> lease.run(new CollectingRenderer(Integer.MAX_VALUE)));
            lease.close();

            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            assertEquals(writesBeforeRun + 2, terminal.attributeWrites);
        }
    }

    @Test
    void cancellationWakesTheManagedLoopWithoutClosingSharedInput() throws Exception {
        var input = new WaitingInputStream();
        var coordinator = new CliInvocationCoordinator();
        var executor = Executors.newSingleThreadExecutor();
        try (var terminal = terminal(input, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var running = executor.submit(() -> {
                lease.run(new CollectingRenderer(Integer.MAX_VALUE));
                return null;
            });
            assertTrue(input.awaitRead());

            assertTrue(invocation.cancel());

            var failure = assertThrows(ExecutionException.class,
                () -> running.get(2, TimeUnit.SECONDS));
            assertEquals(InterruptedIOException.class, failure.getCause().getClass());
            assertFalse(input.closed);
        } finally {
            input.release();
            executor.shutdownNow();
        }
    }

    @Test
    void decodesUnicodeDirectionsAndStandaloneEscapeAndPublishesResize() throws Exception {
        var input = ("界\033[A\033").getBytes(StandardCharsets.UTF_8);
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(input, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(3) {
                @Override public void input(CliTerminalInput event) {
                    super.input(event);
                    if (inputs.size() == 1) {
                        terminal.setSize(Size.of(32, 10));
                        terminal.raise(org.jline.terminal.Terminal.Signal.WINCH);
                        control.requestRender();
                    }
                }

                @Override public CliTerminalFrame render(CliTerminalSize size) {
                    state = "inputs=" + inputs.size();
                    return super.render(size);
                }
            };

            lease.run(renderer);

            assertEquals(List.of(
                CliTerminalInput.key(CliTerminalKey.CHARACTER, "界", Set.of()),
                CliTerminalInput.key(CliTerminalKey.UP, "", Set.of()),
                CliTerminalInput.key(CliTerminalKey.ESCAPE, "", Set.of())), renderer.inputs);
            assertEquals(List.of(new CliTerminalSize(80, 24),
                new CliTerminalSize(32, 10)), renderer.sizes);
            assertTrue(renderer.renders >= 2);
            assertEquals("inputs=3", renderer.lastRenderedState,
                "每个输入事件都必须驱动包含最新状态的下一帧");
        }
    }

    @Test
    void bracketedPasteIsOneNormalizedEventAndDoesNotTriggerEmbeddedControls()
        throws Exception {
        var output = new ByteArrayOutputStream();
        var input = ("\033[200~first\r\n/command\u0003last\r\033[201~\r")
            .getBytes(StandardCharsets.UTF_8);
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(input, output);
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(2);

            lease.run(renderer);

            assertEquals(2, renderer.inputs.size());
            var paste = renderer.inputs.getFirst();
            assertTrue(paste.isPaste());
            assertEquals("first\n/command\u0003last\n", paste.text());
            assertEquals(Optional.empty(), paste.key());
            assertEquals(Set.of(), paste.modifiers());
            assertEquals(Optional.of(CliTerminalKey.ENTER), renderer.inputs.get(1).key());
            assertFalse(invocation.token().isCancelled());
        }
        var writes = output.toString(StandardCharsets.UTF_8);
        assertTrue(writes.contains("\033[?2004h"));
        assertTrue(writes.contains("\033[?2004l"));
    }

    @Test
    void reportsOnlyModifiersEncodedByDistinctTerminalSequences() throws Exception {
        var input = ("A\033[Z\033[13;5u").getBytes(StandardCharsets.UTF_8);
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(input, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(3);

            lease.run(renderer);

            assertEquals(List.of(
                CliTerminalInput.key(CliTerminalKey.CHARACTER, "A", Set.of()),
                CliTerminalInput.key(CliTerminalKey.TAB, "", Set.of(CliTerminalModifier.SHIFT)),
                CliTerminalInput.key(CliTerminalKey.ENTER, "", Set.of(CliTerminalModifier.CONTROL))),
                renderer.inputs);
        }
    }

    @Test
    void asynchronousRenderRequestsAreCoalescedAndFinishRestoresTheLease() throws Exception {
        var input = new WaitingInputStream();
        var coordinator = new CliInvocationCoordinator();
        var updates = Executors.newSingleThreadExecutor();
        try (var terminal = terminal(input, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(Integer.MAX_VALUE) {
                @Override public void start(CliTerminalControl candidate) {
                    super.start(candidate);
                    updates.submit(() -> {
                        state = "updated";
                        control.requestRender();
                        control.requestRender();
                        control.finish();
                    });
                }
            };

            lease.run(renderer);

            assertEquals("updated", renderer.lastRenderedState);
            assertTrue(renderer.renders <= 2, "重复请求应合并为至多一次追加重绘");
            assertFalse(renderer.control.finish());
            assertFalse(input.closed);
        } finally {
            input.release();
            updates.shutdownNow();
        }
    }

    @Test
    void rendererLaneCannotSynchronouslyCloseItsOwnLease() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[0], new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            lease.run(new CliTerminalRenderer() {
                @Override public void start(CliTerminalControl control) {
                    var failure = assertThrows(IllegalStateException.class, lease::close);
                    assertTrue(failure.getMessage().contains("finish"));
                    control.finish();
                }

                @Override public CliTerminalFrame render(CliTerminalSize size) {
                    return new CliTerminalFrame(List.of(), Optional.empty());
                }
            });
        }
    }

    @Test
    void noColorCapabilityStripsRendererStyles() throws Exception {
        var output = new ByteArrayOutputStream();
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[] {'x'}, output);
             var controller = new CliTerminalController(terminal, false);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            assertFalse(lease.capabilities().color());
            lease.run(new CollectingRenderer(1) {
                @Override public CliTerminalFrame render(CliTerminalSize size) {
                    renders++;
                    return new CliTerminalFrame(List.of("\033[31mred\033[0m"),
                        Optional.empty());
                }
            });
        }
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("\033[31m"));
    }

    @Test
    void closingAnUnusedLeaseHasNoPhysicalSideEffectAndMakesTheLaneAvailable()
        throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[] {'x'}, new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var firstTerminal = controller.openInvocation(invocation);
             var firstLease = firstTerminal.acquire()) {
            var writesBeforeAcquire = terminal.attributeWrites;
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));

            firstLease.close();

            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            assertEquals(writesBeforeAcquire, terminal.attributeWrites);
            try (var secondTerminal = controller.openInvocation(invocation);
                 var secondLease = secondTerminal.acquire()) {
                secondLease.run(new CollectingRenderer(1));
            }
        }
    }

    @Test
    void restorationFailureIsSharedByRunCloseAndTheTerminalOwner() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        var terminal = new FailingRestoreTerminal(new ByteArrayInputStream(new byte[] {'x'}),
            new ByteArrayOutputStream());
        var controller = new CliTerminalController(terminal, true);
        var invocation = coordinator.begin();
        var invocationTerminal = controller.openInvocation(invocation);
        var lease = invocationTerminal.acquire();
        try {
            var runFailure = assertThrows(IllegalStateException.class,
                () -> lease.run(new CollectingRenderer(1)));
            assertEquals("restore attributes failed", runFailure.getMessage());
            assertEquals(2, terminal.winchHandlers,
                "属性恢复失败后仍必须恢复 resize handler");

            assertEquals("CLI terminal restoration failed",
                assertThrows(IllegalStateException.class, lease::close).getMessage());
            assertEquals("CLI terminal restoration failed",
                assertThrows(IllegalStateException.class, lease::close).getMessage());
            assertEquals("CLI terminal restoration failed",
                assertThrows(IllegalStateException.class,
                    invocationTerminal::acquire).getMessage());
            assertThrows(IllegalStateException.class,
                () -> controller.openInvocation(invocation));
            assertEquals("CLI terminal restoration failed",
                assertThrows(IllegalStateException.class, controller::close).getMessage());
        } finally {
            invocation.close();
            terminal.close();
        }
    }

    @Test
    void displayCleanupFailureStillRestoresEveryRemainingTerminalMode() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        var terminal = new FailingDisplayCleanupTerminal(
            new ByteArrayInputStream(new byte[] {'x'}), new ByteArrayOutputStream());
        var controller = new CliTerminalController(terminal, true);
        var invocation = coordinator.begin();
        var invocationTerminal = controller.openInvocation(invocation);
        var lease = invocationTerminal.acquire();
        try {
            var renderer = new CollectingRenderer(1) {
                @Override public void stop() {
                    terminal.failNextWriterCall();
                }
            };

            assertThrows(IllegalStateException.class, () -> lease.run(renderer));
            assertTrue(terminal.pasteOffWritten,
                "Display 清理失败后仍必须关闭 bracketed paste");
            assertTrue(terminal.keypadLocalCalls > 0,
                "Display 清理失败后仍必须退出 keypad application mode");
            assertTrue(terminal.flushCallsAfterFailure > 0,
                "Display 清理失败后仍必须尝试 flush");
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            assertEquals(2, terminal.winchHandlers,
                "Display 清理失败后仍必须恢复 resize handler");
        } finally {
            invocation.close();
            terminal.close();
        }
    }

    @Test
    void startFailureStillStopsRendererAndRestoresTheTerminal() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[0], new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var stopped = new boolean[1];
            var failure = new IOException("start failed");
            var renderer = new CliTerminalRenderer() {
                @Override public void start(CliTerminalControl control) throws Exception {
                    throw failure;
                }

                @Override public CliTerminalFrame render(CliTerminalSize size) {
                    throw new AssertionError("render must not run after start failure");
                }

                @Override public void stop() {
                    stopped[0] = true;
                }
            };

            assertEquals(failure, assertThrows(IOException.class, () -> lease.run(renderer)));
            assertTrue(stopped[0]);
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        }
    }

    @Test
    void rendererInitializationFailureStillRestoresRawMode() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = new TrackingTerminal(new ByteArrayInputStream(new byte[0]),
                 new ByteArrayOutputStream(), true);
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var failure = assertThrows(IllegalStateException.class,
                () -> lease.run(new CollectingRenderer(0)));

            assertEquals("resize handler failed", failure.getMessage());
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        }
    }

    @Test
    void concurrentOutputIsSerializedBeforeTheRendererRedraws() throws Exception {
        var input = new WaitingInputStream();
        var terminalBytes = new ByteArrayOutputStream();
        var messageBytes = new ByteArrayOutputStream();
        var coordinator = new CliInvocationCoordinator();
        var rendererStarted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var terminal = terminal(input, terminalBytes);
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(Integer.MAX_VALUE) {
                @Override public void start(CliTerminalControl candidate) {
                    super.start(candidate);
                    rendererStarted.countDown();
                }
            };
            var running = executor.submit(() -> {
                lease.run(renderer);
                return null;
            });
            assertTrue(rendererStarted.await(5, TimeUnit.SECONDS));
            assertTrue(input.awaitRead());

            assertTrue(controller.write(new PrintWriter(messageBytes, true,
                StandardCharsets.UTF_8), "stream-event"));
            renderer.control.finish();

            running.get(5, TimeUnit.SECONDS);
            assertEquals("stream-event\n", messageBytes.toString(StandardCharsets.UTF_8));
            assertTrue(renderer.renders >= 2,
                "写入并发输出后必须在同一 terminal lane 重绘当前 frame");
        } finally {
            input.release();
            executor.shutdownNow();
        }
    }

    @Test
    void outputThatStartsAfterStopIsRejectedWithoutTouchingTheTerminal() throws Exception {
        var input = new WaitingInputStream();
        var terminalBytes = new ByteArrayOutputStream();
        var messageBytes = new ByteArrayOutputStream();
        var coordinator = new CliInvocationCoordinator();
        var executor = Executors.newFixedThreadPool(2);
        try (var terminal = new BlockingRestoreTerminal(input, terminalBytes);
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            var renderer = new CollectingRenderer(Integer.MAX_VALUE);
            var running = executor.submit(() -> {
                lease.run(renderer);
                return null;
            });
            assertTrue(input.awaitRead());

            terminal.blockRestore();
            controller.requestStop();
            assertTrue(terminal.awaitRestore());
            var output = executor.submit(() -> controller.write(new PrintWriter(messageBytes,
                true, StandardCharsets.UTF_8), "late-event"));
            assertFalse(output.get(5, TimeUnit.SECONDS));

            terminal.releaseRestore();
            running.get(5, TimeUnit.SECONDS);
            assertEquals("", messageBytes.toString(StandardCharsets.UTF_8));
        } finally {
            input.release();
            executor.shutdownNow();
        }
    }

    @Test
    void frameMustFitTheObservedPhysicalRows() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var terminal = terminal(new byte[0], new ByteArrayOutputStream());
             var controller = new CliTerminalController(terminal, true);
             var invocation = coordinator.begin();
             var invocationTerminal = controller.openInvocation(invocation);
             var lease = invocationTerminal.acquire()) {
            terminal.setSize(Size.of(10, 1));
            var renderer = new CliTerminalRenderer() {
                @Override public CliTerminalFrame render(CliTerminalSize size) {
                    return new CliTerminalFrame(List.of("first", "second"), Optional.empty());
                }
            };

            var failure = assertThrows(IllegalArgumentException.class,
                () -> lease.run(renderer));
            assertEquals("terminal frame must fit the observed height", failure.getMessage());
            assertTrue(terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
        }
    }

    private static TrackingTerminal terminal(byte[] input, ByteArrayOutputStream output)
        throws IOException {
        return terminal(new ByteArrayInputStream(input), output);
    }

    private static TrackingTerminal terminal(InputStream input, ByteArrayOutputStream output)
        throws IOException {
        return new TrackingTerminal(input, output);
    }

    private static class CollectingRenderer implements CliTerminalRenderer {
        private final int finishAfterInputs;
        final List<CliTerminalInput> inputs = new ArrayList<>();
        final List<CliTerminalSize> sizes = new ArrayList<>();
        CliTerminalControl control;
        volatile String state = "initial";
        String lastRenderedState;
        int renders;

        private CollectingRenderer(int finishAfterInputs) {
            this.finishAfterInputs = finishAfterInputs;
        }

        @Override public void start(CliTerminalControl candidate) {
            control = candidate;
        }

        @Override public void input(CliTerminalInput input) {
            inputs.add(input);
            if (inputs.size() >= finishAfterInputs) control.finish();
        }

        @Override public void resize(CliTerminalSize size) {
            sizes.add(size);
        }

        @Override public CliTerminalFrame render(CliTerminalSize size) {
            renders++;
            lastRenderedState = state;
            return new CliTerminalFrame(List.of(state), Optional.empty());
        }
    }

    private static class TrackingTerminal extends DumbTerminal {
        private int attributeWrites;
        private boolean failResizeHandler;

        private TrackingTerminal(InputStream input, ByteArrayOutputStream output)
            throws IOException {
            this(input, output, false);
        }

        private TrackingTerminal(InputStream input, ByteArrayOutputStream output,
                                 boolean failResizeHandler) throws IOException {
            super("test", "xterm-256color", input, output, StandardCharsets.UTF_8);
            this.failResizeHandler = failResizeHandler;
            setSize(Size.of(80, 24));
            var attributes = getAttributes();
            attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
            attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            setAttributes(attributes);
            attributeWrites = 0;
        }

        @Override public void setAttributes(Attributes attributes) {
            super.setAttributes(attributes);
            attributeWrites++;
        }

        @Override public Integer getNumericCapability(InfoCmp.Capability capability) {
            if (capability == InfoCmp.Capability.max_colors) return 256;
            return super.getNumericCapability(capability);
        }

        @Override public SignalHandler handle(Signal signal, SignalHandler handler) {
            if (failResizeHandler && signal == Signal.WINCH) {
                failResizeHandler = false;
                throw new IllegalStateException("resize handler failed");
            }
            return super.handle(signal, handler);
        }
    }

    private static final class BlockingRestoreTerminal extends TrackingTerminal {
        private final CountDownLatch restoring = new CountDownLatch(1);
        private final CountDownLatch restoreRelease = new CountDownLatch(1);
        private volatile boolean blockRestore;

        private BlockingRestoreTerminal(InputStream input, ByteArrayOutputStream output)
            throws IOException {
            super(input, output);
        }

        @Override public void setAttributes(Attributes attributes) {
            if (blockRestore) {
                restoring.countDown();
                try {
                    restoreRelease.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", failure);
                }
            }
            super.setAttributes(attributes);
        }

        private void blockRestore() {
            blockRestore = true;
        }

        private boolean awaitRestore() throws InterruptedException {
            return restoring.await(5, TimeUnit.SECONDS);
        }

        private void releaseRestore() {
            restoreRelease.countDown();
        }
    }

    private static final class FailingRestoreTerminal extends TrackingTerminal {
        private boolean armed;
        private int attributeWritesAfterArm;
        private int winchHandlers;

        private FailingRestoreTerminal(InputStream input, ByteArrayOutputStream output)
            throws IOException {
            super(input, output);
            armed = true;
        }

        @Override public void setAttributes(Attributes attributes) {
            if (armed && ++attributeWritesAfterArm == 2) {
                throw new IllegalStateException("restore attributes failed");
            }
            super.setAttributes(attributes);
        }

        @Override public SignalHandler handle(Signal signal, SignalHandler handler) {
            if (armed && signal == Signal.WINCH) winchHandlers++;
            return super.handle(signal, handler);
        }
    }

    private static final class FailingDisplayCleanupTerminal extends TrackingTerminal {
        private final PrintWriter failingWriter;
        private final OutputStream failingOutput;
        private boolean failNextWriterCall;
        private boolean pasteOffWritten;
        private int keypadLocalCalls;
        private int flushCallsAfterFailure;
        private int winchHandlers;

        private FailingDisplayCleanupTerminal(InputStream input, ByteArrayOutputStream output)
            throws IOException {
            super(input, output);
            var delegate = super.writer();
            failingOutput = new OutputStream() {
                @Override public void write(int value) {
                    failIfArmed();
                    output.write(value);
                }

                @Override public void write(byte[] value, int offset, int length) {
                    failIfArmed();
                    output.write(value, offset, length);
                }

                @Override public void flush() throws IOException {
                    output.flush();
                }

                private void failIfArmed() {
                    if (!failNextWriterCall) return;
                    failNextWriterCall = false;
                    throw new IllegalStateException("display cleanup failed");
                }
            };
            failingWriter = new PrintWriter(delegate, true) {
                @Override public void write(int value) {
                    failIfArmed();
                    super.write(value);
                }

                @Override public void write(String value) {
                    failIfArmed();
                    trackPasteOff(value);
                    super.write(value);
                }

                @Override public void write(String value, int offset, int length) {
                    failIfArmed();
                    var written = value.substring(offset, offset + length);
                    trackPasteOff(written);
                    super.write(value, offset, length);
                }

                @Override public void write(char[] value, int offset, int length) {
                    failIfArmed();
                    super.write(value, offset, length);
                }

                private void failIfArmed() {
                    if (!failNextWriterCall) return;
                    failNextWriterCall = false;
                    throw new IllegalStateException("display cleanup failed");
                }

                private void trackPasteOff(String value) {
                    if (value.contains(
                        org.jline.reader.impl.LineReaderImpl.BRACKETED_PASTE_OFF)) {
                        pasteOffWritten = true;
                    }
                }
            };
        }

        @Override public PrintWriter writer() {
            return failingWriter == null ? super.writer() : failingWriter;
        }

        @Override public OutputStream output() {
            return failingOutput == null ? super.output() : failingOutput;
        }

        @Override public boolean puts(InfoCmp.Capability capability, Object... params) {
            if (capability == InfoCmp.Capability.keypad_local) keypadLocalCalls++;
            return super.puts(capability, params);
        }

        @Override public void flush() {
            if (!failNextWriterCall) flushCallsAfterFailure++;
            super.flush();
        }

        @Override public SignalHandler handle(Signal signal, SignalHandler handler) {
            if (signal == Signal.WINCH) winchHandlers++;
            return super.handle(signal, handler);
        }

        private void failNextWriterCall() {
            failNextWriterCall = true;
            flushCallsAfterFailure = 0;
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
                throw new InterruptedIOException("test interrupted");
            }
        }

        @Override public void close() {
            closed = true;
            release.countDown();
        }

        private boolean awaitRead() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
