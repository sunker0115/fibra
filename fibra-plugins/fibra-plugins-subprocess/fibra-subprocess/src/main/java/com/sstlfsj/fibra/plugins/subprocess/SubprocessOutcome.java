package com.sstlfsj.fibra.plugins.subprocess;

import java.util.Objects;

public record SubprocessOutcome(Integer exitCode, String signal, SubprocessOutput stdout,
                                SubprocessOutput stderr) {
    public SubprocessOutcome {
        if ((exitCode == null) == (signal == null)) {
            throw new IllegalArgumentException("exactly one of exitCode or signal is required");
        }
        if (signal != null && signal.isBlank()) {
            throw new IllegalArgumentException("signal must not be blank");
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
        private SubprocessOutput stdout;
        private SubprocessOutput stderr;

        public Builder exitCode(Integer value) { exitCode = value; return this; }
        public Builder signal(String value) { signal = value; return this; }
        public Builder stdout(SubprocessOutput value) { stdout = value; return this; }
        public Builder stderr(SubprocessOutput value) { stderr = value; return this; }

        public SubprocessOutcome build() {
            return new SubprocessOutcome(exitCode, signal, stdout, stderr);
        }
    }
}
