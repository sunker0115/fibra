package com.sstlfsj.fibra.example;

import com.sstlfsj.fibra.example.sanitizer.SanitizeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSanitizerScenarioIT {
    @Test
    void deploysInvokesAndRemovesARealNodePlugin(@TempDir Path work) {
        var plugin = Path.of(System.getProperty("fibra.example.plugin"));
        var node = Path.of(System.getProperty("fibra.test.node", "node"));

        try (var scenario = ContentSanitizerScenario.open(plugin, node, work)) {
            assertEquals("Sensitive content sanitizer",
                scenario.descriptor().title());

            var result = scenario.sanitize(new SanitizeRequest(
                "Email alice@example.com with Bearer abcdefghijklmnop "
                    + "or key sk_1234567890abcdef"));

            assertEquals("Email [REDACTED] with [REDACTED] or key [REDACTED]",
                result.text());
            assertEquals(Map.of("email", 1, "bearer-token", 1, "api-key", 1),
                result.redactions());
            assertEquals(3, result.total());

            scenario.remove();
            assertTrue(scenario.registry().snapshot().artifacts().isEmpty());
            assertTrue(scenario.contributions().entries().isEmpty());
            assertEquals(List.of("deploy", "disable", "uninstall"),
                scenario.registry().history().stream().map(entry -> entry.operation())
                    .toList());
        }
    }
}
