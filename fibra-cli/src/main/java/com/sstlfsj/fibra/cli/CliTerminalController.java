package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.util.Objects;

/** 一个 REPL lane 内的终端所有权协调器。 */
final class CliTerminalController implements AutoCloseable {
    private final InputStream input;
    private final PrintWriter output;
    private final boolean interactive;
    private final CliInvocationCoordinator invocations;
    private final Terminal terminal;
    private Lease current;
    private boolean closed;

    CliTerminalController(InputStream input, PrintWriter output, boolean interactive) {
        this(input, output, interactive, new CliInvocationCoordinator());
    }

    CliTerminalController(InputStream input, PrintWriter output, boolean interactive,
                          CliInvocationCoordinator invocations) {
        this(input, output, interactive, invocations, null);
    }

    CliTerminalController(Terminal terminal, boolean interactive,
                          CliInvocationCoordinator invocations) {
        this(terminal.input(), terminal.writer(), interactive, invocations,
            Objects.requireNonNull(terminal, "terminal"));
    }

    private CliTerminalController(InputStream input, PrintWriter output, boolean interactive,
                                  CliInvocationCoordinator invocations, Terminal terminal) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = interactive;
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.terminal = terminal;
    }

    synchronized InvocationTerminal openInvocation() {
        if (closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        return new InvocationTerminal(this);
    }

    private synchronized CliTerminalLease acquire(InvocationTerminal owner) {
        if (closed || owner.closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        if (!interactive) throw unavailable(CliTerminalUnavailableReason.UNSUPPORTED);
        if (current != null) throw unavailable(CliTerminalUnavailableReason.BUSY);
        current = new Lease(owner, terminal == null ? null : terminal.enterRawMode());
        return current;
    }

    private synchronized void release(Lease lease) {
        if (lease.closed) return;
        try {
            if (terminal != null && lease.originalAttributes != null) {
                terminal.setAttributes(lease.originalAttributes);
            }
        } finally {
            if (current == lease) current = null;
            lease.closed = true;
        }
    }

    private synchronized void closeInvocation(InvocationTerminal owner) {
        if (owner.closed) return;
        owner.closed = true;
        if (current != null && current.owner == owner) release(current);
    }

    private synchronized boolean interactive(InvocationTerminal owner) {
        return interactive && !closed && !owner.closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (current != null) release(current);
    }

    private static CliTerminalUnavailableException unavailable(
        CliTerminalUnavailableReason reason) {
        return new CliTerminalUnavailableException(reason);
    }

    static final class InvocationTerminal implements CliTerminal, AutoCloseable {
        private final CliTerminalController controller;
        private boolean closed;

        private InvocationTerminal(CliTerminalController controller) {
            this.controller = controller;
        }

        @Override public boolean interactive() {
            return controller.interactive(this);
        }

        @Override public CliTerminalLease acquire() {
            return controller.acquire(this);
        }

        @Override public void close() {
            controller.closeInvocation(this);
        }
    }

    private final class Lease implements CliTerminalLease {
        private final InvocationTerminal owner;
        private final Attributes originalAttributes;
        private boolean closed;

        private Lease(InvocationTerminal owner, Attributes originalAttributes) {
            this.owner = owner;
            this.originalAttributes = originalAttributes;
        }

        @Override public int read() throws IOException {
            active();
            while (true) {
                if (invocations.currentCancelled()) return interrupted();
                var value = input instanceof NonBlockingInputStream nonBlocking
                    ? nonBlocking.read(100) : input.read();
                if (value == NonBlockingInputStream.READ_EXPIRED) continue;
                if (value == 0x03) {
                    invocations.cancelCurrent();
                    return interrupted();
                }
                if (invocations.currentCancelled()) return interrupted();
                return value;
            }
        }

        @Override public void write(String value) {
            active();
            output.print(Objects.requireNonNull(value, "value"));
        }

        @Override public void flush() {
            active();
            output.flush();
        }

        @Override public void close() {
            release(this);
        }

        private void active() {
            synchronized (CliTerminalController.this) {
                if (closed || current != this || CliTerminalController.this.closed) {
                    throw unavailable(CliTerminalUnavailableReason.CLOSED);
                }
            }
        }

        private int interrupted() throws InterruptedIOException {
            close();
            throw new InterruptedIOException("terminal input cancelled");
        }
    }
}
