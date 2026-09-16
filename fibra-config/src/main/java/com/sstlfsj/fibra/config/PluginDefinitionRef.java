package com.sstlfsj.fibra.config;

/** 配置对插件定义的完整逻辑引用，不引入对 artifact 模块的反向依赖。 */
public record PluginDefinitionRef(String pluginId, String definitionId) {
    public PluginDefinitionRef {
        pluginId = required(pluginId, "plugin id");
        definitionId = required(definitionId, "definition id");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
