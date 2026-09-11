package com.sstlfsj.fibra.plugins.fs.search;

public record SearchPluginConfig(String rgExecutable, String workdir,
                                 SearchLimits limits, SearchTiming timing) {
    public SearchPluginConfig {
        if (rgExecutable == null || rgExecutable.isBlank()) {
            throw new IllegalArgumentException("rgExecutable must not be blank");
        }
        if (workdir == null || workdir.isBlank()) {
            throw new IllegalArgumentException("workdir must not be blank");
        }
        limits = limits == null ? SearchLimits.defaults() : limits;
        timing = timing == null ? SearchTiming.defaults() : timing;
    }

    public static SearchPluginConfig defaults(String rgExecutable, String workdir) {
        return new SearchPluginConfig(rgExecutable, workdir, null, null);
    }

    public SearchPluginConfig withLimits(SearchLimits value) {
        return new SearchPluginConfig(rgExecutable, workdir, value, timing);
    }

    public SearchPluginConfig withTiming(SearchTiming value) {
        return new SearchPluginConfig(rgExecutable, workdir, limits, value);
    }
}
