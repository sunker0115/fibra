package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.config.PublicationRequirement;

import java.util.Objects;

public final class DefinitionBindingPlan {
    private final PluginDefinitionRef definition;
    private final String desiredEntryId;
    private final ExecutionUnitKey unitKey;
    private final PublicationRequirement publicationRequirement;

    private DefinitionBindingPlan(Builder builder) {
        definition = Objects.requireNonNull(builder.definition, "definition");
        desiredEntryId = required(builder.desiredEntryId, "desired entry id");
        unitKey = Objects.requireNonNull(builder.unitKey, "unitKey");
        publicationRequirement = Objects.requireNonNull(
            builder.publicationRequirement, "publicationRequirement");
    }

    public static Builder builder(PluginDefinitionRef definition,
                                  String desiredEntryId) {
        return new Builder(definition, desiredEntryId);
    }

    public PluginDefinitionRef definition() { return definition; }
    public String desiredEntryId() { return desiredEntryId; }
    public ExecutionUnitKey unitKey() { return unitKey; }
    public PublicationRequirement publicationRequirement() {
        return publicationRequirement;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private final PluginDefinitionRef definition;
        private final String desiredEntryId;
        private ExecutionUnitKey unitKey;
        private PublicationRequirement publicationRequirement;

        private Builder(PluginDefinitionRef definition, String desiredEntryId) {
            this.definition = Objects.requireNonNull(definition, "definition");
            this.desiredEntryId = required(desiredEntryId, "desired entry id");
        }

        public Builder unitKey(ExecutionUnitKey value) {
            unitKey = Objects.requireNonNull(value, "unitKey");
            return this;
        }

        public Builder publicationRequirement(PublicationRequirement value) {
            publicationRequirement = Objects.requireNonNull(value,
                "publicationRequirement");
            return this;
        }

        public DefinitionBindingPlan build() {
            return new DefinitionBindingPlan(this);
        }
    }
}
