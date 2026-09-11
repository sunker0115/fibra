package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DesiredInputGraph {
    private final List<DesiredInputNode> roots;
    private final Map<String, NodeRef> nodes;
    private final Map<String, DesiredInputEntry> plugins;

    public DesiredInputGraph(List<? extends DesiredInputNode> roots) {
        this.roots = List.copyOf(roots);
        var indexed = new LinkedHashMap<String, NodeRef>();
        var entries = new LinkedHashMap<String, DesiredInputEntry>();
        index(this.roots, null, "", true, List.of(), indexed, entries);
        nodes = Collections.unmodifiableMap(indexed);
        plugins = Collections.unmodifiableMap(entries);
    }

    public List<DesiredInputNode> roots() { return roots; }
    public Map<String, DesiredInputEntry> plugins() { return plugins; }

    public DesiredInputNode require(String fullId) { return ref(fullId).node(); }

    public EffectiveDesiredEntry effective(String fullId) {
        var ref = ref(fullId);
        var realms = new LinkedHashMap<String, EffectivePolicyValue>();
        var intercepts = new LinkedHashMap<String, EffectivePolicyValue>();
        var enabled = true;
        for (var ancestor : ref.ancestors()) {
            enabled &= ancestor.node().enabled();
            policies(realms, ancestor.fullId(), ancestor.node().realms());
            policies(intercepts, ancestor.fullId(), ancestor.node().intercepts());
        }
        enabled &= ref.node().enabled();
        policies(realms, ref.fullId(), ref.node().realms());
        policies(intercepts, ref.fullId(), ref.node().intercepts());
        return new EffectiveDesiredEntry(ref.fullId(), ref.parentId(), enabled,
            Collections.unmodifiableMap(realms), Collections.unmodifiableMap(intercepts));
    }

    public DesiredInputGraph upsert(String parentId, DesiredInputNode replacement) {
        Objects.requireNonNull(replacement, "replacement");
        var parent = parent(parentId);
        var fullId = complete(childNamespace(parent), replacement.id());
        var existing = nodes.get(fullId);
        if (existing != null) {
            if (!Objects.equals(existing.parentId(), parentId)) {
                throw new IllegalArgumentException("desired node already belongs to another parent " + fullId);
            }
            return new DesiredInputGraph(replace(roots, fullId, replacement, ""));
        }
        return new DesiredInputGraph(insert(roots, parentId, replacement));
    }

    public DesiredInputGraph remove(String fullId) {
        ref(fullId);
        return new DesiredInputGraph(remove(roots, fullId, ""));
    }

    public DesiredInputGraph withEnabled(String fullId, boolean enabled) {
        return new DesiredInputGraph(replace(roots, fullId,
            withEnabled(ref(fullId).node(), enabled), ""));
    }

    public DesiredInputGraph move(String fullId, String parentId, int position) {
        var source = ref(fullId);
        var destination = parent(parentId);
        if (destination != null && isDescendant(destination, source)) {
            throw new IllegalArgumentException("cannot move a node into its descendant");
        }
        var withoutSource = remove(roots, fullId, "");
        var count = childCount(withoutSource, parentId, "");
        if (position < 0 || position > count) {
            throw new IllegalArgumentException("position must be between 0 and " + count);
        }
        return new DesiredInputGraph(insert(withoutSource, parentId, source.node(), position));
    }

    private NodeRef parent(String parentId) {
        if (parentId == null) return null;
        var parent = ref(parentId);
        if (!(parent.node() instanceof DesiredInputGroup)
            && !(parent.node() instanceof DesiredInputInclude)) {
            throw new IllegalArgumentException("plugin nodes cannot have children " + parentId);
        }
        if (parent.node() instanceof DesiredInputInclude include
            && include.content() instanceof DesiredIncludeContent.Uncollected) {
            throw includeContentRequired(parent.fullId());
        }
        return parent;
    }

    private NodeRef ref(String fullId) {
        var node = nodes.get(fullId);
        if (node == null) throw new IllegalArgumentException("unknown desired node " + fullId);
        return node;
    }

    private static void index(List<DesiredInputNode> values, String parentId, String namespace,
                              boolean ancestorsEnabled, List<Ancestor> ancestors,
                              Map<String, NodeRef> index,
                              Map<String, DesiredInputEntry> entries) {
        for (var value : values) {
            var fullId = complete(namespace, value.id());
            var ref = new NodeRef(value, parentId, fullId, List.copyOf(ancestors));
            if (index.putIfAbsent(fullId, ref) != null) {
                throw new IllegalArgumentException("duplicate desired node id " + fullId);
            }
            if (value instanceof DesiredInputEntry entry) entries.put(fullId, entry);
            var enabled = ancestorsEnabled && value.enabled();
            if (value instanceof DesiredInputGroup group) {
                index(group.children(), fullId, namespace, enabled, append(ancestors, fullId, value),
                    index, entries);
            } else if (value instanceof DesiredInputInclude include) {
                if (include.content() instanceof DesiredIncludeContent.Uncollected && enabled) {
                    throw includeContentRequired(fullId);
                }
                if (include.content() instanceof DesiredIncludeContent.Collected collected) {
                    index(collected.children(), fullId, fullId, enabled,
                        append(ancestors, fullId, value), index, entries);
                }
            }
        }
    }

    private static List<Ancestor> append(List<Ancestor> values, String fullId,
                                         DesiredInputNode node) {
        var next = new ArrayList<>(values);
        next.add(new Ancestor(fullId, node));
        return List.copyOf(next);
    }

    private static ConfigException includeContentRequired(String fullId) {
        return new ConfigException(new ConfigDiagnostic(ConfigStage.VALIDATE,
            "INCLUDE_CONTENT_REQUIRED", "enabled include content is not collected", null, fullId), null);
    }

    private static void policies(Map<String, EffectivePolicyValue> result, String owner,
                                 Map<String, LiteralValue> values) {
        values.forEach((key, value) -> result.put(key, new EffectivePolicyValue(owner, value)));
    }

    private boolean isDescendant(NodeRef candidate, NodeRef ancestor) {
        var current = candidate;
        while (current.parentId() != null) {
            if (current.parentId().equals(ancestor.fullId())) return true;
            current = ref(current.parentId());
        }
        return false;
    }

    private static String childNamespace(NodeRef parent) {
        if (parent == null) return "";
        return parent.node() instanceof DesiredInputInclude ? parent.fullId() : namespace(parent);
    }

    private static String namespace(NodeRef ref) {
        var separator = ref.fullId().lastIndexOf(':');
        return separator < 0 ? "" : ref.fullId().substring(0, separator);
    }

    private static int childCount(List<DesiredInputNode> values, String parentId, String namespace) {
        if (parentId == null) return values.size();
        var parent = find(values, parentId, namespace);
        if (parent == null) throw new IllegalArgumentException("unknown desired node " + parentId);
        return children(parent).size();
    }

    private static List<DesiredInputNode> insert(List<DesiredInputNode> values, String parentId,
                                                  DesiredInputNode replacement) {
        return insert(values, parentId, replacement, childCount(values, parentId, ""));
    }

    private static List<DesiredInputNode> insert(List<DesiredInputNode> values, String parentId,
                                                  DesiredInputNode replacement, int position) {
        if (parentId == null) {
            var next = new ArrayList<>(values);
            next.add(position, replacement);
            return List.copyOf(next);
        }
        return modify(values, parentId, "", node -> {
            var children = new ArrayList<>(children(node));
            children.add(position, replacement);
            return withChildren(node, children);
        });
    }

    private static List<DesiredInputNode> remove(List<DesiredInputNode> values, String fullId,
                                                  String namespace) {
        var result = new ArrayList<DesiredInputNode>(values.size());
        for (var value : values) {
            var id = complete(namespace, value.id());
            if (!id.equals(fullId)) result.add(removeFromChildren(value, fullId, namespace));
        }
        return List.copyOf(result);
    }

    private static DesiredInputNode removeFromChildren(DesiredInputNode node, String fullId,
                                                       String namespace) {
        if (node instanceof DesiredInputGroup group) {
            return withChildren(group, remove(group.children(), fullId, namespace));
        }
        if (node instanceof DesiredInputInclude include
            && include.content() instanceof DesiredIncludeContent.Collected collected) {
            return withChildren(include, remove(collected.children(), fullId,
                complete(namespace, include.id())));
        }
        return node;
    }

    private static List<DesiredInputNode> replace(List<DesiredInputNode> values, String fullId,
                                                   DesiredInputNode replacement, String namespace) {
        var result = new ArrayList<DesiredInputNode>(values.size());
        for (var value : values) {
            var id = complete(namespace, value.id());
            result.add(id.equals(fullId) ? replacement
                : mapChildren(value, fullId, namespace, x -> replacement));
        }
        return List.copyOf(result);
    }

    private static List<DesiredInputNode> modify(List<DesiredInputNode> values, String fullId,
                                                  String namespace,
                                                  java.util.function.UnaryOperator<DesiredInputNode> action) {
        var result = new ArrayList<DesiredInputNode>(values.size());
        for (var value : values) {
            var id = complete(namespace, value.id());
            result.add(id.equals(fullId) ? action.apply(value)
                : mapChildren(value, fullId, namespace, action));
        }
        return List.copyOf(result);
    }

    private static DesiredInputNode mapChildren(DesiredInputNode node, String fullId,
                                                String namespace,
                                                java.util.function.UnaryOperator<DesiredInputNode> action) {
        if (node instanceof DesiredInputGroup group) {
            return withChildren(group, modify(group.children(), fullId, namespace, action));
        }
        if (node instanceof DesiredInputInclude include
            && include.content() instanceof DesiredIncludeContent.Collected collected) {
            return withChildren(include, modify(collected.children(), fullId,
                complete(namespace, include.id()), action));
        }
        return node;
    }

    private static DesiredInputNode find(List<DesiredInputNode> values, String fullId,
                                         String namespace) {
        for (var value : values) {
            var id = complete(namespace, value.id());
            if (id.equals(fullId)) return value;
            if (value instanceof DesiredInputGroup group) {
                var found = find(group.children(), fullId, namespace);
                if (found != null) return found;
            } else if (value instanceof DesiredInputInclude include
                && include.content() instanceof DesiredIncludeContent.Collected collected) {
                var found = find(collected.children(), fullId, complete(namespace, include.id()));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<DesiredInputNode> children(DesiredInputNode node) {
        if (node instanceof DesiredInputGroup group) return group.children();
        if (node instanceof DesiredInputInclude include) {
            return include.content() instanceof DesiredIncludeContent.Collected collected
                ? collected.children() : List.of();
        }
        throw new IllegalArgumentException("plugin nodes cannot have children " + node.id());
    }

    private static DesiredInputNode withChildren(DesiredInputNode node,
                                                 List<DesiredInputNode> children) {
        if (node instanceof DesiredInputGroup group) return DesiredInputGroup.builder(group.id())
            .enabled(group.enabled()).realms(group.realms()).intercepts(group.intercepts())
            .children(children).build();
        if (node instanceof DesiredInputInclude include) return DesiredInputInclude.builder(include.id())
            .enabled(include.enabled()).realms(include.realms()).intercepts(include.intercepts())
            .content(new DesiredIncludeContent.Collected(children)).build();
        throw new IllegalArgumentException("plugin nodes cannot have children " + node.id());
    }

    private static DesiredInputNode withEnabled(DesiredInputNode node, boolean enabled) {
        if (node instanceof DesiredInputEntry entry) return entry.toBuilder().enabled(enabled).build();
        if (node instanceof DesiredInputGroup group) return DesiredInputGroup.builder(group.id())
            .enabled(enabled).realms(group.realms()).intercepts(group.intercepts())
            .children(group.children()).build();
        var include = (DesiredInputInclude) node;
        return DesiredInputInclude.builder(include.id()).enabled(enabled).realms(include.realms())
            .intercepts(include.intercepts()).content(include.content()).build();
    }

    private static String complete(String namespace, String id) {
        return namespace.isEmpty() ? id : namespace + ':' + id;
    }

    @Override public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof DesiredInputGraph other && roots.equals(other.roots);
    }
    @Override public int hashCode() { return roots.hashCode(); }

    public record EffectiveDesiredEntry(String entryId, String parentId, boolean enabled,
                                        Map<String, EffectivePolicyValue> realms,
                                        Map<String, EffectivePolicyValue> intercepts) {
        public EffectiveDesiredEntry {
            Objects.requireNonNull(entryId, "entryId");
            realms = Map.copyOf(realms);
            intercepts = Map.copyOf(intercepts);
        }
    }
    public record EffectivePolicyValue(String ownerEntryId, LiteralValue value) {
        public EffectivePolicyValue {
            Objects.requireNonNull(ownerEntryId, "ownerEntryId");
            Objects.requireNonNull(value, "value");
        }
    }
    private record Ancestor(String fullId, DesiredInputNode node) { }
    private record NodeRef(DesiredInputNode node, String parentId, String fullId,
                           List<Ancestor> ancestors) { }
}
