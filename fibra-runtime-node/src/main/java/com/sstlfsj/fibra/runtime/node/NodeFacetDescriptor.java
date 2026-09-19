package com.sstlfsj.fibra.runtime.node;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Node facet payload 的局部静态描述；逻辑 package 身份与依赖由 Engine 提供。 */
public record NodeFacetDescriptor(int protocol, String definitionId, String entrypoint,
                                  List<NodeEndpointManifest> contributions) {
    public NodeFacetDescriptor {
        if (protocol != 1) {
            throw new IllegalArgumentException("unsupported Node protocol " + protocol);
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("entrypoint must not be blank");
        }
        var values = List.copyOf(Objects.requireNonNull(contributions,
            "contributions"));
        var names = new LinkedHashSet<String>();
        for (var contribution : values) {
            if (!names.add(Objects.requireNonNull(contribution, "contribution").name())) {
                throw new IllegalArgumentException("duplicate contribution "
                    + contribution.name());
            }
        }
        contributions = values;
    }
}
