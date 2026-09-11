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

        var snapshot = ConfigContextSnapshot.of(source);
        nested.add("later");

        assertEquals(Map.of("tenant", Map.of("features", java.util.List.of("first"))),
            snapshot.values().toJava());
        assertEquals(64, snapshot.revision().length());
        assertEquals(snapshot.revision(), ConfigContextSnapshot.of(
            Map.of("tenant", Map.of("features", java.util.List.of("first")))).revision());
        assertNotEquals(snapshot.revision(), ConfigContextSnapshot.empty().revision());
        assertThrows(IllegalArgumentException.class,
            () -> ConfigContextSnapshot.of(Map.of("entry", Map.of("id", "forged"))));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.values().values().put("x", LiteralValue.of(true)));
    }
}
