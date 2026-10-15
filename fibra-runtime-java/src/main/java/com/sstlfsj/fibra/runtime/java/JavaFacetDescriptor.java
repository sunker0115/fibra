package com.sstlfsj.fibra.runtime.java;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;

/** Java 本地入口声明；逻辑身份和精确依赖来自外层受管 facet。 */
public record JavaFacetDescriptor(Optional<String> entrypoint, Optional<String> definitionId,
                                  Map<String, String> hostApis) {
    public JavaFacetDescriptor(Optional<String> entrypoint, Optional<String> definitionId) {
        this(entrypoint, definitionId, Map.of());
    }

    public JavaFacetDescriptor {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(definitionId, "definitionId");
        hostApis = Map.copyOf(Objects.requireNonNull(hostApis, "hostApis"));
        hostApis.forEach((id, version) -> {
            if (id.isBlank() || version.isBlank()) {
                throw new IllegalArgumentException("host API id and version must not be blank");
            }
        });
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
