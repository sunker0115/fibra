package com.sstlfsj.fibra.plugins.shell;

import java.time.Duration;

public record ShellRequest(String command, String workdir, Duration timeout) {
    public ShellRequest {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (workdir == null || workdir.isBlank()) {
            throw new IllegalArgumentException("workdir must not be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String command;
        private String workdir;
        private Duration timeout;

        public Builder command(String value) { command = value; return this; }
        public Builder workdir(String value) { workdir = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }

        public ShellRequest build() {
            return new ShellRequest(command, workdir, timeout);
        }
    }
}
