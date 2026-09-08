package com.sstlfsj.fibra.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesiredGraph {
    private final List<DesiredEntry> entries;
    private final Map<String, DesiredEntry> byId;

    public DesiredGraph(List<DesiredEntry> entries) {
        this.entries = List.copyOf(entries);
        var index = new LinkedHashMap<String, DesiredEntry>();
        for (var entry : entries) {
            if (index.putIfAbsent(entry.instanceId(), entry) != null) {
                throw new IllegalArgumentException("duplicate instance id " + entry.instanceId());
            }
        }
        byId = Map.copyOf(index);
    }

    public List<DesiredEntry> entries() {
        return entries;
    }

    public DesiredEntry require(String instanceId) {
        var entry = byId.get(instanceId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown desired entry " + instanceId);
        }
        return entry;
    }

    public DesiredGraph upsert(DesiredEntry replacement) {
        var next = new java.util.ArrayList<DesiredEntry>(entries.size() + 1);
        var replaced = false;
        for (var entry : entries) {
            if (entry.instanceId().equals(replacement.instanceId())) {
                next.add(replacement);
                replaced = true;
            } else {
                next.add(entry);
            }
        }
        if (!replaced) {
            next.add(replacement);
        }
        return new DesiredGraph(next);
    }

    public DesiredGraph remove(String instanceId) {
        return new DesiredGraph(entries.stream()
            .filter(entry -> !entry.instanceId().equals(instanceId)).toList());
    }
}
