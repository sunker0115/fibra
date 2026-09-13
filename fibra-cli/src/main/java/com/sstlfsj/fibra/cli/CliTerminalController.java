package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliTerminal;
import com.sstlfsj.fibra.cli.api.CliTerminalCapabilities;
import com.sstlfsj.fibra.cli.api.CliTerminalControl;
import com.sstlfsj.fibra.cli.api.CliTerminalFrame;
import com.sstlfsj.fibra.cli.api.CliTerminalInput;
import com.sstlfsj.fibra.cli.api.CliTerminalKey;
import com.sstlfsj.fibra.cli.api.CliTerminalLease;
import com.sstlfsj.fibra.cli.api.CliTerminalModifier;
import com.sstlfsj.fibra.cli.api.CliTerminalRenderer;
import com.sstlfsj.fibra.cli.api.CliTerminalSize;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableException;
import com.sstlfsj.fibra.cli.api.CliTerminalUnavailableReason;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

import java.io.IOError;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 一个 REPL lane 内的终端所有权与 renderer 事件循环协调器。 */
final class CliTerminalController implements AutoCloseable {
    private static final long POLL_MILLIS = 50L;

    private final Terminal terminal;
    private final boolean interactive;
    private final boolean color;
    private Lease current;
    private boolean closed;
    private Throwable terminalFailure;

