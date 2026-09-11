package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;
import java.util.Objects;

public final class DesiredInputEntry {
    private final String instanceId;
    private final String definitionName;
    private final boolean enabled;
    private final PublicationRequirement publicationRequirement;
    private final LiteralValue config;
    private final Map<String, LiteralValue> realms;
    private final Map<String, LiteralValue> intercepts;

    private DesiredInputEntry(Builder builder) {
        instanceId = requireName(builder.instanceId, "instance id");
        definitionName = requireName(builder.definitionName, "definition name");
        enabled = builder.enabled;
        publicationRequirement = Objects.requireNonNull(builder.publicationRequirement,
            "publicationRequirement");
        config = Objects.requireNonNull(builder.config, "config");
        realms = Map.copyOf(builder.realms);
        intercepts = Map.copyOf(builder.intercepts);
    }

    public static Builder builder(String instanceId, String definitionName) {
        return new Builder(instanceId, definitionName);
    }

    public Builder toBuilder() {
        return new Builder(instanceId, definitionName).enabled(enabled)
            .publicationRequirement(publicationRequirement).config(config)
            .realms(realms).intercepts(intercepts);
    }

    public String instanceId() { return instanceId; }
    public String definitionName() { return definitionName; }
    public boolean enabled() { return enabled; }
    public PublicationRequirement publicationRequirement() {
        return publicationRequirement;
    }
    public LiteralValue config() { return config; }
    public Map<String, LiteralValue> realms() { return realms; }
    public Map<String, LiteralValue> intercepts() { return intercepts; }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof DesiredInputEntry other)) return false;
        return enabled == other.enabled
            && publicationRequirement == other.publicationRequirement
            && instanceId.equals(other.instanceId)
            && definitionName.equals(other.definitionName)
            && Objects.equals(config, other.config) && realms.equals(other.realms)
            && intercepts.equals(other.intercepts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, definitionName, enabled, publicationRequirement,
            config, realms, intercepts);
    }

    @Override
    public String toString() {
        return "DesiredInputEntry[instanceId=" + instanceId + ", definitionName="
            + definitionName + ", enabled=" + enabled + ", publicationRequirement="
            + publicationRequirement + ", config=" + config
            + ", realms=" + realms + ", intercepts=" + intercepts + ']';
    }

    private static String requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final String instanceId;
        private final String definitionName;
        private boolean enabled = true;
        private PublicationRequirement publicationRequirement =
            PublicationRequirement.ACTIVE_REQUIRED;
        private LiteralValue config = LiteralValue.NullValue.INSTANCE;
        private Map<String, LiteralValue> realms = Map.of();
        private Map<String, LiteralValue> intercepts = Map.of();

        private Builder(String instanceId, String definitionName) {
            this.instanceId = instanceId;
            this.definitionName = definitionName;
        }

        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder publicationRequirement(PublicationRequirement value) {
            publicationRequirement = Objects.requireNonNull(value,
                "publicationRequirement");
            return this;
        }
        public Builder config(LiteralValue value) { config = Objects.requireNonNull(value, "config"); return this; }
        public Builder realms(Map<String, LiteralValue> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, LiteralValue> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public DesiredInputEntry build() { return new DesiredInputEntry(this); }
    }
}
