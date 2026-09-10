package com.sstlfsj.fibra.example.sanitizer;

import java.util.Objects;

public record SanitizeRequest(String text) {
    public SanitizeRequest {
        Objects.requireNonNull(text, "text");
    }
}
