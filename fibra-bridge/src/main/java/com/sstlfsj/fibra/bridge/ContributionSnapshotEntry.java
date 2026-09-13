package com.sstlfsj.fibra.bridge;

public record ContributionSnapshotEntry(ContributionId id, long registrationIdentity,
                                        String kind, Object descriptor) {
}
