package com.sstlfsj.fibra.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryPluginAuditRepository implements PluginAuditRepository {
    private final List<PluginAuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized PluginAuditEntry append(String operation, String target,
                                                boolean succeeded,
                                                String viewRevision, String detail) {
        var entry = new PluginAuditEntry(entries.size() + 1L, Instant.now(), operation,
            target, succeeded, viewRevision, detail);
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized List<PluginAuditEntry> history() {
        return List.copyOf(entries);
    }
}
