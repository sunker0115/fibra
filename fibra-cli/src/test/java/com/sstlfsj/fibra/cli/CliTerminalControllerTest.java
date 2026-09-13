package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Attributes;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliTerminalControllerTest {
    @Test
    void oneInvocationOwnsInputAndScopeCloseRestoresTheLease() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var controller = new CliTerminalController(
            new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)),
            new PrintWriter(output, true, StandardCharsets.UTF_8), true)) {
            var first = controller.openInvocation();
            var lease = first.acquire();

            assertEquals('a', lease.read());
            var busy = assertThrows(CliTerminalUnavailableException.class,
                controller.openInvocation()::acquire);
            assertEquals(CliTerminalUnavailableReason.BUSY, busy.reason());

            first.close();
            try (var restored = controller.openInvocation()) {
                try (var next = restored.acquire()) {
                    assertEquals('b', next.read());
                    next.write("done");
                    next.flush();
                }
            }
            assertEquals("done", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void unsupportedAndClosedTerminalsReturnStableReasons() {
        var unsupported = new CliTerminalController(new ByteArrayInputStream(new byte[0]),
            new PrintWriter(new ByteArrayOutputStream()), false);
        assertEquals(CliTerminalUnavailableReason.UNSUPPORTED,
            assertThrows(CliTerminalUnavailableException.class,
                unsupported.openInvocation()::acquire).reason());
        unsupported.close();
        assertEquals(CliTerminalUnavailableReason.CLOSED,
            assertThrows(CliTerminalUnavailableException.class,
                unsupported::openInvocation).reason());
    }

    @Test
    void rawCtrlCCancelsTheCurrentInvocationAndIsNeverDeliveredAsInput() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        var invocation = coordinator.begin();
        try (var controller = new CliTerminalController(
            new ByteArrayInputStream(new byte[] {0x03, 'x'}),
            new PrintWriter(new ByteArrayOutputStream()), true, coordinator)) {
            try (var terminal = controller.openInvocation(); var lease = terminal.acquire()) {
                assertThrows(InterruptedIOException.class, lease::read);
                assertEquals(true, invocation.token().isCancelled());
                assertThrows(CliTerminalUnavailableException.class, lease::read);
            }

            invocation.close();
            try (var nextInvocation = coordinator.begin();
                 var terminal = controller.openInvocation(); var lease = terminal.acquire()) {
                assertEquals('x', lease.read());
            }
        }
    }

    @Test
    void terminalLeaseUsesRawInputAndRestoresTheOriginalAttributes() throws Exception {
        var coordinator = new CliInvocationCoordinator();
        try (var invocation = coordinator.begin();
             var terminal = new RawTrackingTerminal(new byte[] {0x03});
             var controller = new CliTerminalController(terminal, true, coordinator);
             var invocationTerminal = controller.openInvocation();
             var lease = invocationTerminal.acquire()) {
            assertEquals(false, terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertEquals(false, terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            var writesWhileRaw = terminal.attributeWrites;

            assertThrows(InterruptedIOException.class, lease::read);
            lease.close();

            assertEquals(true, terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ISIG));
            assertEquals(true, terminal.getAttributes().getLocalFlag(Attributes.LocalFlag.ICANON));
            assertEquals(writesWhileRaw + 1, terminal.attributeWrites);
        }
    }

    @Test
    void cancellationWakesANonBlockingTerminalReadWithoutClosingSharedInput() throws Exception {
        var input = new WaitingNonBlockingInput();
        var coordinator = new CliInvocationCoordinator();
        var executor = Executors.newSingleThreadExecutor();
        try (var invocation = coordinator.begin();
             var controller = new CliTerminalController(input,
                 new PrintWriter(new ByteArrayOutputStream()), true, coordinator);
             var terminal = controller.openInvocation(); var lease = terminal.acquire()) {
            var reading = executor.submit(lease::read);
            assertEquals(true, input.awaitRead());

            assertEquals(true, coordinator.cancelCurrent());

            var failure = assertThrows(ExecutionException.class,
                () -> reading.get(2, TimeUnit.SECONDS));
            assertEquals(InterruptedIOException.class, failure.getCause().getClass());
            assertEquals(false, input.closed);
        } finally {
            input.release();
            executor.shutdownNow();
        }
    }

    private static final class RawTrackingTerminal extends DumbTerminal {
        private int attributeWrites;

        private RawTrackingTerminal(byte[] input) throws Exception {
            super(new ByteArrayInputStream(input), new ByteArrayOutputStream());
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
    }

    private static final class WaitingNonBlockingInput extends NonBlockingInputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean closed;

        @Override public int read(long timeout, boolean peek) throws java.io.IOException {
            entered.countDown();
            if (timeout > 0) return READ_EXPIRED;
            try {
                release.await();
                return EOF;
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
