package com.sstlfsj.fibra.bridge;

public record ContributionId(String providerInstanceId, String localName) {
    public ContributionId {
        if (providerInstanceId == null || providerInstanceId.isBlank()) {
            throw new IllegalArgumentException("providerInstanceId must not be blank");
        }
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("localName must not be blank");
        }
    }
}
