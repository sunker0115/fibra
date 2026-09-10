package com.sstlfsj.fibra.bridge;

import java.util.Objects;

/** 同一个目录 revision 的外部描述和不可变调用路由。 */
public record ContributionDirectoryView(ContributionSnapshot snapshot,
                                        ContributionRoutes routes) {
    public ContributionDirectoryView {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(routes, "routes");
    }
}
