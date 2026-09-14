package com.sstlfsj.fibra.registry;

import com.sstlfsj.fibra.engine.TargetSaveState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryPluginAuditRepository implements PluginAuditRepository {
    private final List<PluginAuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized PluginAuditEntry append(String operation, String target,
                                                boolean succeeded,
                                                TargetSaveState targetSaveState,
                                                String viewRevision, String detail) {
        var entry = PluginAuditEntry.builder().sequence(entries.size() + 1L)
            .timestamp(Instant.now()).operation(operation).target(target)
            .succeeded(succeeded).targetSaveState(targetSaveState)
            .viewRevision(viewRevision).detail(detail).build();
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized List<PluginAuditEntry> history() {
        return List.copyOf(entries);
    }
}
