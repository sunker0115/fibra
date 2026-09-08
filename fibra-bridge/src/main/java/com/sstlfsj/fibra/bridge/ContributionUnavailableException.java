package com.sstlfsj.fibra.bridge;

public final class ContributionUnavailableException extends IllegalStateException {
    ContributionUnavailableException(ContributionId id) {
        super("contribution is unavailable: " + id.providerInstanceId()
            + '/' + id.localName());
    }
}
