package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 从不可变 raw desired graph 和上下文快照派生有效配置，不修改二者。 */
public final class DesiredEvaluation {
    private final DesiredInputGraph graph;
    private final ConfigContextSnapshot context;
    private final Map<String, ResolvedDesiredEntry> entries;

    private DesiredEvaluation(DesiredInputGraph graph, ConfigContextSnapshot context,
                              Map<String, ResolvedDesiredEntry> entries) {
        this.graph = graph;
        this.context = context;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static DesiredEvaluation evaluate(DesiredInputGraph graph,
                                             ConfigContextSnapshot context) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(context, "context");
        var entries = new LinkedHashMap<String, ResolvedDesiredEntry>();
        evaluate(graph.roots(), "", null, context.values(), true, graph, entries);
        return new DesiredEvaluation(graph, context, entries);
    }

    public DesiredInputGraph graph() {
        return graph;
    }

    public ConfigContextSnapshot context() {
        return context;
    }

    public Map<String, ResolvedDesiredEntry> entries() {
        return entries;
    }

    public ResolvedDesiredEntry require(String entryId) {
        var entry = entries.get(entryId);
        if (entry == null) throw new IllegalArgumentException("unknown desired node " + entryId);
        return entry;
    }

    private static void evaluate(List<DesiredInputNode> nodes, String namespace, String parentId,
                                 LiteralValue.ObjectValue inheritedContext, boolean parentActive,
                                 DesiredInputGraph graph,
                                 Map<String, ResolvedDesiredEntry> result) {
        for (var node : nodes) {
            var fullId = namespace.isEmpty() ? node.id() : namespace + ':' + node.id();
            var localContext = overlay(inheritedContext, node.context());
            var rawEffective = graph.effective(fullId);
            var active = parentActive && rawEffective.enabled();
            if (active) {
                try {
                    active = ConfigExpressionEvaluator.evaluateCondition(node.when(),
                        withEntry(localContext, fullId, parentId));
                } catch (ConfigException failure) {
                    throw atEntry(failure, fullId);
                }
            }
            var effective = new DesiredInputGraph.EffectiveDesiredEntry(
                rawEffective.entryId(), rawEffective.parentId(), active,
                rawEffective.realms(), rawEffective.intercepts());
            Optional<LiteralValue> config = Optional.empty();
            if (active && node instanceof DesiredInputEntry entry) {
                try {
                    config = Optional.of(ConfigExpressionEvaluator.evaluate(entry.config(),
                        withEntry(localContext, fullId, parentId)));
                } catch (ConfigException failure) {
                    throw atEntry(failure, fullId);
                }
            }
            result.put(fullId, new ResolvedDesiredEntry(node, effective, config));
            if (node instanceof DesiredInputGroup group) {
                evaluate(group.children(), namespace, fullId, localContext, active, graph, result);
            } else if (node instanceof DesiredInputInclude include
                && include.content() instanceof DesiredIncludeContent.Collected collected) {
                evaluate(collected.children(), fullId, fullId, localContext, active, graph, result);
            }
        }
    }

    private static LiteralValue.ObjectValue overlay(LiteralValue.ObjectValue inherited,
                                                     Map<String, LiteralValue> local) {
        var values = new LinkedHashMap<>(inherited.values());
        values.putAll(local);
        return new LiteralValue.ObjectValue(values);
    }

    private static LiteralValue.ObjectValue withEntry(LiteralValue.ObjectValue context,
                                                       String id, String parentId) {
        var values = new LinkedHashMap<>(context.values());
        values.put("entry", LiteralValue.of(Map.of(
            "id", id,
            "parentId", parentId == null ? LiteralValue.NullValue.INSTANCE : parentId)));
        return new LiteralValue.ObjectValue(values);
    }

    private static ConfigException atEntry(ConfigException failure, String entryId) {
        var diagnostic = failure.diagnostic();
        return new ConfigException(new ConfigDiagnostic(diagnostic.stage(), diagnostic.code(),
            diagnostic.message(), diagnostic.source(), entryId), failure);
    }
}
