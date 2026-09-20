package com.sstlfsj.fibra.example.sanitizer;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentSanitizerContributionTest {
    @Test
    void mapsTheLanguageNeutralWireContractToTypedHostValues() {
        var codec = ContentSanitizerContribution.KIND.codec().orElseThrow();
        var descriptor = codec.decodeDescriptor(LiteralValue.of(Map.of(
            "title", "Sensitive content sanitizer",
            "supportedRules", List.of("email", "api-key"))));
        var encoded = codec.encodeInput(new SanitizeRequest("mail a@example.com"));
        var result = codec.decodeOutput(LiteralValue.of(Map.of(
            "text", "mail [REDACTED]",
            "redactions", Map.of("email", 1),
            "total", 1)));

        assertEquals("Sensitive content sanitizer", descriptor.title());
        assertEquals(List.of("email", "api-key"), descriptor.supportedRules());
        assertEquals(LiteralValue.of(Map.of("text", "mail a@example.com")), encoded);
        assertEquals("mail [REDACTED]", result.text());
        assertEquals(Map.of("email", 1), result.redactions());
        assertEquals(1, result.total());
    }

    @Test
    void rejectsAnInvalidRemoteResultInsteadOfGuessing() {
        var codec = ContentSanitizerContribution.KIND.codec().orElseThrow();

        assertThrows(IllegalArgumentException.class,
            () -> codec.decodeOutput(LiteralValue.of(Map.of("text", "missing counts"))));
    }
}
