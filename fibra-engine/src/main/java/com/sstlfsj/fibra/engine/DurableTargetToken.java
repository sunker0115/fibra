package com.sstlfsj.fibra.engine;

import java.util.Objects;

public final class DurableTargetToken {
    private final long targetRevision;
    private final String targetDigest;

    private DurableTargetToken(long targetRevision, String targetDigest) {
        if (targetRevision < 1) {
            throw new IllegalArgumentException("target revision must be positive");
        }
        this.targetRevision = targetRevision;
        this.targetDigest = Objects.requireNonNull(targetDigest, "targetDigest");
        if (!targetDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "target digest must be a lowercase SHA-256 digest");
        }
    }

    static DurableTargetToken issue(long targetRevision, String targetDigest) {
        return new DurableTargetToken(targetRevision, targetDigest);
    }

    public long targetRevision() {
        return targetRevision;
    }

    public String targetDigest() {
        return targetDigest;
    }
}
