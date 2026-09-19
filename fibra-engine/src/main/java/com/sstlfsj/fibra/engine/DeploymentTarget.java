package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.ConfigContextSnapshot;
import com.sstlfsj.fibra.config.DesiredIncludeContent;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputGroup;
import com.sstlfsj.fibra.config.DesiredInputInclude;
import com.sstlfsj.fibra.config.DesiredInputNode;
import com.sstlfsj.fibra.value.LiteralValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已分配部署代次的完整逻辑目标；内容摘要不包含部署代次。 */
public final class DeploymentTarget {
    private final long targetRevision;
    private final String targetDigest;
    private final Map<PluginId, PluginSelection> selections;
    private final DesiredInputGraph desiredGraph;
    private final ConfigContextSnapshot configContext;

    private DeploymentTarget(long targetRevision, Collection<PluginSelection> selections,
                             DesiredInputGraph desiredGraph,
                             ConfigContextSnapshot configContext) {
        if (targetRevision < 1) {
            throw new IllegalArgumentException("target revision must be positive");
        }
        this.targetRevision = targetRevision;
        this.selections = index(selections);
        this.desiredGraph = Objects.requireNonNull(desiredGraph, "desiredGraph");
        this.configContext = Objects.requireNonNull(configContext, "configContext");
        targetDigest = digest(canonicalBytes(this.selections.values(), desiredGraph,
            configContext));
    }

    public static DeploymentTarget of(long targetRevision,
                                      Collection<PluginSelection> selections,
                                      DesiredInputGraph desiredGraph,
                                      ConfigContextSnapshot configContext) {
        return new DeploymentTarget(targetRevision, selections, desiredGraph,
            configContext);
    }

    public long targetRevision() { return targetRevision; }
    public String targetDigest() { return targetDigest; }
    public Map<PluginId, PluginSelection> selections() { return selections; }
    public DesiredInputGraph desiredGraph() { return desiredGraph; }
    public ConfigContextSnapshot configContext() { return configContext; }

    public boolean hasSameContent(Collection<PluginSelection> candidateSelections,
                                  DesiredInputGraph candidateDesired,
                                  ConfigContextSnapshot candidateContext) {
        return targetDigest.equals(digestOf(candidateSelections, candidateDesired,
            candidateContext));
    }

    public static String digestOf(Collection<PluginSelection> selections,
                                  DesiredInputGraph desiredGraph,
                                  ConfigContextSnapshot configContext) {
        return digest(canonicalBytes(index(selections).values(),
            Objects.requireNonNull(desiredGraph, "desiredGraph"),
            Objects.requireNonNull(configContext, "configContext")));
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) return true;
        if (!(candidate instanceof DeploymentTarget other)) return false;
        return targetRevision == other.targetRevision
            && targetDigest.equals(other.targetDigest)
            && selections.equals(other.selections)
            && desiredGraph.equals(other.desiredGraph)
            && configContext.equals(other.configContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetRevision, targetDigest, selections, desiredGraph,
            configContext);
    }

    @Override
    public String toString() {
        return "DeploymentTarget[targetRevision=" + targetRevision
            + ", targetDigest=" + targetDigest + ", selections=" + selections
            + ", desiredGraph=" + desiredGraph + ", configContext="
            + configContext.values() + ']';
    }

    static byte[] canonicalBytes(Collection<PluginSelection> selections,
                                 DesiredInputGraph desiredGraph,
                                 ConfigContextSnapshot configContext) {
        var canonicalSelections = selections.stream()
            .sorted(Comparator.comparing(selection -> selection.pluginId().value()))
            .map(selection -> Map.of(
                "pluginId", selection.pluginId().value(),
                "packageRevision", selection.packageRevision(),
                "enabled", selection.enabled()))
            .toList();
        return LiteralValue.of(Map.of(
            "format", 1,
            "selections", canonicalSelections,
            "desired", encodeNodes(desiredGraph.roots()),
            "configContext", configContext.values()))
            .canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<PluginId, PluginSelection> index(
        Collection<PluginSelection> values) {
        Objects.requireNonNull(values, "selections");
        var ordered = values.stream()
            .map(value -> Objects.requireNonNull(value, "selection"))
            .sorted(Comparator.comparing(selection -> selection.pluginId().value()))
            .toList();
        var index = new LinkedHashMap<PluginId, PluginSelection>();
        for (var selection : ordered) {
            if (index.putIfAbsent(selection.pluginId(), selection) != null) {
                throw new IllegalArgumentException(
                    "duplicate plugin selection " + selection.pluginId());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static LiteralValue encodeNodes(List<DesiredInputNode> nodes) {
        return new LiteralValue.ListValue(nodes.stream()
            .map(DeploymentTarget::encodeNode).toList());
    }

    private static LiteralValue encodeNode(DesiredInputNode node) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("id", node.id());
        fields.put("enabled", node.enabled());
        fields.put("when", node.when());
        fields.put("context", new LiteralValue.ObjectValue(node.context()));
        fields.put("realms", new LiteralValue.ObjectValue(node.realms()));
        fields.put("intercepts", new LiteralValue.ObjectValue(node.intercepts()));
        switch (node) {
            case DesiredInputEntry entry -> {
                fields.put("kind", "plugin");
                fields.put("definition", Map.of(
                    "pluginId", entry.definitionRef().pluginId(),
                    "facetId", entry.definitionRef().facetId(),
                    "definitionId", entry.definitionRef().definitionId()));
                fields.put("config", entry.config());
                fields.put("publicationRequirement",
                    entry.publicationRequirement().name());
            }
            case DesiredInputGroup group -> {
                fields.put("kind", "group");
                fields.put("children", encodeNodes(group.children()));
            }
            case DesiredInputInclude include -> {
                fields.put("kind", "include");
                fields.put("content", switch (include.content()) {
                    case DesiredIncludeContent.Collected collected -> Map.of(
                        "state", "collected",
                        "children", encodeNodes(collected.children()));
                    case DesiredIncludeContent.Uncollected ignored -> Map.of(
                        "state", "uncollected");
                });
            }
        }
        return LiteralValue.of(fields);
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
