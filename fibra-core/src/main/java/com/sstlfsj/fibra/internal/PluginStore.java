package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.Fibra;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class PluginStore {
    private final Map<Object, List<Fibra>> runtimes = new IdentityHashMap<>();

    public void add(Object plugin, Fibra fibra) {
        runtimes.computeIfAbsent(plugin, ignored -> new ArrayList<>()).add(fibra);
    }

    public void remove(Object plugin, Fibra fibra) {
        var fibras = runtimes.get(plugin);
        if (fibras == null) {
            return;
        }
        fibras.removeIf(candidate -> candidate == fibra);
        if (fibras.isEmpty()) {
            runtimes.remove(plugin);
        }
    }

    public List<Fibra> fibras() {
        return runtimes.values().stream()
            .flatMap(List::stream)
            .toList();
    }

    public int size() {
        return runtimes.size();
    }

    public List<Object> keys() {
        return List.copyOf(runtimes.keySet());
    }

    public List<List<Fibra>> values() {
        return runtimes.values().stream().map(List::copyOf).toList();
    }

    public Map<Object, List<Fibra>> entries() {
        var result = new IdentityHashMap<Object, List<Fibra>>();
        runtimes.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    public boolean has(Object plugin) {
        return runtimes.containsKey(plugin);
    }

    public List<Fibra> fibras(Object plugin) {
        var fibras = runtimes.get(plugin);
        return fibras == null ? List.of() : List.copyOf(fibras);
    }
}
