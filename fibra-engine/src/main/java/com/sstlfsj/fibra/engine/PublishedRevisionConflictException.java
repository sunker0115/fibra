package com.sstlfsj.fibra.engine;

public final class PublishedRevisionConflictException extends RuntimeException {
    private final String expected;
    private final String actual;

    public PublishedRevisionConflictException(String expected, String actual) {
        super("published view revision conflict: expected " + expected
            + " but was " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public String expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }
}
