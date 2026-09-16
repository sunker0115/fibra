package com.sstlfsj.fibra.runtime.java;

import java.util.Objects;
import java.util.Optional;

/** Java 本地入口声明；逻辑身份和精确依赖来自外层受管 facet。 */
public record JavaFacetDescriptor(Optional<String> entrypoint) {
    public JavaFacetDescriptor {
        Objects.requireNonNull(entrypoint, "entrypoint");
        entrypoint.ifPresent(value -> {
            if (value.isBlank()) throw new IllegalArgumentException("entrypoint must not be blank");
        });
    }
}
