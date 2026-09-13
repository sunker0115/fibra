package com.sstlfsj.fibra.cli;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTerminalSignalBridgeTest {
    @Test
    void nativeWinchIsForwardedOnlyWhileTheSessionOwnsTheBridge() throws Exception {
        var registrations = new FakeNativeSignals();
        try (var terminal = new DumbTerminal("test", "xterm-256color",
                 new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                 StandardCharsets.UTF_8);
             var bridge = CliTerminalSignalBridge.install(terminal, registrations)) {
            var raised = new AtomicInteger();
            terminal.handle(Terminal.Signal.WINCH, ignored -> raised.incrementAndGet());

            registrations.fire();

            assertEquals(1, raised.get());
            assertTrue(registrations.registered);
            assertFalse(registrations.unregistered);
        }

        registrations.fire();
        assertTrue(registrations.unregistered);
        assertEquals(registrations.previous, registrations.restored);
    }

    @Test
    void failedNativeRegistrationIsAStableNoOp() throws Exception {
        var registrations = new FakeNativeSignals();
        registrations.fail = true;
        try (var terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]),
                 new ByteArrayOutputStream());
             var ignored = CliTerminalSignalBridge.install(terminal, registrations)) {
            assertTrue(registrations.registered);
        }
        assertFalse(registrations.unregistered);
    }

    private static final class FakeNativeSignals
        implements CliTerminalSignalBridge.NativeSignals {
        private final Object previous = new Object();
        private boolean fail;
        private boolean registered;
        private boolean unregistered;
        private Object restored;
        private Runnable callback;

        @Override public Object register(String name, Runnable candidate) {
            assertEquals("WINCH", name);
            registered = true;
            callback = candidate;
            return fail ? null : previous;
        }

        @Override public void unregister(String name, Object handle) {
            assertEquals("WINCH", name);
            unregistered = true;
            restored = handle;
        }

        private void fire() {
            if (callback != null) callback.run();
        }
    }
}
