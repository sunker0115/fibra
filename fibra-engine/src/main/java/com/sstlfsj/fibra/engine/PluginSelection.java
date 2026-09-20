package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginId;

import java.util.Objects;

/** 一个逻辑插件在唯一部署目标中的精确 package 选择和统一启停门。 */
public record PluginSelection(PluginId pluginId, String packageRevision, boolean enabled) {
    public PluginSelection {
        Objects.requireNonNull(pluginId, "pluginId");
        if (packageRevision == null || !packageRevision.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "package revision must be a lowercase SHA-256 digest");
        }
    }
}
