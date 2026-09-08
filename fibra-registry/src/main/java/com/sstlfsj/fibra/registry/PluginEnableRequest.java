package com.sstlfsj.fibra.registry;

import java.util.Map;
import java.util.Objects;

public final class PluginEnableRequest {
    private final String instanceId;
    private final String definitionName;
    private final Object config;
    private final Map<String, Object> realms;
    private final Map<String, Object> intercepts;

    private PluginEnableRequest(Builder builder) {
        instanceId = requireName(builder.instanceId, "instanceId");
        definitionName = requireName(builder.definitionName, "definitionName");
        config = builder.config;
        realms = Map.copyOf(builder.realms);
        intercepts = Map.copyOf(builder.intercepts);
    }

    public static Builder builder(String instanceId, String definitionName) {
        return new Builder(instanceId, definitionName);
    }

    public static PluginEnableRequest of(String instanceId, String definitionName,
                                         Object config) {
        return builder(instanceId, definitionName).config(config).build();
    }

    public String instanceId() { return instanceId; }
    public String definitionName() { return definitionName; }
    public Object config() { return config; }
    public Map<String, Object> realms() { return realms; }
    public Map<String, Object> intercepts() { return intercepts; }

    private static String requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final String instanceId;
        private final String definitionName;
        private Object config;
        private Map<String, Object> realms = Map.of();
        private Map<String, Object> intercepts = Map.of();

        private Builder(String instanceId, String definitionName) {
            this.instanceId = instanceId;
            this.definitionName = definitionName;
        }

        public Builder config(Object value) { config = value; return this; }
        public Builder realms(Map<String, Object> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, Object> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public PluginEnableRequest build() { return new PluginEnableRequest(this); }
    }
}
