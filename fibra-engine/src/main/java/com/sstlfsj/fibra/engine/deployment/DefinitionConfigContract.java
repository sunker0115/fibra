package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.config.ConfigSchemaSource;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import java.util.Objects;

/** 当前精确 selection 的纯配置规则，不包含实例现值或资源句柄。 */
public record DefinitionConfigContract(long targetRevision, String packageRevision,
                                       PluginDefinitionRef definition, ConfigSchemaSource source) {
    public DefinitionConfigContract {
        if (targetRevision < 0) throw new IllegalArgumentException("negative target revision");
        Objects.requireNonNull(packageRevision, "packageRevision");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
    }
}
