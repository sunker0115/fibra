package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public record FsTarget(String key, String displayPath) {
    public FsTarget {
        key = requireNonBlank(key, "key");
        displayPath = requireNonBlank(displayPath, "displayPath");
    }

    static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
