package com.sstlfsj.fibra.example.sanitizer;

import java.util.List;

public record SanitizerDescriptor(String title, List<String> supportedRules) {
    public SanitizerDescriptor {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        supportedRules = List.copyOf(supportedRules);
        if (supportedRules.isEmpty()
            || supportedRules.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("supportedRules must contain names");
        }
    }
}
