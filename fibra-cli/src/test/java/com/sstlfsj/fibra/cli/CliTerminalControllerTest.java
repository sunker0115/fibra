package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

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
}
