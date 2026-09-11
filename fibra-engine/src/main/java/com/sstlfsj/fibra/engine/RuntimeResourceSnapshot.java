package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.List;
import java.util.Objects;

/** 资源身份与清理事实；不暴露装载器、进程或可变句柄。 */
public record RuntimeResourceSnapshot(RuntimeId runtimeId, List<Resource> resources) {
    public RuntimeResourceSnapshot {
        Objects.requireNonNull(runtimeId, "runtimeId");
        resources = List.copyOf(resources);
        if (resources.stream().map(Resource::identity).distinct().count() != resources.size()) {
            throw new IllegalArgumentException("duplicate runtime resource identity");
        }
        if (resources.stream().anyMatch(resource -> !runtimeId.equals(resource.artifact().runtimeId()))) {
            throw new IllegalArgumentException("resource belongs to another runtime");
        }
    }

    public enum State { PREPARED, ACTIVE, RETIRED, CLOSED, CLOSE_FAILED }

    public record Resource(ArtifactRecord artifact, String identity, State state, String failure) {
        public Resource {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(state, "state");
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("empty resource identity");
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private ArtifactRecord artifact;
            private String identity;
            private State state;
            private String failure;

            private Builder() { }
            public Builder artifact(ArtifactRecord value) { artifact = value; return this; }
            public Builder identity(String value) { identity = value; return this; }
            public Builder state(State value) { state = value; return this; }
            public Builder failure(String value) { failure = value; return this; }
            public Resource build() { return new Resource(artifact, identity, state, failure); }
        }
    }
}
