package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;
import java.util.Objects;

public final class DesiredInputEntry implements DesiredInputNode {
    private final String id;
    private final String definitionName;
    private final boolean enabled;
    private final PublicationRequirement publicationRequirement;
    private final LiteralValue config;
    private final Map<String, LiteralValue> realms;
    private final Map<String, LiteralValue> intercepts;

    private DesiredInputEntry(Builder builder) {
        id = DesiredInputNode.requireId(builder.id);
        definitionName = requireName(builder.definitionName, "definition name");
        enabled = builder.enabled;
        publicationRequirement = Objects.requireNonNull(builder.publicationRequirement,
            "publicationRequirement");
        config = Objects.requireNonNull(builder.config, "config");
        realms = PolicyValues.realms(builder.realms);
        intercepts = PolicyValues.intercepts(builder.intercepts);
    }

    public static Builder builder(String id, String definitionName) {
        return new Builder(id, definitionName);
    }

    public Builder toBuilder() {
        return new Builder(id, definitionName).enabled(enabled)
            .publicationRequirement(publicationRequirement).config(config)
            .realms(realms).intercepts(intercepts);
    }

    @Override public String id() { return id; }
    public String definitionName() { return definitionName; }
    @Override public boolean enabled() { return enabled; }
    public PublicationRequirement publicationRequirement() {
        return publicationRequirement;
    }
    public LiteralValue config() { return config; }
    @Override public Map<String, LiteralValue> realms() { return realms; }
    @Override public Map<String, LiteralValue> intercepts() { return intercepts; }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof DesiredInputEntry other)) return false;
        return enabled == other.enabled
            && publicationRequirement == other.publicationRequirement
            && id.equals(other.id)
            && definitionName.equals(other.definitionName)
            && Objects.equals(config, other.config) && realms.equals(other.realms)
            && intercepts.equals(other.intercepts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, definitionName, enabled, publicationRequirement,
            config, realms, intercepts);
    }

    @Override
    public String toString() {
        return "DesiredInputEntry[id=" + id + ", definitionName="
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
        private final String id;
        private final String definitionName;
        private boolean enabled = true;
        private PublicationRequirement publicationRequirement =
            PublicationRequirement.ACTIVE_REQUIRED;
        private LiteralValue config = LiteralValue.NullValue.INSTANCE;
        private Map<String, LiteralValue> realms = Map.of();
        private Map<String, LiteralValue> intercepts = Map.of();

        private Builder(String id, String definitionName) {
            this.id = id;
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
