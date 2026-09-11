package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiteralValues {
    private LiteralValues() {
    }

    static Object freeze(Object value) {
        return LiteralValue.of(value).toJava();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<?, ?> value) {
        return (Map<String, Object>) freeze(value);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> freezeEntries(List<?> entries) {
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
