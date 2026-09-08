package com.sstlfsj.fibra.config;

import java.nio.file.Path;
import java.util.Set;

public record DesiredSourceSnapshot(String sourceId, String revision, Set<Path> sources) {
    public DesiredSourceSnapshot {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
        sources = Set.copyOf(sources);
    }
}
