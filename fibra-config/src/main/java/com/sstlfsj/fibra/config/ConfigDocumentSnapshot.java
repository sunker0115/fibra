package com.sstlfsj.fibra.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

public record ConfigDocumentSnapshot(Path path, String revision, byte[] content) {
    public ConfigDocumentSnapshot {
        Objects.requireNonNull(path, "path");
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
