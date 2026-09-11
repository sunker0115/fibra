package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginInstanceState;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

/** Engine 持有的声明实例；动态子实例只进入 RuntimeDiagnostics。 */
public record PluginInstanceSnapshot(long identity, String instanceId, String definitionName,
                                     LiteralValue config, PluginInstanceState state,
                                     PublicationRequirement publicationRequirement,
                                     String failure) {
    public PluginInstanceSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definitionName, "definitionName");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(publicationRequirement, "publicationRequirement");
    }

    public boolean requirementSatisfied() { return publicationRequirement.accepts(state); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long identity;
        private String instanceId;
        private String definitionName;
        private LiteralValue config;
        private PluginInstanceState state;
        private PublicationRequirement publicationRequirement;
        private String failure;

        private Builder() { }

        public Builder identity(long value) { identity = value; return this; }
        public Builder instanceId(String value) { instanceId = value; return this; }
        public Builder definitionName(String value) { definitionName = value; return this; }
        public Builder config(LiteralValue value) { config = value; return this; }
        public Builder state(PluginInstanceState value) { state = value; return this; }
        public Builder publicationRequirement(PublicationRequirement value) {
            publicationRequirement = value; return this;
        }
        public Builder failure(String value) { failure = value; return this; }
        public PluginInstanceSnapshot build() {
            return new PluginInstanceSnapshot(identity, instanceId, definitionName, config, state,
                publicationRequirement, failure);
        }
    }
}
