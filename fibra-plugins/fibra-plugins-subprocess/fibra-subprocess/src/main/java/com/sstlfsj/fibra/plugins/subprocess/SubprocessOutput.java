package com.sstlfsj.fibra.plugins.subprocess;

import java.util.Objects;

public record SubprocessOutput(String text, boolean truncated, long totalBytes) {
    public SubprocessOutput {
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

        public SubprocessOutput build() {
            return new SubprocessOutput(text, truncated, totalBytes);
        }
    }
}
