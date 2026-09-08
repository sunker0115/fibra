package com.sstlfsj.fibra.runtime.node;

import com.sstlfsj.fibra.bridge.ContributionKind;

import java.util.Optional;

@FunctionalInterface
public interface NodeContributionKindResolver {
    Optional<ContributionKind<?, ?, ?>> find(String name);
}
