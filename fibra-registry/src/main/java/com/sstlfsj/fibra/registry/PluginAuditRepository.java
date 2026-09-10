package com.sstlfsj.fibra.registry;

import java.util.List;

public interface PluginAuditRepository extends AutoCloseable {
    PluginAuditEntry append(String operation, String target, boolean succeeded,
                            String viewRevision, String detail);

    List<PluginAuditEntry> history();

    @Override
    default void close() {
    }
}
