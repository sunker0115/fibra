package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Objects;

/** 一个 REPL lane 内的终端所有权协调器。 */
final class CliTerminalController implements AutoCloseable {
    private final InputStream input;
    private final PrintWriter output;
    private final boolean interactive;
    private Lease current;
    private boolean closed;

    CliTerminalController(InputStream input, PrintWriter output, boolean interactive) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = interactive;
    }

    synchronized InvocationTerminal openInvocation() {
        if (closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        return new InvocationTerminal(this);
    }

    private synchronized CliTerminalLease acquire(InvocationTerminal owner) {
        if (closed || owner.closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        if (!interactive) throw unavailable(CliTerminalUnavailableReason.UNSUPPORTED);
        if (current != null) throw unavailable(CliTerminalUnavailableReason.BUSY);
        current = new Lease(owner);
        return current;
    }

    private synchronized void release(Lease lease) {
        if (current == lease) current = null;
        lease.closed = true;
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
        private boolean closed;

        private Lease(InvocationTerminal owner) {
            this.owner = owner;
        }

        @Override public int read() throws IOException {
            active();
            return input.read();
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
    }
}
