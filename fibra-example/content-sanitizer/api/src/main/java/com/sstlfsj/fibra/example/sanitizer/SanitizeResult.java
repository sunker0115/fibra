package com.sstlfsj.fibra.example.sanitizer;

import java.util.Map;
import java.util.Objects;

public record SanitizeResult(String text, Map<String, Integer> redactions, int total) {
    public SanitizeResult {
        Objects.requireNonNull(text, "text");
        redactions = Map.copyOf(redactions);
        if (redactions.entrySet().stream().anyMatch(entry -> entry.getKey() == null
            || entry.getKey().isBlank() || entry.getValue() == null
            || entry.getValue() < 0)) {
            throw new IllegalArgumentException("redactions must contain non-negative counts");
        }
        if (total < 0 || redactions.values().stream().mapToInt(Integer::intValue).sum()
            != total) {
            throw new IllegalArgumentException("total must equal the redaction counts");
        }
    }
}
