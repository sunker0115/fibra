package com.sstlfsj.fibra.artifact;

public record ExecutionTarget(String value) {
    public ExecutionTarget {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("execution target must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
