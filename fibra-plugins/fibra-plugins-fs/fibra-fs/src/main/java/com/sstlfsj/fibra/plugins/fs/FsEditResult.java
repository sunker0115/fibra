package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public record FsEditResult(FsVersion version, String before, String after) {
    public FsEditResult {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
