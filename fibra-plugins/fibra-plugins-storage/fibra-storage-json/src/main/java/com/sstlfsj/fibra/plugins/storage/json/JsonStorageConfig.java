package com.sstlfsj.fibra.plugins.storage.json;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public record JsonStorageConfig(String root) {
    public JsonStorageConfig {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("storage root must not be blank");
        }
        try {
            if (!Path.of(root).isAbsolute()) {
                throw new IllegalArgumentException("storage root must be absolute");
            }
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("storage root is not a valid path", failure);
        }
    }
}
