package com.sstlfsj.fibra.plugins.fs.search;

public record SearchTiming(Long timeoutMillis, Long graceMillis) {
    private static final long MAX_TIMER_DELAY_MILLIS = 2_147_483_647L;
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000;
    private static final long DEFAULT_GRACE_MILLIS = 3_000;

    public SearchTiming {
        timeoutMillis = bounded(timeoutMillis, DEFAULT_TIMEOUT_MILLIS, "timeoutMillis");
        graceMillis = bounded(graceMillis, DEFAULT_GRACE_MILLIS, "graceMillis");
    }

    public static SearchTiming defaults() {
        return new SearchTiming(null, null);
    }

    private static long positive(Long value, long fallback, String name) {
        var resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return resolved;
    }

    private static long bounded(Long value, long fallback, String name) {
        var resolved = positive(value, fallback, name);
        if (resolved > MAX_TIMER_DELAY_MILLIS) {
            throw new IllegalArgumentException(name
                + " must be a positive finite number no greater than " + MAX_TIMER_DELAY_MILLIS);
        }
        return resolved;
    }
}
