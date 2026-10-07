package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.engine.BuiltInPluginPackage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Java provider 私有的 built-in definition 所有者；Engine 只接收 metadata。 */
public final class JavaBuiltInPackage {
    private final BuiltInPluginPackage metadata;
    private final Map<FacetId, Map<String, JavaDefinitionEntry<?>>> definitions;

    public JavaBuiltInPackage(BuiltInPluginPackage metadata,
                              Map<FacetId, List<JavaDefinitionEntry<?>>> entries) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        if (metadata.facets().stream().anyMatch(facet ->
            !JavaRuntimeProvider.RUNTIME_ID.equals(facet.runtimeId()))) {
            throw new IllegalArgumentException("Java built-in package contains another runtime");
        }
        var copied = new LinkedHashMap<FacetId, Map<String, JavaDefinitionEntry<?>>>();
        Objects.requireNonNull(entries, "entries").forEach((facetId, values) -> {
            var byId = new LinkedHashMap<String, JavaDefinitionEntry<?>>();
            for (var entry : List.copyOf(values)) {
                var previous = byId.putIfAbsent(entry.definition().name(), entry);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate Java built-in definition "
                        + facetId + '/' + entry.definition().name());
                }
            }
            copied.put(Objects.requireNonNull(facetId, "facetId"), Map.copyOf(byId));
        });
        definitions = Map.copyOf(copied);
    }

    private void validateDefinitions() {
        var expected = metadata.facets().stream().collect(java.util.stream.Collectors.toMap(
            facet -> facet.facetId(), facet -> facet.definitionIds()));
        if (!definitions.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException("Java built-in facets do not exactly match metadata");
        }
        definitions.forEach((facetId, values) -> {
            if (!values.keySet().equals(Set.copyOf(expected.get(facetId)))) {
                throw new IllegalArgumentException("Java built-in definitions do not exactly match metadata for "
                    + facetId);
            }
        });
    }

    public BuiltInPluginPackage metadata() {
        return metadata;
    }

    JavaDefinitionEntry<?> definition(FacetId facetId, String definitionId) {
        // 在 candidate prepare 内校验，让可信持久目标可进入 BLOCKED 并由管理面修正。
        validateDefinitions();
        var values = definitions.get(facetId);
        if (values == null || !values.containsKey(definitionId)) {
            throw new IllegalArgumentException("unknown Java built-in definition "
                + metadata.pluginId() + '/' + facetId + '/' + definitionId);
        }
        return values.get(definitionId);
    }
}
