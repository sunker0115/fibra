package com.sstlfsj.fibra.plugins.fs.search;

import java.util.Objects;

record SearchMatch(String path, int lineNumber, String line) {
    SearchMatch {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(line, "line");
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
    }
}
