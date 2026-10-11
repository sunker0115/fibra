package com.sstlfsj.fibra.engine.deployment;

import com.sstlfsj.fibra.artifact.ExecutionTarget;
import com.sstlfsj.fibra.artifact.FacetId;
import com.sstlfsj.fibra.artifact.RuntimeId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 一个 facet 的原始声明名称；无执行入口的 facet 保留合法空列表。 */
public record FacetDefinitionNames(FacetId facetId, RuntimeId runtimeId,
                                   ExecutionTarget executionTarget,
                                   List<String> definitionIds) {
    public FacetDefinitionNames {
        Objects.requireNonNull(facetId, "facetId");
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(executionTarget, "executionTarget");
        Objects.requireNonNull(definitionIds, "definitionIds");
        definitionIds = definitionIds.stream()
            .map(id -> Objects.requireNonNull(id, "definitionId"))
            .sorted()
            .toList();
        var names = new HashSet<String>();
        for (var id : definitionIds) {
            if (id.isBlank()) {
                throw new IllegalArgumentException("definition id must not be blank");
            }
            if (!names.add(id)) {
                throw new IllegalArgumentException("duplicate definition id " + id);
            }
        }
    }
}
