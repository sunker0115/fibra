package com.sstlfsj.fibra.artifact;

public record RuntimeId(String value) {
    public RuntimeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
