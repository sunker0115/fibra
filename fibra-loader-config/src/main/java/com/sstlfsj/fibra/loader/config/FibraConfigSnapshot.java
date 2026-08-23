package com.sstlfsj.fibra.loader.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 一次成功解析并提交的不可变配置树快照。 */
public final class FibraConfigSnapshot {
    private final Path rootPath;
    private final List<FibraConfigEntry> entries;
    private final List<FibraConfigEntry> allEntries;
    private final Map<String, FibraConfigEntry> index;

    FibraConfigSnapshot(Path rootPath, List<FibraConfigEntry> entries) {
        this.rootPath = Objects.requireNonNull(rootPath, "rootPath");
        this.entries = List.copyOf(entries);
        var all = new ArrayList<FibraConfigEntry>();
        var byId = new LinkedHashMap<String, FibraConfigEntry>();
        append(this.entries, all, byId);
        this.allEntries = List.copyOf(all);
        this.index = Map.copyOf(byId);
    }

    public Path rootPath() {
        return rootPath;
    }

    public List<FibraConfigEntry> entries() {
        return entries;
    }

    public List<FibraConfigEntry> allEntries() {
        return allEntries;
    }

    public Optional<FibraConfigEntry> resolve(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        return Optional.ofNullable(index.get(entryId));
    }

    private static void append(List<FibraConfigEntry> entries, List<FibraConfigEntry> all,
                               Map<String, FibraConfigEntry> index) {
        for (var entry : entries) {
            if (index.putIfAbsent(entry.entryId(), entry) != null) {
                throw new IllegalArgumentException("duplicate complete entry id "
                    + entry.entryId());
            }
            all.add(entry);
            append(entry.children(), all, index);
        }
    }
}
