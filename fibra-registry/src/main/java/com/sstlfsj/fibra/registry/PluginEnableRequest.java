package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;
import java.util.Objects;

public final class PluginEnableRequest {
    private final String instanceId;
    private final String definitionName;
    private final LiteralValue config;
    private final Map<String, LiteralValue> realms;
    private final Map<String, LiteralValue> intercepts;

    private PluginEnableRequest(Builder builder) {
        instanceId = requireName(builder.instanceId, "instanceId");
        definitionName = requireName(builder.definitionName, "definitionName");
        config = Objects.requireNonNull(builder.config, "config");
        realms = Map.copyOf(builder.realms);
        intercepts = Map.copyOf(builder.intercepts);
    }

    public static Builder builder(String instanceId, String definitionName) {
        return new Builder(instanceId, definitionName);
    }

    public static PluginEnableRequest of(String instanceId, String definitionName,
                                         LiteralValue config) {
        return builder(instanceId, definitionName).config(config).build();
    }

    public String instanceId() { return instanceId; }
    public String definitionName() { return definitionName; }
    public LiteralValue config() { return config; }
    public Map<String, LiteralValue> realms() { return realms; }
    public Map<String, LiteralValue> intercepts() { return intercepts; }

    private static String requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final String instanceId;
        private final String definitionName;
        private LiteralValue config = LiteralValue.NullValue.INSTANCE;
        private Map<String, LiteralValue> realms = Map.of();
        private Map<String, LiteralValue> intercepts = Map.of();

        private Builder(String instanceId, String definitionName) {
            this.instanceId = instanceId;
            this.definitionName = definitionName;
        }

        public Builder config(LiteralValue value) { config = Objects.requireNonNull(value, "config"); return this; }
        public Builder realms(Map<String, LiteralValue> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, LiteralValue> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public PluginEnableRequest build() { return new PluginEnableRequest(this); }
    }
}
