package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public record FsWriteResult(FsWriteOperation operation, FsVersion version, String before, String after) {
    public FsWriteResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(after, "after");
    }
}
