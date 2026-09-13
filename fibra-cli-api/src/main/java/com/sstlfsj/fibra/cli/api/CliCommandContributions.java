package com.sstlfsj.fibra.cli.api;

import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;

public final class CliCommandContributions {
    public static final ContributionKind<CliCommandDescriptor, CliCommandRequest, CliCommandResult>
        KIND = ContributionKind.local("fibra.cli.command", CliCommandDescriptor.class,
            CliCommandRequest.class, CliCommandResult.class);

    private CliCommandContributions() {
    }

    public static ContributionId id(String providerInstanceId, String localName) {
        return new ContributionId(providerInstanceId, localName);
    }
}
