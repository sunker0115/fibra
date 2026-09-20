package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigContextSnapshotTest {
    @Test
    void freezesContentCalculatesRevisionAndRejectsReservedMetadata() {
        var nested = new ArrayList<>(java.util.List.of("first"));
        var source = new LinkedHashMap<String, Object>();
        source.put("tenant", Map.of("features", nested));

        var snapshot = snapshot(source);
        nested.add("later");

        assertEquals(Map.of("tenant", Map.of("features", java.util.List.of("first"))),
            snapshot.values().toJava());
        assertEquals(64, snapshot.revision().length());
        assertEquals(snapshot.revision(), snapshot(
            Map.of("tenant", Map.of("features", java.util.List.of("first")))).revision());
        assertNotEquals(snapshot.revision(), ConfigContextSnapshot.empty().revision());
        assertThrows(IllegalArgumentException.class,
            () -> snapshot(Map.of("entry", Map.of("id", "forged"))));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.values().values().put("x", LiteralValue.of(true)));
    }

    @Test
    void canonicalRevisionNormalizesKeyOrderNumbersAndUnicode() {
        var first = new LinkedHashMap<String, Object>();
        first.put("z", new java.math.BigDecimal("1000.00"));
        first.put("语言", "中文");
        first.put("a", java.util.List.of(1, "文本"));
        var second = new LinkedHashMap<String, Object>();
        second.put("a", java.util.List.of(1L, "文本"));
        second.put("语言", "中文");
        second.put("z", 1000L);

        var left = snapshot(first);
        var right = snapshot(second);

        assertEquals("{\"a\":[1,\"文本\"],\"z\":1E+3,\"语言\":\"中文\"}",
            left.values().canonicalJson());
        assertEquals(left.revision(), right.revision());
    }

    @Test
    void distinguishesMissingValuesFromExplicitNull() {
        var explicitNull = new LinkedHashMap<String, Object>();
        explicitNull.put("optional", null);

        var missing = ConfigContextSnapshot.empty();
        var present = snapshot(explicitNull);

        assertNotEquals(missing, present);
        assertNotEquals(missing.revision(), present.revision());
        assertEquals(LiteralValue.NullValue.INSTANCE, present.values().values().get("optional"));
    }

    private static ConfigContextSnapshot snapshot(Map<String, ?> values) {
        return ConfigContextSnapshot.of((LiteralValue.ObjectValue) LiteralValue.of(values));
    }
}
