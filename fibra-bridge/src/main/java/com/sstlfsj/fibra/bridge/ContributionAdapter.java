package com.sstlfsj.fibra.bridge;

@FunctionalInterface
public interface ContributionAdapter<D> {
    String externalName(ContributionId id, D descriptor);
}
