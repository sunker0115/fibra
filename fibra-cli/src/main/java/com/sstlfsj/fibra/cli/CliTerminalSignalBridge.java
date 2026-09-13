package com.sstlfsj.fibra.cli;

import org.jline.terminal.Terminal;
import org.jline.utils.Signals;

import java.util.Objects;

/** 将进程 WINCH 转交给关闭了 JLine nativeSignals 的当前终端会话。 */
final class CliTerminalSignalBridge implements AutoCloseable {
    private static final NativeSignals SYSTEM = new NativeSignals() {
        @Override public Object register(String name, Runnable callback) {
            return Signals.register(name, callback);
        }

        @Override public void unregister(String name, Object previous) {
            Signals.unregister(name, previous);
        }
    };

    private final Terminal terminal;
    private final NativeSignals nativeSignals;
    private Object previous;
    private boolean installed;
    private boolean closed;

    private CliTerminalSignalBridge(Terminal terminal, NativeSignals nativeSignals) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.nativeSignals = Objects.requireNonNull(nativeSignals, "nativeSignals");
    }

    static CliTerminalSignalBridge install(Terminal terminal) {
        return install(terminal, SYSTEM);
    }

    static CliTerminalSignalBridge install(Terminal terminal, NativeSignals nativeSignals) {
        var bridge = new CliTerminalSignalBridge(terminal, nativeSignals);
        bridge.previous = nativeSignals.register("WINCH", bridge::raise);
        bridge.installed = bridge.previous != null;
        return bridge;
    }

    private synchronized void raise() {
        if (!closed) terminal.raise(Terminal.Signal.WINCH);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (installed) nativeSignals.unregister("WINCH", previous);
    }

    interface NativeSignals {
        Object register(String name, Runnable callback);

        void unregister(String name, Object previous);
    }
}
