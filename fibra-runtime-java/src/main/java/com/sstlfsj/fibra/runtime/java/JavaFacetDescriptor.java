package com.sstlfsj.fibra.runtime.java;

import java.util.Objects;
import java.util.Optional;

/** Java 本地入口声明；逻辑身份和精确依赖来自外层受管 facet。 */
public record JavaFacetDescriptor(Optional<String> entrypoint, Optional<String> definitionId) {
    public JavaFacetDescriptor {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(definitionId, "definitionId");
        if (entrypoint.isPresent() != definitionId.isPresent()) {
            throw new IllegalArgumentException("entrypoint and definitionId must be declared together");
        }
        entrypoint.ifPresent(value -> {
            if (value.isBlank()) throw new IllegalArgumentException("entrypoint must not be blank");
        });
        definitionId.ifPresent(value -> {
            if (value.isBlank()) throw new IllegalArgumentException("definitionId must not be blank");
        });
    }
}
