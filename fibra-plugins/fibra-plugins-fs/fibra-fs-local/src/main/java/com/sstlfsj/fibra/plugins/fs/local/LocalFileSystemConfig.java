package com.sstlfsj.fibra.plugins.fs.local;

import java.nio.file.Path;
import java.util.Objects;

/** Configuration kept in the provider artifact rather than the shared file-system contract. */
public record LocalFileSystemConfig(String root, String spillDirectory) {
    public LocalFileSystemConfig {
        root = nonBlank(root, "root");
        spillDirectory = nonBlank(spillDirectory, "spillDirectory");
        if (Path.of(spillDirectory).isAbsolute() || spillDirectory.contains("..")) {
            throw new IllegalArgumentException("spillDirectory must be root-relative");
        }
    }

    public Path rootPath() {
        return Path.of(root);
    }

    private static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
