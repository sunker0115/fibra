package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public record FsEdit(String oldText, String newText, boolean replaceAll) {
    public FsEdit {
        Objects.requireNonNull(oldText, "oldText");
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
        Objects.requireNonNull(newText, "newText");
    }
}
