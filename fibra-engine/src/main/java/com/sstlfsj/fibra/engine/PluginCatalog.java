package com.sstlfsj.fibra.engine;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PluginCatalog {
    private final Map<String, PluginCatalogEntry<?>> entries;

    private PluginCatalog(Collection<PluginCatalogEntry<?>> entries) {
        var index = new LinkedHashMap<String, PluginCatalogEntry<?>>();
        for (var entry : entries) {
            var name = entry.definition().name();
            if (index.putIfAbsent(name, entry) != null) {
                throw new IllegalArgumentException("duplicate plugin definition " + name);
            }
        }
        this.entries = Map.copyOf(index);
    }

    public static PluginCatalog empty() {
        return new PluginCatalog(List.of());
    }

    public static PluginCatalog of(PluginCatalogEntry<?>... entries) {
        return new PluginCatalog(Arrays.asList(entries));
    }

    public static PluginCatalog combine(Collection<PluginCatalog> catalogs) {
        return new PluginCatalog(catalogs.stream()
            .flatMap(catalog -> catalog.entries.values().stream()).toList());
    }

    public Optional<PluginCatalogEntry<?>> find(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    public Collection<PluginCatalogEntry<?>> entries() {
        return entries.values();
    }

}
