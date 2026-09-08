package com.sstlfsj.fibra.bridge;

import java.util.List;

public record ContributionSnapshot(long revision,
                                   List<ContributionSnapshotEntry> entries) {
    public ContributionSnapshot {
        entries = List.copyOf(entries);
    }
}
