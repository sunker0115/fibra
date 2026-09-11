package com.sstlfsj.fibra.plugins.subprocess;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record SubprocessSpec(List<String> argv, String cwd, int stdoutMaxBytes,
                             int stderrMaxBytes, Duration grace) {
    public SubprocessSpec {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank elements");
        }
        if (cwd == null || cwd.isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        if (stdoutMaxBytes <= 0 || stderrMaxBytes <= 0) {
            throw new IllegalArgumentException("output limits must be positive");
        }
        if (grace == null || grace.isZero() || grace.isNegative()) {
            throw new IllegalArgumentException("grace must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> argv;
        private String cwd;
        private int stdoutMaxBytes;
        private int stderrMaxBytes;
        private Duration grace;

        public Builder argv(List<String> value) { argv = value; return this; }
        public Builder cwd(String value) { cwd = value; return this; }
        public Builder stdoutMaxBytes(int value) { stdoutMaxBytes = value; return this; }
        public Builder stderrMaxBytes(int value) { stderrMaxBytes = value; return this; }
        public Builder grace(Duration value) { grace = value; return this; }

        public SubprocessSpec build() {
            return new SubprocessSpec(argv, cwd, stdoutMaxBytes, stderrMaxBytes, grace);
        }
    }
}
