package com.sstlfsj.fibra.cli;

import com.sstlfsj.fibra.cli.api.CliApplication;
import com.sstlfsj.fibra.cli.api.CliProfile;
import com.sstlfsj.fibra.engine.PublishedRuntime;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jline.terminal.Terminal;

/**
 * 借用现有 {@link PublishedRuntime} 的单物理终端 CLI 会话。
 * 会话拥有自己的执行与终端 lane；不同会话可借用同一 PublishedRuntime 并发运行，但不得同时读取同一个
 * 物理输入源。会话不拥有或关闭 PublishedRuntime、Engine 或 Host。
 */
public final class CliSession implements AutoCloseable {
    private final CliExecutionSession execution;

    private CliSession(Builder builder) {
        var terminal = CliTerminalSession.open(builder.input, builder.output, builder.error,
            builder.systemTerminal, builder.terminal);
        execution = new CliExecutionSession(builder.application, () -> builder.published,
            () -> builder.profile, terminal, null, builder.historyFile, List.of());
    }

    public static Builder builder(CliApplication application, PublishedRuntime published,
                                  CliProfile profile) {
        return new Builder(application, published, profile);
    }

    /** 顺序执行一次命令；同一会话已有执行或已经关闭时拒绝。 */
    public int execute(String... arguments) {
        return execution.execute(arguments);
    }

    /** 在人类呈现通道安全输出一条异步消息；会话开始关闭后返回 false。 */
    public boolean printAbove(String value) {
        return execution.printAbove(value);
    }

    /** 停止新准入、取消并等待本会话调用结束；借用的运行时和 streams 保持可用。 */
    @Override public void close() {
        execution.close();
    }

    public static final class Builder {
        private final CliApplication application;
        private final PublishedRuntime published;
        private final CliProfile profile;
        private InputStream input = System.in;
        private PrintWriter output = new PrintWriter(System.out, true);
        private PrintWriter error = new PrintWriter(System.err, true);
        private boolean systemTerminal;
        private Path historyFile;
        private Terminal terminal;

        private Builder(CliApplication application, PublishedRuntime published,
                        CliProfile profile) {
            this.application = Objects.requireNonNull(application, "application");
            this.published = Objects.requireNonNull(published, "published");
            this.profile = Objects.requireNonNull(profile, "profile");
        }

        /** 使用借用的 streams，并固定为非 TTY/dumb 模式。 */
        public Builder streams(InputStream input, PrintWriter output, PrintWriter error) {
            this.input = Objects.requireNonNull(input, "input");
            this.output = Objects.requireNonNull(output, "output");
            this.error = Objects.requireNonNull(error, "error");
            systemTerminal = false;
            terminal = null;
            return this;
        }

        /** 使用当前进程的真实系统终端；不会安装进程退出或 Host 关闭逻辑。 */
        public Builder systemTerminal() {
            input = System.in;
            output = new PrintWriter(System.out, true);
            error = new PrintWriter(System.err, true);
            systemTerminal = true;
            terminal = null;
            return this;
        }

        Builder terminal(Terminal value) {
            terminal = Objects.requireNonNull(value, "terminal");
            systemTerminal = false;
            return this;
        }

        public Builder historyFile(Path value) {
            historyFile = Objects.requireNonNull(value, "historyFile").toAbsolutePath().normalize();
            return this;
        }

        public CliSession build() {
            return new CliSession(this);
        }
    }
}
