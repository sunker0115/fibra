package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.ConfigContextSnapshot;

import java.util.Objects;

/** 以独立 context revision CAS 替换当前运行上下文，不修改持久部署目标。 */
public record ReplaceConfigContext(String expectedRevision, String expectedContextRevision,
                                   ConfigContextSnapshot context) implements EngineCommand {
    public ReplaceConfigContext {
        if (expectedContextRevision == null || expectedContextRevision.isBlank()) {
            throw new IllegalArgumentException("expectedContextRevision must not be blank");
        }
        Objects.requireNonNull(context, "context");
    }
}
