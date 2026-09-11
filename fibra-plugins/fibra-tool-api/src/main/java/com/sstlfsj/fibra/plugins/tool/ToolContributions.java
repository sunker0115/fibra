package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;

public final class ToolContributions {
    public static final ContributionKind<ToolDescriptor, ToolRequest, ToolResult> KIND =
        ContributionKind.local("fibra.tool", ToolDescriptor.class, ToolRequest.class,
            ToolResult.class);

    private ToolContributions() {
    }

    public static ContributionId id(String providerInstanceId, String localName) {
        return new ContributionId(providerInstanceId, localName);
    }
}
