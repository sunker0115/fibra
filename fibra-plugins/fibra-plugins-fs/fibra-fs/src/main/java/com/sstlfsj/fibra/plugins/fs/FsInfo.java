package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public record FsInfo(FsFileType type, FsVersion version, Long sizeBytes) {
    public FsInfo {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(version, "version");
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
