package com.sstlfsj.fibra.artifact;

public record ArtifactId(String value) {
    public ArtifactId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifact id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
