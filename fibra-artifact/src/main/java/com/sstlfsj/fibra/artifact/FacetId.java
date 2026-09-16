package com.sstlfsj.fibra.artifact;

public record FacetId(String value) {
    public FacetId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("facet id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
