package com.sstlfsj.fibra.bridge;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Host 级不可变 contribution kind 注册表。 */
public final class ContributionKindRegistry {
    private final Map<String, ContributionKind<?, ?, ?>> kinds;

    private ContributionKindRegistry(Collection<? extends ContributionKind<?, ?, ?>> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        var values = new LinkedHashMap<String, ContributionKind<?, ?, ?>>();
        for (var kind : List.copyOf(kinds)) {
            Objects.requireNonNull(kind, "kind");
            if (values.putIfAbsent(kind.name(), kind) != null) {
                throw new IllegalArgumentException("duplicate contribution kind " + kind.name());
            }
        }
        this.kinds = Map.copyOf(values);
    }

    public static ContributionKindRegistry empty() {
        return new ContributionKindRegistry(List.of());
    }

    public static ContributionKindRegistry of(ContributionKind<?, ?, ?>... kinds) {
        return new ContributionKindRegistry(List.of(kinds));
    }

    public static ContributionKindRegistry of(
        Collection<? extends ContributionKind<?, ?, ?>> kinds) {
        return new ContributionKindRegistry(kinds);
    }

    /** 按名字返回注册时的同一 kind 实例。 */
    public Optional<ContributionKind<?, ?, ?>> find(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(kinds.get(name));
    }
}
