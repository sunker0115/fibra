package com.sstlfsj.fibra.engine;

public final class EngineConflictException extends IllegalStateException {
    EngineConflictException(String expected, String actual) {
        super("expected engine revision " + expected + " but found " + actual);
    }
}
