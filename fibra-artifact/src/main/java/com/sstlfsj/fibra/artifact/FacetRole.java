package com.sstlfsj.fibra.artifact;

public record FacetRole(String value) {
    public static final FacetRole HOST = new FacetRole("host");
    public static final FacetRole COMMAND = new FacetRole("command");
    public static final FacetRole CLIENT = new FacetRole("client");

    public FacetRole {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("facet role must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