    CliTerminalController(Terminal terminal, boolean color) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.interactive = !Terminal.TYPE_DUMB.equals(terminal.getType())
            && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
        var colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        this.color = interactive && color && colors != null && colors >= 8;
    }

    synchronized boolean interactive() {
        throwIfTerminalFailed();
        return interactive && !closed;
    }

    synchronized InvocationTerminal openInvocation(
        CliInvocationCoordinator.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        throwIfTerminalFailed();
        if (closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        return new InvocationTerminal(this, invocation);
    }

    private synchronized CliTerminalLease acquire(InvocationTerminal owner) {
        throwIfTerminalFailed();
        if (closed || owner.closed) throw unavailable(CliTerminalUnavailableReason.CLOSED);
        if (!interactive) throw unavailable(CliTerminalUnavailableReason.UNSUPPORTED);
        if (current != null) throw unavailable(CliTerminalUnavailableReason.BUSY);
        current = new Lease(owner, owner.invocation);
        return current;
    }

    private void requestClose(Lease lease) {
        synchronized (this) {
            if (lease.restoration.isDone() || lease.closing) return;
            lease.closing = true;
            lease.accepting = false;
            if (lease.running) return;
            if (current == lease) current = null;
            lease.restoration.complete(null);
            notifyAll();
        }
    }

    void requestStop() {
        final Lease lease;
        synchronized (this) {
            if (closed) return;
            closed = true;
            lease = current;
        }
        if (lease != null) requestClose(lease);
    }

    boolean write(PrintWriter writer, String value) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(value, "value");
        final PendingOutput pending;
        Lease lane = null;
        synchronized (this) {
            while (true) {
                if (closed) return false;
                var lease = current;
                if (lease == null || !lease.running || lease.restoration.isDone()) {
                    writer.println(value);
                    return true;
                }
                if (lease.runnerThread == Thread.currentThread()) {
                    lane = lease;
                    pending = null;
                    break;
                }
                if (!lease.accepting) {
                    awaitOutputLane();
                    continue;
                }
                pending = new PendingOutput(writer, value);
                lease.outputs.addLast(pending);
                lease.dirty = true;
                notifyAll();
                break;
            }
            if (lane == null) {
                while (!pending.completed) {
                    awaitOutputLane();
                }
            }
        }
        if (lane != null) {
            lane.writeFromLane(writer, value);
            return true;
        }
        if (pending.failure != null) {
            if (pending.failure instanceof RuntimeException runtime) throw runtime;
            if (pending.failure instanceof Error error) throw error;
            throw new IllegalStateException("CLI 输出呈现失败", pending.failure);
        }
        return true;
    }

    private void awaitOutputLane() {
        try {
            wait();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 CLI 输出呈现时被中断", failure);
        }
    }

    private void closeInvocation(InvocationTerminal owner) {
        final Lease lease;
        synchronized (this) {
            if (owner.closed) return;
            owner.closed = true;
            lease = current != null && current.owner == owner ? current : null;
        }
        if (lease != null) {
            requestClose(lease);
            awaitRestored(lease);
        }
    }

    private synchronized boolean interactive(InvocationTerminal owner) {
        return interactive && !closed && !owner.closed;
    }

    @Override
    public void close() {
        requestStop();
        final Throwable failure;
        synchronized (this) {
            while (current != null && current.runnerThread != Thread.currentThread()) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 CLI 终端恢复时被中断", interrupted);
                }
            }
            failure = terminalFailure;
        }
        if (failure != null) throw terminalRestorationFailure(failure);
    }

    private static CliTerminalUnavailableException unavailable(
        CliTerminalUnavailableReason reason) {
        return new CliTerminalUnavailableException(reason);
    }

    static final class InvocationTerminal implements CliApplicationRunner.InvocationTerminal {
        private final CliTerminalController controller;
        private final CliInvocationCoordinator.Invocation invocation;
        private boolean closed;

        private InvocationTerminal(CliTerminalController controller,
                                   CliInvocationCoordinator.Invocation invocation) {
            this.controller = controller;
            this.invocation = invocation;
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

    private enum Binding {
        CHARACTER, ENTER, TAB, BACKSPACE, ESCAPE, UP, DOWN, LEFT, RIGHT, HOME, END,
        DELETE, PAGE_UP, PAGE_DOWN, SHIFT_TAB, SHIFT_ENTER, CONTROL_ENTER, PASTE,
        INTERRUPT, EOF
    }

    private final class Lease implements CliTerminalLease {
        private final InvocationTerminal owner;
        private final CliInvocationCoordinator.Invocation invocation;
        private final CliTerminalCapabilities capabilities =
            new CliTerminalCapabilities(color, true);
        private final Control control = new Control();
        private final CompletableFuture<Void> restoration = new CompletableFuture<>();
        private boolean accepting = true;
        private boolean closing;
        private boolean running;
        private boolean used;
        private boolean dirty = true;
        private boolean finish;
        private boolean resizeDirty = true;
        private CliTerminalRenderer renderer;
        private CliTerminalSize size;
        private Display display;
        private Terminal.SignalHandler previousResizeHandler;
        private Thread runnerThread;
        private Attributes originalAttributes;
        private boolean rawModeAttempted;
        private boolean keypadAttempted;
        private boolean resizeHandlerInstalled;
        private boolean bracketedPasteEnabled;
        private final ArrayDeque<PendingOutput> outputs = new ArrayDeque<>();

        private Lease(InvocationTerminal owner,
                      CliInvocationCoordinator.Invocation invocation) {
            this.owner = owner;
            this.invocation = invocation;
        }

        @Override public CliTerminalCapabilities capabilities() {
            return capabilities;
        }

        @Override public void run(CliTerminalRenderer candidate) throws Exception {
            Objects.requireNonNull(candidate, "renderer");
            synchronized (CliTerminalController.this) {
                active();
                if (used) throw unavailable(CliTerminalUnavailableReason.CLOSED);
                used = true;
                running = true;
                renderer = candidate;
                runnerThread = Thread.currentThread();
            }

            Throwable failure = null;
            boolean startAttempted = false;
            try {
                originalAttributes = terminal.getAttributes();
                rawModeAttempted = true;
                terminal.enterRawMode();
                display = new Display(terminal, false);
                previousResizeHandler = terminal.handle(Terminal.Signal.WINCH,
                    ignored -> markResize());
                resizeHandlerInstalled = true;
                keypadAttempted = true;
                terminal.puts(InfoCmp.Capability.keypad_xmit);
                terminal.writer().write(LineReaderImpl.BRACKETED_PASTE_ON);
                bracketedPasteEnabled = true;
                terminal.flush();
                startAttempted = true;
                candidate.start(control);
                tick();
                var bindings = new BindingReader(new PollingReader(this));
                var keys = keyMap();
                while (!finished()) {
                    final Binding binding;
                    try {
                        binding = bindings.readBinding(keys);
                    } catch (IOError inputFailure) {
                        throw unwrap(inputFailure);
                    } catch (EndOfFileException ignored) {
                        inputEof(candidate);
                        break;
                    }
                    if (binding == null) {
                        inputEof(candidate);
                        break;
                    }
                    if (binding == Binding.INTERRUPT) {
                        invocation.cancel();
                        throw interrupted();
                    }
                    if (binding == Binding.EOF) {
                        inputEof(candidate);
                        break;
                    }
                    if (binding == Binding.PASTE) {
                        final String pasted;
                        try {
                            pasted = bindings.readStringUntil(LineReaderImpl.BRACKETED_PASTE_END);
                        } catch (IOError inputFailure) {
                            throw unwrap(inputFailure);
                        } catch (EndOfFileException ignored) {
                            inputEof(candidate);
                            break;
                        }
                        candidate.input(CliTerminalInput.paste(normalizePaste(pasted)));
                    } else {
                        candidate.input(toInput(binding, bindings.getLastBinding()));
                    }
                    synchronized (CliTerminalController.this) {
                        if (accepting && current == this) dirty = true;
                    }
                    tick();
                }
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                synchronized (CliTerminalController.this) {
                    accepting = false;
                    closing = true;
                }
                if (startAttempted) {
                    try {
                        candidate.stop();
                    } catch (Throwable stopFailure) {
                        failure = merge(failure, stopFailure);
                    }
                }
                failure = restore(this, failure);
            }
            rethrow(failure);
        }

        @Override public void close() {
            synchronized (CliTerminalController.this) {
                if (running && runnerThread == Thread.currentThread()
                    && !restoration.isDone()) {
                    throw new IllegalStateException(
                        "terminal lease cannot close from its renderer lane; use finish()");
                }
            }
            requestClose(this);
            awaitRestored(this);
        }

        private void markResize() {
            synchronized (CliTerminalController.this) {
                if (accepting && !restoration.isDone()) resizeDirty = true;
            }
        }

        private boolean finished() {
            synchronized (CliTerminalController.this) {
                return closing || restoration.isDone()
                    || (finish && !dirty && !resizeDirty);
            }
        }

        private void tick() throws Exception {
            if (invocation.token().isCancelled()) throw interrupted();
            final boolean notifyResize;
            final boolean render;
            final CliTerminalSize observed;
            synchronized (CliTerminalController.this) {
                if (closing || closed || current != this || CliTerminalController.this.closed) {
                    finish = true;
                    return;
                }
                observed = observedSize();
                notifyResize = resizeDirty || !observed.equals(size);
                if (notifyResize) {
                    size = observed;
                    resizeDirty = false;
                    dirty = true;
                }
                render = dirty;
                dirty = false;
            }
            drainOutputs();
            if (notifyResize) renderer.resize(observed);
            if (render) render(observed);
        }

        private void drainOutputs() throws Exception {
            List<PendingOutput> pending;
            synchronized (CliTerminalController.this) {
                if (outputs.isEmpty()) return;
                pending = new ArrayList<>(outputs);
                outputs.clear();
            }
            Throwable failure = null;
            try {
                if (display != null) display.update(List.of(), 0);
                for (var output : pending) output.writer.println(output.value);
                terminal.flush();
            } catch (Throwable caught) {
                failure = caught;
            }
            synchronized (CliTerminalController.this) {
                for (var output : pending) {
                    output.failure = failure;
                    output.completed = true;
                }
                dirty = true;
                CliTerminalController.this.notifyAll();
            }
            rethrow(failure);
        }

        private void writeNow(PrintWriter writer, String value) {
            try {
                if (display != null) display.update(List.of(), 0);
                writer.println(value);
                terminal.flush();
                dirty = true;
            } catch (RuntimeException | Error failure) {
                throw failure;
            }
        }

        private void writeFromLane(PrintWriter writer, String value) {
            try {
                drainOutputs();
                writeNow(writer, value);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("CLI 输出呈现失败", failure);
            }
        }

        private void render(CliTerminalSize observed) throws Exception {
            var frame = Objects.requireNonNull(renderer.render(observed), "renderer frame");
            var lines = attributedLines(frame, observed);
            var displaySize = Size.of(observed.columns(), observed.rows());
            display.resize(displaySize);
            var cursor = frame.cursor().map(value ->
                displaySize.cursorPos(value.row(), value.column())).orElse(-1);
            display.update(lines, cursor);
        }

        private List<AttributedString> attributedLines(CliTerminalFrame frame,
                                                        CliTerminalSize observed) {
            if (frame.lines().size() > observed.rows()) {
                throw new IllegalArgumentException("terminal frame must fit the observed height");
            }
            var lines = new ArrayList<AttributedString>(frame.lines().size());
            for (var line : frame.lines()) {
                var attributed = AttributedString.fromAnsi(line);
                if (attributed.columnLength(terminal) > observed.columns()) {
                    throw new IllegalArgumentException(
                        "terminal frame lines must fit the observed width");
                }
                lines.add(color ? attributed : new AttributedString(attributed.toString()));
            }
            frame.cursor().ifPresent(cursor -> {
                var width = lines.get(cursor.row()).columnLength(terminal);
                if (cursor.column() > width || cursor.column() >= observed.columns()) {
                    throw new IllegalArgumentException(
                        "terminal cursor must address a visible frame cell");
                }
            });
            return lines;
        }

        private void inputEof(CliTerminalRenderer candidate) throws Exception {
            if (!finished()) candidate.input(CliTerminalInput.key(
                CliTerminalKey.EOF, "", Set.of()));
            synchronized (CliTerminalController.this) {
                finish = true;
            }
        }

        private void active() {
            if (restoration.isDone() || closing || current != this
                || CliTerminalController.this.closed
                || owner.closed) {
                throw unavailable(CliTerminalUnavailableReason.CLOSED);
            }
        }

        private InterruptedIOException interrupted() {
            synchronized (CliTerminalController.this) {
                accepting = false;
                closing = true;
            }
            return new InterruptedIOException("terminal input cancelled");
        }

        private final class Control implements CliTerminalControl {
            @Override public boolean requestRender() {
                synchronized (CliTerminalController.this) {
                    if (!accepting || restoration.isDone() || current != Lease.this) {
                        return false;
                    }
                    dirty = true;
                    return true;
                }
            }

            @Override public boolean finish() {
                synchronized (CliTerminalController.this) {
                    if (!accepting || restoration.isDone() || current != Lease.this) {
                        return false;
                    }
                    finish = true;
                    return true;
                }
            }
        }
    }

    private final class PollingReader extends NonBlockingReader {
        private final Lease lease;

        private PollingReader(Lease lease) {
            this.lease = lease;
        }

        @Override protected int read(long timeout, boolean peek) throws IOException {
            checkClosed();
            var deadline = timeout == 0 ? Long.MAX_VALUE
                : System.nanoTime() + timeout * 1_000_000L;
            while (true) {
                tick();
                if (lease.finished()) return EOF;
                if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) {
                    return READ_EXPIRED;
                }
                var remaining = deadline == Long.MAX_VALUE ? POLL_MILLIS
                    : Math.max(1L, (deadline - System.nanoTime() + 999_999L) / 1_000_000L);
                var value = peek
                    ? terminal.reader().peek(Math.min(POLL_MILLIS, remaining))
                    : terminal.reader().read(Math.min(POLL_MILLIS, remaining));
                if (peek && value == EOF) return READ_EXPIRED;
                if (value != READ_EXPIRED) return value;
                if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) {
                    return READ_EXPIRED;
                }
            }
        }

        @Override public int readBuffered(char[] buffer, int offset, int length, long timeout)
            throws IOException {
            if (length == 0) return 0;
            var value = read(timeout, false);
            if (value < 0) return value;
            buffer[offset] = (char) value;
            return 1;
        }

        @Override public void close() throws IOException {
            super.close();
        }

        private void tick() throws IOException {
            try {
                lease.tick();
            } catch (InterruptedIOException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                throw new RendererIOException(failure);
            }
        }
    }

    private CliTerminalSize observedSize() {
        var observed = terminal.getSize();
        return new CliTerminalSize(Math.max(1, observed.getColumns()),
            Math.max(1, observed.getRows()));
    }

    private KeyMap<Binding> keyMap() {
        var keys = new KeyMap<Binding>();
        keys.setUnicode(Binding.CHARACTER);
        keys.setNomatch(Binding.CHARACTER);
        keys.setAmbiguousTimeout(100L);
        keys.bind(Binding.INTERRUPT, KeyMap.ctrl('C'));
        keys.bind(Binding.EOF, KeyMap.ctrl('D'));
        keys.bind(Binding.PASTE, LineReaderImpl.BRACKETED_PASTE_BEGIN);
        keys.bind(Binding.SHIFT_TAB, "\033[Z", "\033[9;2u");
        keys.bind(Binding.SHIFT_ENTER, "\033[13;2u");
        keys.bind(Binding.CONTROL_ENTER, "\033[13;5u");
        keys.bind(Binding.ENTER, "\r", "\n");
        keys.bind(Binding.TAB, "\t");
        keys.bind(Binding.BACKSPACE, KeyMap.del(), KeyMap.ctrl('H'));
        keys.bind(Binding.ESCAPE, KeyMap.esc());
        bind(keys, Binding.UP, InfoCmp.Capability.key_up, "\033[A", "\033OA");
        bind(keys, Binding.DOWN, InfoCmp.Capability.key_down, "\033[B", "\033OB");
        bind(keys, Binding.RIGHT, InfoCmp.Capability.key_right, "\033[C", "\033OC");
        bind(keys, Binding.LEFT, InfoCmp.Capability.key_left, "\033[D", "\033OD");
        bind(keys, Binding.HOME, InfoCmp.Capability.key_home, "\033[H", "\033OH", "\033[1~");
        bind(keys, Binding.END, InfoCmp.Capability.key_end, "\033[F", "\033OF", "\033[4~");
        bind(keys, Binding.DELETE, InfoCmp.Capability.key_dc, "\033[3~");
        bind(keys, Binding.PAGE_UP, InfoCmp.Capability.key_ppage, "\033[5~");
        bind(keys, Binding.PAGE_DOWN, InfoCmp.Capability.key_npage, "\033[6~");
        return keys;
    }

    private void bind(KeyMap<Binding> keys, Binding binding, InfoCmp.Capability capability,
                      String... fallbacks) {
        keys.bind(binding, KeyMap.key(terminal, capability));
        keys.bind(binding, fallbacks);
    }

    private static CliTerminalInput toInput(Binding binding, String value) {
        return switch (binding) {
            case CHARACTER -> CliTerminalInput.key(
                CliTerminalKey.CHARACTER, value, Set.of());
            case ENTER -> key(CliTerminalKey.ENTER);
            case TAB -> key(CliTerminalKey.TAB);
            case BACKSPACE -> key(CliTerminalKey.BACKSPACE);
            case ESCAPE -> key(CliTerminalKey.ESCAPE);
            case UP -> key(CliTerminalKey.UP);
            case DOWN -> key(CliTerminalKey.DOWN);
            case LEFT -> key(CliTerminalKey.LEFT);
            case RIGHT -> key(CliTerminalKey.RIGHT);
            case HOME -> key(CliTerminalKey.HOME);
            case END -> key(CliTerminalKey.END);
            case DELETE -> key(CliTerminalKey.DELETE);
            case PAGE_UP -> key(CliTerminalKey.PAGE_UP);
            case PAGE_DOWN -> key(CliTerminalKey.PAGE_DOWN);
            case SHIFT_TAB -> CliTerminalInput.key(
                CliTerminalKey.TAB, "", Set.of(CliTerminalModifier.SHIFT));
            case SHIFT_ENTER -> CliTerminalInput.key(
                CliTerminalKey.ENTER, "", Set.of(CliTerminalModifier.SHIFT));
            case CONTROL_ENTER -> CliTerminalInput.key(
                CliTerminalKey.ENTER, "", Set.of(CliTerminalModifier.CONTROL));
            case PASTE, INTERRUPT, EOF -> throw new IllegalArgumentException(
                "control binding is internal");
        };
    }

    private static CliTerminalInput key(CliTerminalKey key) {
        return CliTerminalInput.key(key, "", Set.of());
    }

    private static String normalizePaste(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private Throwable restore(Lease lease, Throwable failure) {
        Throwable restorationFailure = null;
        try {
            lease.drainOutputs();
        } catch (Throwable outputFailure) {
            restorationFailure = merge(restorationFailure, outputFailure);
        }
        var modesAttempted = lease.display != null || lease.bracketedPasteEnabled
            || lease.keypadAttempted;
        try {
            if (lease.display != null) lease.display.update(List.of(), 0);
        } catch (Throwable displayFailure) {
            restorationFailure = merge(restorationFailure, displayFailure);
        }
        if (lease.bracketedPasteEnabled) {
            try {
                terminal.writer().write(LineReaderImpl.BRACKETED_PASTE_OFF);
            } catch (Throwable pasteFailure) {
                restorationFailure = merge(restorationFailure, pasteFailure);
            } finally {
                lease.bracketedPasteEnabled = false;
            }
        }
        if (lease.keypadAttempted) {
            try {
                terminal.puts(InfoCmp.Capability.keypad_local);
            } catch (Throwable keypadFailure) {
                restorationFailure = merge(restorationFailure, keypadFailure);
            }
        }
        if (modesAttempted) {
            try {
                terminal.flush();
            } catch (Throwable flushFailure) {
                restorationFailure = merge(restorationFailure, flushFailure);
            }
        }
        if (lease.rawModeAttempted) {
            try {
                terminal.setAttributes(lease.originalAttributes);
            } catch (Throwable attributesFailure) {
                restorationFailure = merge(restorationFailure, attributesFailure);
            }
        }
        if (lease.resizeHandlerInstalled) {
            try {
                terminal.handle(Terminal.Signal.WINCH, lease.previousResizeHandler);
            } catch (Throwable handlerFailure) {
                restorationFailure = merge(restorationFailure, handlerFailure);
            }
        }
        synchronized (this) {
            lease.accepting = false;
            lease.running = false;
            lease.runnerThread = null;
            if (current == lease) current = null;
            if (restorationFailure != null) {
                terminalFailure = merge(terminalFailure, restorationFailure);
            }
            notifyAll();
        }
        if (restorationFailure == null) lease.restoration.complete(null);
        else lease.restoration.completeExceptionally(restorationFailure);
        return restorationFailure == null ? failure : merge(failure, restorationFailure);
    }

    private static Throwable merge(Throwable failure, Throwable cleanupFailure) {
        if (failure == null) return cleanupFailure;
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        return failure;
    }

    private static Exception unwrap(IOError failure) {
        var cause = failure.getCause();
        if (cause instanceof RendererIOException renderer
            && renderer.getCause() instanceof Exception rendererFailure) {
            return rendererFailure;
        }
        if (cause instanceof Exception exception) return exception;
        throw failure;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new RuntimeException(failure);
    }

    private void awaitRestored(Lease lease) {
        try {
            lease.restoration.join();
        } catch (CompletionException failure) {
            throw terminalRestorationFailure(failure.getCause());
        }
    }

    private void throwIfTerminalFailed() {
        if (terminalFailure != null) throw terminalRestorationFailure(terminalFailure);
    }

    private static IllegalStateException terminalRestorationFailure(Throwable failure) {
        return new IllegalStateException("CLI terminal restoration failed", failure);
    }

    private static final class PendingOutput {
        private final PrintWriter writer;
        private final String value;
        private boolean completed;
        private Throwable failure;

        private PendingOutput(PrintWriter writer, String value) {
            this.writer = writer;
            this.value = value;
        }
    }

    private static final class RendererIOException extends IOException {
        private RendererIOException(Exception cause) {
            super("terminal renderer failed", cause);
        }
    }
}
