package com.sstlfsj.fibra.plugins.shell;

import java.util.Objects;

public record ShellOutput(String text, boolean truncated, long totalBytes) {
    public ShellOutput {
        Objects.requireNonNull(text, "text");
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String text;
        private boolean truncated;
        private long totalBytes;

        public Builder text(String value) { text = value; return this; }
        public Builder truncated(boolean value) { truncated = value; return this; }
        public Builder totalBytes(long value) { totalBytes = value; return this; }

        public ShellOutput build() {
            return new ShellOutput(text, truncated, totalBytes);
        }
    }
}
