package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.ServiceKey;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DesiredEntry {
    private final String instanceId;
    private final String definitionName;
    private final boolean enabled;
    private final PublicationRequirement publicationRequirement;
    private final Object config;
    private final Map<String, Object> realms;
    private final Map<String, Object> intercepts;
    private final Set<ServiceKey<?>> requires;
    private final Set<ServiceKey<?>> provides;
    private final Path source;

    private DesiredEntry(Builder builder) {
        instanceId = requireName(builder.instanceId, "instance id");
        definitionName = requireName(builder.definitionName, "definition name");
        enabled = builder.enabled;
        publicationRequirement = Objects.requireNonNull(builder.publicationRequirement,
            "publicationRequirement");
        config = builder.config;
        realms = Map.copyOf(builder.realms);
        intercepts = Map.copyOf(builder.intercepts);
        requires = Set.copyOf(builder.requires);
        provides = Set.copyOf(builder.provides);
        source = Objects.requireNonNull(builder.source, "source");
    }

    public static Builder builder(String instanceId, String definitionName) {
        return new Builder(instanceId, definitionName);
    }

    public Builder toBuilder() {
        return new Builder(instanceId, definitionName).enabled(enabled)
            .publicationRequirement(publicationRequirement).config(config)
            .realms(realms).intercepts(intercepts).requires(requires).provides(provides)
            .source(source);
    }

    public String instanceId() { return instanceId; }
    public String definitionName() { return definitionName; }
    public boolean enabled() { return enabled; }
    public PublicationRequirement publicationRequirement() {
        return publicationRequirement;
    }
    public Object config() { return config; }
    public Map<String, Object> realms() { return realms; }
    public Map<String, Object> intercepts() { return intercepts; }
    public Set<ServiceKey<?>> requires() { return requires; }
    public Set<ServiceKey<?>> provides() { return provides; }
    public Path source() { return source; }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof DesiredEntry other)) return false;
        return enabled == other.enabled
            && publicationRequirement == other.publicationRequirement
            && instanceId.equals(other.instanceId)
            && definitionName.equals(other.definitionName)
            && Objects.equals(config, other.config) && realms.equals(other.realms)
            && intercepts.equals(other.intercepts) && requires.equals(other.requires)
            && provides.equals(other.provides) && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, definitionName, enabled, publicationRequirement,
            config, realms, intercepts, requires, provides, source);
    }

    @Override
    public String toString() {
        return "DesiredEntry[instanceId=" + instanceId + ", definitionName="
            + definitionName + ", enabled=" + enabled + ", publicationRequirement="
            + publicationRequirement + ", config=" + config
            + ", realms=" + realms + ", intercepts=" + intercepts + ", requires="
            + requires + ", provides=" + provides + ", source=" + source + ']';
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
        private Object config;
        private Map<String, Object> realms = Map.of();
        private Map<String, Object> intercepts = Map.of();
        private Set<ServiceKey<?>> requires = Set.of();
        private Set<ServiceKey<?>> provides = Set.of();
        private Path source = Path.of("programmatic");

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
        public Builder config(Object value) { config = value; return this; }
        public Builder realms(Map<String, Object> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, Object> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public Builder requires(Set<ServiceKey<?>> value) {
            requires = Objects.requireNonNull(value, "requires"); return this;
        }
        public Builder provides(Set<ServiceKey<?>> value) {
            provides = Objects.requireNonNull(value, "provides"); return this;
        }
        public Builder source(Path value) {
            source = Objects.requireNonNull(value, "source"); return this;
        }
        public DesiredEntry build() { return new DesiredEntry(this); }
    }
}
