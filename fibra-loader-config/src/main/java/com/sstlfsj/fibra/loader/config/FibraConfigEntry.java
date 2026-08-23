package com.sstlfsj.fibra.loader.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 经过 include、patch 和继承展开后的不可变配置条目。 */
public final class FibraConfigEntry {
    public enum Kind {
        PLUGIN,
        GROUP,
        INCLUDE
    }

    private final String id;
    private final String entryId;
    private final String sourceEntryId;
    private final Kind kind;
    private final String pluginId;
    private final Path source;
    private final Path includedPath;
    private final boolean declaredDisabled;
    private final boolean disabled;
    private final Object config;
    private final Map<String, Object> inject;
    private final Map<String, Object> localIntercept;
    private final Map<String, Object> localIsolate;
    private final Map<String, Object> intercept;
    private final Map<String, Object> isolate;
    private final List<FibraConfigEntry> children;

    private FibraConfigEntry(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.entryId = Objects.requireNonNull(builder.entryId, "entryId");
        this.sourceEntryId = Objects.requireNonNull(builder.sourceEntryId, "sourceEntryId");
        this.kind = Objects.requireNonNull(builder.kind, "kind");
        this.pluginId = builder.pluginId;
        this.source = Objects.requireNonNull(builder.source, "source");
        this.includedPath = builder.includedPath;
        this.declaredDisabled = builder.declaredDisabled;
        this.disabled = builder.disabled;
        this.config = LiteralValues.freeze(builder.config);
        this.inject = LiteralValues.freezeMap(builder.inject);
        this.localIntercept = LiteralValues.freezeMap(builder.localIntercept);
        this.localIsolate = LiteralValues.freezeMap(builder.localIsolate);
        this.intercept = LiteralValues.freezeMap(builder.intercept);
        this.isolate = LiteralValues.freezeMap(builder.isolate);
        this.children = List.copyOf(builder.children);
    }

    public String id() {
        return id;
    }

    public String entryId() {
        return entryId;
    }

    public String sourceEntryId() {
        return sourceEntryId;
    }

    public Kind kind() {
        return kind;
    }

    public String pluginId() {
        return pluginId;
    }

    public Path source() {
        return source;
    }

    public Path includedPath() {
        return includedPath;
    }

    public boolean declaredDisabled() {
        return declaredDisabled;
    }

    public boolean disabled() {
        return disabled;
    }

    public Object config() {
        return config;
    }

    public Map<String, Object> inject() {
        return inject;
    }

    public Map<String, Object> localIntercept() {
        return localIntercept;
    }

    public Map<String, Object> localIsolate() {
        return localIsolate;
    }

    public Map<String, Object> intercept() {
        return intercept;
    }

    public Map<String, Object> isolate() {
        return isolate;
    }

    public List<FibraConfigEntry> children() {
        return children;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FibraConfigEntry entry)) {
            return false;
        }
        return declaredDisabled == entry.declaredDisabled
            && disabled == entry.disabled
            && id.equals(entry.id)
            && entryId.equals(entry.entryId)
            && sourceEntryId.equals(entry.sourceEntryId)
            && kind == entry.kind
            && Objects.equals(pluginId, entry.pluginId)
            && source.equals(entry.source)
            && Objects.equals(includedPath, entry.includedPath)
            && Objects.equals(config, entry.config)
            && inject.equals(entry.inject)
            && localIntercept.equals(entry.localIntercept)
            && localIsolate.equals(entry.localIsolate)
            && intercept.equals(entry.intercept)
            && isolate.equals(entry.isolate)
            && children.equals(entry.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, entryId, sourceEntryId, kind, pluginId, source, includedPath,
            declaredDisabled, disabled, config, inject, localIntercept, localIsolate,
            intercept, isolate, children);
    }

    static final class Builder {
        private String id;
        private String entryId;
        private String sourceEntryId;
        private Kind kind;
        private String pluginId;
        private Path source;
        private Path includedPath;
        private boolean declaredDisabled;
        private boolean disabled;
        private Object config;
        private Map<String, Object> inject = Map.of();
        private Map<String, Object> localIntercept = Map.of();
        private Map<String, Object> localIsolate = Map.of();
        private Map<String, Object> intercept = Map.of();
        private Map<String, Object> isolate = Map.of();
        private List<FibraConfigEntry> children = List.of();

        Builder id(String value) {
            id = value;
            return this;
        }

        Builder entryId(String value) {
            entryId = value;
            return this;
        }

        Builder sourceEntryId(String value) {
            sourceEntryId = value;
            return this;
        }

        Builder kind(Kind value) {
            kind = value;
            return this;
        }

        Builder pluginId(String value) {
            pluginId = value;
            return this;
        }

        Builder source(Path value) {
            source = value;
            return this;
        }

        Builder includedPath(Path value) {
            includedPath = value;
            return this;
        }

        Builder declaredDisabled(boolean value) {
            declaredDisabled = value;
            return this;
        }

        Builder disabled(boolean value) {
            disabled = value;
            return this;
        }

        Builder config(Object value) {
            config = value;
            return this;
        }

        Builder inject(Map<String, Object> value) {
            inject = value;
            return this;
        }

        Builder intercept(Map<String, Object> value) {
            intercept = value;
            return this;
        }

        Builder isolate(Map<String, Object> value) {
            isolate = value;
            return this;
        }

        Builder localIntercept(Map<String, Object> value) {
            localIntercept = value;
            return this;
        }

        Builder localIsolate(Map<String, Object> value) {
            localIsolate = value;
            return this;
        }

        Builder children(List<FibraConfigEntry> value) {
            children = value;
            return this;
        }

        FibraConfigEntry build() {
            return new FibraConfigEntry(this);
        }
    }
}
