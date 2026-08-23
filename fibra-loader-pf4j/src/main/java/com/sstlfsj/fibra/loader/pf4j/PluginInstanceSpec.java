package com.sstlfsj.fibra.loader.pf4j;

import com.sstlfsj.fibra.Context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一个 PF4J 制品创建的 Fibra 运行实例规格。 */
public final class PluginInstanceSpec {
    private final String entryId;
    private final String pluginId;
    private final Context parentContext;
    private final PluginConfigFactory configFactory;
    private final boolean constantConfig;
    private final Map<String, Object> requirements;

    private PluginInstanceSpec(Builder builder) {
        this.entryId = requireText(builder.entryId, "entryId");
        this.pluginId = requireText(builder.pluginId, "pluginId");
        this.parentContext = Objects.requireNonNull(builder.parentContext, "parentContext");
        this.configFactory = builder.configFactory;
        this.constantConfig = builder.constantConfig;
        this.requirements = Collections.unmodifiableMap(
            new LinkedHashMap<>(builder.requirements));
    }

    public static Builder builder(String entryId, String pluginId) {
        return new Builder(entryId, pluginId);
    }

    public String entryId() {
        return entryId;
    }

    public String pluginId() {
        return pluginId;
    }

    public Context parentContext() {
        return parentContext;
    }

    public PluginConfigFactory configFactory() {
        return configFactory;
    }

    public Map<String, Object> requirements() {
        return requirements;
    }

    PluginInstanceSpec withConfigFactory(PluginConfigFactory nextFactory,
                                         boolean nextConstantConfig) {
        return builder(entryId, pluginId)
            .parentContext(parentContext)
            .configFactory(nextFactory)
            .constantConfig(nextConstantConfig)
            .requirements(requirements)
            .build();
    }

    boolean constantConfig() {
        return constantConfig;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final String entryId;
        private final String pluginId;
        private Context parentContext;
        private PluginConfigFactory configFactory = ignored -> null;
        private boolean constantConfig = true;
        private final Map<String, Object> requirements = new LinkedHashMap<>();

        private Builder(String entryId, String pluginId) {
            this.entryId = entryId;
            this.pluginId = pluginId;
        }

        public Builder parentContext(Context parentContext) {
            this.parentContext = Objects.requireNonNull(parentContext, "parentContext");
            return this;
        }

        public Builder config(Object config) {
            this.configFactory = ignored -> config;
            this.constantConfig = true;
            return this;
        }

        public Builder configFactory(PluginConfigFactory configFactory) {
            this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
            this.constantConfig = false;
            return this;
        }

        private Builder constantConfig(boolean value) {
            constantConfig = value;
            return this;
        }

        public Builder require(String serviceName) {
            return require(serviceName, null);
        }

        public Builder require(String serviceName, Object intercept) {
            requirements.put(requireText(serviceName, "serviceName"), intercept);
            return this;
        }

        public Builder requirements(Map<String, ?> requirements) {
            Objects.requireNonNull(requirements, "requirements").forEach(this::require);
            return this;
        }

        public PluginInstanceSpec build() {
            return new PluginInstanceSpec(this);
        }
    }
}
