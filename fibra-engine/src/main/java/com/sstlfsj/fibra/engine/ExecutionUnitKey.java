package com.sstlfsj.fibra.engine;

public record ExecutionUnitKey(String value) implements Comparable<ExecutionUnitKey> {
    public ExecutionUnitKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("execution unit key must not be blank");
        }
    }

    @Override
    public int compareTo(ExecutionUnitKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
