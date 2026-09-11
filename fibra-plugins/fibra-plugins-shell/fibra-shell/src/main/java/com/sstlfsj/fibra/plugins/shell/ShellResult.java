package com.sstlfsj.fibra.plugins.shell;

import java.time.Duration;
import java.util.Objects;

public record ShellResult(Integer exitCode, String signal, boolean timedOut, boolean aborted,
                          Duration timeout, ShellOutput stdout, ShellOutput stderr) {
    public ShellResult {
        if ((exitCode == null) == (signal == null)) {
            throw new IllegalArgumentException("exactly one of exitCode or signal is required");
        }
        if (signal != null && signal.isBlank()) {
            throw new IllegalArgumentException("signal must not be blank");
        }
        if (timedOut && aborted) {
            throw new IllegalArgumentException("timedOut and aborted must be mutually exclusive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer exitCode;
        private String signal;
        private boolean timedOut;
        private boolean aborted;
        private Duration timeout;
        private ShellOutput stdout;
        private ShellOutput stderr;

        public Builder exitCode(Integer value) { exitCode = value; return this; }
        public Builder signal(String value) { signal = value; return this; }
        public Builder timedOut(boolean value) { timedOut = value; return this; }
        public Builder aborted(boolean value) { aborted = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder stdout(ShellOutput value) { stdout = value; return this; }
        public Builder stderr(ShellOutput value) { stderr = value; return this; }

        public ShellResult build() {
            return new ShellResult(exitCode, signal, timedOut, aborted, timeout, stdout, stderr);
        }
    }
}
