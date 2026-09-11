package com.sstlfsj.fibra.plugins.fs.search;

public record SearchLimits(Integer globMaxResults, Integer grepMaxMatches,
                           Integer grepMaxLineBytes, Integer rawOutputMaxBytes,
                           Integer stderrMaxBytes) {
    private static final int DEFAULT_GLOB_MAX_RESULTS = 100;
    private static final int DEFAULT_GREP_MAX_MATCHES = 250;
    private static final int DEFAULT_GREP_MAX_LINE_BYTES = 2_000;
    private static final int DEFAULT_RAW_OUTPUT_MAX_BYTES = 20_000_000;
    private static final int DEFAULT_STDERR_MAX_BYTES = 64 * 1_024;

    public SearchLimits {
        globMaxResults = positive(globMaxResults, DEFAULT_GLOB_MAX_RESULTS,
            "globMaxResults");
        grepMaxMatches = positive(grepMaxMatches, DEFAULT_GREP_MAX_MATCHES,
            "grepMaxMatches");
        grepMaxLineBytes = positive(grepMaxLineBytes, DEFAULT_GREP_MAX_LINE_BYTES,
            "grepMaxLineBytes");
        rawOutputMaxBytes = positive(rawOutputMaxBytes, DEFAULT_RAW_OUTPUT_MAX_BYTES,
            "rawOutputMaxBytes");
        stderrMaxBytes = positive(stderrMaxBytes, DEFAULT_STDERR_MAX_BYTES,
            "stderrMaxBytes");
    }

    public static SearchLimits defaults() {
        return new SearchLimits(null, null, null, null, null);
    }

    public SearchLimits withGlobMaxResults(int value) {
        return new SearchLimits(value, grepMaxMatches, grepMaxLineBytes,
            rawOutputMaxBytes, stderrMaxBytes);
    }

    private static int positive(Integer value, int fallback, String name) {
        var resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return resolved;
    }
}
