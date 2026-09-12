package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginInstance;

import java.util.Objects;

/** 一个已绑定 definition、可与其他实例目标一起登记的插件配置更新。 */
public record PluginUpdate<C>(PluginInstance<C> instance,
                              PluginDefinition.Prepared<C> prepared) {
    public PluginUpdate {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.definition() != instance.definition()) {
            throw new IllegalArgumentException(
                "prepared config belongs to another plugin definition");
        }
    }

    public static <C> PluginUpdate<C> config(PluginInstance<C> instance, C config) {
        Objects.requireNonNull(instance, "instance");
        return new PluginUpdate<>(instance, instance.definition().prepare(config));
    }

    public static <C> PluginUpdate<C> prepared(PluginInstance<C> instance,
                                                PluginDefinition.Prepared<C> prepared) {
        return new PluginUpdate<>(instance, prepared);
    }
}
