package com.sstlfsj.fibra.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 一个 CLI 会话内唯一的物理终端、行编辑器和输出呈现所有者。 */
final class CliTerminalSession implements AutoCloseable {
    private final Terminal terminal;
    private final PrintWriter output;
    private final PrintWriter error;
    private final CliTerminalController controller;
    private final CliTerminalSignalBridge resizeBridge;
    private LineReader lineReader;
    private int lineMessages;
    private boolean lineTransitioning;
    private boolean stopping;
    private boolean closed;

    private CliTerminalSession(Terminal terminal, PrintWriter output, PrintWriter error,
                               boolean nativeResizeBridge, boolean color) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        controller = new CliTerminalController(terminal, color);
        resizeBridge = nativeResizeBridge && controller.interactive()
            ? CliTerminalSignalBridge.install(terminal) : null;
    }

    static CliTerminalSession open(InputStream input, PrintWriter output, PrintWriter error,
                                   boolean systemTerminal, Terminal suppliedTerminal) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        try {
            if (suppliedTerminal != null) {
                return new CliTerminalSession(suppliedTerminal, output, error, false,
                    CliRepl.colorEnabled(System.getenv()));
            }
            if (systemTerminal) {
                var terminal = TerminalBuilder.builder().system(true)
                    .systemOutput(TerminalBuilder.SystemOutput.SysErr)
                    .nativeSignals(false).build();
                return new CliTerminalSession(terminal, output, error, true,
                    CliRepl.colorEnabled(System.getenv()));
            }
            var terminal = new DumbTerminal(new BorrowedInputStream(input),
                new PrintWriterOutputStream(error));
            return new CliTerminalSession(terminal, output, error, false, false);
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动 CLI 终端: " + message(failure), failure);
        }
    }

    Terminal terminal() {
        return terminal;
    }

    CliTerminalController controller() {
        return controller;
    }

    PrintWriter outputWriter() {
        return output;
    }

    PrintWriter errorWriter() {
        return error;
    }

    boolean interactive() {
        return controller.interactive();
    }

    String readLine(LineReader reader, String prompt) {
        Objects.requireNonNull(reader, "reader");
        var interrupted = false;
        var readThread = Thread.currentThread();
        final Terminal.SignalHandler previousInterruptHandler;
        synchronized (this) {
            while ((lineTransitioning || lineMessages != 0) && !stopping && !closed) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) readThread.interrupt();
            if (stopping || closed) throw new EndOfFileException();
            if (lineReader != null) throw new IllegalStateException("CLI line editor is already active");
            previousInterruptHandler = terminal.handle(Terminal.Signal.INT,
                ignored -> readThread.interrupt());
            lineReader = reader;
            lineTransitioning = false;
        }
        try {
            return reader.readLine(interactive() ? prompt : "");
        } finally {
            interrupted = false;
            synchronized (this) {
                if (lineReader == reader) {
                    lineTransitioning = true;
                    while (lineMessages != 0) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    lineReader = null;
                }
            }
            try {
                terminal.handle(Terminal.Signal.INT, previousInterruptHandler);
            } finally {
                synchronized (this) {
                    lineTransitioning = false;
                    notifyAll();
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    boolean printAbove(String value) {
        Objects.requireNonNull(value, "value");
        final LineReader reader;
        var interrupted = false;
        var admitted = false;
        synchronized (this) {
            while (lineTransitioning && !stopping && !closed) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (!stopping && !closed) {
                admitted = true;
                lineMessages++;
                reader = lineReader;
            } else {
                reader = null;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (!admitted) return false;
        try {
            if (reader != null) {
                reader.printAbove(value);
                return true;
            }
            return controller.write(error, value);
        } finally {
            synchronized (this) {
                lineMessages--;
                if (lineMessages == 0) notifyAll();
            }
        }
    }

    private void awaitMessages() {
        var interrupted = false;
        synchronized (this) {
            while (lineMessages != 0) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    void stdout(String value) {
        Objects.requireNonNull(value, "value");
        ensureOpen();
        controller.write(output, value);
    }

    void stderr(String value) {
        Objects.requireNonNull(value, "value");
        ensureOpen();
        controller.write(error, value);
    }

    void requestStop() {
        final LineReader reader;
        synchronized (this) {
            if (stopping || closed) return;
            stopping = true;
            reader = lineReader;
        }
        controller.requestStop();
        if (reader != null) {
            try {
                terminal.raise(Terminal.Signal.INT);
            } catch (RuntimeException ignored) {
                // 最终 close 仍会等待执行 lane；信号只是唤醒阻塞 readLine。
            }
        }
    }

    synchronized boolean stopping() {
        return stopping;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
        }
        requestStop();
        awaitMessages();
        Throwable failure = null;
        try {
            controller.close();
        } catch (Throwable caught) {
            failure = caught;
        }
        if (resizeBridge != null) {
            try {
                resizeBridge.close();
            } catch (Throwable caught) {
                failure = merge(failure, caught);
            }
        }
        try {
            terminal.close();
        } catch (Throwable caught) {
            failure = merge(failure, caught);
        }
        output.flush();
        error.flush();
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        rethrow(failure);
    }

    private synchronized void ensureOpen() {
        if (closed) throw new IllegalStateException("CLI terminal session is closed");
    }

    private static Throwable merge(Throwable failure, Throwable cleanupFailure) {
        if (failure == null) return cleanupFailure;
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("关闭交互终端失败: " + message(failure), failure);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** DumbTerminal 可以关闭适配器，但不得关闭调用方借出的输入流。 */
    private static final class BorrowedInputStream extends FilterInputStream {
        private BorrowedInputStream(InputStream input) {
            super(input);
        }

        @Override public void close() {
        }
    }

    private static final class PrintWriterOutputStream extends OutputStream {
        private final PrintWriter writer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private PrintWriterOutputStream(PrintWriter writer) {
            this.writer = writer;
        }

        @Override public synchronized void write(int value) {
            buffer.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            buffer.write(bytes, offset, length);
        }

        @Override public synchronized void flush() {
            if (buffer.size() != 0) {
                writer.print(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            }
            writer.flush();
        }

        @Override public void close() {
            flush();
        }
    }
}
