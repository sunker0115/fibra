package com.sstlfsj.fibra.bridge;

import java.util.Objects;

public record ContributionBinding<D, I, O>(
    ContributionKind<D, I, O> kind,
    String localName,
    D descriptor,
    ContributionHandler<I, O> handler
) {
    public ContributionBinding {
        Objects.requireNonNull(kind, "kind");
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("localName must not be blank");
        }
        if (!kind.descriptorType().isInstance(descriptor)) {
            throw new IllegalArgumentException(
                "descriptor is not a " + kind.descriptorType().getName());
        }
        Objects.requireNonNull(handler, "handler");
    }
}
