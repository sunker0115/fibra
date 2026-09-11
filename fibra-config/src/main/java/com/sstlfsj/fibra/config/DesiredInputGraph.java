package com.sstlfsj.fibra.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesiredInputGraph {
    private final List<DesiredInputEntry> entries;
    private final Map<String, DesiredInputEntry> byId;

    public DesiredInputGraph(List<DesiredInputEntry> entries) {
        this.entries = List.copyOf(entries);
        var index = new LinkedHashMap<String, DesiredInputEntry>();
        for (var entry : this.entries) {
            if (index.putIfAbsent(entry.instanceId(), entry) != null) {
                throw new IllegalArgumentException("duplicate instance id " + entry.instanceId());
            }
        }
        byId = Map.copyOf(index);
    }

    public List<DesiredInputEntry> entries() {
        return entries;
    }

    public DesiredInputEntry require(String instanceId) {
        var entry = byId.get(instanceId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown desired entry " + instanceId);
        }
        return entry;
    }

    public DesiredInputGraph upsert(DesiredInputEntry replacement) {
        var next = new java.util.ArrayList<DesiredInputEntry>(entries.size() + 1);
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
        return new DesiredInputGraph(next);
    }

    public DesiredInputGraph remove(String instanceId) {
        return new DesiredInputGraph(entries.stream()
            .filter(entry -> !entry.instanceId().equals(instanceId)).toList());
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof DesiredInputGraph other
            && entries.equals(other.entries);
    }

    @Override
    public int hashCode() { return entries.hashCode(); }
}
