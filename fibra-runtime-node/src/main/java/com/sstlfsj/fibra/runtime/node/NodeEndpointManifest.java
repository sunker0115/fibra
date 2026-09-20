package com.sstlfsj.fibra.runtime.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        descriptor = descriptor == null ? Map.of() : freeze(descriptor);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> values) {
            var result = new LinkedHashMap<Object, Object>();
            values.forEach((key, entry) -> result.put(key, freeze(entry)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<Object>(values.size());
            values.forEach(entry -> result.add(freeze(entry)));
            return List.copyOf(result);
        }
        return value;
    }
}
