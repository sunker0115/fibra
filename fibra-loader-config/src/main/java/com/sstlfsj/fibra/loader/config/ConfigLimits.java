package com.sstlfsj.fibra.loader.config;

record ConfigLimits(long maxFileBytes, int maxDepth, int maxStringLength,
                    int maxEntriesPerFile) {
    ConfigLimits {
        if (maxFileBytes < 1 || maxDepth < 1 || maxStringLength < 1
            || maxEntriesPerFile < 1) {
            throw new IllegalArgumentException("config limits must be positive");
        }
    }

    static ConfigLimits defaults() {
        return new ConfigLimits(4L * 1024 * 1024, 100, 1024 * 1024, 10_000);
    }
}
