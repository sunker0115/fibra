package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.ServiceKey;

/** Bridge 在运行域内使用的稳定服务契约。 */
public final class ContributionServices {
    public static final ServiceKey<ContributionRegistrar> REGISTRAR =
        ServiceKey.of("fibra.contribution.registrar", ContributionRegistrar.class);

    private ContributionServices() {
    }
}
