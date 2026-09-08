package com.sstlfsj.fibra.runtime.node;

import java.util.Map;

public record NodeEndpointManifest(String name, String kind, int schemaVersion,
                                   String method, Object descriptor) {
    public NodeEndpointManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("endpoint name must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("endpoint kind must not be blank");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("endpoint method must not be blank");
        }
        descriptor = descriptor == null ? Map.of() : descriptor;
    }
}
