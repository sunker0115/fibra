package com.sstlfsj.fibra.loader.pf4j;

import java.nio.file.Path;
import java.util.Objects;

/** 外部候选插件发现或更新失败的最近一次可观测记录。 */
public record FibraPluginWatchFailure(Path candidate, Throwable cause) {
    public FibraPluginWatchFailure {
        candidate = Objects.requireNonNull(candidate, "candidate")
            .toAbsolutePath().normalize();
        cause = Objects.requireNonNull(cause, "cause");
    }
}
