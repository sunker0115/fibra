package com.sstlfsj.fibra.plugins.fs;

public record FsVersion(String value) {
    public FsVersion {
        value = FsTarget.requireNonBlank(value, "value");
    }
}
