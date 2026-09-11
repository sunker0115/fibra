package com.sstlfsj.fibra.plugins.fs.tool;

/** Bounded read presentation settings matching the fixed DSH tool-fs contract. */
public record FileToolConfig(Integer readLimit, Integer readMaxLineLength,
                             Long readMaxBytes, Long readSourceMaxBytes) {
    private static final int DEFAULT_READ_LIMIT = 2_000;
    private static final int DEFAULT_MAX_LINE_LENGTH = 2_000;
    private static final long DEFAULT_MAX_BYTES = 50L * 1_024;
    private static final long DEFAULT_SOURCE_MAX_BYTES = 10L * 1_024 * 1_024;

    public FileToolConfig() {
        this(null, null, null, null);
    }

    public FileToolConfig {
        readLimit = positiveOrDefault(readLimit, DEFAULT_READ_LIMIT, "readLimit");
        readMaxLineLength = positiveOrDefault(readMaxLineLength, DEFAULT_MAX_LINE_LENGTH,
            "readMaxLineLength");
        readMaxBytes = positiveOrDefault(readMaxBytes, DEFAULT_MAX_BYTES, "readMaxBytes");
        readSourceMaxBytes = positiveOrDefault(readSourceMaxBytes, DEFAULT_SOURCE_MAX_BYTES,
            "readSourceMaxBytes");
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String name) {
        if (value == null) return defaultValue;
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positiveOrDefault(Long value, long defaultValue, String name) {
        if (value == null) return defaultValue;
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
