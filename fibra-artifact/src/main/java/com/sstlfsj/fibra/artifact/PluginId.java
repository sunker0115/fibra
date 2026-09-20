package com.sstlfsj.fibra.artifact;

public record PluginId(String value) {
    public PluginId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("plugin id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
