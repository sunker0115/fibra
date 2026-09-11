package com.sstlfsj.fibra.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiteralValueTest {
    @Test
    void recursivelyFreezesContainersAndPreservesNulls() {
        var values = new ArrayList<Object>();
        values.add(null);
        values.add("before");
        var source = new LinkedHashMap<String, Object>();
        source.put("values", values);
        var frozen = LiteralValue.of(source);
        values.set(1, "after");
        source.clear();

        assertEquals("{\"values\":[null,\"before\"]}", frozen.canonicalJson());
        var object = assertInstanceOf(LiteralValue.ObjectValue.class, frozen);
        assertThrows(UnsupportedOperationException.class, () -> object.values().clear());
        var list = assertInstanceOf(LiteralValue.ListValue.class, object.values().get("values"));
        assertThrows(UnsupportedOperationException.class, () -> list.values().clear());
    }

    @Test
    void canonicalEncodingNormalizesNumbersAndObjectOrderButPreservesListOrder() {
        var first = new LinkedHashMap<String, Object>();
        first.put("z", new BigDecimal("1000.00"));
        first.put("a", List.of(true, false));
        var second = Map.of("a", List.of(true, false), "z", 1000L);

        assertEquals(LiteralValue.of(first), LiteralValue.of(second));
        assertEquals("{\"a\":[true,false],\"z\":1E+3}", LiteralValue.of(first).canonicalJson());
        assertEquals(LiteralValue.of(0), LiteralValue.of(new BigDecimal("-0.000")));
        assertNotEquals(LiteralValue.of(List.of(1, 2)), LiteralValue.of(List.of(2, 1)));
    }

    @Test
    void encodesStringsWithoutLosingEscapesOrUnicode() {
        assertEquals("\"引号\\\"\\\\\\n\\r\\t\\b\\f\\u0001\"",
            LiteralValue.of("引号\"\\\n\r\t\b\f\u0001").canonicalJson());
    }

    @Test
    void rejectsMutableNumbersNonFiniteNumbersLiveObjectsAndCycles() {
        assertThrows(IllegalArgumentException.class, () ->
            LiteralValue.of(new java.util.concurrent.atomic.AtomicInteger(1)));
        assertThrows(IllegalArgumentException.class, () -> LiteralValue.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LiteralValue.of(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> LiteralValue.of(new Object()));
        assertThrows(IllegalArgumentException.class, () -> LiteralValue.of(Map.of(1, "not text")));
        var cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> LiteralValue.of(cyclic));
    }

    @Test
    void javaProjectionIsDeeplyImmutableAndContainsOnlyData() {
        var value = LiteralValue.of(Map.of("items", List.of(1, "two")));
        var projected = assertInstanceOf(Map.class, value.toJava());
        var items = assertInstanceOf(List.class, projected.get("items"));
        assertEquals(new BigDecimal("1"), items.getFirst());
        assertThrows(UnsupportedOperationException.class, projected::clear);
        assertThrows(UnsupportedOperationException.class, items::clear);
        assertNull(LiteralValue.of(null).toJava());
    }
}
