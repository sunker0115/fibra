package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DesiredInputGroup implements DesiredInputNode {
    private final String id;
    private final boolean enabled;
    private final LiteralValue when;
    private final Map<String, LiteralValue> context;
    private final Map<String, LiteralValue> realms;
    private final Map<String, LiteralValue> intercepts;
    private final List<DesiredInputNode> children;

    private DesiredInputGroup(Builder builder) {
        id = DesiredInputNode.requireId(builder.id);
        enabled = builder.enabled;
        when = Objects.requireNonNull(builder.when, "when");
        ConfigExpressionEvaluator.validateCondition(when);
        context = DesiredInputValues.context(builder.context);
        realms = PolicyValues.realms(builder.realms);
        intercepts = PolicyValues.intercepts(builder.intercepts);
        children = List.copyOf(builder.children);
    }

    public static Builder builder(String id) { return new Builder(id); }
    @Override public String id() { return id; }
    @Override public boolean enabled() { return enabled; }
    @Override public LiteralValue when() { return when; }
    @Override public Map<String, LiteralValue> context() { return context; }
    @Override public Map<String, LiteralValue> realms() { return realms; }
    @Override public Map<String, LiteralValue> intercepts() { return intercepts; }
    public List<DesiredInputNode> children() { return children; }

    @Override public boolean equals(Object value) {
        return this == value || value instanceof DesiredInputGroup other && enabled == other.enabled
            && id.equals(other.id) && when.equals(other.when) && context.equals(other.context)
            && realms.equals(other.realms)
            && intercepts.equals(other.intercepts) && children.equals(other.children);
    }
    @Override public int hashCode() {
        return Objects.hash(id, enabled, when, context, realms, intercepts, children);
    }

    public static final class Builder {
        private final String id;
        private boolean enabled = true;
        private LiteralValue when = LiteralValue.of(true);
        private Map<String, LiteralValue> context = Map.of();
        private Map<String, LiteralValue> realms = Map.of();
        private Map<String, LiteralValue> intercepts = Map.of();
        private List<? extends DesiredInputNode> children = List.of();
        private Builder(String id) { this.id = id; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder when(LiteralValue value) {
            when = Objects.requireNonNull(value, "when"); return this;
        }
        public Builder context(Map<String, LiteralValue> value) {
            context = Objects.requireNonNull(value, "context"); return this;
        }
        public Builder realms(Map<String, LiteralValue> value) {
            realms = Objects.requireNonNull(value, "realms"); return this;
        }
        public Builder intercepts(Map<String, LiteralValue> value) {
            intercepts = Objects.requireNonNull(value, "intercepts"); return this;
        }
        public Builder children(List<? extends DesiredInputNode> value) {
            children = Objects.requireNonNull(value, "children"); return this;
        }
        public DesiredInputGroup build() { return new DesiredInputGroup(this); }
    }
}
