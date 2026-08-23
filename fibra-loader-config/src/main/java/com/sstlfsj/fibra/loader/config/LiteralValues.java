package com.sstlfsj.fibra.loader.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiteralValues {
    private LiteralValues() {
    }

    static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("literal object keys must be strings");
                }
                result.put(name, freeze(nested));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>(list.size());
            list.forEach(nested -> result.add(freeze(nested)));
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number
            || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("unsupported literal value type "
            + value.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> value) {
        return (Map<String, Object>) freeze(value);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> freezeEntries(List<? extends Map<String, ?>> entries) {
        return (List<Map<String, Object>>) (List<?>) freeze(entries);
    }

    static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> result.put((String) key, mutable(nested)));
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>(list.size());
            list.forEach(nested -> result.add(mutable(nested)));
            return result;
        }
        return value;
    }
}
