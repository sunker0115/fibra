package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiteralValuesTest {
    @Test
    void canonicalEncodingRoundTripsThroughTheRealJsonReaderWithoutLosingData() {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("null", null);
        data.put("integer", new java.math.BigInteger("123456789012345678901234567890"));
        data.put("decimal", new java.math.BigDecimal("0.10000000000000000001"));
        data.put("escaped", "\"\\\u0000\n中文\ud83d\ude00\ud800");
        data.put("nested", List.of(Map.of("value", true), Map.of("value", false)));
        var original = LiteralValue.of(data);
        var encoded = "[{\"id\":\"p\",\"plugin\":\"p\",\"config\":"
            + original.canonicalJson() + "}]";
        var parsed = new ConfigDocumentReader(ConfigLimits.defaults()).read(
            java.nio.file.Path.of("input.json"),
            encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);

        assertEquals(original, LiteralValue.of(parsed.entries().getFirst().get("config")));
    }

    @Test
    void sourceReadersPreserveExactDecimalDigits() {
        var reader = new ConfigDocumentReader(ConfigLimits.defaults());
        var sources = Map.of(
            "literal.json", "[{\"id\":\"p\",\"plugin\":\"p\",\"config\":0.10000000000000000001}]",
            "literal.yaml", "- id: p\n  plugin: p\n  config: 0.10000000000000000001\n");
        sources.forEach((name, text) -> {
            var document = reader.read(java.nio.file.Path.of(name),
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            assertEquals(new java.math.BigDecimal("0.10000000000000000001"),
                document.entries().getFirst().get("config"), name);
        });
    }

    @Test
    void configurationUsesTheSharedCanonicalDataBoundary() {
        var input = Map.of("numbers", List.of(1, 2.50));

        assertEquals(LiteralValue.of(input).toJava(), LiteralValues.freeze(input));
    }

    @Test
    void configurationRejectsMutableAndNonFiniteNumericValues() {
        assertThrows(IllegalArgumentException.class, () ->
            LiteralValues.freeze(Map.of("value", new AtomicInteger(1))));
        assertThrows(IllegalArgumentException.class, () -> LiteralValues.freeze(Double.NaN));
    }
}
