package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Map;
import java.util.Objects;

public final class DesiredInputInclude implements DesiredInputNode {
    private final String id;
    private final boolean enabled;
    private final Map<String, LiteralValue> realms;
    private final Map<String, LiteralValue> intercepts;
    private final DesiredIncludeContent content;

    private DesiredInputInclude(Builder builder) {
        id = DesiredInputNode.requireId(builder.id);
        enabled = builder.enabled;
        realms = PolicyValues.realms(builder.realms);
        intercepts = PolicyValues.intercepts(builder.intercepts);
        content = Objects.requireNonNull(builder.content, "content");
    }

    public static Builder builder(String id) { return new Builder(id); }
    @Override public String id() { return id; }
    @Override public boolean enabled() { return enabled; }
    @Override public Map<String, LiteralValue> realms() { return realms; }
    @Override public Map<String, LiteralValue> intercepts() { return intercepts; }
    public DesiredIncludeContent content() { return content; }

    @Override public boolean equals(Object value) {
        return this == value || value instanceof DesiredInputInclude other && enabled == other.enabled
            && id.equals(other.id) && realms.equals(other.realms)
            && intercepts.equals(other.intercepts) && content.equals(other.content);
    }
    @Override public int hashCode() { return Objects.hash(id, enabled, realms, intercepts, content); }

    public static final class Builder {
        private final String id;
        private boolean enabled = true;
        private Map<String, LiteralValue> realms = Map.of();
        private Map<String, LiteralValue> intercepts = Map.of();
        private DesiredIncludeContent content = DesiredIncludeContent.Uncollected.INSTANCE;
        private Builder(String id) { this.id = id; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder realms(Map<String, LiteralValue> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, LiteralValue> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public Builder content(DesiredIncludeContent value) {
            content = Objects.requireNonNull(value, "content"); return this;
        }
        public DesiredInputInclude build() { return new DesiredInputInclude(this); }
    }
}
