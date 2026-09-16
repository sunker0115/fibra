package com.sstlfsj.fibra.artifact;

public interface PluginPackageInstallTransaction extends AutoCloseable {
    PluginPackageRecord candidate();

    /** 原子发布整个逻辑 package revision，不维护 current 指针。 */
    PluginPackageRecord save();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
